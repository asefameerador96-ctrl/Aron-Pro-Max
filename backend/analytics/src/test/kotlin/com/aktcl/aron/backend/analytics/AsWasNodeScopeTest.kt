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
    fun theReachStillBoundsAnAsWasNodeButAZoneThatLeftIsNotHeldAgainstTheCaller() {
        val tso = Reach(2, Role.TSO, day, false, setOf(z1), emptySet(), false, listOf(ReachNode("territory", t1)))
        // Before any move T1 holds a zone this caller does not reach: refused, as ever.
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { terr(reach = tso) }.code)
        val z1Only = dash().summary(national, "zone", z1, day, day).kpis
        moveZ2()
        // After Z2 left, the territory is the caller's again: their own default dashboard and an explicit T1, on the old date, show their zone only.
        val own = dash().summary(tso, null, null, day, day)
        assertEquals(z1Only.net_mtk, own.kpis.net_mtk, "only the zone the caller reaches, never the zone that left")
        assertEquals(listOf(z1), own.children.map { it.node.id })
        assertEquals(own.kpis, terr(reach = tso).kpis)
        assertEquals(dash().geoValidation(national, "zone", z1, day, day).visits, dash().geoValidation(tso, "territory", t1, day, day).visits, "geo-validation cuts the same way")
        // Never wider than the reach: a node that held nothing of the caller's on that date is refused.
        val other = Reach(4, Role.TSO, day, false, setOf(z2), emptySet(), false, listOf(ReachNode("zone", z2)))
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { terr(node = t2, reach = other) }.code, "T2 had no zone on that date")
        assertEquals(63_000L, terr(reach = Reach(3, Role.TSO, day, false, setOf(z1, z2), emptySet(), false, listOf(ReachNode("territory", t1)))).kpis.gross_mtk)
    }

    @Test
    fun aPeriodAcrossTheMoveCountsEachDayUnderTheTerritoryTheZoneHadThatDay() {
        // Day 2 repeats day 1's zone rows; the move took effect on day 2 (the history is set to that date).
        fresh.dataSource.connection.use { c ->
            c.createStatement().use {
                for (t in listOf("agg_daily_zone", "agg_daily_route_sku", "agg_daily_route_brand", "agg_daily_route_segment", "agg_daily_outlet")) {
                    it.execute("CREATE TEMP TABLE x AS SELECT * FROM dw.$t; UPDATE x SET business_date = business_date + 1; INSERT INTO dw.$t SELECT * FROM x; DROP TABLE x")
                }
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
        // Every other read of the summary follows the same days: brand, category, segment and channel add up to the node's own figure.
        for ((name, s) in listOf("T1" to old, "T2" to new)) {
            assertEquals(s.kpis.net_mtk, s.by_brand.sumOf { it.net_mtk }, "$name by_brand")
            assertEquals(s.kpis.net_mtk, s.by_category.sumOf { it.net_mtk }, "$name by_category")
            assertEquals(s.kpis.net_mtk, s.by_segment.sumOf { it.net_mtk }, "$name by_segment")
            assertEquals(s.kpis.net_mtk, s.by_channel.sumOf { it.net_mtk }, "$name by_channel")
        }
        assertEquals(setOf(z1, z2), old.children.map { it.node.id }.toSet())
        assertEquals(setOf(z2), new.children.map { it.node.id }.toSet())
        assertEquals(zoneDay1 + z2Day + zoneDay1, old.children.sumOf { it.kpis.net_mtk }, "children add up to the node")
        assertEquals(geo(day, day2).visits + geo(day, day2, node = t2).visits, dash().geoValidation(national, null, null, day, day2).visits, "geo-validation splits the same visits")
        // The children of each node are cut by the same per-day chain: they add up to the node (a zone that left would otherwise be listed under the old
        // territory with its days after the move).
        for ((name, g) in listOf("T1" to geo(day, day2), "T2" to geo(day, day2, node = t2))) {
            assertTrue(g.visits > 0, "$name has visits")
            assertEquals(g.visits, g.children.sumOf { it.visits }, "$name geo-validation children add up to the node")
            assertEquals(g.geo_valid_visits, g.children.sumOf { it.geo_valid_visits }, "$name geo-valid children add up to the node")
        }
        assertEquals(setOf(z2), geo(day, day2, node = t2).children.map { it.node.id }.toSet())
        // A window on the move date alone, and a caller who only reaches the zone that stayed: allowed, their zone's day 2 only.
        val stayer = Reach(2, Role.TSO, day2, false, setOf(z1), emptySet(), false, listOf(ReachNode("territory", t1)))
        assertEquals(zoneDay1, terr(day2, day2, reach = stayer).kpis.net_mtk)
        assertEquals(zoneDay1 + zoneDay1, terr(day, day2, reach = stayer).kpis.net_mtk, "both days: the zone that stayed, none of the one that left")
        // The old window start alone (day 1) is unaffected by the move date.
        assertEquals(zoneDay1 + z2Day, terr(day, day).kpis.net_mtk)
    }

    @Test
    fun aNodeThatLostEveryZoneHasNoMembersFromThenOnAndKeepsThemBefore() {
        // Both zones left T1 for T2 on day 2: T1 has no member from then on (403), still has both before it, and T2 has none on day 1.
        moveZ2()
        exec("UPDATE app.zone SET territory_id = $t2 WHERE code = 'Z1'")
        exec("UPDATE app.zone_history SET valid_to = DATE '$day2' WHERE zone_id IN ($z1, $z2) AND territory_id = $t1 AND valid_to IS NOT NULL")
        exec("UPDATE app.zone_history SET valid_from = DATE '$day2' WHERE zone_id IN ($z1, $z2) AND territory_id = $t2")
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { terr(day2, day2, t1) }.code, "T1 had no zone on day 2")
        assertTrue(terr(day, day2, t1).kpis.gross_mtk > 0, "but it had both on day 1")
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { terr(day, day, t2) }.code, "and T2 had none on day 1")
        // T1 holds nothing today, so only its members of the window can tie it to a reach: a caller who reached none of them is refused like an unknown id.
        val stranger = Reach(9, Role.TSO, day, false, setOf(999_999L), emptySet(), false, listOf(ReachNode("zone", 999_999L)))
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { terr(day, day, t1, stranger) }.code)
        assertEquals(63_000L, terr(day, day, t1, Reach(3, Role.TSO, day, false, setOf(z1, z2), emptySet(), false, listOf(ReachNode("territory", t1)))).kpis.gross_mtk)
    }

    @Test
    fun aTerritoryHoldingARouteLessZoneOutsideTheReachIsRefusedOnTheAsWasPathToo() {
        moveZ2()   // a zone moved: the as-was path answers
        // Z1 stays in T1 and a new zone with no route joins it; the caller reaches Z1 only. Today's members come from app.zone, so the route-less zone counts.
        exec("INSERT INTO app.zone (code, name, territory_id) VALUES ('ZX','ZoneX', $t1)")
        val zx = id("SELECT id FROM app.zone WHERE code = 'ZX'")
        assertTrue(fresh.db.jdbi.withHandle<Boolean, Exception> { h -> !AsWasSql.probe(h, day).geo }, "the as-was path is the one under test")
        val tso = Reach(2, Role.TSO, day, false, setOf(z1), emptySet(), false, listOf(ReachNode("territory", t1)))
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { terr(reach = tso) }.code, "T1 holds ZX today and the caller does not reach it")
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { dash().summary(tso, null, null, day, day) }.code, "also the caller's own default node")
        // Reaching it as well makes the node theirs again.
        val both = Reach(3, Role.TSO, day, false, setOf(z1, zx), emptySet(), false, listOf(ReachNode("territory", t1)))
        assertEquals(dash().summary(national, "zone", z1, day, day).kpis.net_mtk, terr(reach = both).kpis.net_mtk)
    }

    @Test
    fun theLoginSubmitListsOfAnOldDateReadTheNodeAsItWasOnThatDate() {
        moveZ2()
        val ops = OpsService(fresh.db, com.aktcl.aron.backend.platform.RegistryDefaults(), AronClock { now })
        fun routes(s: LoginSubmitStatus) = (s.not_logged_in + s.logged_in_not_submitted + s.submitted + s.exceptions).map { it.route_code }.toSet()
        // On the fixture date T1 held both zones: R1 and R2 (Z1) and R3 (Z2); today it holds Z1 only.
        val old = ops.loginSubmit(national, dash(), day, "territory", t1)
        assertEquals(setOf("R1", "R2", "R3"), routes(old), "the routes of the zones T1 had that day")
        assertEquals(setOf(z1, z2), old.zones.map { it.zone_id }.toSet(), "the Final Submit zones too")
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { ops.loginSubmit(national, dash(), day, "territory", t2) }.code, "T2 had no zone that day")
    }

    @Test
    fun syncHealthOfATsoWhoseZoneLeftTheirTerritoryAnswersWhileTheDimensionStillLags() {
        // The move is only in app.zone (dw.dim_geo is rebuilt by the projector, up to minutes later), as in production right after an admin edit.
        fresh.db.jdbi.useHandle<Exception> {
            it.execute("INSERT INTO app.wing (code, name) VALUES ('W2','Wing2')")
            it.execute("INSERT INTO app.division (code, name, wing_id) VALUES ('D2','Div2', (SELECT id FROM app.wing WHERE code = 'W2'))")
            it.execute("INSERT INTO app.territory (code, name, division_id) VALUES ('T2','Terr2', (SELECT id FROM app.division WHERE code = 'D2'))")
            it.execute("UPDATE app.zone SET territory_id = (SELECT id FROM app.territory WHERE code = 'T2') WHERE code = 'Z2'")
        }
        assertEquals(t1, id("SELECT territory_id FROM dw.dim_geo WHERE route_code = 'R3'"), "the dimension has not caught up")
        val ops = OpsService(fresh.db, com.aktcl.aron.backend.platform.RegistryDefaults(), AronClock { now })
        val tso = Reach(2, Role.TSO, day, false, setOf(z1), emptySet(), false, listOf(ReachNode("territory", t1)))
        // The other dashboards answer for this caller (T1 holds only their zone now); so must sync-health.
        dash().summary(tso, null, null, day, day)
        ops.syncHealth(tso, day, null, null, false, 10, null)
        ops.syncHealth(tso, day, "territory", t1, false, 10, null)
    }

    @Test
    fun aNodeWithNoMemberInTheReachIsRefusedLikeAnUnknownOne() {
        moveZ2()
        // T1 had both zones on the fixture date and holds neither today's... of this caller's: nothing of it is in the reach.
        val stranger = Reach(9, Role.TSO, day, false, setOf(999_999L), emptySet(), false, listOf(ReachNode("zone", 999_999L)))
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { terr(reach = stranger) }.code)
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { terr(node = 888_888L, reach = stranger) }.code, "the same answer as an unknown id")
    }

    @Test
    fun theScopedNodeEndpointsResolveTheNodeOnTheirDate() {
        moveZ2()
        // T2 did not exist on the fixture date: every endpoint that takes a node and a date must say so, none may read today's structure.
        val c = AronClock { now }
        val e1 = assertFailsWith<ApiProblem> { FlaggedMemos(fresh.db).page(national, day, day, "territory", t2, null, 10, null) }
        assertEquals(ProblemCode.ERR_OUT_OF_SCOPE, e1.code)
        val e2 = assertFailsWith<ApiProblem> { DailyTrackingService(fresh.db, com.aktcl.aron.backend.platform.RegistryDefaults(), c).page(national, day, "territory", t2, 10, null) }
        assertEquals(ProblemCode.ERR_FORBIDDEN, e2.code)
        // The old territory still answers on that date: both zones' routes for a national caller, only the caller's own zone for a TSO of zone 1.
        FlaggedMemos(fresh.db).page(national, day, day, "territory", t1, null, 10, null)
        val tracking = DailyTrackingService(fresh.db, com.aktcl.aron.backend.platform.RegistryDefaults(), c)
        assertEquals(setOf(z1, z2), tracking.page(national, day, "territory", t1, 50, null).items.map { it.zone_id }.toSet())
        val tso = Reach(2, Role.TSO, day, false, setOf(z1), emptySet(), false, listOf(ReachNode("territory", t1)))
        assertEquals(setOf(z1), tracking.page(tso, day, "territory", t1, 50, null).items.map { it.zone_id }.toSet(), "the zone that left is not the caller's: cut from the read")
        FlaggedMemos(fresh.db).page(tso, day, day, "territory", t1, null, 10, null)
    }

    @Test
    fun aZoneAndATerritoryWithNoRouteYetAreNodesOnBothPaths() {
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('TN','TerrNew', (SELECT division_id FROM app.territory WHERE code = 'T1'))")
        exec("INSERT INTO app.zone (code, name, territory_id) VALUES ('ZN','ZoneNew', (SELECT id FROM app.territory WHERE code = 'TN'))")
        val tn = id("SELECT id FROM app.territory WHERE code = 'TN'"); val zn = id("SELECT id FROM app.zone WHERE code = 'ZN'")
        val ownZone = Reach(7, Role.TSO, now.atZone(java.time.ZoneOffset.UTC).toLocalDate(), false, setOf(zn), emptySet(), false, listOf(ReachNode("territory", tn)))
        // Plain path (no geography period has closed since the window began): the route-less zone and its territory open, with zero figures.
        assertEquals(0L, dash().summary(national, "zone", zn, day, day).kpis.gross_mtk)
        assertEquals(0L, terr(node = tn).kpis.gross_mtk)
        assertEquals(0L, dash().summary(ownZone, null, null, day, day).kpis.gross_mtk)
        // As-was path (a zone moved since): the same answers, not 403 on one path and 200 on the other.
        moveZ2()
        assertEquals(0L, dash().summary(national, "zone", zn, day, day).kpis.gross_mtk)
        assertEquals(0L, terr(node = tn).kpis.gross_mtk)
        assertEquals(0L, dash().summary(ownZone, null, null, day, day).kpis.gross_mtk)
    }

    @Test
    fun aBrandCallerAcrossAMoveIsCutByTheSameChain() {
        fresh.dataSource.connection.use { c ->
            c.createStatement().use {
                for (t in listOf("agg_daily_zone", "agg_daily_route_sku", "agg_daily_route_brand", "agg_daily_route_segment", "agg_daily_outlet")) {
                    it.execute("CREATE TEMP TABLE x AS SELECT * FROM dw.$t; UPDATE x SET business_date = business_date + 1; INSERT INTO dw.$t SELECT * FROM x; DROP TABLE x")
                }
            }
        }
        val b1 = setOf(id("SELECT id FROM app.product_node WHERE code = 'B1'"))
        moveZ2()
        exec("UPDATE app.zone_history SET valid_to = DATE '$day2' WHERE zone_id = $z2 AND territory_id = $t1 AND valid_to IS NOT NULL")
        exec("UPDATE app.zone_history SET valid_from = DATE '$day2' WHERE zone_id = $z2 AND territory_id = $t2")
        val all = dash().summary(national, null, null, day, day2, b1)
        val old = dash().summary(national, "territory", t1, day, day2, b1); val new = dash().summary(national, "territory", t2, day, day2, b1)
        assertTrue(old.kpis.net_mtk > 0 && new.kpis.net_mtk > 0)
        assertEquals(all.kpis.net_mtk, old.kpis.net_mtk + new.kpis.net_mtk, "the brand value splits between the two territories by the day")
        for ((name, s) in listOf("T1" to old, "T2" to new)) {
            assertEquals(s.kpis.net_mtk, s.by_segment.sumOf { it.net_mtk }, "$name by_segment")
            assertEquals(s.kpis.net_mtk, s.by_category.sumOf { it.net_mtk }, "$name by_category")
            assertEquals(s.kpis.net_mtk, s.children.sumOf { it.kpis.net_mtk }, "$name children")
        }
    }
}
