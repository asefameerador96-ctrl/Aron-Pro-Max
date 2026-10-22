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
            c.createStatement().use { it.execute(resource("dashboard_summaries_test.sql")) }
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
        assertEquals("150.000", cig.daily_target); assertEquals(50.0, cig.achievement_pct); assertEquals("75.000", cig.remaining); assertEquals(2, cig.target_routes)
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
        assertEquals(listOf(66.67, 0.0, 26.67, 6.67, 0.0, 0.0), std.channels.map { it.share_pct })
        val calls = svc.channelCalls(national(), all)
        val cpr = svc.cpr(national(), all)
        assertEquals(listOf(2L, 0L, 1L, 1L, 0L, 0L), calls.channels.map { it.value })
        assertEquals(cpr.successful_calls, calls.total)                        // brief s3.2: the channels add up to the successful calls
        assertEquals(0, calls.unclassified)
        assertEquals(5, cpr.target_outlets); assertEquals(4, cpr.successful_calls); assertEquals(80.0, cpr.cpr_pct)
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
        assertEquals(4, g.total_calls); assertEquals(3, g.geo_validated); assertEquals(75.0, g.geo_pct)    // visit 3 was abandoned, visit 2 out of range
        val l = svc.loginSubmit(national(), all)
        assertEquals(3, l.target_routes); assertEquals(2, l.successful_login); assertEquals(66.67, l.login_pct)   // R3 never downloaded
        assertEquals(1, l.submitted); assertEquals(50.0, l.submit_pct)
        val f = svc.finalSubmit(national(), all)
        assertEquals(2, f.total_zones); assertEquals(2, f.service_zones); assertEquals(1, f.final_submitted); assertEquals(1, f.remaining); assertEquals(50.0, f.submit_pct)
    }

    @Test
    fun theMidnightJobSubmitsUnsubmittedZonesOfPastDaysOnly() {
        assertEquals(1, svc.finalSubmit(national(), all).final_submitted)                  // today: zone 1 still open
        now = Instant.parse("2026-10-05T01:00:00Z")                                        // 07:00 Dhaka the next day
        AggregationWorker(fresh.db, AronClock { now }).runUntilIdle()
        val f = svc.finalSubmit(national(), all)
        assertEquals(2, f.final_submitted); assertEquals(0, f.remaining); assertEquals(100.0, f.submit_pct)
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
        assertEquals(2, cpr.successful_calls); assertEquals(40.0, cpr.cpr_pct)
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
}
