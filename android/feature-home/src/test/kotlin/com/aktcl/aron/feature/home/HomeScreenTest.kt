package com.aktcl.aron.feature.home

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.SyncChipState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SR-008, 009, 063, 064 on the screen, on the 360 x 800 design frame of screens-sr (under 700 dp the compact variant hides the device line). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private val header = HomeModel.header("SR", "Testing Banani", "sr334001", "Apsis RouteDaily", "daily", "2026-10-05")

    private fun show(
        language: AppLanguage, tasks: Int = 0, freshness: BundleFreshness = BundleFreshness.Fresh, health: DeviceHealth? = null, onTile: (HomeTile) -> Unit = {},
        hero: HomeHero? = null, kpis: HomeKpis? = null, money: HomeMoneyLines? = null, dock: HomeDock? = null, sync: SyncChipState? = null, offline: Boolean = false,
    ) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language) {
                    HomeContent(header, HomeTiles.resolve(emptySet(), tasks), freshness, offline, health, onTile, hero = hero, kpis = kpis, money = money, dock = dock, sync = sync, onSettings = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private val hero = HomeHero("4,391.00 ৳", 6, 60, "08:52", HomeDayState.IN_FIELD)
    private val kpis = HomeKpis("10%", 54, 0, listOf(HomeStockLine("Lighter", 400, 25, "pieces"), HomeStockLine("Match", 0, 0, "dozen")))
    private val money = HomeMoneyLines(listOf(HomeMoneyLine("Lighter", "4,687.50 ৳")), "4,828.50 ৳", null, "−437.50 ৳", "4,391.00 ৳")

    @Test fun dockLabelFollowsTheDayStateTable() {
        var got: HomeTile? = null
        val dock = androidx.compose.runtime.mutableStateOf<HomeDock?>(HomeDock.CHECK_IN)
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(AppLanguage.EN) { HomeContent(header, HomeTiles.resolve(emptySet(), 0), BundleFreshness.Fresh, false, null, { got = it }, dock = dock.value) }
            }
        }
        val expected = mapOf(
            HomeDock.CHECK_IN to "Check in", HomeDock.TAKE_STOCK to "Take stock", HomeDock.START_SELLING to "Start selling",
            HomeDock.SUBMIT to "Sales submit", HomeDock.CHECK_OUT to "Check out",
        )
        expected.forEach { (d, label) ->
            compose.runOnIdle { dock.value = d }
            compose.onNodeWithTag(HomeScreenTags.DOCK).assertExists()
            compose.onNodeWithText(label).performClick()
            assertEquals(d.target, got)
        }
        compose.runOnIdle { dock.value = null }
        compose.onNodeWithTag(HomeScreenTags.DOCK).assertDoesNotExist()
    }

    @Test fun heroKpiAndMoneyCardsShowTheirFigures() {
        show(AppLanguage.EN, hero = hero, kpis = kpis, money = money, dock = HomeDock.START_SELLING, sync = SyncChipState.Waiting(3))
        compose.onNodeWithTag(HomeScreenTags.HERO).assertExists()
        compose.onNodeWithText("Today's total").assertExists()
        compose.onNodeWithText("Checked in 08:52").assertExists()
        compose.onNodeWithTag(HomeScreenTags.CHIP).assertExists()
        compose.onNodeWithTag(HomeScreenTags.KPI).assertExists()
        compose.onNodeWithText("Strike rate").assertExists(); compose.onNodeWithText("Non-visit").assertExists()
        compose.onNodeWithTag(HomeScreenTags.STOCK).assertExists()
        compose.onAllNodesWithText("Lighter").assertCountEquals(2) // the stock card and the money card
        compose.onNodeWithText("Match").assertDoesNotExist() // a zero category is hidden
        compose.onNodeWithTag(HomeScreenTags.MONEY).assertExists()
        compose.onNodeWithText("Discount").assertDoesNotExist(); compose.onNodeWithText("QC").assertExists()
    }

    @Test fun stockQuantitiesNeverClip() {
        // a cigarette line in sticks is the widest quantity of the card: it shrinks to fit its column, it never loses digits
        show(AppLanguage.EN, kpis = HomeKpis("10%", 54, 0, listOf(HomeStockLine("Cigarette", 12_000, 9_800, "sticks"))))
        listOf("12,000 sticks", "9,800 sticks").forEach { qty ->
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(qty).assertExists().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertFalse("$qty overflows its column", results.single().didOverflowWidth)
            assertEquals(1, results.single().lineCount)
        }
    }

    @Test fun expiredBundleDisablesADockThatWouldSell() {
        show(AppLanguage.EN, freshness = BundleFreshness.Expired(3), dock = HomeDock.START_SELLING)
        compose.onNodeWithTag(HomeScreenTags.DOCK).assertExists()
        compose.onNodeWithText("Start selling").assertIsNotEnabled()
        compose.onNodeWithText("This needs a fresh route bundle.").assertExists()
    }

    @Test fun missingBundleKeepsTheCheckInDockEnabled() {
        show(AppLanguage.EN, freshness = BundleFreshness.Missing, dock = HomeDock.CHECK_IN)
        compose.onNodeWithText("Check in").assertIsEnabled()
    }

    @Test fun completeDayShowsTheSuccessChipAndNoDock() {
        show(AppLanguage.BN, hero = hero.copy(dayState = HomeDayState.COMPLETE), dock = null)
        compose.onNodeWithTag(HomeScreenTags.DAY_DONE).assertExists()
        compose.onNodeWithTag(HomeScreenTags.DOCK).assertDoesNotExist()
    }

    @Test fun tilesRenderInTwoGroupsWithTheTaskBadge() {
        show(AppLanguage.EN, tasks = 2, dock = HomeDock.START_SELLING)
        compose.onNodeWithText("Today's work").assertDoesNotExist() // the reference has one grid, no group labels
        compose.onNodeWithTag(HomeScreenTags.tile(HomeTile.TASKS)).assertExists()
        compose.onNodeWithContentDescription("2 open items", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(HomeScreenTags.SETTINGS).assertExists()
        HomeTile.entries.filter { it.alwaysShown }.forEach { compose.onNodeWithTag(HomeScreenTags.tile(it)).assertExists() }
    }

    @Test fun offlineShowsTheOfflineBanner() {
        show(AppLanguage.EN, offline = true)
        compose.onNodeWithText("You are offline. Your work is saved on this phone.").assertExists()
    }

    @Test fun headerAndTilesInBangla() {
        show(AppLanguage.BN)
        compose.onNodeWithText("SR - Testing Banani (sr334001)").assertExists()
        // the two caption lines of screens-sr s2: the route name over "<ISO date> · <route kind>"
        compose.onNodeWithText("Apsis RouteDaily\n২০২৬-১০-০৫ · প্রতিদিন").assertExists()   // the date is a number: Bengali digits in Bangla (UI-SR-08)
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
        // caption text with the warning word, no card; the icon speaks the full sentence
        compose.onNodeWithText("Warning  ·  Battery 35%  ·  Free 450 MB  ·  3 waiting to upload  ·  Last sync 30 min ago").assertExists()
        compose.onNodeWithContentDescription("Check this phone: low battery, low storage or no recent sync.").assertExists()
        compose.onNodeWithText("Low storage on this phone. Free some space so your work can be saved.").assertDoesNotExist()
    }

    @Test fun storageUnder200MbIsTheOneDangerCase() {
        show(AppLanguage.EN, health = DeviceHealthModel.of(80, 150, 0, 5))
        compose.onNodeWithText("Low storage on this phone. Free some space so your work can be saved.").performScrollTo().assertExists()
    }
}

/** F-SR-005, F-SR-007: Settings language and Logout with confirmation. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchRowsAnnounceTheirState() {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        val on = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) { AronTheme(AppLanguage.EN) { SettingsContent("1", {}, {}, sunlight = on.value, onSunlight = { on.value = it }) } }
        }
        compose.onNodeWithTag(SettingsTags.SUNLIGHT).assertIsOn().performClick()
        compose.onNodeWithTag(SettingsTags.SUNLIGHT).assertIsOff()
    }

    @Test fun logoutAsksForConfirmationThenLogsOut() {
        var out = 0
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(AppLanguage.EN) { SettingsContent("0.1.0 (12)", {}, { out++ }) } } }
        compose.onNodeWithTag(SettingsTags.LOGOUT).performClick()
        assertEquals(0, out)
        compose.onNodeWithText("Your saved work stays on this phone and will still upload.").assertExists()
        compose.onAllNodesWithText("Log out").onLast().performClick()
        assertEquals(1, out)
    }

    @Test fun banglaShowsTheVersionWithBengaliDigits() {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.BN)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(AppLanguage.BN) { SettingsContent("0.1.0 (12)", {}, {}) } } }
        compose.onNodeWithText("সংস্করণ ০.১.০ (১২)").assertExists()
    }

    @Test fun optionalRowsAppearOnlyWhenTheirParameterIsSet() {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        var update = 0; var support = 0; var wifi: Boolean? = null
        val withRows = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(AppLanguage.EN) {
                    if (withRows.value) SettingsContent("1", {}, {}, onUpdate = { update++ }, updateAvailable = true, photosWifiOnly = true, onPhotosWifiOnly = { wifi = it }, onSupport = { support++ })
                    else SettingsContent("1", {}, {})
                }
            }
        }
        compose.onNodeWithTag(SettingsTags.UPDATE).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.PHOTOS_WIFI).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.SUPPORT).assertDoesNotExist()
        compose.runOnIdle { withRows.value = true }
        compose.onNodeWithText("App update (new version ready)").performScrollTo().performClick()
        compose.onNodeWithTag(SettingsTags.SUPPORT).performScrollTo().performClick()
        compose.onNodeWithTag(SettingsTags.PHOTOS_WIFI).assertExists()
        assertEquals(1, update); assertEquals(1, support)
    }
}
