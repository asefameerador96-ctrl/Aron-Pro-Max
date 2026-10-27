package com.aktcl.aron.db

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V0118 reuse surface (review High 4, Low 14, 15; D-DB-REUSE-SURFACE-01): dw.fact_memo_line follows dw.fact_memo, the
 * reuse views read it, the printed memo number is personal data, and the reconcile and restatement tables exist.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReuseSurfaceTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use { SeedLoader.load(it) }
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

    // The seed's clock (D-TEST-DATE-PIN-01): the rolling dev seed and the partitions move with it.
    private val day = "app.dhaka_date(now())"
    private val sr = "(SELECT id FROM app.app_user WHERE username = 'sr1001')"
    private val route = "(SELECT id FROM app.route WHERE code = 'MIR-SR-D')"
    private val outlet = "(SELECT min(id) FROM app.outlet WHERE route_id = (SELECT id FROM app.route WHERE code = 'MIR-SR-D'))"

    private fun Connection.memoWithLines(uuid: String, no: String, lines: Int) {
        exec(
            "INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, " +
                "outlet_id, memo_no, memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, " +
                "qc_deduction_mtk, round_adj_mtk, net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count) " +
                "VALUES ('$uuid', '$uuid', $day, $sr, $route, now(), 0, gen_random_uuid(), $outlet, 'sr1001-261005-$no', 'sale', now(), $day, 'outlet', " +
                "${lines * 20000}, 0, 0, 0, 0, ${lines * 20000}, ${lines * 20000}, 0, false, $lines, 0, 0)",
        )
        for (n in 1..lines) exec(
            "INSERT INTO app.memo_line (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, memo_client_uuid, " +
                "line_no, sku_id, line_kind, qty_entered, unit_entered, pack_factor, qty_base, price_type, price_valid_from, base_price_mtk, " +
                "price_per_qty, gross_mtk) SELECT gen_random_uuid(), gen_random_uuid(), $day, $sr, $route, now(), 0, '$uuid', " +
                "$n, min(id), 'sale', 1, 'pack', 20, 20, 'outlet', (SELECT min(valid_from) FROM app.sku_price), 1000, 1, 20000 FROM app.sku WHERE category_code = 'cigarette'",
        )
    }

    private fun Connection.project(uuid: String, status: String = "active") = exec(
        "INSERT INTO dw.fact_memo (business_date, memo_client_uuid, memo_no, user_id, route_id, outlet_id, status, committed_at, line_count, gross_mtk, " +
            "offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, net_mtk, paid_mtk, due_mtk, is_credit, captured_offline, received_at, last_event_id) " +
            "SELECT business_date, client_uuid, memo_no, user_id, route_id, outlet_id, '$status', committed_at, line_count, gross_mtk, 0, 0, 0, net_mtk, paid_mtk, 0, " +
            "false, false, now(), 1 FROM app.memo WHERE client_uuid = '$uuid' " +
            "ON CONFLICT (memo_client_uuid, business_date) DO UPDATE SET status = excluded.status, updated_at = now()",
    )

    @Test
    fun memoLinesFollowTheirMemoFact() = db.connect().use { c ->
        c.tx {
            val m = "0e000000-0000-4000-8000-000000000001"
            memoWithLines(m, "101", 3)
            assertEquals("0", scalar("SELECT count(*) FROM dw.fact_memo_line WHERE memo_client_uuid = '$m'"), "no fact, no lines")
            project(m)
            assertEquals("3|60|60000|seed", scalar("SELECT count(*) || '|' || sum(qty_base) || '|' || sum(gross_mtk) || '|' || string_agg(DISTINCT data_origin::text, ',') FROM dw.v_memo_line WHERE memo_client_uuid = '$m' AND is_effective"))
            // The scope keys come from the memo fact; the line's own origin from app.memo_line.
            assertEquals("1", scalar("SELECT count(DISTINCT (outlet_id, route_id, user_id)) FROM dw.fact_memo_line WHERE memo_client_uuid = '$m'"))
            // A rebuild that marks the memo superseded rewrites its lines: they stay, no longer effective; nothing doubles.
            project(m, "superseded")
            assertEquals("3|0", scalar("SELECT count(*) || '|' || count(*) FILTER (WHERE is_effective) FROM dw.v_memo_line WHERE memo_client_uuid = '$m'"))
            assertEquals("superseded|false", scalar("SELECT status || '|' || is_effective FROM dw.v_memo WHERE memo_client_uuid = '$m'"))
            exec("DELETE FROM dw.fact_memo WHERE memo_client_uuid = '$m'")
            assertEquals("0", scalar("SELECT count(*) FROM dw.fact_memo_line WHERE memo_client_uuid = '$m'"))
        }
    }

    @Test
    fun aWipeAndRebuildOfTheMemoFactsRebuildsTheLinesOnce() = db.connect().use { c ->
        // AggregationTest-style: TRUNCATE the facts (no row trigger fires), then project again; no duplicate, no orphan.
        c.tx {
            val m = "0e000000-0000-4000-8000-000000000002"
            memoWithLines(m, "102", 2)
            project(m)
            exec("TRUNCATE dw.fact_memo")
            assertEquals("0", scalar("SELECT count(*) FROM dw.fact_memo_line"))
            project(m)
            project(m)
            assertEquals("2", scalar("SELECT count(*) FROM dw.fact_memo_line WHERE memo_client_uuid = '$m'"))
        }
    }

    @Test
    fun theReuseViewsExportTheMemoUuidAndThePrintedNumberIsPersonal() = db.connect().use { c ->
        assertEquals("0", c.scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'dw' AND table_name IN ('v_memo', 'v_memo_line', 'v_due_ledger') AND column_name LIKE '%memo_no'"))
        assertEquals("memo_client_uuid", c.scalar("SELECT column_name FROM information_schema.columns WHERE table_schema = 'dw' AND table_name = 'v_memo' AND column_name = 'memo_client_uuid'"))
        for ((rel, col) in listOf("app.memo" to "memo_no", "app.memo_void" to "memo_no", "app.sale_abort" to "memo_no", "app.due_ledger" to "memo_no",
            "app.due_collection" to "against_memo_no", "dw.fact_memo" to "memo_no", "dw.v_memo_flags" to "memo_no")) {
            assertTrue(c.scalar("SELECT col_description('$rel'::regclass, (SELECT attnum FROM pg_attribute WHERE attrelid = '$rel'::regclass AND attname = '$col'))")!!.startsWith("[pii:personal] "), "$rel.$col")
            assertTrue(!c.scalar("SELECT obj_description('$rel'::regclass)")!!.endsWith("pii: none"), rel)
        }
        // The BI reader reads the flagged memos but never the printed number; every other view stays a whole-table grant.
        assertEquals("f|t|t|t", c.scalar("SELECT concat_ws('|', has_column_privilege('bi_reader', 'dw.v_memo_flags', 'memo_no', 'SELECT'), has_column_privilege('bi_reader', 'dw.v_memo_flags', 'memo_client_uuid', 'SELECT'), has_table_privilege('bi_reader', 'dw.v_memo', 'SELECT'), has_column_privilege('web_ro', 'dw.v_memo_flags', 'memo_no', 'SELECT'))"))
        assertEquals("0", c.scalar("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'dw' AND c.relkind = 'v' AND c.relname LIKE 'v\\_%' AND c.relname <> 'v_memo_flags' AND NOT has_table_privilege('bi_reader', c.oid, 'SELECT')"))
    }

    @Test
    fun reconcileFindingsComputeTheirDifferenceAndTheRestatementLogIsAppendOnly() = db.connect().use { c ->
        c.tx {
            exec("INSERT INTO dw.agg_reconcile (business_date, grain, key_id, metric, source_value, agg_value) SELECT $day, 'route', id, 'net_mtk', 120000, 100000 FROM app.route WHERE code = 'MIR-SR-D'")
            assertEquals("20000", scalar("SELECT diff FROM dw.agg_reconcile"))
            exec("UPDATE dw.agg_reconcile SET consecutive_runs = 2, re_enqueued = true")
            // One open finding per date, grain, key and metric (the nightly run upserts it).
            assertEquals("23505", refused("INSERT INTO dw.agg_reconcile (business_date, grain, key_id, metric, source_value, agg_value) SELECT $day, 'route', id, 'net_mtk', 1, 2 FROM app.route WHERE code = 'MIR-SR-D'"))
            assertEquals("23514", refused("INSERT INTO dw.agg_reconcile (business_date, grain, key_id, metric, source_value, agg_value) VALUES ($day, 'house', 1, 'net_mtk', 0, 0)"))
            exec("INSERT INTO dw.agg_restatement_log (business_date, grain, key1, key2, reason, old_row, new_row, delta) VALUES ($day, 'route_sku', 1, 2, 'late_batch', '{\"qty_base\": 20}', '{\"qty_base\": 40}', '{\"qty_base\": 20}')")
            assertEquals("42501", refused("UPDATE dw.agg_restatement_log SET reason = 'rebuild'"))
            assertEquals("42501", refused("DELETE FROM dw.agg_restatement_log"))
            assertEquals("23514", refused("INSERT INTO dw.agg_restatement_log (business_date, grain, key1, reason) VALUES ($day, 'route', 1, 'because')"))
        }
        // The worker writes both; readers outside see neither (not stable views).
        assertEquals("t|t|f", c.scalar("SELECT concat_ws('|', has_table_privilege('worker_rw', 'dw.agg_restatement_log', 'INSERT'), has_table_privilege('worker_rw', 'dw.agg_reconcile', 'UPDATE'), has_table_privilege('bi_reader', 'dw.agg_reconcile', 'SELECT'))"))
    }

    @Test
    fun theMigrationBackfillsLinesOfMemoFactsAlreadyThere() {
        val old = TestPostgres.createDatabase()
        try {
            Flyway.configure().configuration(old.flyway().configuration).target("117").load().migrate()
            old.connect().use { c ->
                SeedLoader.load(c)
                c.memoWithLines("0e000000-0000-4000-8000-000000000031", "131", 2)
                c.project("0e000000-0000-4000-8000-000000000031")
            }
            old.flyway().migrate()
            old.connect().use { c ->
                assertEquals("2", c.scalar("SELECT count(*) FROM dw.v_memo_line WHERE memo_client_uuid = '0e000000-0000-4000-8000-000000000031'"))
            }
        } finally {
            old.close()
        }
    }
}
