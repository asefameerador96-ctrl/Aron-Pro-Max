package com.aktcl.aron.db

import org.flywaydb.core.Flyway
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** V0191 (review B5): the backfill of app.late_row_fact, its write-once grant, and the two columns that travel with a record. */
class LateRowFactV0191Test {
    private val day = "DATE '2026-10-05'" // date-pin-ok: a fixed day inside the migrated partitions; the test reads no clock

    private fun at(hhmm: String) = "TIMESTAMPTZ '2026-10-05 $hhmm+00'" // date-pin-ok: fixed instants of the fixture

    private fun memo(uuid: String, no: String, received: String) =
        "INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, " +
            "outlet_id, memo_no, memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, " +
            "qc_deduction_mtk, round_adj_mtk, net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count, received_at) " +
            "VALUES ('$uuid', '$uuid', $day, (SELECT id FROM app.app_user WHERE username = 'sr1001'), (SELECT id FROM app.route WHERE code = 'MIR-SR-D'), ${at("08:00")}, 0, gen_random_uuid(), " +
            "(SELECT min(id) FROM app.outlet WHERE route_id = (SELECT id FROM app.route WHERE code = 'MIR-SR-D')), 'sr1001-261005-$no', 'sale', ${at("08:00")}, $day, 'outlet', 1000, 0, 0, 0, 0, 1000, 1000, 0, false, 1, 0, 0, $received)"

    private fun due(uuid: String, received: String) =
        "INSERT INTO app.due_collection (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, outlet_id, " +
            "against_memo_client_uuid, against_memo_no, against_memo_business_date, amount_mtk, is_full_settlement, outstanding_before_mtk, received_at) " +
            "VALUES ('$uuid', '$uuid', $day, (SELECT id FROM app.app_user WHERE username = 'sr1001'), (SELECT id FROM app.route WHERE code = 'MIR-SR-D'), ${at("08:00")}, 0, " +
            "(SELECT min(id) FROM app.outlet WHERE route_id = (SELECT id FROM app.route WHERE code = 'MIR-SR-D')), gen_random_uuid(), 'x-1', $day, 100, false, 500, $received)"

    @Test
    fun rowsStoredInsideAClosedWindowBeforeTheTableExistedGetAFactAndNoOtherRowDoes() {
        val old = TestPostgres.createDatabase()
        try {
            Flyway.configure().configuration(old.flyway().configuration).target("190").load().migrate()
            val open1 = "0f000000-0000-4000-8000-000000000001"; val closed1 = "0f000000-0000-4000-8000-000000000002"
            val between = "0f000000-0000-4000-8000-000000000003"; val closed2 = "0f000000-0000-4000-8000-000000000004"
            val dueOpen = "0f000000-0000-4000-8000-000000000005"; val dueClosed = "0f000000-0000-4000-8000-000000000006"
            old.connect().use { c ->
                SeedLoader.load(c)
                val zone = "(SELECT zone_id FROM app.route WHERE code = 'MIR-SR-D')"
                val by = "(SELECT min(id) FROM app.app_user)"
                // window 1: 15:00 to 17:00 (reopened); window 2: from 19:00, still closed
                c.exec("INSERT INTO app.final_submit (client_uuid, zone_id, business_date, submitted_by, submitted_at, via, reopened_at, reopened_by, reopen_reason) " +
                    "VALUES (gen_random_uuid(), $zone, $day, NULL, ${at("15:00")}, 'job', ${at("17:00")}, $by, 'qa reopen of the zone-day')")
                c.exec("INSERT INTO app.final_submit (client_uuid, zone_id, business_date, submitted_by, submitted_at, via) VALUES (gen_random_uuid(), $zone, $day, NULL, ${at("19:00")}, 'job')")
                c.exec(memo(open1, "001", at("14:00"))); c.exec(memo(closed1, "002", at("16:00"))); c.exec(memo(between, "003", at("18:00"))); c.exec(memo(closed2, "004", at("20:00")))
                c.exec(due(dueOpen, at("13:00"))); c.exec(due(dueClosed, at("16:30")))
            }
            old.flyway().migrate()
            old.connect().use { c ->
                assertEquals(setOf(closed1, closed2, dueClosed), c.column("SELECT client_uuid::text FROM app.late_row_fact").filterNotNull().toSet(), "only rows stored inside a closed window")
                assertEquals("0", c.scalar("SELECT count(*) FROM app.late_row_fact WHERE NOT stored_closed"))
                // the table holds the exceptions: a fact with stored_closed = false cannot exist
                assertFailsWith<SQLException> { c.exec("INSERT INTO app.late_row_fact (client_uuid, stored_closed) VALUES (gen_random_uuid(), false)") }
            }
        } finally {
            old.close()
        }
    }

    @Test
    fun theFactTableIsWrittenOnceAndTheTwoRecordColumnsExist() {
        val db = TestPostgres.createDatabase().migrated()
        try {
            db.connect().use { c ->
                for ((priv, want) in listOf("SELECT" to "true", "INSERT" to "true", "UPDATE" to "false", "DELETE" to "false")) {
                    assertEquals(want, c.scalar("SELECT has_table_privilege('api_rw', 'app.late_row_fact', '$priv')::text"), "api_rw $priv")
                }
                assertEquals("false", c.scalar("SELECT has_table_privilege('worker_rw', 'app.late_row_fact', 'UPDATE')::text"))
                assertEquals("0", c.scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'late_row_fact' AND column_name = 'anchor_clock'"))
                assertEquals("1", c.scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'route_day_event' AND column_name = 'anchor_clock'"))
                assertEquals("1", c.scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'outlet_change_request' AND column_name = 'approved_cluster_id'"))
            }
        } finally {
            db.close()
        }
    }
}
