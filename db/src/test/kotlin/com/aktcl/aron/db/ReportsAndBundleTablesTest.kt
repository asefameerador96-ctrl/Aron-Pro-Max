package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** V0030 export log and PII budget, V0031 device/activity/consent facts, V0032 bundle snapshots, V0036 report indexes and events, V0037/V0038 task route and cancel reason. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportsAndBundleTablesTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use { it.exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('dmo9001', 'DMO', 'DMO')") }
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

    private val user = "(SELECT id FROM app.app_user WHERE username = 'dmo9001')"

    @Test
    fun anExportKeepsWhoWhatAndFiltersAndOnlyItsJobMovesForward() = db.connect().use { c ->
        c.tx {
            exec("INSERT INTO app.report_export (export_id, report_key, user_id, format, filters, scope_hash) VALUES ('00000000-0000-4000-8000-0000000000e1', 'sales-by-route', $user, 'xlsx', '{\"from\": \"2026-10-01\"}', 'h1')")
            exec("UPDATE app.report_export SET status = 'running', claimed_by = 'worker-1', claimed_at = now(), started_at = now()")
            assertEquals("23514", refused("UPDATE app.report_export SET status = 'done'"))             // done needs row_count and finished_at
            exec("UPDATE app.report_export SET status = 'done', row_count = 120, pii_included = true, finished_at = now(), blob_path = 'x/1.xlsx'")
            assertEquals("42501", refused("UPDATE app.report_export SET status = 'running'"))
            assertEquals("42501", refused("UPDATE app.report_export SET row_count = 121"))                // finished: frozen
            exec("UPDATE app.report_export SET expires_at = now() + interval '1 day'")                          // except its expiry
            assertEquals("42501", refused("UPDATE app.report_export SET filters = '{}'"))
            assertEquals("42501", refused("UPDATE app.report_export SET user_id = user_id + 1"))
            assertEquals("42501", refused("DELETE FROM app.report_export"))
            assertEquals("23514", refused("INSERT INTO app.report_export (export_id, report_key, user_id, format, filters, scope_hash) VALUES (gen_random_uuid(), 'Bad Key', $user, 'xlsx', '{}', 'h')"))
            exec("INSERT INTO app.pii_read_budget (user_id, hour_start, rows_read) VALUES ($user, date_trunc('hour', now()), 10) " +
                "ON CONFLICT (user_id, hour_start) DO UPDATE SET rows_read = app.pii_read_budget.rows_read + excluded.rows_read")
            assertEquals("23514", refused("INSERT INTO app.pii_read_budget (user_id, hour_start) VALUES ($user, date_trunc('hour', now()) + interval '1 minute')"))
        }
        assertEquals(listOf("t", "t"), c.column("SELECT has_table_privilege(r, 'app.report_export', 'UPDATE')::text FROM unnest(ARRAY['worker_rw', 'api_rw']) r").map { it?.take(1) })
    }

    @Test
    fun piiBudgetsAreTheDocs19KeysWithTheTsoOverride() = db.connect().use { c ->
        assertEquals(
            listOf("cfg.pii.export_rows_per_day|5000|down|server", "cfg.pii.list_rows_per_hour|2000|down|server"),
            c.column("SELECT concat_ws('|', key, default_value::text, restrictive_dir, delivery) FROM app.cfg_key WHERE key LIKE 'cfg.pii.%rows%' ORDER BY key"),
        )
        assertEquals("TSO|5000", c.scalar("SELECT r.role || '|' || v.value::text FROM app.cfg_value v JOIN app.role_def r ON r.ordinal = v.scope_id " +
            "WHERE v.key = 'cfg.pii.list_rows_per_hour' AND v.scope_type = 'role' AND v.effective_to IS NULL"))
    }

    @Test
    fun deviceFactsArePartitionedAndTheRollupIsKeyedByDayRoleScreenAction() = db.connect().use { c ->
        assertEquals(
            listOf("dw.fact_activity", "dw.fact_device_integrity"),
            c.column("SELECT parent FROM app.partition_policy WHERE parent IN ('dw.fact_activity', 'dw.fact_device_integrity') ORDER BY 1"),
        )
        c.tx {
            exec("INSERT INTO dw.fact_activity (user_key, device_key, business_date, occurred_at, role, screen, action, seq, source_uuid) VALUES (1, 1, '2026-10-07', now(), 'SR', 'sale', 'open', 0, gen_random_uuid())")
            exec("INSERT INTO dw.fact_device_integrity (device_key, user_key, business_date, observed_at, rooted_hint, source_uuid) VALUES (1, 1, '2026-10-07', now(), NULL, gen_random_uuid())")
            exec("INSERT INTO dw.agg_daily_screen_use VALUES ('2026-10-07', 'SR', 'sale', 'open', 1, 1)")
            assertEquals("23505", refused("INSERT INTO dw.agg_daily_screen_use VALUES ('2026-10-07', 'SR', 'sale', 'open', 2, 2)"))
            exec("INSERT INTO dw.fact_consent (user_key, policy_version, accepted_at, business_date, source_uuid) VALUES (1, 'location_notice:1', now(), '2026-10-07', gen_random_uuid())")
            assertEquals("1", scalar("SELECT count(*) FROM dw.fact_activity WHERE business_date = '2026-10-07'"))
        }
        assertEquals(listOf("f", "f"), c.column("SELECT has_table_privilege(r, 'dw.fact_activity', 'SELECT')::text FROM unnest(ARRAY['web_ro', 'bi_reader']) r").map { it?.take(1) })
    }

    @Test
    fun bundleSnapshotsAreNumberedPerUserAndDate() = db.connect().use { c ->
        c.tx {
            val ins = "INSERT INTO app.bundle_snapshot (user_id, business_date, snapshot_seq, content_sha256) VALUES ($user, '2026-10-07', %d, decode(repeat('%s', 32), 'hex'))"
            exec(ins.format(1, "aa")); exec(ins.format(2, "bb")); exec(ins.format(3, "aa"))   // A -> B -> A gives 1, 2, 3
            assertEquals("23505", refused(ins.format(3, "cc")))
            assertEquals("23514", refused(ins.format(0, "cc")))
            assertEquals("3", scalar("SELECT max(snapshot_seq) FROM app.bundle_snapshot"))
        }
        assertEquals("t", c.scalar("SELECT has_table_privilege('worker_rw', 'app.bundle_snapshot', 'DELETE')"))
    }

    @Test
    fun theRebuildQueriesHaveTheirIndexesAndTheNewEventsAreCatalogued() = db.connect().use { c ->
        assertEquals(
            listOf("agg_daily_route_segment_segment", "day_exception_approved_dates", "due_collection_route_date",
                "fact_memo_zone_date", "fact_visit_zone_date", "stock_movement_route_date"),
            c.column("SELECT relname FROM pg_class WHERE relname IN ('due_collection_route_date', 'stock_movement_route_date', 'day_exception_approved_dates', " +
                "'fact_visit_zone_date', 'fact_memo_zone_date', 'agg_daily_route_segment_segment') ORDER BY 1"),
        )
        c.tx {
            exec("INSERT INTO dw.agg_daily_route_segment (business_date, route_id, segment_id, memo_count, sold_qty_base, gross_mtk) VALUES ('2026-10-07', 1, 2, 3, 400, 50000)")
            for ((type, payload) in listOf(
                "due.collected" to """{"route_id": null, "business_date": "2026-10-07", "outlet_id": 9, "amount_mtk": 1500000}""",
                "day_exception.decided" to """{"route_id": 1, "from_date": "2026-10-07", "to_date": "2026-10-08", "status": "approved"}""",
                "risk_signal.changed" to """{"business_date": "2026-10-07", "code": "GEO_MOCK", "subject_type": "user", "subject_id": 1, "user_id": null}""",
            )) {
                exec("INSERT INTO app.domain_event (event_type, aggregate_type, aggregate_id, business_date, payload) VALUES ('$type', '${type.substringBefore('.')}', 'x', '2026-10-07', '$payload')")
            }
            assertEquals("3", scalar("SELECT count(*) FROM app.domain_event WHERE event_type IN ('due.collected', 'day_exception.decided', 'risk_signal.changed')"))
        }
    }

    @Test
    fun aTaskKeepsItsRouteAndItsCancelReasonOnce() = db.connect().use { c ->
        c.tx {
            val ins = "INSERT INTO app.task (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, task_type_code, assignee_user_id, title, route_id) " +
                "VALUES (gen_random_uuid(), gen_random_uuid(), '2026-10-07', $user, now(), 0, 'general', $user, 'Check stock', %s)"
            assertEquals("23503", refused(ins.format("-1")))                                                       // route must exist
            exec(ins.format("NULL"))
            assertEquals("23514", refused("UPDATE app.task SET status = 'cancelled', cancelled_by = $user, cancel_reason = 'too short'"))
            exec("UPDATE app.task SET status = 'cancelled', status_changed_at = now(), cancelled_by = $user, cancel_reason = 'Outlet closed for renovation'")
            assertEquals("42501", refused("UPDATE app.task SET cancel_reason = 'Another reason entirely'"))      // write-once
            assertEquals("42501", refused("UPDATE app.task SET route_id = NULL, title = 'x'"))
        }
        assertEquals(listOf("t", "t"), c.column("SELECT convalidated FROM pg_constraint WHERE conname IN ('task_route_id_fkey', 'task_cancel_reason_check')"))
    }
}
