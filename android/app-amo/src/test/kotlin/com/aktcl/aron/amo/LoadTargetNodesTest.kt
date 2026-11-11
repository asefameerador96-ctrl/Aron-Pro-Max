package com.aktcl.aron.amo

import com.aktcl.aron.feature.amo.RouteOutOfReach
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The zone-then-routes loop of Team Performance and Sales till now (contract 1.11.2 `level=route&node_id=`): a refusal ends it after ONE ask. */
class LoadTargetNodesTest {
    private fun node(type: String, id: Long, name: String?): String {
        val nm = if (name != null) ",\"name\":\"$name\"" else ""
        return """{"node":{"type":"$type","id":$id$nm},"period_days":6,"rows":[{"name":"Marise","category_code":"cigarette","target_qty_base":100,"achieved_qty_base":50,"tilldate_target_qty_base":60,"report_unit":"pack","target_qty_report":"5.000","achieved_qty_report":"2.500","tilldate_target_qty_report":"3.000","remaining_qty_report":"2.500","remaining_tilldate_qty_report":"0.500"}]}"""
    }

    private val purged = mutableListOf<Long>()

    private suspend fun run(zone: AmoFetch, routes: Map<Long, RouteTargetsRead>, ids: List<Long>): Pair<List<TargetNodes>, List<Long>> {
        val asked = mutableListOf<Long>(); val published = mutableListOf<TargetNodes>(); purged.clear()
        loadTargetNodes({ zone }, { id -> asked += id; routes[id] ?: RouteTargetsRead(AmoFetch.Unavailable, false) }, ids, { "Route $it" }, { purged += it }) { published += it }
        return published to asked
    }

    private fun fresh(json: String) = RouteTargetsRead(AmoFetch.Fresh(json), false)
    private val zoneOk = AmoFetch.Fresh(node("zone", 5, "Gulshan"))
    private val gone = RouteTargetsRead(AmoFetch.Gone, false)
    private val notOffered = RouteTargetsRead(AmoFetch.Unavailable, true)

    @Test fun everyRouteAnswersAndFillsInWithItsNameFromTheMembers() = runBlocking {
        val (out, asked) = run(zoneOk, mapOf(1L to fresh(node("route", 1, null)), 2L to fresh(node("route", 2, "Banani"))), listOf(1, 2))
        assertEquals(listOf(1L, 2L), asked)
        val last = out.last()
        assertEquals(listOf("Route 1", "Banani"), last.routes.map { it.name }); assertFalse(last.refused); assertFalse(last.failed); assertTrue(last.outOfReach.isEmpty()); assertFalse(last.routeLevelUnavailable)
        assertEquals("5.000", last.routes.first().rows.single().qty!!.target.toPlainString())
    }

    /** Lead ruling 2026-11-11 06:00 UTC (a): a 403 marks that route alone; the loop reaches the later routes; no screen-level refusal. */
    @Test fun aRouteRefusedWith403IsMarkedAndTheLoopContinuesWithTheLaterRoutes() = runBlocking {
        val (out, asked) = run(zoneOk, mapOf(1L to gone, 2L to fresh(node("route", 2, "B")), 3L to gone, 4L to fresh(node("route", 4, "D"))), listOf(1, 2, 3, 4))
        assertEquals("every route is asked once, the refused ones included", listOf(1L, 2L, 3L, 4L), asked)
        val last = out.last()
        assertEquals(listOf(RouteOutOfReach(1, "Route 1"), RouteOutOfReach(3, "Route 3")), last.outOfReach)
        assertEquals(listOf("B", "D"), last.routes.map { it.name })
        assertFalse("no screen-level refusal banner for a route", last.refused); assertEquals("Gulshan", last.zone!!.name); assertFalse(last.routeLevelUnavailable)
    }

    /** (b): a 400 means the server has no route level: one note, the zone card stays, no access banner, no further asks. */
    @Test fun aRouteRead400IsRouteDetailUnavailableNotAnAccessProblemAndStopsTheLoop() = runBlocking {
        val (out, asked) = run(zoneOk, mapOf(1L to notOffered), listOf(1, 2, 3))
        assertEquals(listOf(1L), asked)
        val last = out.last()
        assertTrue(last.routeLevelUnavailable); assertFalse(last.refused); assertFalse(last.failed); assertEquals("Gulshan", last.zone!!.name); assertTrue(last.outOfReach.isEmpty())
    }

    @Test fun aSecondRouteNotOfferedAfterTheFirstKeepsTheFirst() = runBlocking {
        val (out, asked) = run(zoneOk, mapOf(1L to fresh(node("route", 1, "A")), 2L to notOffered), listOf(1, 2, 3))
        assertEquals(listOf(1L, 2L), asked)
        assertEquals(listOf("A"), out.last().routes.map { it.name }); assertTrue(out.last().routeLevelUnavailable)
    }

    @Test fun anOfflineRouteStopsTheLoopWithoutARefusalOrANote() = runBlocking {
        val (out, asked) = run(zoneOk, emptyMap(), listOf(1, 2, 3))
        assertEquals(listOf(1L), asked); assertFalse(out.last().refused); assertFalse(out.last().routeLevelUnavailable)
    }

    @Test fun aKeptRouteCopyKeepsItsPlaceAsTheStaleNoteAndARefusedZoneIsRefused() = runBlocking {
        val (out, _) = run(zoneOk, mapOf(1L to RouteTargetsRead(AmoFetch.Cached(node("route", 1, "A")), false), 2L to gone), listOf(1, 2))
        assertTrue("the kept-copy note stays", out.last().stale); assertEquals(1, out.last().routes.size); assertEquals(1, out.last().outOfReach.size)
        val (z, _) = run(AmoFetch.Gone, emptyMap(), listOf())
        assertTrue(z.last().refused); assertTrue(z.last().failed)
        val (k, _) = run(AmoFetch.Cached(node("zone", 5, "Gulshan")), emptyMap(), listOf())
        assertTrue(k.last().stale); assertFalse(k.last().refused)
    }

    /** D-AMO-CALLS-31 (5): a refused zone is ONE refusal; the routes are not asked at all. */
    @Test fun aZoneRefusedWith403EndsTheLoopAtOnceAndNoRouteIsAsked() = runBlocking {
        val (out, asked) = run(AmoFetch.Gone, mapOf(1L to fresh(node("route", 1, "A"))), listOf(1, 2, 3, 4, 5))
        assertTrue("no route was asked", asked.isEmpty())
        assertTrue(out.last().refused); assertTrue(out.last().routes.isEmpty()); assertTrue(out.last().outOfReach.isEmpty()); assertEquals(1, out.size)
        assertEquals("the old route copies are dropped with the refused zone", listOf(1L, 2L, 3L, 4L, 5L), purged)
    }

    @Test fun aZoneThatAnswersPurgesNothing() = runBlocking {
        run(zoneOk, mapOf(1L to fresh(node("route", 1, "A"))), listOf(1)); assertTrue(purged.isEmpty())
    }
}
