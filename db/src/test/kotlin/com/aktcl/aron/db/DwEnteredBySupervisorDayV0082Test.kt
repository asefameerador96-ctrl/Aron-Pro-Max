package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** V0082/V0083 (docs/requests/backend-reports-db-entered-by.md): dw entered_by_user_id, dw.fact_supervisor_day, dirty kind. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DwEnteredBySupervisorDayV0082Test {
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

    private val sr = "(SELECT id FROM app.app_user WHERE username = 'sr1001')"
    private val admin = "(SELECT id FROM app.app_user WHERE username = 'admin1001')"

    private fun fact(no: Int, source: String, enteredBy: String) =
        "INSERT INTO dw.fact_memo (business_date, memo_client_uuid, memo_no, user_id, outlet_id, status, committed_at, line_count, gross_mtk, " +
            "offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, net_mtk, paid_mtk, due_mtk, is_credit, captured_offline, received_at, last_event_id, " +
            "entry_source, entered_by_user_id) VALUES (DATE '2026-10-05', gen_random_uuid(), 'M-$no', $sr, (SELECT min(id) FROM app.outlet), 'active', now(), " +
            "1, 1000, 0, 0, 0, 1000, 1000, 0, false, false, now(), 1, '$source', $enteredBy)"

    @Test
    fun aFactNamesItsWebUserOnlyWhenManual() = db.connect().use { c ->
        c.tx {
            exec(fact(1, "device", "NULL"))
            exec(fact(2, "manual", admin))
            exec(fact(3, "manual", "NULL"))   // the projector before it copies entered_by_user_id: still accepted
            assertEquals("23514", refused(fact(4, "device", admin)))
        }
        for (t in listOf("fact_memo", "fact_visit")) {
            assertEquals(
                "t",
                c.scalar("SELECT convalidated FROM pg_constraint WHERE conname = '${t}_entered_by_manual_only' AND conrelid = 'dw.$t'::regclass"),
            )
        }
    }

    @Test
    fun theSupervisorDayFactHoldsOneRowPerUserAndDate() = db.connect().use { c ->
        c.tx {
            val amo = "(SELECT id FROM app.app_user WHERE username = 'amo1001')"
            exec("INSERT INTO dw.fact_supervisor_day (user_id, business_date, role, checked_in_at) VALUES ($amo, DATE '2026-10-05', 'AMO', now())")
            assertEquals("1|1|f", scalar("SELECT count(*) || '|' || max(submit_cycle) || '|' || bool_or(submit_voided)::text::char FROM dw.fact_supervisor_day"))
            assertEquals("23505", refused("INSERT INTO dw.fact_supervisor_day (user_id, business_date, role) VALUES ($amo, DATE '2026-10-05', 'AMO')"))
            assertEquals("23514", refused("INSERT INTO dw.fact_supervisor_day (user_id, business_date, role) VALUES ($amo, DATE '2026-10-06', 'SR')"))
        }
        fun can(role: String, priv: String) = c.scalar("SELECT has_table_privilege('$role', 'dw.fact_supervisor_day', '$priv')::text")
        assertEquals("true|true|true|true", listOf("SELECT", "INSERT", "UPDATE", "DELETE").joinToString("|") { can("worker_rw", it)!! })
        assertEquals("false|false", listOf("web_ro", "bi_reader").joinToString("|") { can(it, "SELECT")!! })
    }

    @Test
    fun theWorkerCanQueueASupervisorDayRebuild() = db.connect().use { c ->
        c.tx {
            exec("SELECT app.mark_dirty('supervisor_day_agg', 42, DATE '2026-10-05', 'test')")
            assertEquals("1", scalar("SELECT count(*) FROM app.dirty_key WHERE kind = 'supervisor_day_agg'"))
            assertEquals("23514", refused("SELECT app.mark_dirty('supervisor_day', 42, DATE '2026-10-05')"))
        }
        assertEquals("t", c.scalar("SELECT convalidated FROM pg_constraint WHERE conname = 'dirty_key_kind_check' AND conrelid = 'app.dirty_key'::regclass"))
    }
}
