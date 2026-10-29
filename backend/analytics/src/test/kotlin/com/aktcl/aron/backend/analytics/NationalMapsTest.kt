package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachNode
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FF Geo pins and pop-up, the route map and the sale position (brief s5.2 to s5.4) on the Gulshan-like map fixture. */
class NationalMapsTest {
    private val day = LocalDate.parse("2026-10-04")
    private lateinit var fresh: FreshDb
    private val now = Instant.parse("2026-10-04T09:00:00Z")
    private lateinit var svc: NationalDashboardService
    private var r1 = 0L; private var r2 = 0L; private var z2 = 0L

    private fun resource(n: String) = javaClass.classLoader.getResourceAsStream(n)!!.bufferedReader().readText()

    @BeforeEach
    fun setUp() {
        fresh = FreshDb.create()
        fresh.dataSource.connection.use { c ->
            for (f in listOf("seed_day.sql", "national_day_extra.sql", "national_map_extra.sql")) c.createStatement().use { it.execute(resource(f)) }
        }
        fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
        AggregationWorker(fresh.db, AronClock { now }).also { it.requestRebuild(day); it.runUntilIdle() }
        r1 = id("SELECT id FROM app.route WHERE code = 'R1'"); r2 = id("SELECT id FROM app.route WHERE code = 'R2'"); z2 = id("SELECT id FROM app.zone WHERE code = 'Z2'")
        svc = NationalDashboardService(fresh.db, AronClock { now })
    }

    @AfterEach
    fun tearDown() = fresh.close()

    private fun id(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery(sql).mapTo(Long::class.java).one() }
    private fun national() = NationalCaller(Reach(1, Role.TOP, day, true, emptySet(), emptySet(), false, emptyList()))
    private fun zones(vararg z: Long) = NationalCaller(Reach(1, Role.TSO, day, false, z.toSet(), emptySet(), false, listOf(ReachNode("zone", z.first()))))
    private val all = NationalDashboardFilter(date = "2026-10-04")

    @Test
    fun pinColourBoundariesAreRedBelow70AmberUpTo90AndGreenAbove() {
        assertEquals("red", pinColor(69, 100)); assertEquals("amber", pinColor(70, 100)); assertEquals("amber", pinColor(90, 100))
        assertEquals("green", pinColor(91, 100)); assertEquals("green", pinColor(0, 0)); assertEquals("red", pinColor(0, 3))
    }

    @Test
    fun pinsAreOnePerRouteAtTheLatestPositionColouredByGeoFencing() {
        val p = svc.ffGeoPins(national(), FfGeoPinsRequest(all))
        assertEquals(listOf(r1, r2), p.pins.map { it.route_id })
        val a = p.pins.first { it.route_id == r1 }; val b = p.pins.first { it.route_id == r2 }
        assertEquals(23.7937, a.lat); assertEquals(90.4043, a.lng)                      // the 06:30Z fix is the latest of R1
        assertEquals("red", a.color); assertEquals(5000, a.geo_pct_bp)                      // 1 of 2 completed calls inside the radius
        assertEquals("green", b.color); assertEquals(10000, b.geo_pct_bp); assertEquals(23.79375, b.lat)   // R2's latest is its 05:00Z edit position
        assertEquals(ColorCounts(1, 0, 1), p.counts); assertEquals(1, p.routes_without_position)         // R3 sent no position yet
        val red = svc.ffGeoPins(national(), FfGeoPinsRequest(all, listOf("red")))
        assertEquals(listOf(r1), red.pins.map { it.route_id }); assertEquals(ColorCounts(1, 0, 1), red.counts)   // counts are before the colour filter
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { svc.ffGeoPins(national(), FfGeoPinsRequest(all, listOf("blue"))) }.code)
        assertEquals(emptyList(), svc.ffGeoPins(zones(z2), FfGeoPinsRequest(all)).pins.map { it.route_id })        // zone 2's only route sent no position; zone 1's pins are not its to see
    }

    @Test
    fun routePopupGivesTheHeaderTheMissPercentAndNoPhoneNumber() {
        val p = svc.ffGeoRoutePopup(national(), r1, "2026-10-04")
        assertEquals("R1", p.route.code); assertEquals("SR One", p.sr_name); assertNull(p.contact)
        assertEquals("T1", p.territory.code); assertEquals("Z1", p.zone.code); assertEquals(5000, p.miss_pct_bp)
        assertEquals("2026-10-04T06:30:00Z", p.position_at)
        assertEquals(ProblemCode.ERR_NOT_FOUND, assertFailsWith<ApiProblem> { svc.ffGeoRoutePopup(zones(z2), r1, "2026-10-04") }.code)   // outside the reach: no existence leak
    }

    @Test
    fun routeMapShowsMarkersPathAndTheThreeCards() {
        val m = svc.routeMap(national(), r1, "2026-10-04")
        assertEquals(listOf("O1" to "geo_validated", "O2" to "photo_validation", "O3" to "not_called"), m.outlets.map { it.code to it.state })
        assertEquals(listOf(CategoryQty("cigarette", "stick", 25)), m.outlets.first { it.code == "O1" }.sales)   // 20 + the 5-stick repeat sale
        assertEquals(emptyList(), m.outlets.first { it.code == "O3" }.sales)
        assertEquals(23.79251, m.sale_start!!.lat)                                       // where the first call of the day was made
        assertEquals(23.7937, m.sr_last_position!!.lat); assertEquals(3, m.path.size)
        assertEquals(3470.55, m.time_card.distance_covered_m)                            // metres along the three fixes, independent haversine
        assertEquals(RouteMapCpr(3, 2, 1, 6667), m.cpr)
        val cig = m.target_vs_achievement.first { it.category_code == "cigarette" }
        assertEquals("100.000", cig.daily_target); assertEquals(45, cig.sales); assertEquals(4500L, cig.achievement_pct_bp)   // 2 500 / 25 working days
        assertEquals("2500.000", cig.monthly_target_qty); assertEquals(25, cig.working_days)                                  // R-W-06: T and W sent
        assertEquals("2026-10-04T04:10:00Z", m.time_card.first_sale_time); assertEquals("2026-10-04T08:00:00Z", m.time_card.last_sale_time)
        assertEquals(13_800L, m.time_card.total_sale_seconds); assertEquals(6_900L, m.time_card.average_sale_seconds)   // 3 h 50 min over 2 successful calls
        assertEquals("CZ1", m.last_sale_place)
        assertTrue(m.outlets.all { it.contact == null })
        assertEquals(ProblemCode.ERR_NOT_FOUND, assertFailsWith<ApiProblem> { svc.routeMap(zones(z2), r1, "2026-10-04") }.code)
    }

    /**
     * Lead ruling R-W-06 (tso-manual X2): the route map's Target vs Achievement card uses the Sales card's rule through the same
     * functions ([dailyTargetThousandths], [salesAchievementBp]): bp = round_half_up(10000 x sales x W / T) once from the integers, the
     * daily target T / W for display only; T (`monthly_target_qty`, a whole ".000") and W (`working_days`, at the route's zone) are
     * sent. Hand computed with an independent Python-integer oracle (round half up).
     */
    @Test
    fun routeMapTargetCardUsesTheSalesCardRule() {
        val z1 = id("SELECT zone_id FROM app.route WHERE id = $r1")
        fun card() = svc.routeMap(national(), r1, "2026-10-04").target_vs_achievement.single { it.category_code == "cigarette" }
        fun sql(s: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(s) }
        // W 26 at R1's zone: Friday the 30th declared a selling day there (25 + 1). T 2530, sales 45.
        sql("INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en) VALUES ('2026-10-30', 'zone', $z1, 'makeup_day', true, 'X2 make-up day')")
        sql("UPDATE app.route_monthly_target SET qty_base = 2530 WHERE route_id = $r1")
        val c = card()
        assertEquals(26, c.working_days); assertEquals("2530.000", c.monthly_target_qty); assertEquals(45L, c.sales)
        assertEquals("97.308", c.daily_target)                                           // 2530 / 26 = 97.3077 -> 97.308, rounded once
        // 10000 x 45 x 26 / 2530 = 4624.505 -> 4625. The old rule, 45 over the rounded 97.308, gave 450000 / 97.308 = 4624.46 -> 4624.
        assertEquals(4625L, c.achievement_pct_bp)
        assertEquals(c.achievement_pct_bp, salesAchievementBp(45, 26, 2530), "the Sales card's function")
        assertEquals(4624L, ratioBp(java.math.BigDecimal(45), java.math.BigDecimal("97.308")), "the old daily-target rule differs by 1 bp here")
        // A target that sums to 0: daily "0.000", no bp (Percent.bp of a 0 whole), T still sent.
        sql("UPDATE app.route_monthly_target SET qty_base = 0 WHERE route_id = $r1")
        card().let { assertEquals("0.000", it.daily_target); assertNull(it.achievement_pct_bp); assertEquals("0.000", it.monthly_target_qty); assertEquals(26, it.working_days) }
        // No working day at the zone (R-F-13): no daily target, no bp, W 0 sent, T still sent.
        sql("UPDATE app.route_monthly_target SET qty_base = 2530 WHERE route_id = $r1")
        sql("DELETE FROM app.calendar_holiday WHERE scope_type = 'zone'")
        sql("INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en) SELECT d::date, 'zone', $z1, 'emergency_off', false, 'X2 off' FROM generate_series(DATE '2026-10-01', DATE '2026-10-31', interval '1 day') d")
        card().let { assertEquals(0, it.working_days); assertNull(it.daily_target); assertNull(it.achievement_pct_bp); assertEquals("2530.000", it.monthly_target_qty) }
        // No target at all: T null, no daily target, no bp; W still sent.
        sql("DELETE FROM app.calendar_holiday WHERE scope_type = 'zone'")
        sql("DELETE FROM app.route_monthly_target WHERE route_id = $r1")
        card().let { assertNull(it.monthly_target_qty); assertNull(it.daily_target); assertNull(it.achievement_pct_bp); assertEquals(25, it.working_days); assertEquals(45L, it.sales) }
    }

    @Test
    fun salePositionTellsHitFromMissAndNewFromRepeatAndEdited() {
        val o = { code: String -> id("SELECT id FROM app.outlet WHERE code = '$code'") }
        val miss = svc.salePosition(national(), o("O2"), "2026-10-04")
        assertEquals(50, miss.radius_m); assertEquals(SalePositionBase(23.7925, 90.4078), miss.base)
        val s = miss.sales.single()
        assertEquals("new", s.kind); assertEquals(81.83, s.distance_m); assertEquals(false, s.within_fence); assertEquals("miss", s.status)
        assertEquals("00000000-0000-4000-8000-0000000000b1", s.photo_media_uuid); assertEquals(81.83, miss.header_distance_m)   // "SR Hit / Miss Position (81.83 Meter)"
        val hit = svc.salePosition(national(), o("O1"), "2026-10-04")
        assertEquals(listOf("new", "repeat"), hit.sales.map { it.kind }); assertTrue(hit.sales.all { it.within_fence && it.photo_media_uuid == null })
        val edited = svc.salePosition(national(), o("O4"), "2026-10-04").sales.single()
        assertEquals("edited", edited.kind); assertEquals(23.79375, edited.lat); assertEquals(5.56, edited.distance_m); assertTrue(edited.within_fence)   // the edit's own position
        assertEquals(ProblemCode.ERR_NOT_FOUND, assertFailsWith<ApiProblem> { svc.salePosition(zones(z2), o("O2"), "2026-10-04") }.code)
    }

    @Test
    fun brandUsersGetNoMapPages() {
        val b = NationalCaller(Reach(1, Role.TOP, day, true, emptySet(), emptySet(), false, emptyList()), setOf(1L))
        for (call in listOf<() -> Unit>({ svc.ffGeoPins(b, FfGeoPinsRequest(all)) }, { svc.ffGeoRoutePopup(b, r1, null) }, { svc.routeMap(b, r1, null) }, { svc.salePosition(b, 1, null) })) {
            assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { call() }.code)
        }
    }

    @Test
    fun checker_aVoidedFixLeavesThePathAndTheDistance() {
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("UPDATE app.geo_fix SET voided_at = now() WHERE route_id = $r1 AND lat = 23.7808")
        }
        AggregationWorker(fresh.db, AronClock { now }).also { it.requestRebuild(day); it.runUntilIdle() }
        val m = svc.routeMap(national(), r1, "2026-10-04")
        assertEquals(2, m.path.size)
        assertEquals(1, m.path.count { it.lat == 23.7808 }.let { 1 - it })
        assertEquals(3470.55 > m.time_card.distance_covered_m, true)                       // now only the first and the last fix: A to C
    }
}
