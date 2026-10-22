package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.contract.Role
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D-ROLES-02, checker row 2 (B2): a geographic field role gets no reach for a role the user no longer holds (the upload
 * token of a phone whose user changed role), whatever scope rows the user has now.
 */
class FieldRoleReachTest {
    private val d = LocalDate.of(2026, 10, 22)
    // wing 1 > division 10, 20 > territory 100 (div 10), 200 (div 20) > zone 1000 (terr 100), 2000 (terr 200)
    private val geo = Geo(
        zoneTerritory = mapOf(1000L to 100L, 2000L to 200L), territoryDivision = mapOf(100L to 10L, 200L to 20L),
        divisionWing = mapOf(10L to 1L, 20L to 1L), routeZone = mapOf(5L to 1000L),
    )
    private fun row(type: String, id: Long) = ScopeRow(type, id, d.minusDays(30), null)
    private fun reach(role: Role, rows: List<ScopeRow>, current: Role? = null) = ReachCalculator.compute(7, role, d, rows, emptyList(), geo, current)

    @Test
    fun aRoleTheUserNoLongerHoldsReachesNothing() {
        // TSO promoted to NATIONAL with a national row; AMO demoted to SR with its zone row left open.
        reach(Role.TSO, listOf(row("national", 0), row("territory", 100)), current = Role.NATIONAL).let { assertFalse(it.national); assertTrue(it.zoneIds.isEmpty()); assertTrue(it.routeIds.isEmpty()) }
        reach(Role.AMO, listOf(row("zone", 1000)), current = Role.SR).let { assertFalse(it.national); assertTrue(it.zoneIds.isEmpty()) }
        assertEquals(setOf(1000L), reach(Role.AMO, listOf(row("zone", 1000)), current = Role.AMO).zoneIds)
        // The national roles and SR are unchanged by the check.
        assertTrue(reach(Role.NATIONAL, emptyList(), current = Role.NATIONAL).national)
        assertTrue(ReachCalculator.compute(7, Role.SR, d, emptyList(), listOf(AssignmentRow(5, "primary", d.minusDays(1), null)), geo, Role.TSO).routeIds == setOf(5L))
    }
}
