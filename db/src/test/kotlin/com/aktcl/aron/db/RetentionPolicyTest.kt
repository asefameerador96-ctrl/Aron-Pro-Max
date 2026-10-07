package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** V0044/V0045 (AUD-DA-06, docs/16 s13.1): retention classes, archive manifest flow, archive candidates, default-partition check. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetentionPolicyTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() { db = TestPostgres.createDatabase().migrated() }

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

    @Test
    fun everyRegisteredParentHasAClass() = db.connect().use { c ->
        assertEquals(
            listOf(
                "app.domain_event|audit", "app.geo_fix|fix", "app.memo|transaction", "app.memo_line|transaction", "app.visit|transaction",
                "dw.fact_activity|telemetry", "dw.fact_device_integrity|event_fact", "dw.fact_geo_fix|fix", "dw.fact_memo|event_fact", "dw.fact_visit|event_fact",
            ),
            c.column("SELECT parent || '|' || retention_class FROM app.partition_policy ORDER BY parent"),
        )
        assertEquals("audit|null|null", c.scalar("SELECT concat_ws('|', retention_class, coalesce(hot_months::text, 'null'), coalesce(keep_months::text, 'null')) FROM app.retention_policy WHERE hot_months IS NULL"))
    }

    @Test
    fun archiveCandidatesArePartitionsPastTheirHotWindow() = db.connect().use { c ->
        c.tx {
            exec("SELECT app.ensure_partitions('2024-01-01', '2024-03-01')")
            // 2026-10 today: transaction keeps 13 months hot (cut-off 2025-09-01), fix 6, event_fact 25 (cut-off 2024-09-01)
            val cands = column("SELECT parent || '|' || partition_name || '|' || retention_class FROM app.archive_candidates('2026-10-07') WHERE month = '2024-01-01'")
            assertTrue("app.memo|app.memo_y2024m01|transaction" in cands, cands.toString())
            assertTrue("app.geo_fix|app.geo_fix_y2024m01|fix" in cands, cands.toString())
            assertTrue("dw.fact_memo|dw.fact_memo_y2024m01|event_fact" in cands, cands.toString())
            assertTrue(cands.none { it!!.startsWith("app.domain_event|") }, "audit never leaves the primary")
            assertEquals("0", scalar("SELECT count(*) FROM app.archive_candidates('2026-10-07') WHERE month >= '2025-09-01' AND retention_class = 'transaction'"))

            exec("INSERT INTO app.archive_manifest (parent, partition_name, month) VALUES ('app.memo', 'app.memo_y2024m01', '2024-01-01')")
            assertEquals("42501", refused("UPDATE app.archive_manifest SET status = 'dropped'"))                       // no skipping
            assertEquals("23514", refused("UPDATE app.archive_manifest SET status = 'exported'"))                      // export needs its facts
            exec("UPDATE app.archive_manifest SET status = 'exported', row_count = 10, sha256 = repeat('a', 64), blob_url = 'archive/memo/2024-01.parquet', format = 'parquet', exported_at = now()")
            exec("UPDATE app.archive_manifest SET status = 'verified', verified_at = now()")
            exec("UPDATE app.archive_manifest SET status = 'dropped', dropped_at = now()")
            assertEquals("42501", refused("UPDATE app.archive_manifest SET status = 'verified'"))
            assertEquals("42501", refused("DELETE FROM app.archive_manifest"))
            assertEquals("0", scalar("SELECT count(*) FROM app.archive_candidates('2026-10-07') WHERE partition_name = 'app.memo_y2024m01'"))
        }
    }

    @Test
    fun defaultPartitionRowsAreReported() = db.connect().use { c ->
        c.tx {
            assertEquals(emptyList(), column("SELECT parent FROM app.default_partition_rows()"))
            exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('sr0001', 'SR One', 'SR')")
            exec("INSERT INTO app.geo_fix (business_date, source_type, source_client_uuid, user_id, captured_at, purpose, fix_status, lat, lng, accuracy_m, " +
                "provider, is_mock, reused, device_owner, dev_options_enabled, adb_enabled, auto_time_enabled, mock_app_present) " +
                "SELECT '2019-01-01', 'visit', gen_random_uuid(), id, now(), 'visit_open', 'ok', 23.8, 90.4, 10, 'fused', false, false, true, false, false, true, false FROM app.app_user WHERE username = 'sr0001'")
            exec("SET LOCAL ROLE worker_rw")                     // the worker has no rights on single partitions
            assertEquals(listOf("app.geo_fix|1"), column("SELECT parent || '|' || row_count FROM app.default_partition_rows()"))
            exec("RESET ROLE")
        }
        assertEquals("f|f", c.scalar("SELECT has_table_privilege('api_rw', 'app.archive_manifest', 'SELECT')::text || '|' || has_table_privilege('api_rw', 'app.archive_manifest', 'UPDATE')::text")
            ?.replace("true", "t")?.replace("false", "f"))
        assertEquals("t|f", c.scalar("SELECT has_table_privilege('jobs_rw', 'app.archive_manifest', 'UPDATE')::text || '|' || has_table_privilege('web_ro', 'app.retention_policy', 'SELECT')::text")
            ?.replace("true", "t")?.replace("false", "f"))
    }
}
