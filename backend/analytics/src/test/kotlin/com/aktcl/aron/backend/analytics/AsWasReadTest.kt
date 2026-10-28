package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Reach
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
    fun tearDown() = fresh.close()

    private fun id(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery(sql).mapTo(Long::class.java).one() }
    private fun text(sql: String) = fresh.db.jdbi.withHandle<String, Exception> { it.createQuery(sql).mapTo(String::class.java).one() }
    private fun exec(sql: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(sql) }
    private fun caller() = NationalCaller(Reach(1, Role.TOP, day, true, emptySet(), emptySet(), false, emptyList()))
    private fun f(zones: List<Long> = emptyList(), territories: List<Long> = emptyList(), divisions: List<Long> = emptyList(), wings: List<Long> = emptyList(), brands: List<String> = emptyList()) =
        NationalDashboardFilter(date = day.toString(), zone_ids = zones, territory_ids = territories, division_ids = divisions, wing_ids = wings, brand_codes = brands)

    /** What a person sees for [flt]: the sales cards, the channel cards, CPR and the brand breakdowns. */
    private fun screens(flt: NationalDashboardFilter): String = listOf(
        national.sales(caller(), flt).cards.map { Triple(it.category_code, it.total_sales, it.target_routes) },
        national.channelStd(caller(), flt).let { r -> r.shares.map { it.code to it.value } + ("total" to r.total_std) },
        national.channelCalls(caller(), flt).shares.map { it.code to it.value },
        national.cpr(caller(), flt).let { listOf(it.target_outlets, it.successful_calls) },
        national.breakdown(caller(), NationalBreakdownRequest(flt, "std", "brand")).items.map { it.name to it.value },
        national.breakdown(caller(), NationalBreakdownRequest(flt, "memo", "variant")).items.map { it.name to it.value },
        national.geoFencing(caller(), flt).let { listOf(it.total_calls, it.valid_calls) },
    ).joinToString("\n")

    @Test
    fun aRouteMovedToAnotherZoneTodayKeepsItsOldZoneOnTheOldDateAndTotalsDoNotChange() {
        val all = screens(f()); val inZ1 = screens(f(zones = listOf(z1))); val inZ2 = screens(f(zones = listOf(z2)))
        assertNotEquals(inZ1, inZ2, "the fixture has different figures in the two zones")
        exec("UPDATE app.route SET zone_id = (SELECT id FROM app.zone WHERE code = 'Z2') WHERE code = 'R2'")
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
        // A date older than the history falls back to the current dimension row.
        assertEquals("$z2/Zone2", zoneOn("1990-01-01"))
    }
}
