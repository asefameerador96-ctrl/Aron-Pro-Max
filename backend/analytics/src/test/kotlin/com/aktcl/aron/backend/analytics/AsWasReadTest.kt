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
import kotlin.test.assertTrue

/**
 * Read half of as-was attribution (D-REPORTS-ASWAS-02) on real PostgreSQL 16. A master change made today (a route to another zone,
 * a zone to another territory, division and wing, a variant to another brand, an outlet to another channel) must not move the
 * figures of the fixture's earlier business date on any screen that filters or groups by that placement, and the totals must not
 * change either. The queries are NOT rebuilt after the change: the aggregates were always as-was, so only the read joins differ.
 */
class AsWasReadTest {
    private val day = LocalDate.parse("2026-10-04")   // the fixture's own date, not the dev seed's
    private lateinit var fresh: FreshDb
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private lateinit var national: NationalDashboardService
    private lateinit var dash: DashboardService
    private var z1 = 0L
    private var z2 = 0L

    private fun resource(n: String) = checkNotNull(javaClass.classLoader.getResourceAsStream(n)).bufferedReader().readText()

    @BeforeEach
    fun setUp() {
        AsWasSql.probeTtlMs = 0   // the tests edit masters and read at once
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
        z1 = id("SELECT id FROM app.zone WHERE code = 'Z1'"); z2 = id("SELECT id FROM app.zone WHERE code = 'Z2'")
        national = NationalDashboardService(fresh.db, AronClock { now })
        dash = DashboardService(fresh.db, AronClock { now })
    }

    @AfterEach
    fun tearDown() { AsWasSql.probeTtlMs = 20_000; fresh.close() }

    private fun id(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery(sql).mapTo(Long::class.java).one() }
    private fun text(sql: String) = fresh.db.jdbi.withHandle<String, Exception> { it.createQuery(sql).mapTo(String::class.java).one() }
    /** A master edit as the worker would see it: the current dimensions follow it (the worker refreshes them every few minutes), the history keeps the old side. */
    private fun exec(sql: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(sql); Aggregator.refreshDimensions(it); DashboardSummaries.refreshDimensions(it) }
    private fun caller() = NationalCaller(Reach(1, Role.TOP, day, true, emptySet(), emptySet(), false, emptyList()))
    private fun f(zones: List<Long> = emptyList(), territories: List<Long> = emptyList(), divisions: List<Long> = emptyList(), wings: List<Long> = emptyList(), brands: List<String> = emptyList()) =
        NationalDashboardFilter(date = day.toString(), zone_ids = zones, territory_ids = territories, division_ids = divisions, wing_ids = wings, brand_codes = brands)

    /** What a person sees for [flt]: the sales cards, the channel cards, CPR and the brand breakdowns. */
    private fun screens(flt: NationalDashboardFilter, c: NationalCaller = caller()): String = listOf(
        national.sales(c, flt).cards.map { Triple(it.category_code, it.total_sales, it.target_routes) },
        national.channelStd(c, flt).let { r -> r.channels.map { it.channel to it.value } + ("total" to r.total) },
        national.channelCalls(c, flt).channels.map { it.channel to it.value },
        national.cpr(c, flt).let { listOf(it.target_outlets, it.successful_calls) },
        national.breakdown(c, NationalBreakdownRequest(flt, "std", "brand")).items.map { it.name to it.value },
        national.breakdown(c, NationalBreakdownRequest(flt, "memo", "variant")).items.map { it.name to it.value },
        national.geoFencing(c, flt).let { listOf(it.total_calls, it.geo_validated) },
    ).joinToString("\n")

    @Test
    fun aRouteMovedToAnotherZoneTodayKeepsItsOldZoneOnTheOldDateAndTotalsDoNotChange() {
        val all = screens(f()); val inZ1 = screens(f(zones = listOf(z1))); val inZ2 = screens(f(zones = listOf(z2)))
        assertNotEquals(inZ1, inZ2, "the fixture has different figures in the two zones")
        exec("UPDATE app.route SET zone_id = (SELECT id FROM app.zone WHERE code = 'Z2') WHERE code = 'R2'")
        assertEquals(z2, id("SELECT zone_id FROM dw.dim_geo WHERE route_code = 'R2'"), "the current dimension moved")
        assertEquals(inZ1, screens(f(zones = listOf(z1))), "Z1 lost the route's old day")
        assertEquals(inZ2, screens(f(zones = listOf(z2))), "Z2 gained the route's old day")
        assertEquals(all, screens(f()), "the national totals moved")
        assertEquals(63_000, dash.summary(Reach(1, Role.TSO, day, true, emptySet(), emptySet(), false, emptyList()), null, null, day, day).kpis.gross_mtk)
    }

    @Test
    fun aZoneMovedToAnotherTerritoryDivisionAndWingKeepsItsOldAncestorsOnTheOldDate() {
        val t1 = id("SELECT id FROM app.territory WHERE code = 'T1'"); val d1 = id("SELECT id FROM app.division WHERE code = 'D1'"); val w1 = id("SELECT id FROM app.wing WHERE code = 'W1'")
        val before = screens(f(territories = listOf(t1), divisions = listOf(d1), wings = listOf(w1)))
        val wingsBefore = dash.summary(Reach(1, Role.TSO, day, true, emptySet(), emptySet(), false, emptyList()), null, null, day, day).children.map { it.node.id to it.kpis.net_mtk }
        exec("INSERT INTO app.wing (code, name) VALUES ('W2','Wing2')")
        exec("INSERT INTO app.division (code, name, wing_id) VALUES ('D2','Div2', (SELECT id FROM app.wing WHERE code = 'W2'))")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('T2','Terr2', (SELECT id FROM app.division WHERE code = 'D2'))")
        exec("UPDATE app.zone SET territory_id = (SELECT id FROM app.territory WHERE code = 'T2') WHERE code IN ('Z1', 'Z2')")
        assertEquals("W2", text("SELECT w.code FROM dw.dim_geo g JOIN app.wing w ON w.id = g.wing_id WHERE g.route_code = 'R1'"), "the current dimension moved")
        assertEquals(before, screens(f(territories = listOf(t1), divisions = listOf(d1), wings = listOf(w1))), "the old chain lost the old day")
        val t2 = id("SELECT id FROM app.territory WHERE code = 'T2'"); val d2 = id("SELECT id FROM app.division WHERE code = 'D2'"); val w2 = id("SELECT id FROM app.wing WHERE code = 'W2'")
        for (flt in listOf(f(territories = listOf(t2)), f(divisions = listOf(d2)), f(wings = listOf(w2)))) {
            assertEquals(0L, national.sales(caller(), flt).cards.sumOf { it.total_sales }, "the new chain did not exist on the old day")
        }
        val wingsAfter = dash.summary(Reach(1, Role.TSO, day, true, emptySet(), emptySet(), false, emptyList()), null, null, day, day).children.map { it.node.id to it.kpis.net_mtk }
        assertEquals(wingsBefore, wingsAfter, "the wing children of the national summary moved")
        assertEquals(63_000L, wingsAfter.sumOf { it.second })
    }

    @Test
    fun aVariantMovedToAnotherBrandKeepsItsOldBrandOnTheOldDate() {
        val b1 = screens(f(brands = listOf("B1"))); val b2 = screens(f(brands = listOf("B2"))); val all = screens(f())
        val brandsBefore = dash.summary(Reach(1, Role.TSO, day, true, emptySet(), emptySet(), false, emptyList()), null, null, day, day).by_brand.map { it.code to it.net_mtk }
        exec("UPDATE app.product_node SET parent_id = (SELECT id FROM app.product_node WHERE code = 'B2') WHERE code = 'V1'")
        assertEquals("B2", text("SELECT brand_code FROM dw.dim_product WHERE variant_name = 'Var1' LIMIT 1"), "the current dimension moved")
        assertEquals(b1, screens(f(brands = listOf("B1"))), "B1 lost the variant's old day")
        assertEquals(b2, screens(f(brands = listOf("B2"))))
        assertEquals(all, screens(f()))
        assertEquals(brandsBefore, dash.summary(Reach(1, Role.TSO, day, true, emptySet(), emptySet(), false, emptyList()), null, null, day, day).by_brand.map { it.code to it.net_mtk })
    }

    @Test
    fun anOutletMovedToAnotherChannelTodayKeepsItsOldChannelOnTheOldDate() {
        val before = screens(f()); val channelsBefore = dash.summary(Reach(1, Role.TSO, day, true, emptySet(), emptySet(), false, emptyList()), null, null, day, day).by_channel.map { it.code to it.net_mtk }
        assertTrue(channelsBefore.isNotEmpty())
        exec("UPDATE app.outlet SET channel = 'MT' WHERE channel = 'GT'")
        assertEquals(0, id("SELECT count(*) FROM dw.dim_outlet WHERE channel = 'GT'"), "the current dimension moved")
        assertEquals(before, screens(f()), "the channel cards moved with the outlets")
        assertEquals(channelsBefore, dash.summary(Reach(1, Role.TSO, day, true, emptySet(), emptySet(), false, emptyList()), null, null, day, day).by_channel.map { it.code to it.net_mtk })
    }

    @Test
    fun theHelperAnswersTheOldPlacementForTheOldDateAndTheNewOneFromTheChangeOn() {
        val r2 = id("SELECT id FROM app.route WHERE code = 'R2'")
        val today = text("SELECT app.dhaka_date(now())::text")
        exec("UPDATE app.route SET zone_id = (SELECT id FROM app.zone WHERE code = 'Z2') WHERE code = 'R2'")
        fun zoneOn(date: String) = text("SELECT g.zone_id::text || '/' || g.zone_name FROM (SELECT 1) x ${AsWasSql.geo("g", "$r2", "DATE '$date'")}")
        assertEquals("$z1/Zone1", zoneOn(day.toString()))
        assertEquals("$z2/Zone2", zoneOn(today), "today's date reads exactly as before the change")
    }

    @Test
    fun withHistoryPresentAndNothingChangedTheFiguresAreTheControlTotals() {
        // History present, and the fixture's own house edit closed every route's period, so this runs the timeline path with nothing moved on the day.
        assertEquals(75, national.sales(caller(), f()).cards.single { it.category_code == "cigarette" }.total_sales)
        assertEquals(75, national.sales(caller(), f(zones = listOf(z1, z2))).cards.single { it.category_code == "cigarette" }.total_sales)
        assertEquals(63_000, dash.summary(Reach(1, Role.TSO, day, true, emptySet(), emptySet(), false, emptyList()), null, null, day, day).kpis.gross_mtk)
    }

    @Test
    fun withNoMasterChangeAtAllTheFastPathAnswersTheControlTotals() {
        exec("DO \$\$ DECLARE t text; BEGIN FOR t IN SELECT history_table FROM app.master_history_map LOOP EXECUTE format('DELETE FROM app.%I', t); END LOOP; END \$\$")
        val p = fresh.db.jdbi.withHandle<AsWasSql.Plain, Exception> { AsWasSql.probe(it, day) }
        assertTrue(p.geo && p.product && p.outlet, "no history period closed: all three dimensions are on the plain path")
        assertEquals(75, national.sales(caller(), f()).cards.single { it.category_code == "cigarette" }.total_sales)
        assertEquals(63_000, dash.summary(Reach(1, Role.TSO, day, true, emptySet(), emptySet(), false, emptyList()), null, null, day, day).kpis.gross_mtk)
    }

    @Test
    fun aZoneCallerKeepsSeeingARouteThatLeftItsZoneOnTheOldDate() {
        val tso = NationalCaller(Reach(1, Role.TSO, day, false, setOf(z1), emptySet(), false, listOf(ReachNode("zone", z1))))
        val before = screens(f(), tso)
        exec("UPDATE app.route SET zone_id = (SELECT id FROM app.zone WHERE code = 'Z2') WHERE code = 'R2'")
        assertEquals(before, screens(f(), tso), "the TSO of Z1 lost the old day of the route that left Z1")
        val z2Tso = NationalCaller(Reach(1, Role.TSO, day, false, setOf(z2), emptySet(), false, listOf(ReachNode("zone", z2))))
        assertEquals(screens(f(zones = listOf(z2))), screens(f(), z2Tso), "scope is the reach, never more than it")
    }
}
