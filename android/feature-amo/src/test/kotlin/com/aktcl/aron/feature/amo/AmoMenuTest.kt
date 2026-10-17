package com.aktcl.aron.feature.amo

import com.aktcl.aron.feature.home.RoleHomes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmoMenuTest {
    private fun section(srs: Int, vararg pending: Pair<String, Long>): String {
        val team = (1..srs).joinToString(",") { """{"user_id":$it,"username":"sr$it","full_name":"SR $it","role":"SR","route_ids":[1]}""" }
        val reqs = pending.joinToString(",") { (st, by) -> """{"request_uuid":"u","request_type":"new","status":"$st","requested_by_user_id":$by,"requested_at":"2026-10-17T03:00:00Z"}""" }
        return """{"zone_ids":[1],"route_ids":[1],"team":[$team],"pending_outlet_requests":[$reqs]}"""
    }

    @Test fun fullAmoHasSeventeenWithAsthaAndSixteenWhileItIsDeferred() {
        val full = AmoReach.parse(section(3), 99)
        assertEquals(16, AmoMenu.leaves(full).size)
        assertEquals(17, AmoMenu.leaves(full.copy(asthaOutlets = true)).size)
        assertTrue(AmoLeaf.SETTINGS in AmoMenu.leaves(full))
    }

    @Test fun ssUserHasThirteenWithoutTeamLocationPerformanceAndSrStock() {
        val ss = AmoReach.parse(section(0), 99)
        assertTrue(ss.ss)
        val leaves = AmoMenu.leaves(ss)
        assertEquals(13, leaves.size)
        assertTrue(AmoLeaf.TASK_DELEGATION in leaves && AmoLeaf.LIVE_DASHBOARD in leaves)
        assertTrue(leaves.none { it == AmoLeaf.TEAM_PERFORMANCE || it == AmoLeaf.SR_STOCK || it == AmoLeaf.TEAM_LOCATION || it == AmoLeaf.ASTHA })
        assertEquals(listOf(AmoLeaf.TASK_DELEGATION, AmoLeaf.LIVE_DASHBOARD), AmoMenu.hub(RoleHomes.AmoTile.TEAM, ss))
    }

    @Test fun outletBadgeOnlyAboveZeroAndOnlyForOthersPendingRequests() {
        assertEquals(emptyMap<String, Int>(), AmoMenu.badges(AmoReach.parse(section(2), 99)))
        assertEquals(emptyMap<String, Int>(), AmoMenu.badges(AmoReach.parse(section(2, "verified" to 5L, "rejected" to 5L), 99)))
        // the AMO's own New shop goes to the web approver: no verification step, no badge
        assertEquals(emptyMap<String, Int>(), AmoMenu.badges(AmoReach.parse(section(2, "pending" to 99L), 99)))
        assertEquals(mapOf(RoleHomes.AmoTile.OUTLET to 2), AmoMenu.badges(AmoReach.parse(section(2, "pending" to 5L, "pending" to 6L, "approved" to 7L), 99)))
    }

    @Test fun missingOrMalformedSectionIsTheReducedMenuAndNoBadge() {
        for (bad in listOf(null, "", "not json", "[]", "{}")) {
            val r = AmoReach.parse(bad, 1)
            assertEquals(AmoReach.NONE, r)
            assertEquals(13, AmoMenu.leaves(r).size)
            assertTrue(AmoMenu.badges(r).isEmpty())
        }
    }

    @Test fun saleHubHoldsSaleStockMemoSummary() {
        assertEquals(listOf(AmoLeaf.SALE, AmoLeaf.STOCK, AmoLeaf.MEMO, AmoLeaf.SUMMARY), AmoMenu.hub(RoleHomes.AmoTile.SALE, AmoReach(2, 0)))
    }
}
