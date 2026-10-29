package com.aktcl.aron.core.map

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * N-053 acceptance, phone-free half: with the network off the screen falls back to the list with the age of each fix,
 * shows the map image as last seen when the bounded cache has one, and never composes the Maps SDK view.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LiteMapScreenTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_791_342_600_000L + 15 * 60_000 // 09:25 Dhaka
    private val points = listOf(
        MapPoint("sr-1", "Rahim (R-12)", 23.79, 90.40, 1_791_342_600_000L, "visit"), // 09:10, 15 min ago
        MapPoint("sr-2", "Karim (R-14)", 23.80, 90.41, now - 150 * 60_000, "check_in"), // 2.5 h ago: greyed
        MapPoint("sr-3", "Selim (R-15)", null, null),
    )

    private fun show(online: Boolean, keyPresent: Boolean = true, cache: MapTileCache? = null, language: AppLanguage = AppLanguage.EN) =
        rule.setContent {
            AronTheme(language) { LiteMapScreen(points, MapSettings(), online, keyPresent, now, cache, "team-zone-3") }
        }

    @Test
    fun offlineShowsTheListWithTheAgeOfEachFixAndNoMap() {
        show(online = false)
        rule.onNodeWithTag(LiteMapTags.LIST).assertIsDisplayed()
        rule.onNodeWithText("Last seen 09:10 (15 min ago) · Visit").assertIsDisplayed()
        rule.onNodeWithText("Last seen 06:55 (150 min ago) · Check-in").assertIsDisplayed()
        rule.onNodeWithText("No location yet").assertIsDisplayed()
        rule.onNodeWithTag(LiteMapTags.MAP).assertDoesNotExist()
        rule.onNodeWithTag(LiteMapTags.SNAPSHOT).assertDoesNotExist()
        rule.onNodeWithText("No network: the map is not loaded. The list shows the last known positions.").assertIsDisplayed()
    }

    @Test
    fun offlineShowsTheCachedImageOfThisViewOnly() {
        val cache = MapTileCache(File(tmp.root, "map"), { 20L * 1024 * 1024 })
        val bmp = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        cache.put("team-zone-3", bytes)
        show(online = false, cache = cache)
        // the image is read and decoded on Dispatchers.IO, which compose's idling does not track: wait for it, never race it
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithTag(LiteMapTags.SNAPSHOT).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(LiteMapTags.SNAPSHOT).assertIsDisplayed()
        rule.onNodeWithTag(LiteMapTags.MAP).assertDoesNotExist()
    }

    @Test
    fun noKeyOnlineShowsTheListNotABrokenMap() {
        show(online = true, keyPresent = false)
        rule.onNodeWithTag(LiteMapTags.MAP).assertDoesNotExist()
        rule.onNodeWithText("The map is not available on this phone. The list shows the last known positions.").assertIsDisplayed()
        rule.onNodeWithText("Rahim (R-12)").assertIsDisplayed()
    }

    /** tso-manual A3: the interactive map (TSO My Team and Retailer) shows the same list offline and never composes the SDK. */
    @Test
    fun interactiveOfflineShowsTheListAndNoMap() {
        rule.setContent { AronTheme(AppLanguage.EN) { LiteMapScreen(points, MapSettings(), false, true, now, null, "team-zone-3", interactive = true, circle = MapCircle(23.79, 90.40, 100)) } }
        rule.onNodeWithTag(LiteMapTags.LIST).assertIsDisplayed()
        rule.onNodeWithText("Rahim (R-12)").assertIsDisplayed()
        rule.onNodeWithTag(LiteMapTags.MAP).assertDoesNotExist()
    }

    /** Outlets have no fix age: no "No location yet" line and never greyed; the note (distance, mock chip) shows under the name. */
    @Test
    fun outletRowsShowTheirNoteAndAnUnknownSourceAsIs() {
        val pts = listOf(
            MapPoint("o-1", "Shop One", 23.79, 90.40, kind = MapPointKind.OUTLET, note = "12 m"),
            MapPoint("sr-9", "Selim", 23.79, 90.41, now - 5 * 60_000, "সিঙ্ক", note = "Mock GPS today: 2"),
        )
        rule.setContent { AronTheme(AppLanguage.EN) { LiteMapScreen(pts, MapSettings(), false, true, now, null, "near", interactive = true) } }
        rule.onNodeWithText("12 m").assertIsDisplayed()
        rule.onNodeWithText("No location yet").assertDoesNotExist()
        rule.onNodeWithText("Last seen 09:20 (5 min ago) · সিঙ্ক\nMock GPS today: 2").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "bn")
    fun banglaUsesBengaliDigits() {
        show(online = false, language = AppLanguage.BN)
        rule.onNodeWithText("সর্বশেষ দেখা ০৯:১০ (১৫ মিনিট আগে) · ভিজিট").assertIsDisplayed()
    }
}
