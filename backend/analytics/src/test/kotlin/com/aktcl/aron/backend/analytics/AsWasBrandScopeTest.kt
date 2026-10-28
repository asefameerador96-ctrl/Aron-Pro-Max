package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachNode
import com.aktcl.aron.contract.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Brand scope of the dashboard summary (D-REPORTS-BRANDSCOPE-01, docs/34 rule 16 "own brands only") on real PostgreSQL 16: a BRAND caller's
 * brand-bearing figures are the subset of its brands within its reach, as the brands were on each day (D-REPORTS-ASWAS-02), and every figure
 * without a brand dimension is hidden. The fixture has one segment holding two brands, B1 and B2, in two zones.
 */
class AsWasBrandScopeTest {
    private val day = LocalDate.parse("2026-10-04")   // the fixture's own date, not the dev seed's
    private lateinit var fresh: FreshDb
    private val now = Instant.parse("2026-10-04T12:00:00Z")

    private fun resource(n: String) = checkNotNull(javaClass.classLoader.getResourceAsStream(n)).bufferedReader().readText()

    @BeforeEach
    fun setUp() {
        AsWasSql.probeTtlMs = 0
        fresh = FreshDb.create()
        fresh.dataSource.connection.use { c ->
            c.createStatement().use { it.execute(resource("seed_day.sql")) }
            c.createStatement().use { it.execute(resource("national_day_extra.sql")) }
            for (u in listOf("a1", "a2", "a4", "a5")) c.createStatement().use {
                it.execute("INSERT INTO app.domain_event (event_type, aggregate_type, aggregate_id, business_date, source_client_uuid) VALUES ('memo.created','memo','x','2026-10-04','00000000-0000-4000-8000-0000000000$u')")
            }
        }
        val w = AggregationWorker(fresh.db, AronClock { now })
        fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
        w.requestRebuild(day); w.runUntilIdle()
    }

    @AfterEach
    fun tearDown() { AsWasSql.probeTtlMs = 20_000; fresh.close() }

    private fun dash() = DashboardService(fresh.db, AronClock { now })   // a new service per read: the summary cache holds the first object
    private fun id(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery(sql).mapTo(Long::class.java).one() }
    private fun exec(sql: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(sql); Aggregator.refreshDimensions(it); DashboardSummaries.refreshDimensions(it) }
    private val national = Reach(1, Role.TOP, day, true, emptySet(), emptySet(), false, emptyList())
    private fun inZones(vararg z: Long) = Reach(2, Role.TSO, day, false, z.toSet(), emptySet(), false, listOf(ReachNode("zone", z.first())))
    private fun b(code: String) = id("SELECT id FROM app.product_node WHERE code = '$code'")
    private fun summary(reach: Reach = national, brands: Set<Long>? = null) = dash().summary(reach, null, null, day, day, brands)

    /** The unscoped figures of one brand, keyed by code: the control the scoped caller must equal. */
    private fun natBrand(s: DashboardSummary, code: String) = s.by_brand.single { it.name == name(code) }
    private fun name(code: String) = mapOf("B1" to "BrandOne", "B2" to "BrandTwo").getValue(code)

    @Test
    fun aBrandCallerSeesItsOwnBrandOnlyAndTheFiguresWithoutABrandAreHidden() {
        val all = summary()
        val n1 = natBrand(all, "B1"); val n2 = natBrand(all, "B2")
        assertTrue(n1.net_mtk > 0 && n2.net_mtk > 0 && n1.net_mtk != n2.net_mtk, "the fixture sells both brands, in different amounts")
        val s = summary(brands = setOf(b("B1")))
        assertEquals(listOf(n1.copy(memo_ratio_pct = null)), s.by_brand, "only B1, its own memo share withheld (it would reveal the total of all brands)")
        assertEquals(n1.net_mtk, s.by_segment.single().net_mtk, "the shared segment is cut to B1's lines")
        assertEquals(n1.qty_base, s.by_segment.single().qty_base)
        assertEquals(0, s.by_segment.single().memo_count, "memos of one brand within a segment are not additive, so none is shown")
        assertNull(s.by_segment.single().memo_ratio_pct)
        assertEquals(n1.net_mtk, s.by_category.sumOf { it.net_mtk }, "the category volume is B1's SKUs only")
        assertEquals(n1.qty_base, s.by_category.sumOf { it.qty_base })
        assertTrue(s.by_channel.isEmpty(), "the channel split has no brand dimension")
        assertEquals(n1.net_mtk, s.kpis.gross_mtk); assertEquals(n1.net_mtk, s.kpis.net_mtk)
        assertEquals(0, s.kpis.active_memo_count + s.kpis.target_routes + s.kpis.visited_outlets + s.kpis.successful_calls + s.kpis.mock_visits)
        assertNull(s.kpis.login_pct); assertNull(s.kpis.strike_rate_pct); assertNull(s.kpis.geo_valid_pct); assertNull(s.kpis.final_submit_pct)
        assertEquals(n1.net_mtk, s.children.sumOf { it.kpis.net_mtk }, "the children carry the brand value and nothing else")
        assertTrue(s.children.all { it.kpis.active_memo_count == 0 && it.kpis.target_routes == 0 })
        // The other brand and the all-brand totals are nowhere in the answer.
        val text = s.toString()
        assertTrue(!text.contains(n2.name!!) && s.kpis.gross_mtk != all.kpis.gross_mtk && s.kpis.gross_mtk != n2.net_mtk)
    }

    @Test
    fun aCallerWithBothBrandsSeesTheNationalBrandFiguresAndANoBrandCallerSeesNothing() {
        val all = summary()
        val both = summary(brands = setOf(b("B1"), b("B2")))
        assertEquals(all.by_brand.map { it.copy(memo_ratio_pct = null) }, both.by_brand)
        assertEquals(all.by_brand.sumOf { it.net_mtk }, both.kpis.net_mtk)
        val none = summary(brands = emptySet())
        assertTrue(none.by_brand.isEmpty() && none.by_segment.isEmpty() && none.by_category.isEmpty() && none.children.isEmpty())
        assertEquals(0, none.kpis.gross_mtk)
    }

    @Test
    fun aBrandCallerIsNeverWiderThanItsReach() {
        val z1 = id("SELECT id FROM app.zone WHERE code = 'Z1'"); val z2 = id("SELECT id FROM app.zone WHERE code = 'Z2'")
        for (z in listOf(z1, z2)) {
            val inReach = summary(inZones(z))
            val s = summary(inZones(z), setOf(b("B1")))
            val control = inReach.by_brand.singleOrNull { it.name == "BrandOne" }
            assertEquals(listOf(control?.copy(memo_ratio_pct = null)).filterNotNull(), s.by_brand)
            assertEquals(control?.net_mtk ?: 0L, s.kpis.net_mtk)
        }
        assertNotEquals(summary(inZones(z1), setOf(b("B1"))).kpis.net_mtk + summary(inZones(z2), setOf(b("B1"))).kpis.net_mtk, 0L)
        // Cached per scope: the brand caller's answer is not served to an unscoped caller of the same reach, nor the other way round.
        val d = dash()
        val scoped = d.summary(national, null, null, day, day, setOf(b("B1")))
        assertEquals(2, d.summary(national, null, null, day, day).by_brand.size)
        assertEquals(scoped, d.summary(national, null, null, day, day, setOf(b("B1"))))
        assertEquals(1, scoped.by_brand.size)
    }

    @Test
    fun aBrandThatLostItsSkusLaterStillOwnsThemOnTheOldDay() {
        val before1 = summary(brands = setOf(b("B1"))); val before2 = summary(brands = setOf(b("B2")))
        exec("UPDATE app.product_node SET parent_id = (SELECT id FROM app.product_node WHERE code = 'B2') WHERE code = 'V1'")
        assertEquals("B2", fresh.db.jdbi.withHandle<String, Exception> { it.createQuery("SELECT brand_code FROM dw.dim_product WHERE variant_name = 'Var1' LIMIT 1").mapTo(String::class.java).one() }, "the current dimension moved")
        val a1 = summary(brands = setOf(b("B1"))); val a2 = summary(brands = setOf(b("B2")))
        assertEquals(before1.by_category, a1.by_category, "B1 lost the variant's old day")
        assertEquals(before1.by_segment, a1.by_segment)
        assertEquals(before1.kpis, a1.kpis)
        assertEquals(before2.by_category, a2.by_category, "B2 gained the variant's old day")
        assertEquals(before2.by_segment, a2.by_segment)
        assertEquals(before2.kpis, a2.kpis)
        assertEquals(before1.by_brand, a1.by_brand); assertEquals(before2.by_brand, a2.by_brand)
    }
}
