package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * V0043 (AUD-DA-02): capture rows freeze zone, cluster, channel and geo class at ingest; app.route_zone_history keeps
 * every zone a route belonged to; a zone move never changes the attribution of rows already captured, and a late
 * upload for a date before the move is attributed to the zone of that date.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaptureContextTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use {
            it.exec(
                """
                INSERT INTO app.wing (code, name) VALUES ('W1', 'Wing 1');
                INSERT INTO app.division (code, name, wing_id) SELECT 'D1', 'Division 1', id FROM app.wing;
                INSERT INTO app.territory (code, name, division_id) SELECT 'T1', 'Territory 1', id FROM app.division;
                INSERT INTO app.zone (code, name, territory_id) SELECT z, z, id FROM app.territory, unnest(ARRAY['Z1', 'Z2', 'Z3']) z;
                INSERT INTO app.cluster (zone_id, name) SELECT id, 'Bazar ' || code FROM app.zone;
                INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'R-1', 'Route 1', id, 'sr', 'daily', 127 FROM app.zone WHERE code = 'Z1';
                INSERT INTO app.app_user (username, full_name, role) VALUES ('sr0001', 'SR One', 'SR');
                INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel, geo_class)
                  SELECT 'O-1', 'Shop', 'Owner', z.id, r.id, c.id, 'GT', (SELECT geo_class FROM app.geo_class_def ORDER BY 1 LIMIT 1)
                    FROM app.zone z JOIN app.cluster c ON c.zone_id = z.id, app.route r WHERE z.code = 'Z1';
                """.trimIndent(),
            )
        }
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

    private fun zone(code: String) = "(SELECT id FROM app.zone WHERE code = '$code')"
    private val route = "(SELECT id FROM app.route WHERE code = 'R-1')"
    private val outlet = "(SELECT id FROM app.outlet WHERE code = 'O-1')"
    private val user = "(SELECT id FROM app.app_user WHERE username = 'sr0001')"

    private fun memo(uuid: String, date: String, seq: String, extra: String = "", values: String = "") =
        "INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, " +
            "outlet_id, memo_no, memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, " +
            "qc_deduction_mtk, round_adj_mtk, net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count$extra) " +
            "VALUES ('$uuid', '$uuid', '$date', $user, $route, now(), 0, gen_random_uuid(), $outlet, 'sr0001-${date.replace("-", "").drop(2)}-$seq', " +
            "'sale', now(), '$date', 'outlet', 1000, 0, 0, 0, 0, 1000, 1000, 0, false, 1, 0, 0$values)"

    private fun Connection.memoContext(uuid: String) = scalar(
        "SELECT concat_ws('|', z.code, c.zone_id = m.zone_id, m.outlet_channel, m.outlet_geo_class IS NOT NULL) " +
            "FROM app.memo m JOIN app.zone z ON z.id = m.zone_id JOIN app.cluster c ON c.id = m.cluster_id WHERE m.client_uuid = '$uuid'",
    )

    private fun Connection.history() = column(
        "SELECT concat_ws('|', z.code, h.valid_from, coalesce(h.valid_to::text, 'open')) FROM app.route_zone_history h " +
            "JOIN app.zone z ON z.id = h.zone_id WHERE h.route_id = $route ORDER BY h.valid_from",
    )

    @Test
    fun aRouteStartsWithOneOpenHistoryRow() = db.connect().use { c ->
        assertEquals(listOf("Z1|-infinity|open"), c.history())
    }

    @Test
    fun aZoneMoveKeepsCapturedRowsAndALateUploadUsesTheZoneOfItsDate() = db.connect().use { c ->
        c.tx {
            val today = scalar("SELECT app.dhaka_date(now())")!!
            val tomorrow = scalar("SELECT app.dhaka_date(now()) + 1")!!
            exec(memo("00000000-0000-4000-8000-00000000c001", "2026-09-15", "001"))
            assertEquals("Z1|t|GT|t", memoContext("00000000-0000-4000-8000-00000000c001"))

            exec("UPDATE app.route SET zone_id = ${zone("Z2")} WHERE code = 'R-1'")
            exec("UPDATE app.route SET zone_id = ${zone("Z3")} WHERE code = 'R-1'")                  // second move before it takes effect replaces it
            assertEquals(listOf("Z1|-infinity|$tomorrow", "Z3|$tomorrow|open"), history())        // effective from the next business date
            exec("UPDATE app.route SET name = 'Route one' WHERE code = 'R-1'")                       // no zone change: no history row
            assertEquals(2, history().size)

            // the captured row keeps Z1; a late upload dated before the move and a row of the move day are Z1; tomorrow is Z3
            assertEquals("Z1|t|GT|t", memoContext("00000000-0000-4000-8000-00000000c001"))
            fun zoneOf(uuid: String) = scalar("SELECT z.code FROM app.memo m JOIN app.zone z ON z.id = m.zone_id WHERE client_uuid = '$uuid'")
            exec(memo("00000000-0000-4000-8000-00000000c002", "2026-09-16", "002"))
            assertEquals("Z1", zoneOf("00000000-0000-4000-8000-00000000c002"))
            exec(memo("00000000-0000-4000-8000-00000000c003", today, "003"))
            assertEquals("Z1", zoneOf("00000000-0000-4000-8000-00000000c003"))
            exec(memo("00000000-0000-4000-8000-00000000c004", tomorrow, "004"))
            assertEquals("Z3", zoneOf("00000000-0000-4000-8000-00000000c004"))

            // last month's sales by frozen zone are unchanged by the move
            assertEquals(listOf("Z1|2000"), column(
                "SELECT z.code || '|' || sum(m.net_mtk) FROM app.memo m JOIN app.zone z ON z.id = m.zone_id " +
                    "WHERE m.business_date BETWEEN '2026-09-01' AND '2026-09-30' GROUP BY z.code"))
            assertEquals("23P01", refused("INSERT INTO app.route_zone_history (route_id, zone_id, valid_from, valid_to) VALUES ($route, ${zone("Z2")}, '2026-01-01', '2026-02-01')"))
        }
    }

    @Test
    fun theContextIsWriteOnceAndAWriterMaySetItItself() = db.connect().use { c ->
        c.tx {
            exec(memo("00000000-0000-4000-8000-00000000c010", "2026-09-20", "010", ", zone_id, outlet_channel", ", ${zone("Z2")}, 'MT'"))
            assertEquals("Z2|f|MT|t", memoContext("00000000-0000-4000-8000-00000000c010"))  // the writer's values win; cluster is the outlet's
            assertEquals("42501", refused("UPDATE app.memo SET zone_id = ${zone("Z1")} WHERE client_uuid = '00000000-0000-4000-8000-00000000c010'"))
            assertEquals("42501", refused("UPDATE app.memo SET outlet_channel = 'GT' WHERE client_uuid = '00000000-0000-4000-8000-00000000c010'"))
        }
    }

    private val due = "INSERT INTO app.due_collection (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, outlet_id, " +
        "against_memo_client_uuid, against_memo_no, against_memo_business_date, amount_mtk, is_full_settlement, outstanding_before_mtk) " +
        "VALUES (gen_random_uuid(), gen_random_uuid(), '2026-09-21', $user, $route, now(), 0, $outlet, gen_random_uuid(), 'sr0001-260920-001', '2026-09-20', 500, false, 1000)"

    @Test
    fun theGuardsLetANullContextBeFilledOnceOnEveryPartition() = db.connect().use { c ->
        c.tx {
            // a row written without the stamp (as before V0043) has NULL context; the guard lets it be filled once
            exec("ALTER TABLE app.due_collection DISABLE TRIGGER due_collection_stamp_context")
            exec(due)
            exec("ALTER TABLE app.due_collection ENABLE TRIGGER due_collection_stamp_context")
            exec("UPDATE app.due_collection SET zone_id = ${zone("Z1")}")
            assertEquals("42501", refused("UPDATE app.due_collection SET zone_id = ${zone("Z2")}"))
            // the replaced guard reached the memo partitions: every partition trigger carries the new arguments
            assertEquals("0", scalar(
                "SELECT count(*) FROM pg_trigger t JOIN pg_class k ON k.oid = t.tgrelid JOIN pg_inherits i ON i.inhrelid = k.oid " +
                    "WHERE i.inhparent IN ('app.memo'::regclass, 'app.visit'::regclass) AND t.tgname IN ('memo_immutable', 'visit_immutable') " +
                    "AND position('=outlet_geo_class' IN encode(t.tgargs, 'escape')) = 0"))
        }
    }

    @Test
    fun theMigrationBackfillsRowsWrittenBeforeIt() {
        val old = TestPostgres.createDatabase()
        try {
            org.flywaydb.core.Flyway.configure().configuration(old.flyway().configuration).target("42").load().migrate()
            old.connect().use { o ->
                o.exec(
                    """
                    INSERT INTO app.wing (code, name) VALUES ('W1', 'Wing 1');
                    INSERT INTO app.division (code, name, wing_id) SELECT 'D1', 'Division 1', id FROM app.wing;
                    INSERT INTO app.territory (code, name, division_id) SELECT 'T1', 'Territory 1', id FROM app.division;
                    INSERT INTO app.zone (code, name, territory_id) SELECT 'Z1', 'Z1', id FROM app.territory;
                    INSERT INTO app.cluster (zone_id, name) SELECT id, 'Bazar' FROM app.zone;
                    INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'R-1', 'Route 1', id, 'sr', 'daily', 127 FROM app.zone;
                    INSERT INTO app.app_user (username, full_name, role) VALUES ('sr0001', 'SR One', 'SR');
                    INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel)
                      SELECT 'O-1', 'Shop', 'Owner', z.id, r.id, c.id, 'MT' FROM app.zone z, app.route r, app.cluster c;
                    """.trimIndent(),
                )
                o.exec(memo("00000000-0000-4000-8000-00000000c020", "2026-09-15", "020"))
                o.exec(due)
            }
            old.flyway().migrate()
            old.connect().use { o ->
                assertEquals("Z1|MT|t", o.scalar("SELECT concat_ws('|', z.code, m.outlet_channel, m.cluster_id IS NOT NULL) FROM app.memo m JOIN app.zone z ON z.id = m.zone_id"))
                assertEquals("Z1|true", o.scalar("SELECT z.code || '|' || (d.cluster_id IS NOT NULL) FROM app.due_collection d JOIN app.zone z ON z.id = d.zone_id"))
                assertEquals("1", o.scalar("SELECT count(*) FROM app.route_zone_history WHERE valid_from = '-infinity' AND valid_to IS NULL"))
            }
        } finally { old.close() }
    }

    @Test
    fun aDueCollectionGetsZoneAndCluster() = db.connect().use { c ->
        c.tx {
            exec(
                "INSERT INTO app.due_collection (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, outlet_id, " +
                    "against_memo_client_uuid, against_memo_no, against_memo_business_date, amount_mtk, is_full_settlement, outstanding_before_mtk) " +
                    "VALUES (gen_random_uuid(), gen_random_uuid(), '2026-09-21', $user, $route, now(), 0, $outlet, gen_random_uuid(), 'sr0001-260920-001', '2026-09-20', 500, false, 1000)",
            )
            assertEquals("Z1|true", scalar("SELECT z.code || '|' || (d.cluster_id IS NOT NULL) FROM app.due_collection d JOIN app.zone z ON z.id = d.zone_id"))
            assertEquals("42501", refused("UPDATE app.due_collection SET cluster_id = NULL"))
        }
    }

    @Test
    fun writersNeedNoRightsOnTheHistoryTable() = db.connect().use { c ->
        assertEquals("false|false", c.scalar(
            "SELECT has_table_privilege('api_rw', 'app.route_zone_history', 'UPDATE')::text || '|' || has_table_privilege('api_rw', 'app.route_zone_history', 'DELETE')::text"))
        c.tx {
            exec("SET LOCAL ROLE api_rw")
            exec("UPDATE app.route SET zone_id = ${zone("Z2")} WHERE code = 'R-1'")    // SECURITY DEFINER trigger writes the history
            exec("RESET ROLE")
            assertEquals("Z2", history().last()!!.substringBefore('|'))
        }
    }
}
