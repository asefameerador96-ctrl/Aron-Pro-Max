package com.aktcl.aron.core.map

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
        rule.onNodeWithText("Last seen 09:10 (15 min ago) · visit").assertIsDisplayed()
        rule.onNodeWithText("Last seen 06:55 (150 min ago) · check_in").assertIsDisplayed()
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

    @Test
    @Config(qualifiers = "bn")
    fun banglaUsesBengaliDigits() {
        show(online = false, language = AppLanguage.BN)
        rule.onNodeWithText("সর্বশেষ দেখা ০৯:১০ (১৫ মিনিট আগে) · visit").assertIsDisplayed()
    }
}
