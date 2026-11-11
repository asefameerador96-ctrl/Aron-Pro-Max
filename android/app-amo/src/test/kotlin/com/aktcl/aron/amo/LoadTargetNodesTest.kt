package com.aktcl.aron.amo

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

    private suspend fun run(answers: Map<Long?, AmoFetch>, ids: List<Long>): Pair<List<TargetNodes>, List<Long?>> {
        val asked = mutableListOf<Long?>(); val published = mutableListOf<TargetNodes>()
        loadTargetNodes({ id -> asked += id; answers[id] ?: AmoFetch.Unavailable }, ids, { "Route $it" }) { published += it }
        return published to asked
    }

    @Test fun everyRouteAnswersAndFillsInWithItsNameFromTheMembers() = runBlocking {
        val (out, asked) = run(mapOf(null to AmoFetch.Fresh(node("zone", 5, "Gulshan")), 1L to AmoFetch.Fresh(node("route", 1, null)), 2L to AmoFetch.Fresh(node("route", 2, "Banani"))), listOf(1, 2))
        assertEquals(listOf<Long?>(null, 1L, 2L), asked)
        val last = out.last()
        assertEquals(listOf("Route 1", "Banani"), last.routes.map { it.name }); assertFalse(last.refused); assertFalse(last.failed)
        assertEquals("5.000", last.routes.first().rows.single().qty!!.target.toPlainString())
    }

    @Test fun aRouteRefusedWith403StopsTheLoopAtOnceAndSaysRefused() = runBlocking {
        val (out, asked) = run(mapOf(null to AmoFetch.Fresh(node("zone", 5, "Gulshan")), 1L to AmoFetch.Gone), listOf(1, 2, 3, 4))
        assertEquals("one ask for the zone and one refused route, never one per route", listOf<Long?>(null, 1L), asked)
        assertTrue(out.last().refused); assertTrue(out.last().routes.isEmpty()); assertEquals("Gulshan", out.last().zone!!.name)
    }

    @Test fun aSecondRouteRefusedAfterTheFirstKeepsTheFirstAndStops() = runBlocking {
        val (out, asked) = run(mapOf(null to AmoFetch.Fresh(node("zone", 5, "Gulshan")), 1L to AmoFetch.Fresh(node("route", 1, "A")), 2L to AmoFetch.Gone), listOf(1, 2, 3))
        assertEquals(listOf<Long?>(null, 1L, 2L), asked)
        assertEquals(listOf("A"), out.last().routes.map { it.name }); assertTrue(out.last().refused)
    }

    @Test fun anOfflineRouteStopsTheLoopWithoutARefusal() = runBlocking {
        val (out, asked) = run(mapOf(null to AmoFetch.Fresh(node("zone", 5, "Gulshan"))), listOf(1, 2, 3))
        assertEquals(listOf<Long?>(null, 1L), asked); assertFalse(out.last().refused)
    }

    @Test fun aRefusedZoneIsRefusedAndTheKeptCopyIsMarkedStale() = runBlocking {
        val (out, _) = run(mapOf(null to AmoFetch.Gone), listOf())
        assertTrue(out.last().refused); assertTrue(out.last().failed)
        val (kept, _) = run(mapOf(null to AmoFetch.Cached(node("zone", 5, "Gulshan"))), listOf())
        assertTrue(kept.last().stale); assertFalse(kept.last().refused)
    }
}
