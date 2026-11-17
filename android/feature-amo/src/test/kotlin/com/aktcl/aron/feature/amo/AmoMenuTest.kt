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

    @Test fun fullAmoHasEighteenWithAsthaAndSeventeenWhileItIsDeferred() {
        val full = AmoReach.parse(section(3), 99)
        assertEquals(17, AmoMenu.leaves(full).size)
        assertEquals(18, AmoMenu.leaves(full.copy(asthaOutlets = true)).size)
        assertTrue(AmoLeaf.SETTINGS in AmoMenu.leaves(full))
    }

    @Test fun homeTilesFollowTheOldAppsOrderWithTheDeferredHooksInPlace() {
        val tiles = AmoMenu.tiles(AmoReach.parse(section(3, "pending" to 5L), 99), AmoMenu.badges(AmoReach.parse(section(3, "pending" to 5L), 99)))
        assertEquals(
            listOf(
                "attendance", "joint_call", "control_call", "team_location", "team_performance", "task_delegation", "live_dashboard", "sr_stock",
                "outlet", "stock", "sale", "memo", "summary", "sales_submit", "astha", "report", "sales_journey", "campaign_enrollment", "settings",
            ),
            tiles.map { it.id },
        )
        // Astha and Campaign Enrollment are hook tiles in the old app's place (docs/27, D-PAR-AMO-01); the Outlet tile carries the pending count, no other tile a badge
        assertTrue(AmoMenu.isHook("astha") && AmoMenu.isHook("campaign_enrollment") && !AmoMenu.isHook("report"))
        assertEquals(mapOf("outlet" to 1), tiles.filter { it.badge > 0 }.associate { it.id to it.badge })
        // the hook tiles show whatever the bundle says: the tile count is the same with or without Astha outlets
        assertEquals(19, AmoMenu.tiles(AmoReach(3, 0, asthaOutlets = true)).size)
        assertEquals(19, AmoMenu.tiles(AmoReach(3, 0)).size)
    }

    @Test fun ssUserHasFourteenWithoutTeamLocationPerformanceAndSrStock() {
        val ss = AmoReach.parse(section(0), 99)
        assertTrue(ss.ss)
        val leaves = AmoMenu.leaves(ss)
        assertEquals(14, leaves.size)
        assertTrue(AmoLeaf.TASK_DELEGATION in leaves && AmoLeaf.LIVE_DASHBOARD in leaves)
        assertTrue(leaves.none { it == AmoLeaf.TEAM_PERFORMANCE || it == AmoLeaf.SR_STOCK || it == AmoLeaf.TEAM_LOCATION || it == AmoLeaf.ASTHA })
        assertEquals(listOf(AmoLeaf.TASK_DELEGATION, AmoLeaf.LIVE_DASHBOARD), AmoMenu.hub(RoleHomes.AmoTile.TEAM, ss))
    }

    @Test fun outletBadgeOnlyAboveZeroAndOnlyForOthersPendingRequests() {
        assertEquals(emptyMap<String, Int>(), AmoMenu.badges(AmoReach.parse(section(2), 99)))
        assertEquals(emptyMap<String, Int>(), AmoMenu.badges(AmoReach.parse(section(2, "verified" to 5L, "rejected" to 5L), 99)))
        // the AMO's own New shop goes to the web approver: no verification step, no badge
        assertEquals(emptyMap<String, Int>(), AmoMenu.badges(AmoReach.parse(section(2, "pending" to 99L), 99)))
        assertEquals(mapOf("outlet" to 2), AmoMenu.badges(AmoReach.parse(section(2, "pending" to 5L, "pending" to 6L, "approved" to 7L), 99)))
    }

    @Test fun missingOrMalformedSectionIsTheReducedMenuAndNoBadge() {
        for (bad in listOf(null, "", "not json", "[]", "{}")) {
            val r = AmoReach.parse(bad, 1)
            assertEquals(AmoReach.NONE, r)
            assertEquals(14, AmoMenu.leaves(r).size)
            assertTrue(AmoMenu.badges(r).isEmpty())
        }
    }

    @Test fun saleHubHoldsSaleStockMemoSummary() {
        assertEquals(listOf(AmoLeaf.SALE, AmoLeaf.STOCK, AmoLeaf.MEMO, AmoLeaf.SUMMARY), AmoMenu.hub(RoleHomes.AmoTile.SALE, AmoReach(2, 0)))
    }
}
