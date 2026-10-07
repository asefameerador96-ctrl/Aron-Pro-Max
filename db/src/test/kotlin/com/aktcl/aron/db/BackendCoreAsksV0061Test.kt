package com.aktcl.aron.db

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V0061: app.media_upload (docs/requests/backend-core-media-upload-ledger.md, F-API-007).
 * V0062: outlet_confirmed_lat_lng (docs/requests/backend-core-outlet-geo-index.md, F-API-019).
 * V0063/V0064: app.device.last_sync_error (docs/requests/backend-core-device-telemetry-columns.md, F-SYS-050).
 * The database is migrated to V0060, loaded with the dev seed and the slice smoke outlet, then migrated forward: the
 * same path the dev database takes before the 2026-10-08 phone checks.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackendCoreAsksV0061Test {
    private lateinit var db: TestDatabase
    private var outletsBefore = ""

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase()
        Flyway.configure().configuration(db.flyway().configuration).target("60").load().migrate()
        db.connect().use { c ->
            SeedLoader.load(c)
            c.exec(File(System.getProperty("aron.seed")).resolve("../../infra/sql/devseed-smoke-outlet.sql").readText())
            outletsBefore = c.scalar(SEED_FINGERPRINT)!!
        }
        db.migrated()
    }

    @AfterAll
    fun tearDown() = db.close()

    private fun Connection.tx(block: Connection.() -> Unit) {
        autoCommit = false
        try { block() } finally { rollback(); autoCommit = true }
    }

    private fun Connection.refused(sql: String): String {
        exec("SAVEPOINT r")
        val e = assertFailsWith<SQLException>(sql) { exec(sql) }
        exec("ROLLBACK TO SAVEPOINT r")
        return e.sqlState
    }

    private fun Connection.insertUpload(uuid: String, purpose: String = "feedback", path: String? = null, bytes: Int = 1024): Int =
        prepareStatement(
            "INSERT INTO app.media_upload (media_uuid, purpose, sha256, bytes, user_id, device_id, blob_path, business_date) " +
                "SELECT ?::uuid, ?, decode(repeat('ab', 32), 'hex'), ?, u.id, d.id, ?, DATE '2026-10-08' " +
                "FROM app.app_user u, app.device d WHERE u.username = 'sr1001' AND d.device_uuid = '$DEV_DEVICE' " +
                "ON CONFLICT (media_uuid, purpose) DO NOTHING",
        ).use {
            it.setString(1, uuid)
            it.setString(2, purpose)
            it.setInt(3, bytes)
            it.setString(4, path ?: "photos/2026-10-08/$DEV_DEVICE/$uuid.jpg")
            it.executeUpdate()
        }

    @Test
    fun seedAndSmokeOutletSurviveTheMigrations() = db.connect().use { c ->
        assertEquals(outletsBefore, c.scalar(SEED_FINGERPRINT))
        assertEquals("1", c.scalar("SELECT count(*) FROM app.outlet WHERE code = 'SMOKE-SR-001' AND status = 'active' AND location_confirmed"))
        assertEquals("1|", c.scalar("SELECT count(*) || '|' || coalesce(max(last_sync_error), '') FROM app.device WHERE device_uuid = '$DEV_DEVICE'"))
        assertEquals("64", c.scalar("SELECT max(version::int)::text FROM flyway_schema_history WHERE success"))
    }

    @Test
    fun mediaUploadIsIdempotentByUuidAndPurpose() = db.connect().use { c ->
        c.tx {
            val uuid = "6f1c0a52-0c38-4b3e-9a51-2f0f6ad1c001"
            assertEquals(1, insertUpload(uuid))
            assertEquals(0, insertUpload(uuid), "a repeat stores nothing")
            assertEquals(1, insertUpload(uuid, purpose = "support", path = "photos/2026-10-08/x/$uuid.jpg"))
            assertEquals("2", scalar("SELECT count(*) FROM app.media_upload WHERE media_uuid = '$uuid'"))
            // The blob path is unique and names the media uuid; the purpose and size stay inside the contract.
            val other = "6f1c0a52-0c38-4b3e-9a51-2f0f6ad1c002"
            assertEquals(1, insertUpload(other))
            assertEquals("23505", refused("INSERT INTO app.media_upload SELECT media_uuid, 'support', sha256, bytes, user_id, device_id, blob_path, business_date FROM app.media_upload WHERE media_uuid = '$other'"))
            assertEquals("23514", refused("INSERT INTO app.media_upload (media_uuid, purpose, sha256, bytes, user_id, blob_path, business_date) SELECT '6f1c0a52-0c38-4b3e-9a51-2f0f6ad1c003', 'feedback', sha256, bytes, user_id, 'photos/2026-10-08/x/other.jpg', business_date FROM app.media_upload LIMIT 1"))
            assertEquals("23514", refused("INSERT INTO app.media_upload (media_uuid, purpose, sha256, bytes, user_id, blob_path, business_date) SELECT '6f1c0a52-0c38-4b3e-9a51-2f0f6ad1c004', 'visit', sha256, bytes, user_id, 'photos/2026-10-08/x/6f1c0a52-0c38-4b3e-9a51-2f0f6ad1c004.jpg', business_date FROM app.media_upload LIMIT 1"))
            assertEquals("23514", refused("INSERT INTO app.media_upload (media_uuid, purpose, sha256, bytes, user_id, blob_path, business_date) SELECT '6f1c0a52-0c38-4b3e-9a51-2f0f6ad1c005', 'feedback', sha256, 307201, user_id, 'photos/2026-10-08/x/6f1c0a52-0c38-4b3e-9a51-2f0f6ad1c005.jpg', business_date FROM app.media_upload LIMIT 1"))
            assertEquals("23514", refused("INSERT INTO app.media_upload (media_uuid, purpose, sha256, bytes, user_id, blob_path, business_date) SELECT '6f1c0a52-0c38-4b3e-9a51-2f0f6ad1c006', 'feedback', '\\x00'::bytea, bytes, user_id, 'photos/2026-10-08/x/6f1c0a52-0c38-4b3e-9a51-2f0f6ad1c006.jpg', business_date FROM app.media_upload LIMIT 1"))
            // Rows never change.
            assertTrue(refused("UPDATE app.media_upload SET bytes = 2 WHERE media_uuid = '$uuid'").isNotEmpty())
            assertTrue(refused("DELETE FROM app.media_upload WHERE media_uuid = '$uuid'").isNotEmpty())
            assertTrue(refused("TRUNCATE app.media_upload").isNotEmpty())
        }
    }

    @Test
    fun mediaUploadGrantsAreInsertAndReadForTheApiOnly() = db.connect().use { c ->
        fun can(role: String, priv: String) = c.scalar("SELECT has_table_privilege('$role', 'app.media_upload', '$priv')::text")
        assertEquals("true|true|false|false", listOf("SELECT", "INSERT", "UPDATE", "DELETE").joinToString("|") { can("api_rw", it)!! })
        assertEquals("true|false", listOf("SELECT", "INSERT").joinToString("|") { can("worker_rw", it)!! })
        assertEquals("false|false", listOf("web_ro", "bi_reader").joinToString("|") { can(it, "SELECT")!! })
    }

    @Test
    fun nearbyBoxUsesThePartialIndex() = db.connect().use { c ->
        c.tx {
            exec("SET LOCAL enable_seqscan = off")
            val box = "FROM app.outlet WHERE lat BETWEEN 23.8140 AND 23.8160 AND lng BETWEEN 90.3677 AND 90.3697 " +
                "AND status = 'active' AND location_confirmed AND route_id IS NOT NULL"
            val plan = column("EXPLAIN (COSTS OFF) SELECT code $box").joinToString("\n")
            assertTrue("outlet_confirmed_lat_lng" in plan, plan)
            assertTrue(column("SELECT code $box").contains("SMOKE-SR-001"))
        }
    }

    @Test
    fun lastSyncErrorTakesACodeAndRefusesFreeText() = db.connect().use { c ->
        c.tx {
            exec("UPDATE app.device SET last_sync_error = 'net.timeout:503' WHERE device_uuid = '$DEV_DEVICE'")
            assertEquals("net.timeout:503", scalar("SELECT last_sync_error FROM app.device WHERE device_uuid = '$DEV_DEVICE'"))
            assertEquals("23514", refused("UPDATE app.device SET last_sync_error = 'free text with spaces' WHERE device_uuid = '$DEV_DEVICE'"))
            assertEquals("23514", refused("UPDATE app.device SET last_sync_error = '' WHERE device_uuid = '$DEV_DEVICE'"))
            assertEquals("23514", refused("UPDATE app.device SET last_sync_error = repeat('a', 65) WHERE device_uuid = '$DEV_DEVICE'"))
        }
        assertEquals("true", c.scalar("SELECT has_column_privilege('api_rw', 'app.device', 'last_sync_error', 'UPDATE')::text"))
        assertEquals("t", c.scalar("SELECT convalidated::text::char FROM pg_constraint WHERE conname = 'device_last_sync_error_format'"))
    }

    private companion object {
        const val DEV_DEVICE = "00000000-0000-4000-8000-000000000001"
        const val SEED_FINGERPRINT =
            "SELECT count(*) || '|' || md5(string_agg(code || ':' || coalesce(lat::text, '') || ':' || coalesce(lng::text, '') || ':' || status, ',' ORDER BY code)) FROM app.outlet"
    }
}
