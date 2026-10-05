package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Row N-007 acceptance: audit_log rejects UPDATE and DELETE; the dw tables and the dirty-key table exist; all
 * migrations apply in order on a clean database (MigrationApplyTest). Plus the device rules of docs/24 s7.5, s8.7.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaV1cTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use { c ->
            c.exec(
                """
                INSERT INTO app.app_user (username, full_name, role) VALUES ('sr0001', 'SR One', 'SR'), ('admin1', 'Admin', 'ADMIN');
                INSERT INTO app.audit_log (actor_user_id, via, entity, entity_id, action, after, reason)
                  SELECT id, 'web', 'route', '1', 'update', '{"name": "রুট ১"}', 'rename' FROM app.app_user WHERE username = 'admin1';
                INSERT INTO app.audit_log (actor_user_id, via, entity, entity_id, action, before, after)
                  SELECT id, 'web', 'route', '1', 'update', '{"name": "রুট ১"}', '{"name": "Route 1"}' FROM app.app_user WHERE username = 'admin1';
                INSERT INTO app.audit_log (via, entity, entity_id, action) VALUES ('job', 'partition', 'app.visit', 'ensure');
                """.trimIndent(),
            )
        }
    }

    @AfterAll
    fun tearDown() = db.close()

    private fun <T> tx(block: (Connection) -> T): T = db.connect().use { c ->
        c.autoCommit = false
        try { block(c) } finally { c.rollback() }
    }

    private fun assertRejected(sqlState: String, sql: String) {
        val e = assertFailsWith<SQLException>(sql) { tx { it.exec(sql) } }
        assertEquals(sqlState, e.sqlState, e.message)
    }

    @Test
    fun auditLogRejectsUpdateDeleteAndTruncate() {
        assertRejected("42501", "UPDATE app.audit_log SET reason = 'edited'")
        assertRejected("42501", "UPDATE app.audit_log SET reason = 'edited' WHERE id = 1")
        assertRejected("42501", "DELETE FROM app.audit_log WHERE id = 1")
        assertRejected("42501", "DELETE FROM app.audit_log")
        assertRejected("42501", "TRUNCATE app.audit_log")
        db.connect().use { assertEquals("3", it.scalar("SELECT count(*) FROM app.audit_log")) }
    }

    @Test
    fun auditRowsAreHashChained() = db.connect().use { c ->
        assertNull(c.scalar("SELECT app.audit_verify()"), "the chain verifies")
        assertEquals(null, c.scalar("SELECT prev_hash FROM app.audit_log ORDER BY id LIMIT 1"))
        assertEquals("0", c.scalar("SELECT count(*) FROM app.audit_log a JOIN app.audit_log b ON b.id = a.id + 1 WHERE b.prev_hash <> a.row_hash"))
        // A caller cannot forge the hashes: the trigger recomputes them.
        tx { t ->
            t.exec("INSERT INTO app.audit_log (via, entity, entity_id, action, row_hash) VALUES ('api', 'x', '1', 'y', decode(repeat('00', 32), 'hex'))")
            assertNull(t.scalar("SELECT app.audit_verify()"))
        }
        // Tampering by someone who bypasses the triggers (the table owner) is detected.
        tx { t ->
            t.exec("ALTER TABLE app.audit_log DISABLE TRIGGER audit_log_append_only")
            t.exec("UPDATE app.audit_log SET reason = 'forged' WHERE id = (SELECT min(id) FROM app.audit_log)")
            assertEquals(t.scalar("SELECT min(id) FROM app.audit_log"), t.scalar("SELECT app.audit_verify()"))
        }
        tx { t ->
            t.exec("ALTER TABLE app.audit_log DISABLE TRIGGER audit_log_append_only")
            t.exec("DELETE FROM app.audit_log WHERE id = (SELECT min(id) + 1 FROM app.audit_log)")
            assertTrue(t.scalar("SELECT app.audit_verify()") != null, "a removed row breaks the chain")
        }
    }

    @Test
    fun aggregateAndDirtyKeyTablesExist() = db.connect().use { c ->
        val dw = listOf(
            "agg_daily_route", "agg_daily_route_sku", "agg_daily_zone", "agg_hourly_zone", "agg_daily_outlet", "fact_visit",
            "fact_memo", "fact_geo_fix", "fact_device_day", "dim_date", "dim_geo", "dim_product", "dim_outlet",
        )
        dw.forEach { t -> assertEquals("dw.$t", c.scalar("SELECT to_regclass('dw.$t')::text"), "dw.$t missing") }
        assertEquals("app.dirty_key", c.scalar("SELECT to_regclass('app.dirty_key')::text"))
        assertEquals("1826", c.scalar("SELECT count(*) FROM dw.dim_date"))
        // 2026-10-03 is a Saturday: bit 0 of visit_days_mask; Friday is the weekend.
        assertEquals("0", c.scalar("SELECT visit_day_bit FROM dw.dim_date WHERE business_date = '2026-10-03'"))
        assertEquals("t", c.scalar("SELECT is_weekend FROM dw.dim_date WHERE business_date = '2026-10-09'"))
        val badMoney = c.column(
            "SELECT table_name || '.' || column_name FROM information_schema.columns WHERE table_schema = 'dw' AND column_name LIKE '%\\_mtk' AND data_type <> 'bigint'",
        )
        assertEquals(emptyList(), badMoney)
    }

    @Test
    fun markingAKeyDirtyTwiceKeepsOneRow() = tx { c ->
        c.exec("SELECT app.mark_dirty('bundle_user', 7, '2026-10-05', 'route assigned')")
        c.exec("UPDATE app.dirty_key SET claimed_at = now(), claimed_by = 'worker-1'")
        c.exec("SELECT app.mark_dirty('bundle_user', 7, '2026-10-05')")
        assertEquals("1", c.scalar("SELECT count(*) FROM app.dirty_key"))
        assertEquals("2", c.scalar("SELECT dirty_count FROM app.dirty_key"))
        assertEquals(null, c.scalar("SELECT claimed_at FROM app.dirty_key"), "a re-dirtied key is claimable again")
        assertEquals("route assigned", c.scalar("SELECT reason FROM app.dirty_key"))
    }

    private val device = """
        INSERT INTO app.device (device_uuid, flavour, app_package, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256)
        VALUES (gen_random_uuid(), 'sr', 'com.aktcl.aron.sr', true, 'dev', '{"kty": "EC"}', '%s', decode(repeat('ab', 32), 'hex'));
    """.trimIndent()

    @Test
    fun aUserHoldsAtMostFourActiveBindingsWithDistinctOrdinals() = tx { c ->
        (1..5).forEach { c.exec(device.format("thumb$it")) }
        val user = c.scalar("SELECT id FROM app.app_user WHERE username = 'sr0001'")
        val devices = c.column("SELECT id FROM app.device ORDER BY id")
        (0..3).forEach { c.exec("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal) VALUES (${devices[it]}, $user, $it)") }
        c.exec("SAVEPOINT s")
        assertEquals("23505", assertFailsWith<SQLException> { c.exec("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal) VALUES (${devices[4]}, $user, 0)") }.sqlState)
        c.exec("ROLLBACK TO SAVEPOINT s")
        assertEquals("23514", assertFailsWith<SQLException> { c.exec("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal) VALUES (${devices[4]}, $user, 4)") }.sqlState)
        c.exec("ROLLBACK TO SAVEPOINT s")
        // Unbinding frees the ordinal.
        c.exec("UPDATE app.device_binding SET status = 'revoked', unbound_at = now() WHERE bind_ordinal = 0")
        c.exec("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal) VALUES (${devices[4]}, $user, 0)")
    }

    @Test
    fun aDeviceKeyAndUuidAreRegisteredOnce() {
        assertRejected("23505", device.format("same") + "\n" + device.format("same"))
    }

    @Test
    fun enrolmentTokensStoreOnlyAHashAndRespectTheirLimits() {
        val token = "INSERT INTO app.enrolment_token (token_sha256, token_prefix, flavour, lockdown_level, max_uses, used_count, expires_at, created_by) " +
            "SELECT sha256('t'::bytea), 'AbC-_9', 'sr', 'prod', %d, %d, now() + interval '%d hours', id FROM app.app_user WHERE username = 'admin1'"
        tx { it.exec(token.format(500, 0, 168)) }
        assertRejected("23514", token.format(501, 0, 24))
        assertRejected("23514", token.format(10, 11, 24))
        assertRejected("23514", token.format(10, 0, 169))
        db.connect().use { c ->
            assertEquals(emptyList(), c.column("SELECT column_name FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'enrolment_token' AND column_name IN ('token', 'secret')"))
        }
    }

    @Test
    fun everyDeviceIdColumnPointsAtTheDeviceTable() = db.connect().use { c ->
        val unlinked = c.column(
            """
            SELECT c.table_name FROM information_schema.columns c
              JOIN pg_class k ON k.relname = c.table_name AND k.relnamespace = 'app'::regnamespace AND NOT k.relispartition
             WHERE c.table_schema = 'app' AND c.column_name = 'device_id'
               AND NOT EXISTS (SELECT 1 FROM pg_constraint f WHERE f.conrelid = k.oid AND f.contype = 'f'
                                  AND f.confrelid = 'app.device'::regclass)
            """.trimIndent(),
        )
        assertEquals(emptyList(), unlinked)
    }

    @Test
    fun aReleaseIsPublishedBySomeoneElseThanItsAuthor() {
        val admin = "(SELECT id FROM app.app_user WHERE username = 'admin1')"
        assertRejected(
            "23514",
            "INSERT INTO app.app_release (flavour, version_name, version_code, abi, sha256, size_bytes, download_url, signing_cert_sha256, status, created_by, published_by, published_at) " +
                "VALUES ('sr', '1.0.0', 1, 'universal', sha256('a'::bytea), 1000, 'https://x.invalid/a.apk', sha256('c'::bytea), 'published', $admin, $admin, now())",
        )
    }
}
