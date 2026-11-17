package com.aktcl.aron.amo

import com.aktcl.aron.feature.amo.AmoLeaf
import com.aktcl.aron.feature.amo.AmoMenu
import com.aktcl.aron.feature.amo.AmoReach
import com.aktcl.aron.feature.amo.AmoScreen
import com.aktcl.aron.feature.home.RoleHomes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Navigation coverage (no placeholders): every AMO Home tile and every hub entry reaches a real screen. The wiring is static (a
 * screens map in MainActivity and the hubs' own `when`s), so the test reads the sources: a tile or leaf without its real screen fails
 * here instead of showing a "coming soon" frame on a phone. Deferred programmes (Astha, docs/27) are the one named exception.
 */
class AmoNavigationCoverageTest {
    private fun source(name: String): String {
        val dir = File("src/main/kotlin/com/aktcl/aron/amo")
        return File(dir, name).readText()
    }

    private val main = source("MainActivity.kt")
    private val team = source("AmoTeamHub.kt")
    private val sale = source("AmoSaleHub.kt")
    private val outlet = source("AmoOutletHub.kt")

    /** The screens the app module builds: its map entries `AmoScreen.X to`. */
    private val built: Set<AmoScreen> = AmoScreen.entries.filter { Regex("AmoScreen\\.${it.name} to").containsMatchIn(main) }.toSet()

    @Test fun everyHomeTileHasItsOwnNavigationEntryAndAScreen() {
        val tiles = AmoMenu.tiles(AmoReach(subordinateSrs = 3, pendingVerifications = 0))
        assertEquals("nineteen tiles: the old app's order with the two deferred hooks (D-PAR-AMO-01)", 19, tiles.size)
        // AmoApp itself draws Home, Settings and the Sales Journey empty state (lane A3 fills the journey); every other tile is built in MainActivity
        val inApp = setOf(AmoScreen.SETTINGS, AmoScreen.SALES_JOURNEY, AmoScreen.ASTHA, AmoScreen.CAMPAIGN_ENROLLMENT)
        tiles.forEach { t ->
            val screen = AmoScreen.of(t)
            assertTrue("tile ${t.id} maps to a screen of its own", screen.tileId == t.id)
            assertTrue("tile ${t.id} (${screen.name}) has a real screen in MainActivity, not the placeholder", screen in built || screen in inApp)
        }
        assertTrue("Sales Journey shows an empty state until A3", File("../feature-amo/src/main/kotlin/com/aktcl/aron/feature/amo/AmoApp.kt").readText().contains("amo_journey_empty"))
        assertTrue("Exceptions has a Settings row and a host", AmoScreen.EXCEPTIONS in built)
    }

    @Test fun settingsAndSupportHaveRealScreens() {
        assertTrue("Support is built in MainActivity", AmoScreen.SUPPORT in built)
        assertTrue("Printer diagnostics is built in MainActivity (D-PRINT-02)", AmoScreen.PRINTER in built)
        // Settings, like Home, is rendered by AmoApp itself (AmoSettingsContent), never by the placeholder
        assertTrue(File("../feature-amo/src/main/kotlin/com/aktcl/aron/feature/amo/AmoApp.kt").readText().contains("AmoScreen.SETTINGS -> AmoSettingsContent"))
    }

    @Test fun everyTeamHubEntryOpensARealScreen() {
        val reach = AmoReach(subordinateSrs = 3, pendingVerifications = 0)
        val leaves = AmoMenu.hub(RoleHomes.AmoTile.TEAM, reach)
        assertEquals(5, leaves.size)
        leaves.forEach { l -> assertTrue("Team hub entry ${l.name} has its own screen in AmoTeamHub", Regex("open == AmoLeaf\\.${l.name}\\b").containsMatchIn(team)) }
        assertTrue("Exceptions is a Team hub entry too", team.contains("TeamLeaf.EXCEPTIONS"))
        leaves.forEach { l -> assertTrue("Team tile ${l.name} is built", AmoScreen.entries.first { it.leaf == l } in built) }
    }

    @Test fun everySaleHubEntryOpensARealScreen() {
        val leaves = AmoMenu.hub(RoleHomes.AmoTile.SALE, AmoReach(subordinateSrs = 3, pendingVerifications = 0))
        assertEquals(listOf(AmoLeaf.SALE, AmoLeaf.STOCK, AmoLeaf.MEMO, AmoLeaf.SUMMARY), leaves)
        listOf("SaleLeaf.SALE ->", "SaleLeaf.STOCK ->", "SaleLeaf.MEMO ->", "SaleLeaf.SUMMARY ->").forEach { assertTrue("Sale hub: $it", sale.contains(it)) }
        // each leaf is also a Home tile of its own: the hub opens on it (D-AMO-MAN-02)
        listOf(AmoScreen.STOCK, AmoScreen.SALE, AmoScreen.MEMO, AmoScreen.SUMMARY).forEach { assertTrue("${it.name} is built", it in built) }
    }

    @Test fun theOutletHubReachesRealScreensForEveryEntry() {
        listOf(
            "OutletLeaf.PICK_ROUTE", "OutletLeaf.OWN_LIST", "OutletLeaf.VERIFY_LIST", "OutletLeaf.VERIFY_FORM", "OutletLeaf.NEW_SHOP", "OutletLeaf.CLOSE", "OutletLeaf.INFO",
            "OutletLeaf.UPDATE_BASE", "OutletLeaf.CLUSTER_FORM",
        ).forEach { assertTrue("Outlet hub: $it", outlet.contains("$it ->")) }
        // A4 (screenshots 54 to 64): every shop operation of the menu has its own host
        listOf("AmoNewShopHost(", "AmoCloseHost(", "AmoChangeInfoHost(", "AmoUpdateBaseHost(", "AmoVerifyHost(", "VerifyListHost(", "AmoClusterHost(").forEach { assertTrue("Outlet hub host $it", outlet.contains(it)) }
    }

    @Test fun theOnlyMenuLeafWithoutAScreenIsAsthaAndItIsHiddenWhileDeferred() {
        val full = AmoReach(subordinateSrs = 3, pendingVerifications = 0)
        assertTrue("Astha (docs/27) is not offered while deferred", AmoLeaf.ASTHA !in AmoMenu.leaves(full))
        // every other destination of the menu is a tile, a hub entry or Settings
        val reachable = AmoMenu.leaves(full).toSet()
        val covered = setOf(
            AmoLeaf.ATTENDANCE, AmoLeaf.JOINT_CALL, AmoLeaf.CONTROL_CALL, AmoLeaf.TEAM_LOCATION, AmoLeaf.TEAM_PERFORMANCE, AmoLeaf.TASK_DELEGATION, AmoLeaf.LIVE_DASHBOARD,
            AmoLeaf.SR_STOCK, AmoLeaf.OUTLET, AmoLeaf.STOCK, AmoLeaf.SALE, AmoLeaf.MEMO, AmoLeaf.SUMMARY, AmoLeaf.SALES_SUBMIT, AmoLeaf.REPORT, AmoLeaf.SALES_JOURNEY, AmoLeaf.SETTINGS,
        )
        assertEquals(emptySet<AmoLeaf>(), reachable - covered)
    }
}
