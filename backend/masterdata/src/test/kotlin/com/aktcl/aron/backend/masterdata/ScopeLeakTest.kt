package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.contract.Role
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScopeLeakTest {
    @Test
    fun twoHundredRandomisedQueriesFindZeroCrossScopeRows() {
        for (seed in listOf(1L, 2L, 3L, 20261005L)) {
            val w = ScopeWorld(seed)
            var refused = 0
            val leaks = w.run(200) { u, date, sel ->
                val reach = ReachCalculator.compute(u.id, u.role, date, u.scope, u.assignments, w.geo)
                outcomeOf { ReachFilter.outlets(reach, sel, w.geo, w.outlets.asSequence()).map { it.id } }.also {
                    if (it is ScopeWorld.Outcome.Refused) refused++
                }
            }
            assertEquals(emptyList(), leaks, "seed $seed")
            assertTrue(refused > 0, "seed $seed: the harness must exercise out-of-scope selectors")
        }
    }

    @Test
    fun theHarnessCatchesALeakyOrLossyImplementation() {
        val w = ScopeWorld(1)
        assertTrue(w.run(200) { _, _, _ -> ScopeWorld.Outcome.Rows(w.outlets.map { it.id }.toSet()) }.any { it.detail.startsWith("leaked") })
        assertTrue(w.run(200) { _, _, _ -> ScopeWorld.Outcome.Rows(emptySet()) }.any { it.detail.startsWith("missing") })
        // Ignoring the business date (reach "ever assigned") must be caught too.
        val leaks = w.run(200) { u, _, sel ->
            val reach = ReachCalculator.compute(u.id, u.role, w.day0, u.scope.map { it.copy(validTo = null) }, u.assignments.map { it.copy(validTo = null) }, w.geo)
            outcomeOf { ReachFilter.outlets(reach, sel, w.geo, w.outlets.asSequence()).map { it.id } }
        }
        assertTrue(leaks.isNotEmpty())
    }

    @Test
    fun aTsoSeesOnlyItsTerritoryAndASelectorOutsideIsRefused() {
        val w = ScopeWorld(7)
        val territory = w.territoryDivision.keys.first()
        val d = LocalDate.parse("2026-10-05")
        val reach = ReachCalculator.compute(42, Role.TSO, d, listOf(ScopeRow("territory", territory, d.minusDays(30), null)), emptyList(), w.geo)
        val rows = ReachFilter.outlets(reach, GeoSelector(), w.geo, w.outlets.asSequence())
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { w.zoneTerritory[it.zoneId] == territory })
        val otherZone = w.zoneTerritory.entries.first { it.value != territory }.key
        val refused = outcomeOf { ReachFilter.outlets(reach, GeoSelector(zoneId = otherZone), w.geo, w.outlets.asSequence()).map { it.id } }
        assertTrue(refused is ScopeWorld.Outcome.Refused)
        // Expired scope gives nothing; a future scope gives nothing yet.
        val expired = ReachCalculator.compute(42, Role.TSO, d, listOf(ScopeRow("territory", territory, d.minusDays(30), d)), emptyList(), w.geo)
        assertTrue(ReachFilter.outlets(expired, GeoSelector(), w.geo, w.outlets.asSequence()).isEmpty())
    }

    @Test
    fun anSrSeesOnlyTheOutletsOfRoutesAssignedOnTheDate() {
        val w = ScopeWorld(9)
        val route = w.routeZone.keys.first()
        val d = LocalDate.parse("2026-10-05")
        val asg = listOf(AssignmentRow(route, "cover", d, d.plusDays(1)))
        // Even with a scope row (data error), an SR never gets zone-wide reach.
        val reach = ReachCalculator.compute(7, Role.SR, d, listOf(ScopeRow("national", 0, d.minusDays(1), null)), asg, w.geo)
        val rows = ReachFilter.outlets(reach, GeoSelector(), w.geo, w.outlets.asSequence())
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.routeId == route })
        val nextDay = ReachCalculator.compute(7, Role.SR, d.plusDays(1), emptyList(), asg, w.geo)
        assertTrue(ReachFilter.outlets(nextDay, GeoSelector(), w.geo, w.outlets.asSequence()).isEmpty(), "cover ends (exclusive valid_to)")
    }
}
