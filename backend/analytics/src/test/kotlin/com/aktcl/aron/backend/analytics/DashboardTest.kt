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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** F-API-014 on the seeded day: scoped rollups from dw only, 5 s cache per scope hash (D-NIGHT-SYNC-01), as-of stamp, under one second. */
class DashboardTest {
    private val day = LocalDate.parse("2026-10-04")
    private lateinit var fresh: FreshDb
    private var now = Instant.parse("2026-10-04T12:00:00Z")
    private lateinit var service: DashboardService
    private var z1 = 0L
    private var z2 = 0L
    private var terr = 0L

    @BeforeEach
    fun setUp() {
        fresh = FreshDb.create()
        fresh.dataSource.connection.use { c ->
            c.createStatement().use { it.execute(javaClass.classLoader.getResourceAsStream("seed_day.sql")!!.bufferedReader().readText()) }
            c.createStatement().use { it.execute(javaClass.classLoader.getResourceAsStream("login_extra.sql")!!.bufferedReader().readText()) }   // Successful Login: download and check-in
            for (u in listOf("a1", "a2", "a4", "a5")) c.createStatement().use {
                it.execute("INSERT INTO app.domain_event (event_type, aggregate_type, aggregate_id, business_date, source_client_uuid) VALUES ('memo.created','memo','x','2026-10-04','00000000-0000-4000-8000-0000000000$u')")
            }
        }
        AggregationWorker(fresh.db).also { w -> w.runUntilIdle(); fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }; w.requestRebuild(day); w.runUntilIdle() }
        z1 = id("SELECT id FROM app.zone WHERE code = 'Z1'"); z2 = id("SELECT id FROM app.zone WHERE code = 'Z2'"); terr = id("SELECT id FROM app.territory")
        service = DashboardService(fresh.db, AronClock { now })
    }

    @AfterEach
    fun tearDown() = fresh.close()

    private fun id(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery(sql).mapTo(Long::class.java).one() }
    private fun reach(vararg zones: Long, national: Boolean = false) =
        Reach(1, Role.TSO, day, national, zones.toSet(), emptySet(), false, if (national) emptyList() else listOf(ReachNode("zone", zones.first())))

    @Test
    fun nationalFiguresEqualTheHandComputedControlTotals() {
        val s = service.summary(reach(national = true), null, null, day, day)
        assertEquals("national", s.node.type)
        // Zones 1 and 2 together: 3 target routes, 3 logged in, 2 sales-submitted; 5 target outlets; 4 successful calls; 4 memos; gross 63 000.
        assertEquals(3, s.kpis.target_routes); assertEquals(3, s.kpis.logged_in_routes); assertEquals(100.0, s.kpis.login_pct)
        assertEquals(2, s.kpis.sales_submitted_routes); assertEquals(66.67, s.kpis.submit_pct_of_logged_in); assertEquals(66.67, s.kpis.day_completion_pct)
        assertEquals(5, s.kpis.target_outlets); assertEquals(4, s.kpis.visited_outlets); assertEquals(4, s.kpis.successful_calls); assertEquals(80.0, s.kpis.strike_rate_pct)
        assertEquals(4, s.kpis.active_memo_count); assertEquals(63_000, s.kpis.gross_mtk); assertEquals(63_000, s.kpis.net_mtk)
        // tso-manual B2 (D-TSO-MAN-09, 30): geo = completed calls of the national route set (visit 3 was abandoned: 3 of 4, as the national
        // Geo Fencing card); a zone counts as final-submitted only with a live app.final_submit row, and seed_day.sql has none.
        assertEquals(75.0, s.kpis.geo_valid_pct); assertEquals(20.0, s.kpis.force_sale_pct); assertEquals(1, s.kpis.mock_visits)
        assertEquals(2, s.kpis.zones_with_target_routes); assertEquals(0, s.kpis.zones_final_submitted); assertEquals(0.0, s.kpis.final_submit_pct)
        assertEquals(listOf("wing"), s.children.map { it.node.type }.distinct())
        assertEquals(63_000, s.children.sumOf { it.kpis.net_mtk })
        assertEquals(63_000L, s.by_category.sumOf { it.net_mtk }); assertEquals("cigarette", s.by_category.single().category_code)
        assertEquals("pack", s.by_category.single().entry_unit); assertEquals("7.500", s.by_category.single().qty_entered)   // 75 sticks of 10s, summed per SKU first
        assertEquals(2, s.by_brand.size)
        // BSR of brand 1: 3 memos contain it (R1 two, R3 one) of 4 active memos = 75 %.
        assertEquals(75.0, s.by_brand.first { it.name == "BrandOne" }.memo_ratio_pct)
        assertEquals("GT", s.by_channel.single().code); assertEquals(4, s.by_channel.single().successful_calls)
        assertNull(s.by_channel.single().qty_base, "base units differ per category: no channel quantity is added up")
        assertEquals("2026-10-04", s.from)
    }

    @Test
    fun aZoneCallerSeesOnlyItsZoneAndCannotAskForAnother() {
        val s = service.summary(reach(z1), null, null, day, day)
        assertEquals("zone", s.node.type); assertEquals(z1, s.node.id)
        assertEquals(2, s.kpis.target_routes); assertEquals(39_000, s.kpis.gross_mtk); assertEquals(2, s.children.size)   // a zone's children are its routes: R1, R2
        assertEquals(setOf("route"), s.children.map { it.node.type }.toSet())
        val denied = assertFailsWith<ApiProblem> { service.summary(reach(z1), "zone", z2, day, day) }
        assertEquals(ProblemCode.ERR_FORBIDDEN, denied.code)
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { service.summary(reach(z1), "national", null, day, day) }.code)
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { service.summary(reach(z1), "zone", 999_999, day, day) }.code)   // unknown = outside: no existence leak
        // A territory node is allowed only when every zone under it is in reach.
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { service.summary(reach(z1), "territory", terr, day, day) }.code)
        assertEquals(63_000, service.summary(reach(z1, z2), "territory", terr, day, day).kpis.gross_mtk)
    }

    @Test
    fun cachedFiveSecondsPerScopeAndNeverAcrossScopes() {
        // D-NIGHT-SYNC-01: 5 s (was 30 s), so the cache never holds a synced memo off the dashboard past the 15 s goal.
        val a = service.summary(reach(z1), null, null, day, day)
        now = now.plusSeconds(4)
        assertSame(a, service.summary(reach(z1), null, null, day, day))
        val other = service.summary(reach(z2), null, null, day, day)
        assertTrue(other !== a); assertEquals(24_000, other.kpis.gross_mtk)
        now = now.plus(Duration.ofSeconds(2))
        assertTrue(service.summary(reach(z1), null, null, day, day) !== a, "recomputed once 5 s passed")
    }

    @Test
    fun asOfIsTheNewestAggregateAndRangesAreValidated() {
        val s = service.summary(reach(national = true), null, null, day, day)
        assertNotNull(Instant.parse(s.as_of))
        val empty = service.summary(reach(national = true), null, null, day.plusDays(10), day.plusDays(10))
        assertEquals(0, empty.kpis.target_routes); assertNull(empty.kpis.login_pct); assertEquals(0, empty.children.size)
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { service.summary(reach(national = true), null, null, day, day.minusDays(1)) }.code)
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { service.summary(reach(national = true), null, null, day, day.plusDays(93)) }.code)
    }

    @Test
    fun answersInUnderOneSecond() {
        service.summary(reach(national = true), null, null, day, day)   // warm the connection
        val t0 = System.nanoTime()
        DashboardService(fresh.db, AronClock { now }).summary(reach(national = true), null, null, day, day)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000)
    }

    @Test
    fun checker_rangeBoundaryAndScopedBreakdowns() {
        // 92 days after from is allowed, 93 is not.
        service.summary(reach(national = true), null, null, day, day.plusDays(92))
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { service.summary(reach(national = true), null, null, day, day.plusDays(93)) }.code)
        // A zone caller's breakdowns never include the other zone's figures.
        val s = service.summary(reach(z2), null, null, day, day)
        assertEquals(24_000, s.kpis.gross_mtk)
        assertEquals(24_000L, s.by_category.sumOf { it.net_mtk }); assertEquals(24_000L, s.by_channel.sumOf { it.net_mtk })
        assertTrue(s.by_brand.sumOf { it.net_mtk } <= 24_000L)
        assertEquals(24_000L, s.children.sumOf { it.kpis.net_mtk })
        // Every percentage stays inside the contract range 0..100.
        val all = service.summary(reach(national = true), null, null, day, day)
        for (k in listOf(all.kpis) + all.children.map { it.kpis }) for (v in listOf(k.login_pct, k.submit_pct_of_logged_in, k.day_completion_pct, k.strike_rate_pct, k.geo_valid_pct, k.force_sale_pct, k.final_submit_pct))
            if (v != null) assertTrue(v in 0.0..100.0)
        for (b in all.by_brand) assertTrue((b.memo_ratio_pct ?: 0.0) <= 100.0)
    }

    private fun sql(q: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(q) }

    @Test
    fun bySegmentCountsEachMemoOncePerSegmentAndIsScoped() {
        val s = service.summary(reach(national = true), null, null, day, day)
        val seg = s.by_segment.single()
        assertEquals("Premium", seg.name)
        assertEquals(63_000, seg.net_mtk); assertEquals(4, seg.memo_count)                // 4 active memos, each counted once although memo a2 has two brands
        assertEquals(100.0, seg.memo_ratio_pct)
        assertEquals(s.by_category.sumOf { it.qty_base }, seg.qty_base)                    // no free lines in the fixture
        assertEquals(24_000, service.summary(reach(z2), null, null, day, day).by_segment.single().net_mtk)
        // Rebuilding the route-day twice gives the same rows (delete-then-insert).
        AggregationWorker(fresh.db).also { it.requestRebuild(day); it.runUntilIdle() }
        assertEquals(seg, DashboardService(fresh.db, AronClock { now }).summary(reach(national = true), null, null, day, day).by_segment.single())
    }

    @Test
    fun categoryReportQuantityOnlyWhenEverySkuSharesTheReportUnit() {
        fun cat() = DashboardService(fresh.db, AronClock { now }).summary(reach(national = true), null, null, day, day).by_category.single()
        assertNull(cat().report_unit); assertNull(cat().qty_report)                         // no report unit set
        sql("UPDATE app.sku SET report_unit = 'pack', report_factor = 3")
        val c = cat()
        assertEquals("pack", c.report_unit)
        // exact sum of qty_base / 3, rounded once to 3 decimals half up
        assertEquals(java.math.BigDecimal(c.qty_base).divide(java.math.BigDecimal(3), 3, java.math.RoundingMode.HALF_UP).toPlainString(), c.qty_report)
        sql("UPDATE app.sku SET report_unit = 'box' WHERE code = 'SKU2'")
        assertNull(cat().report_unit); assertNull(cat().qty_report)                         // mixed units are never summed
        sql("UPDATE app.sku SET report_unit = NULL WHERE code = 'SKU2'")
        assertNull(cat().qty_report)                                                        // one SKU without a unit
    }

    @Test
    fun aCoverRouteOutsideTheZonesIsNeverTheDashboardNode() {
        val r3 = id("SELECT id FROM app.route WHERE code = 'R3'")
        // Route node first (as a type-sorted reach list may give it): the dashboard labels and scopes with the zone.
        val mixed = Reach(1, Role.TSO, day, false, setOf(z1), setOf(r3), false, listOf(ReachNode("route", r3), ReachNode("zone", z1)))
        val s = service.summary(mixed, null, null, day, day)
        assertEquals("zone", s.node.type); assertEquals(z1, s.node.id); assertEquals(39_000, s.kpis.gross_mtk)
        assertEquals("zone", service.geoValidation(mixed, null, null, day, day).node.type)
        // Only a cover route and no geo node: the route itself, read route-keyed (D-BC-DASH-ROUTE-KEYED-01; RouteKeyedDashboardTest).
        val onlyRoute = Reach(1, Role.TSO, day, false, emptySet(), setOf(r3), false, listOf(ReachNode("route", r3)))
        val own = service.summary(onlyRoute, null, null, day, day)
        assertEquals("route" to r3, own.node.type to own.node.id); assertEquals(24_000, own.kpis.gross_mtk)
    }
}
