package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachNode
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The twelve cards of the national dashboard on the control-total day of seed_day.sql plus national_day_extra.sql
 * (docs/ui-reference/web/dashboard-brief.md). Expectations are hand computed; the brief's own arithmetic checks are
 * reproduced: channel STD adds up to the cigarette card, channel calls add up to the CPR successful calls, memo counts per
 * brand are not additive, the daily target is the monthly target over working days without Fridays and holidays.
 */
class NationalDashboardTest {
    private val day = LocalDate.parse("2026-10-04")
    private lateinit var fresh: FreshDb
    private var now = Instant.parse("2026-10-04T06:00:00Z")   // 12:00 Dhaka, the day in progress
    private lateinit var svc: NationalDashboardService
    private var z1 = 0L
    private var z2 = 0L

    private fun resource(n: String) = javaClass.classLoader.getResourceAsStream(n)!!.bufferedReader().readText()

    @BeforeEach
    fun setUp() {
        fresh = FreshDb.create()
        fresh.dataSource.connection.use { c ->
            c.createStatement().use { it.execute(resource("seed_day.sql")) }
            c.createStatement().use { it.execute(resource("national_day_extra.sql")) }
            for (u in listOf("a1", "a2", "a4", "a5")) c.createStatement().use {
                it.execute("INSERT INTO app.domain_event (event_type, aggregate_type, aggregate_id, business_date, source_client_uuid) VALUES ('memo.created','memo','x','2026-10-04','00000000-0000-4000-8000-0000000000$u')")
            }
        }
        rebuild()
        z1 = id("SELECT id FROM app.zone WHERE code = 'Z1'"); z2 = id("SELECT id FROM app.zone WHERE code = 'Z2'")
        svc = NationalDashboardService(fresh.db, AronClock { now })
    }

    private fun rebuild() {
        val w = AggregationWorker(fresh.db, AronClock { now })
        fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
        w.requestRebuild(day); w.runUntilIdle()
    }

    @AfterEach
    fun tearDown() = fresh.close()

    private fun id(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery(sql).mapTo(Long::class.java).one() }
    private fun national() = NationalCaller(Reach(1, Role.TOP, day, true, emptySet(), emptySet(), false, emptyList()))
    private fun zones(vararg z: Long) = NationalCaller(Reach(1, Role.TSO, day, false, z.toSet(), emptySet(), false, listOf(ReachNode("zone", z.first()))))
    private fun brandUser(vararg brandCodes: String): NationalCaller {
        val ids = brandCodes.map { id("SELECT id FROM app.product_node WHERE level = 'brand' AND code = '$it'") }.toSet()
        return NationalCaller(Reach(1, Role.TOP, day, true, emptySet(), emptySet(), false, emptyList()), ids)
    }
    private val all = NationalDashboardFilter(date = "2026-10-04")

    @Test
    fun salesCardsShowTotalsRingAndDailyTargetFromMonthlyOverWorkingDays() {
        val s = svc.sales(national(), all)
        assertEquals(25, s.working_days)
        val cig = s.cards.single { it.category_code == "cigarette" }
        assertEquals(75, cig.total_sales)
        // Monthly 2 500 + 1 250 over 25 working days = 150 a day; 75 / 150 = 50.0 %; remaining 75.
        assertEquals("150.000", cig.daily_target); assertEquals(5000L, cig.achievement_pct_bp); assertEquals("75.000", cig.remaining); assertEquals(2, cig.target_routes)
        assertEquals(75, cig.sales_so_far); assertEquals("stick", cig.base_unit)
        assertEquals("2026-10-04", s.date); assertTrue(s.till_time)   // 06:00Z is 12:00 Dhaka on the same day: figures are "till time"
    }

    @Test
    fun channelStdAddsUpToTheCigaretteCardAndChannelCallsToTheCprCalls() {
        val sales = svc.sales(national(), all).cards.single { it.category_code == "cigarette" }.total_sales
        val std = svc.channelStd(national(), all)
        assertEquals(sales, std.total)                                         // brief s4.1: the channels add up to Sales (Cigarette)
        assertEquals(listOf("GT", "DCC", "Astha", "RCC", "MT", "HoReCa"), std.channels.map { it.channel })
        assertEquals(listOf(50L, 0L, 20L, 5L, 0L, 0L), std.channels.map { it.value })
        assertEquals(listOf(6667, 0, 2667, 667, 0, 0), std.channels.map { it.share_pct_bp })
        val calls = svc.channelCalls(national(), all)
        val cpr = svc.cpr(national(), all)
        assertEquals(listOf(2L, 0L, 1L, 1L, 0L, 0L), calls.channels.map { it.value })
        assertEquals(cpr.successful_calls, calls.total)                        // brief s3.2: the channels add up to the successful calls
        assertEquals(0, calls.unclassified)
        assertEquals(5, cpr.target_outlets); assertEquals(4, cpr.successful_calls); assertEquals(8000, cpr.cpr_pct_bp)
    }

    @Test
    fun memoCountsPerBrandAreDistinctMemosNotAdditiveAcrossBrands() {
        val brand = svc.breakdown(national(), NationalBreakdownRequest(all, "memo", "brand"))
        assertEquals(listOf("BrandOne" to 3L, "BrandTwo" to 2L), brand.items.map { it.name to it.value })
        assertTrue(brand.items.sumOf { it.value } > 4)                         // 4 active memos in the day: 3 + 2 > 4, one memo holds both brands
        assertNull(brand.unit)
        assertEquals(listOf(4L), svc.breakdown(national(), NationalBreakdownRequest(all, "memo", "segment")).items.map { it.value })
        assertEquals(listOf(3L, 2L), svc.breakdown(national(), NationalBreakdownRequest(all, "memo", "variant")).items.map { it.value })
        val std = svc.breakdown(national(), NationalBreakdownRequest(all, "std", "brand"))
        assertEquals("cigarette", std.category_code); assertEquals("stick", std.unit)
        assertEquals(listOf("B1" to 60L, "B2" to 15L), std.items.map { it.code to it.value })
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { svc.breakdown(national(), NationalBreakdownRequest(all, "std", "outlet")) }.code)
    }

    @Test
    fun geoFencingLoginAndFinalSubmitFollowTheBrief() {
        val g = svc.geoFencing(national(), all)
        assertEquals(4, g.total_calls); assertEquals(3, g.geo_validated); assertEquals(7500, g.geo_pct_bp)    // visit 3 was abandoned, visit 2 out of range
        val l = svc.loginSubmit(national(), all)
        assertEquals(3, l.target_routes); assertEquals(2, l.successful_login); assertEquals(6667, l.login_pct_bp)   // R3 never downloaded
        assertEquals(1, l.submitted); assertEquals(5000, l.submit_pct_bp)
        val f = svc.finalSubmit(national(), all)
        assertEquals(2, f.total_zones); assertEquals(2, f.service_zones); assertEquals(1, f.final_submitted); assertEquals(1, f.remaining); assertEquals(5000, f.submit_pct_bp)
    }

    @Test
    fun theMidnightJobSubmitsUnsubmittedZonesOfPastDaysOnly() {
        assertEquals(1, svc.finalSubmit(national(), all).final_submitted)                  // today: zone 1 still open
        now = Instant.parse("2026-10-05T01:00:00Z")                                        // 07:00 Dhaka the next day
        AggregationWorker(fresh.db, AronClock { now }).runUntilIdle()
        val f = svc.finalSubmit(national(), all)
        assertEquals(2, f.final_submitted); assertEquals(0, f.remaining); assertEquals(10000, f.submit_pct_bp)
        val auto = fresh.db.jdbi.withHandle<List<Boolean>, Exception> { it.createQuery("SELECT auto_submitted FROM dw.agg_daily_zone WHERE business_date = '2026-10-04' ORDER BY zone_id").mapTo(Boolean::class.java).list() }
        assertEquals(listOf(true, false), auto)                                            // zone 2 was submitted by a TSO, zone 1 by the job
    }

    @Test
    fun scopeComesFromTheTokenAndTheClientCannotWidenIt() {
        assertEquals(45, svc.sales(zones(z1), all).cards.single { it.category_code == "cigarette" }.total_sales)
        // Asking for the other zone returns nothing; asking for both is cut to the reach.
        assertEquals(0, svc.sales(zones(z1), all.copy(zone_ids = listOf(z2))).cards.single { it.category_code == "cigarette" }.total_sales)
        assertEquals(45, svc.sales(zones(z1), all.copy(zone_ids = listOf(z1, z2))).cards.single { it.category_code == "cigarette" }.total_sales)
        assertEquals(30, svc.sales(national(), all.copy(zone_ids = listOf(z2))).cards.single { it.category_code == "cigarette" }.total_sales)
        assertEquals(4, svc.cpr(zones(z1), all).target_outlets)
        // Every card is cut to the reach, not only the first two: zone 1 holds R1 and R2 (outlets O1..O4, 3 completed calls, 2 routes).
        assertEquals(3, svc.channelCalls(zones(z1), all).total); assertEquals(45, svc.channelStd(zones(z1), all).total)
        assertEquals(1, svc.finalSubmit(zones(z1), all).total_zones); assertEquals(2, svc.loginSubmit(zones(z1), all).target_routes)
        assertEquals(3, svc.geoFencing(zones(z1), all).total_calls)
        assertEquals(listOf(45L), svc.breakdown(zones(z1), NationalBreakdownRequest(all, "std", "segment")).items.map { it.value })
        assertEquals(3, svc.breakdown(zones(z1), NationalBreakdownRequest(all, "memo", "segment")).items.single().value)
    }

    @Test
    fun checker_filterOptionsListOnlyActiveHousesAndTheirZones() {
        sql("UPDATE app.house SET status = 'inactive' WHERE code = 'H1D'")                 // a merged duplicate: inactive (D-SCOPE-TREE-01)
        fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
        val o = svc.filterOptions(national())
        assertEquals(listOf("H1"), o.houses.map { it.code })
        assertEquals(listOf("Z1"), o.zones.map { it.code })                                // zone 2 still names the inactive duplicate
    }

    @Test
    fun filterOptionsListOnlyTheCallersReach() {
        val o = svc.filterOptions(zones(z1))
        assertEquals(listOf("Z1"), o.zones.map { it.code }); assertEquals(1, o.wings.size); assertEquals(1, o.houses.size)
        assertEquals(listOf("Z1", "Z2"), svc.filterOptions(national()).zones.map { it.code })
    }

    @Test
    fun houseFilterUsesTheOneActiveHousePerNameAndListsItOnce() {
        val opts = svc.filterOptions(national())
        assertEquals(1, opts.houses.size)                                                  // "House A" and its duplicate "house a" are one
        val houseId = opts.houses.single().id
        assertEquals(75, svc.sales(national(), all.copy(house_ids = listOf(houseId))).cards.single { it.category_code == "cigarette" }.total_sales)
        assertEquals(listOf("Z1", "Z2"), opts.zones.map { it.code }); assertEquals(setOf(opts.houses.single().id), opts.zones.map { it.house_id }.toSet())
        assertEquals(listOf("cigarette"), opts.categories.map { it.code })
        assertEquals(listOf("B1", "B2"), opts.brands.map { it.code }); assertEquals(listOf("SEG"), opts.brands.map { it.segment_code }.distinct())
    }

    @Test
    fun brandUsersSeeOnlyTheirBrandsAndNoCardWithoutABrandDimension() {
        val b = brandUser("B1")
        assertEquals(60, svc.sales(b, all).cards.single { it.category_code == "cigarette" }.total_sales)
        assertEquals(listOf("B1"), svc.breakdown(b, NationalBreakdownRequest(all, "std", "brand")).items.map { it.code })
        assertEquals(60, svc.channelStd(b, all).total)
        for (hidden in listOf<() -> Unit>({ svc.cpr(b, all) }, { svc.finalSubmit(b, all) }, { svc.loginSubmit(b, all) }, { svc.geoFencing(b, all) })) {
            assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { hidden() }.code)
        }
    }

    @Test
    fun productFiltersNarrowTheProductCardsAndTheCprCalls() {
        val f = all.copy(brand_codes = listOf("B2"))
        assertEquals(15, svc.sales(national(), f).cards.single { it.category_code == "cigarette" }.total_sales)
        val cpr = svc.cpr(national(), f)                                                   // outlets that bought BrandTwo: O2 (Astha) and O4 (RCC)
        assertEquals(2, cpr.successful_calls); assertEquals(4000, cpr.cpr_pct_bp)
        assertEquals(listOf(0L, 0L, 1L, 1L, 0L, 0L), svc.channelCalls(national(), f).channels.map { it.value })
    }

    @Test
    fun everyCardCarriesTheTimeOfTheLastCompletePass() {
        val t = Instant.parse("2026-10-04T06:00:00Z")
        assertEquals(t.toString(), svc.sales(national(), all).last_updated_at)
        assertEquals(t.toString(), svc.geoFencing(national(), all).last_updated_at)
        val fr = svc.freshness()
        assertEquals(t.toString(), fr.last_updated_at); assertTrue(fr.tables.all { it.last_updated_at != null })
        assertNotNull(svc.cpr(national(), all).as_of)
    }

    private fun sql(s: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(s) }

    @Test
    fun checker_manualDummyAndAmoRoutesAreNotTargetRoutes() {
        sql("UPDATE app.route SET route_class = 'manual' WHERE code = 'R3'")
        fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
        val l = svc.loginSubmit(national(), all)
        assertEquals(2, l.target_routes)                                                   // R3 (manual) left out
        assertEquals(1, svc.finalSubmit(national(), all).service_zones)                    // zone 2 only had the manual route
        assertEquals(4, svc.cpr(national(), all).target_outlets)
        sql("UPDATE app.route SET route_class = 'normal' WHERE code = 'R3'; UPDATE app.route SET kind = 'amo' WHERE code = 'R3'")
        fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
        assertEquals(2, svc.loginSubmit(national(), all).target_routes)                    // an AMO route is not an SR route
    }

    @Test
    fun checker_aPlannedRouteNobodyOpenedStillCountsAsTargetRoute() {
        sql("""
            INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'R4', 'Route4', id, 'sr', 'daily', 127 FROM app.zone WHERE code = 'Z1';
            INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel)
              SELECT 'O9', 'Outlet 9', 'Owner', r.zone_id, r.id, (SELECT id FROM app.cluster WHERE zone_id = r.zone_id), 'GT' FROM app.route r WHERE r.code = 'R4';
            INSERT INTO app.route_day (route_id, business_date, planned) SELECT id, DATE '2026-10-04', true FROM app.route WHERE code = 'R4';
        """)
        fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
        AggregationWorker(fresh.db, AronClock { now }).runUntilIdle()                      // the stale scan queues the route-day nobody touched
        val l = svc.loginSubmit(national(), all)
        assertEquals(4, l.target_routes); assertEquals(2, l.successful_login)              // 2 of 4, not 2 of 3
        assertEquals(6, svc.cpr(national(), all).target_outlets)                           // R4's one outlet counts although no bundle froze its target
    }

    @Test
    fun checker_productNodesWithoutACodeStillBreakDownAndFilter() {
        sql("UPDATE app.product_node SET code = NULL WHERE level IN ('brand', 'segment', 'variant')")
        fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
        val items = svc.breakdown(national(), NationalBreakdownRequest(all, "memo", "brand")).items
        assertEquals(listOf(3L, 2L), items.map { it.value }); assertTrue(items.all { it.code.startsWith("n") })
        assertEquals(2, svc.filterOptions(national()).brands.size)
        assertEquals(15, svc.sales(national(), all.copy(brand_codes = listOf(items.last().code))).cards.single { it.category_code == "cigarette" }.total_sales)
    }

    @Test
    fun checker_memoCountsAreRefusedWhereTheyCouldNotBeExact() {
        for ((dim, f) in listOf("segment" to all.copy(brand_codes = listOf("B1")), "brand" to all.copy(variant_codes = listOf("V1")))) {
            assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { svc.breakdown(national(), NationalBreakdownRequest(f, "memo", dim)) }.code)
        }
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { svc.breakdown(brandUser("B1"), NationalBreakdownRequest(all, "memo", "segment")) }.code)
        assertEquals(listOf(3L), svc.breakdown(brandUser("B1"), NationalBreakdownRequest(all, "memo", "brand")).items.map { it.value })   // exact: the brand table
        assertEquals(listOf(60L), svc.breakdown(brandUser("B1"), NationalBreakdownRequest(all, "std", "segment")).items.map { it.value })
    }

    @Test
    fun checker_freshnessNeverClaimsMoreThanTheOldestWaitingChange() {
        val later = Instant.now().plusSeconds(60)   // wall-clock-ok: the queue stamps last_dirtied_at with the database clock
        sql("SELECT app.mark_dirty('route_day_agg', (SELECT id FROM app.route WHERE code = 'R1'), DATE '2026-10-04', 'stuck')")
        fresh.db.jdbi.useHandle<Exception> { DashboardSummaries.touchFreshness(it, later) }
        val last = Instant.parse(svc.freshness().last_updated_at!!)
        assertTrue(last.isBefore(later))                   // the queue still holds a change: not "fresh now"
    }

    private fun weekendConfig(v: JsonElement) = object : ServerConfig {
        override fun value(key: String): JsonElement = if (key == "cfg.calendar.weekend_days") v else JsonNull
        override fun configVersion(): Long = 1
    }

    @Test
    fun workingDaysFollowTheConfiguredWeekend() {
        val october = YearMonth.of(2026, 10)   // 5 Fridays, 5 Saturdays, 4 Sundays, one global holiday on a Tuesday
        fun days(cfg: ServerConfig?): Int {
            val s = NationalDashboardService(fresh.db, AronClock { now }, cfg)
            return fresh.db.jdbi.withHandle<Int, Exception> { WorkingCalendar.workingDays(it, october, s.weekendDays()) }
        }
        assertEquals(25, days(null), "no config: the Friday default")
        assertEquals(25, days(weekendConfig(JsonArray(listOf(JsonPrimitive(5))))))
        assertEquals(20, days(weekendConfig(JsonArray(listOf(JsonPrimitive(5), JsonPrimitive(6))))), "Friday and Saturday off")
        assertEquals(26, days(weekendConfig(JsonArray(listOf(JsonPrimitive(7))))), "Sunday only")
        assertEquals(30, days(weekendConfig(JsonArray(emptyList()))), "an empty list is no weekend (registry allows 0..3 days), one holiday off")
        assertEquals(30, days(weekendConfig(JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(9))))), "out-of-range days are ignored, leaving no weekend")
        assertEquals(25, days(weekendConfig(JsonPrimitive("junk"))), "a malformed value falls back to the default")
    }

    @Test
    fun theSalesCardsUseTheConfiguredWeekendForTheDailyTarget() {
        val friSat = NationalDashboardService(fresh.db, AronClock { now }, weekendConfig(JsonArray(listOf(JsonPrimitive(5), JsonPrimitive(6)))))
        val s = friSat.sales(national(), all)
        assertEquals(20, s.working_days)
        // Monthly 2 500 + 1 250 over 20 working days = 187.5 a day (was 150 over 25).
        assertEquals("187.500", s.cards.single { it.category_code == "cigarette" }.daily_target)
        val none = NationalDashboardService(fresh.db, AronClock { now }, weekendConfig(JsonArray(emptyList())))
        assertEquals(30, none.sales(national(), all).working_days)
    }
}
