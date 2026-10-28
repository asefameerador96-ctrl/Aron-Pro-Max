package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * V0122 (D-DB-ROUTE-DAY-VIEW-01, D-BC-DASH-ROUTE-ONLY-01): dw.v_route_day gives the zone-day measures and flags per route,
 * so a route-only TSO's dashboard can sum over its own routes; dw.v_route_day_channel splits sales by the channel frozen
 * on the memo and follows the route that sold.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouteDayViewTest {
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

    // The seed's clock (D-TEST-DATE-PIN-01).
    private val day = "app.dhaka_date(now())"
    private val zone = "(SELECT id FROM app.zone WHERE code = 'Z-MIR')"

    @Test
    fun theRouteRowsCarryTheZoneDayFlagsAndSumToTheZoneTotals() = db.connect().use { c ->
        c.tx {
            exec("DELETE FROM dw.agg_daily_route WHERE business_date = $day")
            exec("DELETE FROM dw.agg_daily_zone WHERE business_date = $day")
            // Three routes of the zone: a target route that logged in fully and submitted, a target route without check-in,
            // and a route with an approved exception.
            exec(
                "INSERT INTO dw.agg_daily_route (business_date, route_id, zone_id, planned, exception_approved, day_state, logged_in_at, downloaded_at, checked_in_at, " +
                    "target_outlets, visited_outlets, successful_calls, visits, active_memo_count, gross_mtk, net_mtk) " +
                    "SELECT $day, r.id, r.zone_id, v.planned, v.exc, v.state, now(), now(), CASE WHEN v.n = 2 THEN NULL ELSE now() END, 10, v.n, v.n, v.n, v.n, v.n * 1000, v.n * 900 " +
                    "FROM (SELECT id, zone_id, row_number() OVER (ORDER BY id) AS k FROM app.route WHERE zone_id = $zone) r " +
                    "JOIN (VALUES (1, true, false, 'sales_submitted'), (2, true, false, 'sales_submitted'), (3, true, true, 'not_started')) v(n, planned, exc, state) ON v.n = r.k",
            )
            assertEquals("3", scalar("SELECT count(*) FROM dw.v_route_day WHERE business_date = $day AND zone_id = $zone"), "the seed zone has three routes")
            assertEquals(
                listOf("1|t|t|t", "2|t|f|f", "3|f|t|f"),
                column("SELECT visits || '|' || is_target_route || '|' || successful_login || '|' || sales_submitted FROM dw.v_route_day WHERE business_date = $day AND zone_id = $zone ORDER BY visits")
                    .map { it!!.replace("true", "t").replace("false", "f") },
            )
            // The zone's Final Submit reaches each of its routes.
            assertEquals("0", scalar("SELECT count(*) FILTER (WHERE zone_final_submitted) FROM dw.v_route_day WHERE business_date = $day AND zone_id = $zone"))
            // The midnight auto-submit stamps the zone-day without setting final_submitted; a later Final Submit sets it.
            exec("INSERT INTO dw.agg_daily_zone (business_date, zone_id, territory_id, final_submitted, final_submitted_at, auto_submitted) SELECT $day, $zone, territory_id, false, now(), true FROM app.zone WHERE id = $zone")
            assertEquals("0|3", scalar("SELECT count(*) FILTER (WHERE zone_final_submitted) || '|' || count(*) FILTER (WHERE zone_auto_submitted) FROM dw.v_route_day WHERE business_date = $day AND zone_id = $zone"))
            exec("UPDATE dw.agg_daily_zone SET final_submitted = true, auto_submitted = false WHERE business_date = $day AND zone_id = $zone")
            assertEquals("3|0", scalar("SELECT count(*) FILTER (WHERE zone_final_submitted) || '|' || count(*) FILTER (WHERE zone_auto_submitted) FROM dw.v_route_day WHERE business_date = $day AND zone_id = $zone"))
            // The zone rollup's rules, computed from the route rows: target_routes 2, logged_in 1, sales_submitted 1, visits 6, net 5400.
            assertEquals(
                "2|1|1|20|6|5400",
                scalar(
                    "SELECT count(*) FILTER (WHERE is_target_route) || '|' || count(*) FILTER (WHERE is_target_route AND successful_login) || '|' || " +
                        "count(*) FILTER (WHERE is_target_route AND sales_submitted) || '|' || sum(target_outlets) FILTER (WHERE is_target_route) || '|' || sum(visits) || '|' || sum(net_mtk) " +
                        "FROM dw.v_route_day WHERE business_date = $day AND zone_id = $zone",
                ),
            )
        }
    }

    @Test
    fun theChannelSplitFollowsTheMemoNotTheOutletsLaterMove() = db.connect().use { c ->
        c.tx {
            val m = "0e000000-0000-4000-8000-0000000000c1"
            val route = "(SELECT id FROM app.route WHERE code = 'MIR-SR-D')"
            val outlet = "(SELECT min(id) FROM app.outlet WHERE route_id = $route)"
            exec(
                "INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, " +
                    "outlet_id, memo_no, memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, " +
                    "qc_deduction_mtk, round_adj_mtk, net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count) " +
                    "VALUES ('$m', '$m', $day, (SELECT id FROM app.app_user WHERE username = 'sr1001'), $route, now(), 0, gen_random_uuid(), $outlet, 'sr1001-261005-901', 'sale', now(), $day, 'outlet', " +
                    "20000, 0, 0, 0, 0, 20000, 20000, 0, false, 1, 0, 0)",
            )
            exec(
                "INSERT INTO dw.fact_memo (business_date, memo_client_uuid, memo_no, user_id, route_id, outlet_id, status, committed_at, line_count, gross_mtk, " +
                    "offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, net_mtk, paid_mtk, due_mtk, is_credit, captured_offline, received_at, last_event_id) " +
                    "SELECT business_date, client_uuid, memo_no, user_id, route_id, outlet_id, 'active', committed_at, line_count, gross_mtk, 0, 0, 0, net_mtk, paid_mtk, 0, " +
                    "false, false, now(), 1 FROM app.memo WHERE client_uuid = '$m'",
            )
            val channel = scalar("SELECT outlet_channel FROM app.memo WHERE client_uuid = '$m'")!!
            assertEquals("t", scalar("SELECT (successful_calls <= active_memo_count)::text FROM dw.v_route_day_channel WHERE business_date = $day AND route_id = $route AND outlet_channel = (SELECT outlet_channel FROM app.memo WHERE client_uuid = '$m')")!!.take(1))
            val before = scalar("SELECT active_memo_count || '|' || net_mtk FROM dw.v_route_day_channel WHERE business_date = $day AND route_id = $route AND outlet_channel = '$channel'")!!
            // The outlet is later reclassified and moved to another route: the memo's channel and route stay.
            exec("UPDATE app.outlet SET channel = CASE channel WHEN 'GT' THEN 'MT' ELSE 'GT' END, route_id = (SELECT max(id) FROM app.route WHERE id <> $route) WHERE id = $outlet")
            assertEquals(before, scalar("SELECT active_memo_count || '|' || net_mtk FROM dw.v_route_day_channel WHERE business_date = $day AND route_id = $route AND outlet_channel = '$channel'"))
            exec("UPDATE dw.fact_memo SET status = 'voided' WHERE memo_client_uuid = '$m'")
            val n = before.substringBefore('|').toInt() - 1
            assertEquals(n.toString(), scalar("SELECT coalesce((SELECT active_memo_count FROM dw.v_route_day_channel WHERE business_date = $day AND route_id = $route AND outlet_channel = '$channel'), 0)"))
        }
    }
}
