package com.aktcl.aron.feature.home

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SR-008, 009, 063, 064 on the screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private val header = HomeModel.header("SR", "Testing Banani", "sr334001", "Apsis RouteDaily", "daily", "2026-10-05")

    private fun show(language: AppLanguage, tasks: Int = 0, freshness: BundleFreshness = BundleFreshness.Fresh, health: DeviceHealth? = null, onTile: (HomeTile) -> Unit = {}) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language) { HomeContent(header, HomeTiles.resolve(emptySet(), tasks), freshness, false, health, onTile) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun headerAndTilesInBangla() {
        show(AppLanguage.BN)
        compose.onNodeWithText("SR - Testing Banani (sr334001)").assertExists()
        compose.onNodeWithText("Apsis RouteDaily, ২০২৬-১০-০৫".replace("২০২৬-১০-০৫", "2026-10-05")).assertExists()
        compose.onNodeWithText("অ্যাটেনডেন্স").assertExists(); compose.onNodeWithText("টাস্ক ডেলিগেশন").assertExists()
        compose.onNodeWithText("ফটো ক্যাপচার").assertDoesNotExist()
    }

    @Test fun tileClickReportsTheTile() {
        var got: HomeTile? = null
        show(AppLanguage.EN, onTile = { got = it })
        compose.onNodeWithTag(HomeScreenTags.tile(HomeTile.ATTENDANCE)).performClick()
        assertEquals(HomeTile.ATTENDANCE, got)
    }

    @Test fun expiredBundleDisablesSellingTilesButNotAttendance() {
        show(AppLanguage.EN, freshness = BundleFreshness.Expired(3))
        compose.onNodeWithTag(HomeScreenTags.tile(HomeTile.SALE)).assertIsNotEnabled()
        compose.onNodeWithTag(HomeScreenTags.tile(HomeTile.ATTENDANCE)).assertIsEnabled()
        compose.onNodeWithTag(HomeScreenTags.BANNER).assertExists()
    }

    @Test fun staleBundleStillSellsWithABanner() {
        show(AppLanguage.EN, freshness = BundleFreshness.Stale(1))
        compose.onNodeWithTag(HomeScreenTags.tile(HomeTile.SALE)).assertIsEnabled(); compose.onNodeWithTag(HomeScreenTags.BANNER).assertExists()
    }

    @Test fun healthLineShowsValuesAndWarns() {
        show(AppLanguage.EN, health = DeviceHealthModel.of(35, 450, 3, 30))
        compose.onNodeWithText("Battery 35%  ·  Free 450 MB  ·  3 waiting to upload  ·  Last sync 30 min ago").assertExists()
        compose.onNodeWithText("Check this phone: low battery, low storage or no recent sync.").assertExists()
    }
}
