package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.contract.Role
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D-ROLES-02 row 4: a `house` scope node reaches the zones of that distribution house, and nothing else. */
class HouseScopeTest {
    private val d = LocalDate.of(2026, 10, 22)
    // territory 100 > zones 1000, 1001 (house 7), 1002 (house 8), 1003 (no house); zone 2000 in territory 200 names house 7 by mistake.
    private val geo = Geo(
        zoneTerritory = mapOf(1000L to 100L, 1001L to 100L, 1002L to 100L, 1003L to 100L, 2000L to 200L),
        territoryDivision = mapOf(100L to 10L, 200L to 10L), divisionWing = mapOf(10L to 1L), routeZone = emptyMap(),
        zoneHouse = mapOf(1000L to 7L, 1001L to 7L, 1002L to 8L, 2000L to 7L, 9999L to 7L),
    )

    @Test
    fun aHouseNodeReachesItsZones() {
        assertEquals(setOf(1000L, 1001L, 2000L), geo.zonesUnder("house", 7))
        assertEquals(setOf(1002L), geo.zonesUnder("house", 8))
        assertTrue(geo.zonesUnder("house", 99).isEmpty())
        val r = ReachCalculator.compute(5, Role.DMO, d, listOf(ScopeRow("house", 8, d.minusDays(1), null)), emptyList(), geo, Role.DMO)
        assertEquals(setOf(1002L), r.zoneIds)
        assertEquals(false, r.national)
        // A zone the snapshot does not know (9999) is never reached through its house.
        assertTrue(9999L !in geo.zonesUnder("house", 7))
    }
}
