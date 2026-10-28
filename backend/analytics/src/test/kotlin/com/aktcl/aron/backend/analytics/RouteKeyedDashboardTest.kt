package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachNode
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * D-BC-DASH-ROUTE-KEYED-01 (db answer V0122, D-DB-ROUTE-DAY-VIEW-01): a TSO holding route assignments and no geo node reads its
 * routes' figures from `dw.v_route_day` / `dw.v_route_day_channel` and the route-grain splits, never a zone's. Seeded day: Z1 = R1, R2;
 * Z2 = R3 (DashboardTest's control totals).
 */
class RouteKeyedDashboardTest {
    private val day = LocalDate.parse("2026-10-04")
    private lateinit var fresh: FreshDb
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private lateinit var service: DashboardService
    private lateinit var ops: OpsService
    private var z1 = 0L
    private var r1 = 0L
    private var r2 = 0L
    private var r3 = 0L

    @BeforeEach
    fun setUp() {
        fresh = FreshDb.create()
        fresh.dataSource.connection.use { c ->
            c.createStatement().use { it.execute(javaClass.classLoader.getResourceAsStream("seed_day.sql")!!.bufferedReader().readText()) }
            c.createStatement().use { it.execute(javaClass.classLoader.getResourceAsStream("login_extra.sql")!!.bufferedReader().readText()) }
            for (u in listOf("a1", "a2", "a4", "a5")) c.createStatement().use {
                it.execute("INSERT INTO app.domain_event (event_type, aggregate_type, aggregate_id, business_date, source_client_uuid) VALUES ('memo.created','memo','x','2026-10-04','00000000-0000-4000-8000-0000000000$u')")
            }
        }
        AggregationWorker(fresh.db).also { w -> w.runUntilIdle(); fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }; w.requestRebuild(day); w.runUntilIdle() }
        z1 = id("SELECT id FROM app.zone WHERE code = 'Z1'")
        r1 = id("SELECT id FROM app.route WHERE code = 'R1'"); r2 = id("SELECT id FROM app.route WHERE code = 'R2'"); r3 = id("SELECT id FROM app.route WHERE code = 'R3'")
        service = DashboardService(fresh.db, AronClock { now })
        ops = OpsService(fresh.db, RegistryDefaults(), AronClock { now })
    }

    @AfterEach
    fun tearDown() = fresh.close()

    private fun id(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery(sql).mapTo(Long::class.java).one() }
    private fun zoneReach(z: Long) = Reach(1, Role.TSO, day, false, setOf(z), emptySet(), false, listOf(ReachNode("zone", z)))
    private fun routeOnly(vararg routes: Long) = Reach(2, Role.TSO, day, false, emptySet(), routes.toSet(), false, routes.sorted().map { ReachNode("route", it, "c$it", "n$it") })
    private fun DashboardKpis.routeFigures() = copy(zones_with_target_routes = null, zones_final_submitted = null, final_submit_pct = null)

    @Test
    fun theViewsSumsOverAZonesRoutesEqualTheZoneRollup() {
        // The analytics half of D-DB-ROUTE-DAY-VIEW-01: the zone rollup's rules applied to dw.v_route_day give the row rebuildZoneDay wrote.
        val measures = listOf(
            "target_routes", "logged_in_routes", "sales_submitted_routes", "final_submitted", "target_outlets", "visited_outlets", "successful_calls", "visits",
            "geo_valid_visits", "force_sale_visits", "mock_visits", "suspicious_visits", "active_memo_count", "gross_mtk", "net_mtk", "dues_collected_mtk",
        )
        val (rollup, fromView) = fresh.db.jdbi.withHandle<Pair<List<Map<String, Any?>>, List<Map<String, Any?>>>, Exception> { h ->
            for (z in h.createQuery("SELECT DISTINCT zone_id FROM dw.agg_daily_route WHERE business_date = :d").bind("d", day).mapTo(Long::class.java).list()) Aggregator.rebuildZoneDay(h, z, day)
            h.createQuery("SELECT zone_id, ${measures.joinToString()} FROM dw.agg_daily_zone WHERE business_date = :d ORDER BY zone_id").bind("d", day).mapToMap().list() to
                h.createQuery(
                    """
                    SELECT zone_id, count(*) FILTER (WHERE is_target_route) target_routes, count(*) FILTER (WHERE is_target_route AND successful_login) logged_in_routes,
                           count(*) FILTER (WHERE is_target_route AND sales_submitted) sales_submitted_routes,
                           (count(*) FILTER (WHERE is_target_route) > 0 AND coalesce(bool_and(day_state = 'final_submitted') FILTER (WHERE is_target_route), false)) final_submitted,
                           coalesce(sum(target_outlets) FILTER (WHERE is_target_route), 0) target_outlets, sum(visited_outlets) visited_outlets, sum(successful_calls) successful_calls,
                           sum(visits) visits, sum(geo_valid_visits) geo_valid_visits, sum(force_sale_visits) force_sale_visits, sum(mock_visits) mock_visits,
                           sum(suspicious_visits) suspicious_visits, sum(active_memo_count) active_memo_count, sum(gross_mtk) gross_mtk, sum(net_mtk) net_mtk,
                           sum(dues_collected_mtk) dues_collected_mtk
                      FROM dw.v_route_day WHERE business_date = :d GROUP BY zone_id ORDER BY zone_id
                    """,
                ).bind("d", day).mapToMap().list()
        }
        fun norm(rows: List<Map<String, Any?>>) = rows.map { r -> r.mapValues { (_, v) -> if (v is Number) v.toLong() else v } }
        assertEquals(2, rollup.size, "both zones have a rollup row: $rollup")
        assertTrue(rollup.sumOf { (it["gross_mtk"] as Number).toLong() } > 0 && rollup.sumOf { (it["visits"] as Number).toLong() } > 0, "the fixture has sales and visits")
        assertEquals(norm(rollup), norm(fromView))
    }

    @Test
    fun aRouteOnlyCallerHoldingEveryRouteOfAZoneSeesThatZonesFigures() {
        val zone = service.summary(zoneReach(z1), null, null, day, day)
        val routes = service.summary(routeOnly(r1, r2), null, null, day, day)
        assertEquals("route" to r1, routes.node.type to routes.node.id, "labelled by the first route")
        assertEquals(zone.kpis, routes.kpis, "every KPI, the zone-day final-submit counts included")
        assertEquals(zone.by_category, routes.by_category); assertEquals(zone.by_brand, routes.by_brand); assertEquals(zone.by_segment, routes.by_segment)
        assertEquals(zone.by_channel, routes.by_channel, "channel at capture equals the current channel when no outlet moved")
        assertEquals(zone.children, routes.children)
        val zg = service.geoValidation(zoneReach(z1), null, null, day, day)
        val rg = service.geoValidation(routeOnly(r1, r2), null, null, day, day)
        assertEquals(zg.copy(as_of = "", node = rg.node), rg.copy(as_of = ""))
    }

    @Test
    fun aRouteOnlyCallerWithOneRouteOfAZoneSeesThatRouteAndNothingElse() {
        val zone = service.summary(zoneReach(z1), null, null, day, day)
        val child = zone.children.single { it.node.id == r1 }
        // Risk signals: one on R1 (counted), one on R2 and one with a zone and no route (both outside a route-only view).
        fresh.db.jdbi.useHandle<Exception> { h ->
            for ((code, route) in listOf("GEO_MOCK" to "'R1'", "GEO_TELEPORT" to "'R2'", "GEO_STALE_FIX" to null)) h.execute(
                "INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, route_id, zone_id, score, config_version) " +
                    "SELECT '$code', 2, DATE '$day', 'user', 'qa-$code', u.id, ${if (route == null) "NULL" else "(SELECT id FROM app.route WHERE code = $route)"}, $z1, 10, 0 " +
                    "FROM app.app_user u WHERE u.username = 'sr001'",
            )
        }
        val own = service.summary(routeOnly(r1), null, null, day, day)
        assertEquals(child.kpis, own.kpis.routeFigures(), "the route's figures are the zone view's row for that route")
        assertEquals(listOf(r1), own.children.map { it.node.id })
        assertTrue(own.kpis.gross_mtk in 1 until zone.kpis.gross_mtk, "control: R2 sells too, so a zone read would differ (${own.kpis.gross_mtk} vs ${zone.kpis.gross_mtk})")
        assertEquals(1, own.kpis.zones_with_target_routes, "the unit stays zone-days: Z1")
        assertEquals(zone.kpis.zones_final_submitted, own.kpis.zones_final_submitted, "the Final Submit of the route's zone")
        assertEquals(own.kpis.net_mtk, own.by_channel.sumOf { it.net_mtk }); assertEquals(own.kpis.successful_calls, own.by_channel.sumOf { it.successful_calls })
        val ownGeo = service.geoValidation(routeOnly(r1), null, null, day, day)
        val zoneGeoChild = service.geoValidation(zoneReach(z1), null, null, day, day).children.single { it.node.id == r1 }
        assertEquals(zoneGeoChild.visits to zoneGeoChild.geo_valid_visits, ownGeo.visits to ownGeo.geo_valid_visits)
        assertEquals(listOf(zoneGeoChild), ownGeo.children)
        assertEquals(mapOf("GEO_MOCK" to 1), ownGeo.signals_by_code, "only R1's signal")
        assertEquals(mapOf("GEO_TELEPORT" to 1, "GEO_MOCK" to 1, "GEO_STALE_FIX" to 1), service.geoValidation(zoneReach(z1), null, null, day, day).signals_by_code, "control: the zone sees all three")
        // No explicit node: a route-only caller reaches no zone, territory or the nation (403, as before).
        for ((lv, n) in listOf("zone" to z1, "zone" to id("SELECT id FROM app.zone WHERE code = 'Z2'"), "territory" to id("SELECT id FROM app.territory"), "national" to null)) {
            assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { service.summary(routeOnly(r1), lv, n, day, day) }.code, "summary $lv")
            assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { service.geoValidation(routeOnly(r1), lv, n, day, day) }.code, "geo $lv")
            assertEquals(ProblemCode.ERR_OUT_OF_SCOPE, assertFailsWith<ApiProblem> { ops.flaggedMemos(routeOnly(r1), day, day, lv, n, null, 50, null) }.code, "flagged $lv")
        }
    }

    @Test
    fun theCacheNeverCrossesRouteSetsThatShareTheFirstRoute() {
        val one = service.summary(routeOnly(r1), null, null, day, day)
        val two = service.summary(routeOnly(r1, r2), null, null, day, day)
        assertEquals(one.node, two.node, "same label")
        assertTrue(two.kpis.gross_mtk > one.kpis.gross_mtk, "a cached answer for R1 alone must not serve R1 + R2")
        assertTrue(service.geoValidation(routeOnly(r1, r2), null, null, day, day).visits > service.geoValidation(routeOnly(r1), null, null, day, day).visits)
    }

    @Test
    fun opsReadsOfARouteOnlyCallerStayOnItsRoutes() {
        val reach = routeOnly(r1)
        val ls = ops.loginSubmit(reach, service, day, null, null)
        val listed = (ls.not_logged_in + ls.logged_in_not_submitted + ls.submitted + ls.exceptions).map { it.route_id }
        assertEquals(listOf(r1), listed, "login-submit lists R1 only")
        val zoneLs = ops.loginSubmit(zoneReach(z1), service, day, null, null)
        assertEquals(zoneLs.zones, ls.zones, "zones[]: the zone of the caller's target route, with its Final Submit")
        assertEquals(service.summary(reach, null, null, day, day).kpis, ls.kpis)
        val health = ops.syncHealth(reach, day, null, null, false, 100, null)
        assertTrue(health.items.all { it.route_ids == listOf(r1) }, "${health.items.map { it.route_ids }}")
        assertEquals(listOf(z1), health.by_zone.map { it.zone_id })
        val zoneDay = ops.syncHealth(zoneReach(z1), day, null, null, false, 100, null).by_zone.single()
        val ownZone = health.by_zone.single()
        assertEquals(zoneDay.final_submitted, ownZone.final_submitted)
        val tracking = DailyTrackingService(fresh.db, RegistryDefaults(), AronClock { now }).page(reach, day, null, null, 100, null)
        assertEquals(listOf(r1), tracking.items.map { it.route_id })
        val other = DailyTrackingService(fresh.db, RegistryDefaults(), AronClock { now }).page(routeOnly(r3), day, null, null, 100, null)
        assertEquals(listOf(r3), other.items.map { it.route_id })
        val flagged = ops.flaggedMemos(reach, day, day, null, null, null, 100, null)
        assertTrue(flagged.items.all { it.route_id == r1 }, "${flagged.items.map { it.route_id }}")
    }
}
