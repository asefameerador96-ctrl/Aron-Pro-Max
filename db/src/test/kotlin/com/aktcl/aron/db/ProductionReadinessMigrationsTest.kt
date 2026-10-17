package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The before-go-live migrations of the production readiness audit (docs/audit/production-readiness-2026-10-15.md):
 * V0084 (SCALE-03) the masterdata change stamp and the snapshot body columns, V0085 (DB-04) the user-date indexes on the
 * dw facts, V0086 (DB-01) the retention grants and the response-expiry index, V0087 (DD-02) the generation mint.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductionReadinessMigrationsTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() { db = TestPostgres.createDatabase().migrated() }

    @AfterAll
    fun tearDown() = db.close()

    /** DB-04: (user_id, business_date) on dw.fact_memo and dw.fact_visit, on the parent and on every partition, including a partition made later. */
    @Test
    fun theUserDateIndexesExistOnTheFactsAndTheirPartitions() = db.connect().use { c ->
        for (t in listOf("fact_memo", "fact_visit")) {
            val name = "${t}_user_date"
            assertEquals(listOf("CREATE INDEX $name ON ONLY dw.$t USING btree (user_id, business_date)"), c.column("SELECT indexdef FROM pg_indexes WHERE schemaname = 'dw' AND indexname = '$name'"))
            val partitions = c.column("SELECT inhrelid::regclass::text FROM pg_inherits WHERE inhparent = 'dw.$t'::regclass")
            assertTrue(partitions.isNotEmpty(), "$t has partitions")
            val indexed = c.column("SELECT i.indrelid::regclass::text FROM pg_index i JOIN pg_class k ON k.oid = i.indexrelid WHERE k.relname LIKE '${t}_%_user_id_business_date_idx' OR k.relname LIKE '%${name}%'")
            partitions.forEach { p -> assertTrue(p in indexed, "$p carries the index: $indexed") }
        }
        // V0086: the partial index the response expiry walks.
        assertEquals(1, c.column("SELECT indexname FROM pg_indexes WHERE schemaname = 'app' AND indexname = 'sync_batch_response_expiry' AND indexdef LIKE '%WHERE (response_gz IS NOT NULL)'").size)
    }

    /** V0084: a write to a reference table the bundle reads bumps the stamp once per statement; a login's own row never does. */
    @Test
    fun aMasterdataWriteBumpsTheChangeStampOncePerStatement() = db.connect().use { c ->
        val before = c.scalar("SELECT value FROM app.change_stamp WHERE name = 'masterdata'")!!.toLong()
        c.exec("INSERT INTO app.wing (code, name) VALUES ('W-STAMP', 'Stamp wing')")
        assertEquals(before + 1, c.scalar("SELECT value FROM app.change_stamp WHERE name = 'masterdata'")!!.toLong())
        c.exec("UPDATE app.wing SET name = 'Stamp wing 2' WHERE code = 'W-STAMP'")
        c.exec("DELETE FROM app.wing WHERE code = 'W-STAMP'")
        assertEquals(before + 3, c.scalar("SELECT value FROM app.change_stamp WHERE name = 'masterdata'")!!.toLong())
        // app_user: only the members the bundle shows.
        c.exec("INSERT INTO app.app_user (username, full_name, role, locale, must_change_password) VALUES ('sr9901', 'Stamp User', 'SR', 'bn', true)")
        val afterInsert = c.scalar("SELECT value FROM app.change_stamp WHERE name = 'masterdata'")!!.toLong()
        assertEquals(before + 4, afterInsert)
        c.exec("UPDATE app.app_user SET password_hash = 'x', last_login_at = now() WHERE username = 'sr9901'")
        assertEquals(afterInsert, c.scalar("SELECT value FROM app.change_stamp WHERE name = 'masterdata'")!!.toLong(), "a password or login write is not a masterdata change")
        c.exec("UPDATE app.app_user SET full_name = 'Renamed' WHERE username = 'sr9901'")
        assertEquals(afterInsert + 1, c.scalar("SELECT value FROM app.change_stamp WHERE name = 'masterdata'")!!.toLong())
        assertEquals(listOf("body_gz", "change_stamp", "config_version", "scope_version"), c.column("SELECT column_name FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'bundle_snapshot' AND column_name IN ('body_gz', 'scope_version', 'config_version', 'change_stamp') ORDER BY 1"))
    }

    /** V0087 (DD-02): the mint closes the current generation and opens the new one in one statement; bad input is refused and nothing changes. */
    @Test
    fun theGenerationMintOpensExactlyOneNewCurrentGeneration() = db.connect().use { c ->
        val created = c.scalar("SELECT generation FROM app.server_generation WHERE is_current")!!
        val minted = c.scalar("SELECT app.mint_server_generation('pitr_restore', now() - interval '1 hour', now() - interval '1 hour', 'RB-14 test')")!!
        assertNotEquals(created, minted)
        assertEquals(listOf(minted), c.column("SELECT generation::text FROM app.server_generation WHERE is_current"))
        assertEquals("pitr_restore", c.scalar("SELECT kind FROM app.server_generation WHERE generation = '$minted'::uuid"))
        assertEquals("f", c.scalar("SELECT is_current FROM app.server_generation WHERE generation = '$created'::uuid")?.take(1)?.lowercase())
        for (bad in listOf("('created', now())", "('pitr_restore', NULL)", "('failover', now() - interval '1 hour', now() - interval '2 hours')")) {
            assertFailsWith<SQLException>(bad) { c.scalar("SELECT app.mint_server_generation$bad") }
        }
        assertEquals(1, c.column("SELECT generation FROM app.server_generation WHERE is_current").size)
        assertEquals(listOf(minted), c.column("SELECT generation::text FROM app.server_generation WHERE is_current"))
        // Revoked from PUBLIC: only the admin login that restores runs it.
        assertEquals("f", c.scalar("SELECT has_function_privilege('public', 'app.mint_server_generation(text, timestamptz, timestamptz, text)', 'EXECUTE')")?.take(1)?.lowercase())
    }
}
