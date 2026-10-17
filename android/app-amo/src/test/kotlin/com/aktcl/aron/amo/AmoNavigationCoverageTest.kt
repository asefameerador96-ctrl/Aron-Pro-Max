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

    @Test fun everyHomeTileOpensARealScreen() {
        val tiles = RoleHomes.amoTiles(emptyMap())
        assertEquals("eight hub tiles", 8, tiles.size)
        tiles.forEach { t ->
            val screen = AmoScreen.of(t)
            assertTrue("tile ${t.id} maps to a screen of its own", screen.tileId == t.id)
            assertTrue("tile ${t.id} (${screen.name}) has a real screen in MainActivity, not the placeholder", screen in built)
        }
    }

    @Test fun settingsAndSupportHaveRealScreens() {
        assertTrue("Support is built in MainActivity", AmoScreen.SUPPORT in built)
        // Settings, like Home, is rendered by AmoApp itself (RoleSettingsContent), never by the placeholder
        assertTrue(File("../feature-amo/src/main/kotlin/com/aktcl/aron/feature/amo/AmoApp.kt").readText().contains("AmoScreen.SETTINGS -> RoleSettingsContent"))
    }

    @Test fun everyTeamHubEntryOpensARealScreen() {
        val reach = AmoReach(subordinateSrs = 3, pendingVerifications = 0)
        val leaves = AmoMenu.hub(RoleHomes.AmoTile.TEAM, reach)
        assertEquals(5, leaves.size)
        leaves.forEach { l -> assertTrue("Team hub entry ${l.name} has its own screen in AmoTeamHub", Regex("open == AmoLeaf\\.${l.name}\\b").containsMatchIn(team)) }
        assertTrue("Exceptions is a Team hub entry too", team.contains("TeamLeaf.EXCEPTIONS"))
    }

    @Test fun everySaleHubEntryOpensARealScreen() {
        val leaves = AmoMenu.hub(RoleHomes.AmoTile.SALE, AmoReach(subordinateSrs = 3, pendingVerifications = 0))
        assertEquals(listOf(AmoLeaf.SALE, AmoLeaf.STOCK, AmoLeaf.MEMO, AmoLeaf.SUMMARY), leaves)
        listOf("SaleLeaf.SALE ->", "SaleLeaf.STOCK ->", "SaleLeaf.MEMO ->", "SaleLeaf.SUMMARY ->").forEach { assertTrue("Sale hub: $it", sale.contains(it)) }
    }

    @Test fun theOutletHubReachesRealScreensForEveryEntry() {
        listOf("OutletLeaf.PICK_ROUTE", "OutletLeaf.OWN_LIST", "OutletLeaf.VERIFY_LIST", "OutletLeaf.FORM", "OutletLeaf.VERIFY_FORM").forEach { assertTrue("Outlet hub: $it", outlet.contains("$it ->")) }
        assertTrue("Update Base has its own host", outlet.contains("AmoUpdateBaseHost("))
    }

    @Test fun theOnlyMenuLeafWithoutAScreenIsAsthaAndItIsHiddenWhileDeferred() {
        val full = AmoReach(subordinateSrs = 3, pendingVerifications = 0)
        assertTrue("Astha (docs/27) is not offered while deferred", AmoLeaf.ASTHA !in AmoMenu.leaves(full))
        // every other destination of the menu is a tile, a hub entry or Settings
        val reachable = AmoMenu.leaves(full).toSet()
        val covered = setOf(
            AmoLeaf.ATTENDANCE, AmoLeaf.JOINT_CALL, AmoLeaf.CONTROL_CALL, AmoLeaf.TEAM_LOCATION, AmoLeaf.TEAM_PERFORMANCE, AmoLeaf.TASK_DELEGATION, AmoLeaf.LIVE_DASHBOARD,
            AmoLeaf.SR_STOCK, AmoLeaf.OUTLET, AmoLeaf.STOCK, AmoLeaf.SALE, AmoLeaf.MEMO, AmoLeaf.SUMMARY, AmoLeaf.SALES_SUBMIT, AmoLeaf.REPORT, AmoLeaf.SETTINGS,
        )
        assertEquals(emptySet<AmoLeaf>(), reachable - covered)
    }
}
