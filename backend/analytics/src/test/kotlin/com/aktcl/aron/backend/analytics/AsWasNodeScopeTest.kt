package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.ApiProblem
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
import kotlin.test.assertTrue

/**
 * A requested node is the node as it was on the dates asked (D-REPORTS-NODEASWAS-01), on real PostgreSQL 16: after a zone moves to another
 * territory, a drill-down on an old date reads the territory as it was then (the zones it had, the figures it had), the new territory has no
 * members on that date (403), a period that straddles the move counts each day by the territory the zone had that day, and the reach still bounds it.
 */
class AsWasNodeScopeTest {
    private val day = LocalDate.parse("2026-10-04")   // the fixture's own date, not the dev seed's
    private val day2 = day.plusDays(1)
    private lateinit var fresh: FreshDb
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private var t1 = 0L
    private var t2 = 0L
    private var z1 = 0L
    private var z2 = 0L

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
        z1 = id("SELECT id FROM app.zone WHERE code = 'Z1'"); z2 = id("SELECT id FROM app.zone WHERE code = 'Z2'")
        t1 = id("SELECT id FROM app.territory WHERE code = 'T1'")
    }

    @AfterEach
    fun tearDown() { AsWasSql.probeTtlMs = 20_000; fresh.close() }

    private fun dash() = DashboardService(fresh.db, AronClock { now })   // a new service per read: the summary cache holds the first object
    private fun id(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery(sql).mapTo(Long::class.java).one() }
    private fun exec(sql: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(sql); Aggregator.refreshDimensions(it); DashboardSummaries.refreshDimensions(it) }
    private val national = Reach(1, Role.TOP, day, true, emptySet(), emptySet(), false, emptyList())
    private fun terr(from: LocalDate = day, to: LocalDate = day, node: Long = t1, reach: Reach = national) = dash().summary(reach, "territory", node, from, to)
    private fun geo(from: LocalDate = day, to: LocalDate = day, node: Long = t1) = dash().geoValidation(national, "territory", node, from, to)

    /** Z2 moves to a new territory under a new division and wing: today's edit, as the master data lane would make it. */
    private fun moveZ2() {
        exec("INSERT INTO app.wing (code, name) VALUES ('W2','Wing2')")
        exec("INSERT INTO app.division (code, name, wing_id) VALUES ('D2','Div2', (SELECT id FROM app.wing WHERE code = 'W2'))")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('T2','Terr2', (SELECT id FROM app.division WHERE code = 'D2'))")
        exec("UPDATE app.zone SET territory_id = (SELECT id FROM app.territory WHERE code = 'T2') WHERE code = 'Z2'")
        t2 = id("SELECT id FROM app.territory WHERE code = 'T2'")
    }

    @Test
    fun aDrillDownOnAnOldDateReadsTheTerritoryAsItWasAndTheNewOneHasNoMembersThen() {
        val before = terr(); val beforeGeo = geo()
        assertTrue(before.kpis.gross_mtk > 0 && before.children.size == 2, "T1 holds both zones on the fixture date")
        moveZ2()
        assertEquals(t1, id("SELECT territory_id FROM dw.dim_geo WHERE route_code = 'R1'"), "Z1 stays")
        val after = terr()
        assertEquals(before.kpis, after.kpis, "T1 lost the day's figures of the zone that left")
        assertEquals(before.children, after.children, "the children listed are the zones T1 had then, none outside it")
        assertEquals(before.by_brand, after.by_brand); assertEquals(before.by_category, after.by_category)
        assertEquals(beforeGeo, geo(), "geo-validation reads the same node")
        for (child in after.children) assertEquals(child.kpis, dash().summary(national, "zone", child.node.id, day, day).kpis, "every listed child can be opened")
        val e = assertFailsWith<ApiProblem> { terr(node = t2) }
        assertEquals(ProblemCode.ERR_FORBIDDEN, e.code, "the new territory had no zone on that date")
    }

    @Test
    fun theReachStillBoundsAnAsWasNode() {
        moveZ2()
        val tso = Reach(2, Role.TSO, day, false, setOf(z1), emptySet(), false, listOf(ReachNode("zone", z1)))
        val e = assertFailsWith<ApiProblem> { terr(reach = tso) }
        assertEquals(ProblemCode.ERR_FORBIDDEN, e.code, "T1 had a zone then that this caller does not reach")
        val both = Reach(3, Role.TSO, day, false, setOf(z1, z2), emptySet(), false, listOf(ReachNode("territory", t1)))
        assertEquals(63_000L, terr(reach = both).kpis.gross_mtk)
    }

    @Test
    fun aPeriodAcrossTheMoveCountsEachDayUnderTheTerritoryTheZoneHadThatDay() {
        // Day 2 repeats day 1's zone rows; the move took effect on day 2 (the history is set to that date).
        fresh.dataSource.connection.use { c ->
            c.createStatement().use {
                it.execute("CREATE TEMP TABLE x AS SELECT * FROM dw.agg_daily_zone; UPDATE x SET business_date = business_date + 1; INSERT INTO dw.agg_daily_zone SELECT * FROM x")
            }
        }
        val zoneDay1 = dash().summary(national, "zone", z1, day, day).kpis.net_mtk
        val z2Day = dash().summary(national, "zone", z2, day, day).kpis.net_mtk
        assertTrue(zoneDay1 > 0 && z2Day > 0)
        moveZ2()
        exec("UPDATE app.zone_history SET valid_to = DATE '$day2' WHERE zone_id = $z2 AND territory_id = $t1 AND valid_to IS NOT NULL")
        exec("UPDATE app.zone_history SET valid_from = DATE '$day2' WHERE zone_id = $z2 AND territory_id = $t2")
        // The old territory had both zones on day 1 and one on day 2; the new one has the moved zone from day 2 only.
        val old = terr(day, day2); val new = terr(day, day2, node = t2)
        assertEquals(zoneDay1 + z2Day + zoneDay1, old.kpis.net_mtk, "T1: both zones on day 1, Z1 alone on day 2")
        assertEquals(z2Day, new.kpis.net_mtk, "T2: the moved zone from day 2")
        assertEquals(setOf(z1, z2), old.children.map { it.node.id }.toSet())
        assertEquals(setOf(z2), new.children.map { it.node.id }.toSet())
        assertEquals(zoneDay1 + z2Day + zoneDay1, old.children.sumOf { it.kpis.net_mtk }, "children add up to the node")
        assertEquals(geo(day, day2).visits + geo(day, day2, node = t2).visits, dash().geoValidation(national, null, null, day, day2).visits, "geo-validation splits the same visits")
        // The old window start alone (day 1) is unaffected by the move date.
        assertEquals(zoneDay1 + z2Day, terr(day, day).kpis.net_mtk)
    }
}
