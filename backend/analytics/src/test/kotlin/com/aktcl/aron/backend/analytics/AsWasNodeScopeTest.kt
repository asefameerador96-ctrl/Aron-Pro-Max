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
 * members on that date (200, no rows: D-REPORTS-NODEASWAS-07), a period that straddles the move counts each day by the territory the zone had that day, and the reach still bounds it.
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
        // D-LEAD-REPORT-SELECTOR-01: a node inside the reach that held no zone on that date is 200 with nothing in it, not a refusal; an id that is no node is the refusal.
        val empty = terr(node = t2)
        assertEquals(0L, empty.kpis.gross_mtk, "the new territory had no zone on that date: nothing in it")
        assertTrue(empty.children.isEmpty())
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { terr(node = 999_999L) }.code, "an id that is no territory")
    }

    @Test
    fun aTerritoryPlusACoverRouteIsOneTopNodeForTheRouteListAsForTheKpis() {
        // D-REPORTS-NODEASWAS-11: resolveScopedNode counts dashboard-level top nodes like DashboardService, so a territory plus a cover route reads the
        // territory's members over the window, not the whole reach.
        moveZ2()   // Z2 joined the new territory T2 today; on the fixture date T2 had no zone
        val r3 = id("SELECT id FROM app.route WHERE code = 'R3'")
        val caller = Reach(5, Role.TSO, day, false, setOf(z2), emptySet(), false, listOf(ReachNode("territory", t2), ReachNode("route", r3)))
        val scoped = fresh.db.jdbi.withHandle<ScopedNode, Exception> { resolveScopedNode(it, caller, null, null, from = day) }
        assertEquals(emptyList(), scoped.zones, "T2 had no member on that date: nothing to read (the KPIs agree)")
        assertEquals(0L, dash().summary(caller, null, null, day, day).kpis.gross_mtk)
    }

    @Test
    fun aNodeWithNoZoneAtAllIsTheCallersOnlyThroughItsParentChain() {
        // A new, empty territory under T1's division: no zone today, none in the window. The reach carries zones only, so the parent chain is what ties it to a scope node.
        val d1 = id("SELECT division_id FROM app.territory WHERE id = $t1"); val w1 = id("SELECT wing_id FROM app.division WHERE id = $d1")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('TEMPTY','Empty territory', $d1)")
        val tEmpty = id("SELECT id FROM app.territory WHERE code = 'TEMPTY'")
        exec("INSERT INTO app.division (code, name, wing_id) VALUES ('DOTHER','Other division', $w1)")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('TOTHER','Other empty', (SELECT id FROM app.division WHERE code = 'DOTHER'))")
        val tOther = id("SELECT id FROM app.territory WHERE code = 'TOTHER'")
        fun reachOf(vararg tops: ReachNode) = Reach(5, Role.TSO, day, false, setOf(z1, z2), emptySet(), false, tops.toList())
        val dmo = reachOf(ReachNode("division", d1)); val wm = reachOf(ReachNode("wing", w1)); val tso = reachOf(ReachNode("zone", z1))
        assertEquals(0L, terr(node = tEmpty, reach = dmo).kpis.gross_mtk, "under the caller's division: in reach, nothing in it")
        assertEquals(0L, terr(node = tEmpty, reach = wm).kpis.gross_mtk, "under the caller's wing: in reach, nothing in it")
        assertEquals(0L, terr(node = tOther, reach = wm).kpis.gross_mtk, "another division of the same wing")
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { terr(node = tOther, reach = dmo) }.code, "another division is not the DMO's")
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { terr(node = tEmpty, reach = tso) }.code, "a zone scope node does not own an empty territory")
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { terr(node = 999_999L, reach = dmo) }.code, "an unknown id")
    }

    @Test
    fun resolveScopedNodeTiesAnEmptyNodeToTheCallerOnlyThroughItsParentChain() {
        // The shared resolver of Ops, DailyTracking and FlaggedMemos (not DashboardService): same chain rule as the summary.
        val d1 = id("SELECT division_id FROM app.territory WHERE id = $t1"); val w1 = id("SELECT wing_id FROM app.division WHERE id = $d1")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('TEMPTY','Empty territory', $d1)")
        val tEmpty = id("SELECT id FROM app.territory WHERE code = 'TEMPTY'")
        exec("INSERT INTO app.division (code, name, wing_id) VALUES ('DOTHER','Other division', $w1)")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('TOTHER','Other empty', (SELECT id FROM app.division WHERE code = 'DOTHER'))")
        val tOther = id("SELECT id FROM app.territory WHERE code = 'TOTHER'")
        fun reachOf(vararg tops: ReachNode) = Reach(5, Role.TSO, day, false, setOf(z1, z2), emptySet(), false, tops.toList())
        fun node(reach: Reach, id: Long) = fresh.db.jdbi.withHandle<ScopedNode, Exception> { resolveScopedNode(it, reach, "territory", id, from = day) }
        val dmo = reachOf(ReachNode("division", d1)); val wm = reachOf(ReachNode("wing", w1)); val tso = reachOf(ReachNode("zone", z1))
        assertEquals(emptyList(), node(dmo, tEmpty).zones, "under the caller's division: in reach, no zones")
        assertEquals(emptyList(), node(wm, tEmpty).zones, "under the caller's wing")
        assertEquals(emptyList(), node(wm, tOther).zones, "another division of the same wing")
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { node(dmo, tOther) }.code, "another division is not the DMO's")
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { node(tso, tEmpty) }.code, "a zone scope node does not own an empty territory")
    }

    @Test
    fun resolveScopedNodeChainCoversTheNodeItselfAndEmptyDivisionsAndWings() {
        val d1 = id("SELECT division_id FROM app.territory WHERE id = $t1"); val w1 = id("SELECT wing_id FROM app.division WHERE id = $d1")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('TEMPTY','Empty territory', $d1)")
        val tEmpty = id("SELECT id FROM app.territory WHERE code = 'TEMPTY'")
        exec("INSERT INTO app.division (code, name, wing_id) VALUES ('DEMPTY','Empty division', $w1)")
        val dEmpty = id("SELECT id FROM app.division WHERE code = 'DEMPTY'")
        exec("INSERT INTO app.wing (code, name) VALUES ('WEMPTY','Empty wing')")
        val wEmpty = id("SELECT id FROM app.wing WHERE code = 'WEMPTY'")
        fun reachOf(vararg tops: ReachNode) = Reach(5, Role.TSO, day, false, setOf(z1, z2), emptySet(), false, tops.toList())
        fun node(reach: Reach, level: String, id: Long) = fresh.db.jdbi.withHandle<ScopedNode, Exception> { resolveScopedNode(it, reach, level, id, from = day) }
        assertEquals(emptyList(), node(reachOf(ReachNode("territory", tEmpty)), "territory", tEmpty).zones, "the empty territory is the caller's own top node")
        assertEquals(emptyList(), node(reachOf(ReachNode("wing", w1)), "division", dEmpty).zones, "an empty division under the caller's wing")
        assertEquals(emptyList(), node(reachOf(ReachNode("division", dEmpty)), "division", dEmpty).zones, "an empty division that is the caller's top node")
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { node(reachOf(ReachNode("division", d1)), "division", dEmpty) }.code, "a sibling division is not the DMO's")
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { node(reachOf(ReachNode("wing", w1)), "wing", wEmpty) }.code, "an empty wing that is not the caller's")
        assertEquals(emptyList(), node(reachOf(ReachNode("wing", wEmpty)), "wing", wEmpty).zones, "an empty wing that is the caller's top node")
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
        assertEquals(0L, terr(node = t2, reach = other).kpis.gross_mtk, "T2 is inside this caller's reach today (it holds Z2 only) and had no zone on that date: 200, nothing in it")
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { terr(node = t1, reach = other) }.code, "T1 still holds Z1, which this caller cannot see: outside the reach, refused")
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
        // Both zones left T1 for T2 on day 2: T1 has no member from then on (200, no rows), still has both before it, and T2 has none on day 1.
        moveZ2()
        exec("UPDATE app.zone SET territory_id = $t2 WHERE code = 'Z1'")
        exec("UPDATE app.zone_history SET valid_to = DATE '$day2' WHERE zone_id IN ($z1, $z2) AND territory_id = $t1 AND valid_to IS NOT NULL")
        exec("UPDATE app.zone_history SET valid_from = DATE '$day2' WHERE zone_id IN ($z1, $z2) AND territory_id = $t2")
        assertEquals(0L, terr(day2, day2, t1).kpis.gross_mtk, "T1 had no zone on day 2: 200, nothing in it (D-LEAD-REPORT-SELECTOR-01)")
        assertTrue(terr(day, day2, t1).kpis.gross_mtk > 0, "but it had both on day 1")
        assertEquals(0L, terr(day, day, t2).kpis.gross_mtk, "and T2 had none on day 1: 200, nothing in it")
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
        assertEquals(emptySet(), routes(ops.loginSubmit(national, dash(), day, "territory", t2)), "T2 had no zone that day: 200, no routes (D-LEAD-REPORT-SELECTOR-01)")
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { ops.loginSubmit(national, dash(), day, "territory", 999_999L) }.code, "an id that is no territory")
    }

    @Test
    fun geoValidationSignalsAndMismatchFollowTheZoneChainOfEachDayAcrossAMove() {
        // Z2's visits of day 1 were re-checked by the server and disagreed with the device; Z2 raised one signal on each day.
        exec("UPDATE dw.fact_visit SET server_verdict = 'out_of_range', device_verdict = 'in_range' WHERE zone_id = $z2 AND visit_kind = 'sr_call' AND NOT voided")
        exec("INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, zone_id, score, status, config_version) VALUES " +
            "('GEO_MOCK', 2, DATE '$day', 'visit', 's1', $z2, 20, 'open', 1), ('GEO_MOCK', 2, DATE '$day2', 'visit', 's2', $z2, 20, 'open', 1)")
        moveZ2()
        exec("UPDATE app.zone_history SET valid_to = DATE '$day2' WHERE zone_id = $z2 AND territory_id = $t1 AND valid_to IS NOT NULL")
        exec("UPDATE app.zone_history SET valid_from = DATE '$day2' WHERE zone_id = $z2 AND territory_id = $t2")
        val old = geo(day, day2); val new = geo(day, day2, node = t2)
        assertTrue((old.geo_mismatch_pct ?: 0.0) > 0.0, "T1 had Z2's re-checked visits on day 1")
        assertEquals(null, new.geo_mismatch_pct, "T2 had Z2 from day 2 only, and nothing was checked then")
        assertEquals(1, old.signals_by_code["GEO_MOCK"], "T1: the signal Z2 raised while it was T1's")
        assertEquals(1, new.signals_by_code["GEO_MOCK"], "T2: the signal of day 2")
    }

    @Test
    fun theLoginSubmitListsPlaceARouteInTheZoneItHadOnThatDate() {
        // R3 (Z2) is reassigned to Z1 today: on the fixture date it is still Z2's route.
        exec("UPDATE app.route SET zone_id = $z1 WHERE code = 'R3'")
        val ops = OpsService(fresh.db, com.aktcl.aron.backend.platform.RegistryDefaults(), AronClock { now })
        fun routes(s: LoginSubmitStatus) = (s.not_logged_in + s.logged_in_not_submitted + s.submitted + s.exceptions).map { it.route_code }.toSet()
        assertEquals(setOf("R3"), routes(ops.loginSubmit(national, dash(), day, "zone", z2)), "Z2 had R3 that day")
        assertEquals(setOf("R1", "R2"), routes(ops.loginSubmit(national, dash(), day, "zone", z1)), "Z1 did not")
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
        // D-LEAD-REPORT-SELECTOR-01: the node is real and in the national reach, so each answers 200 with no rows; an id that is no node is the refusal.
        assertEquals(0, FlaggedMemos(fresh.db).page(national, day, day, "territory", t2, null, 10, null).items.size)
        assertEquals(0, DailyTrackingService(fresh.db, com.aktcl.aron.backend.platform.RegistryDefaults(), c).page(national, day, "territory", t2, 10, null).items.size)
        assertEquals(ProblemCode.ERR_OUT_OF_SCOPE, assertFailsWith<ApiProblem> { FlaggedMemos(fresh.db).page(national, day, day, "territory", 999_999L, null, 10, null) }.code)
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { DailyTrackingService(fresh.db, com.aktcl.aron.backend.platform.RegistryDefaults(), c).page(national, day, "territory", 999_999L, 10, null) }.code)
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
