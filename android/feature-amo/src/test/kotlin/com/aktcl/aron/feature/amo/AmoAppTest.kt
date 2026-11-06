package com.aktcl.aron.feature.amo

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.platform.testTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.HomeTags
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.RoleHero
import com.aktcl.aron.feature.home.RoleHomeTags
import com.aktcl.aron.feature.home.RoleHomes
import com.aktcl.aron.feature.home.RoleTileGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.aktcl.aron.core.ui.R as CoreR
import com.aktcl.aron.feature.home.R as HomeR

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AmoAppTest {
    @get:Rule val compose = createComposeRule()

    private val user = HomeUser("Testing Banani", "amo5001", "AMO", offline = true, reauthRequired = false, updateRequired = false)

    private fun show(
        language: AppLanguage, user: HomeUser = this.user, badges: Map<String, Int> = emptyMap(), onLogout: () -> Unit = {},
        onSync: (() -> Unit)? = null, onSunlight: (Boolean) -> Unit = {},
    ): Context {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AronTheme(language) {
                    AmoApp(
                        user, "2026-10-05", BundleStatus.Offline, "0.1.0 (1)", onLanguageSelect = {}, onLogout = onLogout,
                        hero = RoleHero.Amo(4_391_000, 6, 60), badges = badges, reach = AmoReach(3, 0), syncedAt = "10:42", onSync = onSync, onSunlight = onSunlight,
                    )
                }
            }
        }
        return context
    }

    @Test
    fun catalogueIsTheManualsSeventeenTilesInOrderAndHidesAstha() {
        val tiles = AmoMenu.tiles(AmoReach(3, 0), mapOf("outlet" to 3))
        assertEquals(
            listOf(
                "attendance", "joint_call", "control_call", "team_location", "team_performance", "task_delegation", "live_dashboard", "sr_stock",
                "outlet", "stock", "sale", "memo", "summary", "sales_submit", "report", "sales_journey", "settings",
            ),
            tiles.map { it.id },
        )
        assertFalse(tiles.any { it.id.contains("astha") || it.id.contains("campaign") || it.id.contains("target") })
        assertEquals(3, tiles.first { it.id == "outlet" }.badge)
        assertEquals(0, tiles.first { it.id == "sale" }.badge)
        // every tile maps to its own screen
        tiles.forEach { assertEquals(it.id, AmoScreen.of(it).tileId) }
    }

    @Test
    fun syncStateFollowsTheBundleCheckAndOfflineUnlock() {
        assertEquals(SyncChipState.Offline, RoleHomes.syncState(BundleStatus.Unchanged, offline = true))
        assertEquals(SyncChipState.Syncing, RoleHomes.syncState(BundleStatus.Checking, offline = false))
        assertEquals(SyncChipState.Synced, RoleHomes.syncState(BundleStatus.Ready("v1", "2026-10-05"), offline = false))
        assertEquals(SyncChipState.Synced, RoleHomes.syncState(BundleStatus.Unchanged, offline = false))
        assertEquals(SyncChipState.Offline, RoleHomes.syncState(BundleStatus.Offline, offline = false))
        // a refusal arrived over a working connection: the chip is not "offline"; the bundle line carries the refusal
        assertEquals(SyncChipState.Synced, RoleHomes.syncState(BundleStatus.Refused(401, "x"), offline = false))
        assertEquals(SyncChipState.Offline, RoleHomes.syncState(BundleStatus.Refused(401, "x"), offline = true))
    }

    @Test
    fun banglaHomeShowsHeaderDateOfflineBannerTilesAndBadge() {
        val context = show(AppLanguage.BN, badges = mapOf("outlet" to 3))
        compose.onNodeWithTag(HomeTags.HEADER).assertIsDisplayed()
        // screenshots 8, 9: "<name> (<username>)" over "<zone> AMO, <date>" (no zone name in the bundle yet: "AMO, <date>"); TalkBack reads the full sentence
        compose.onNodeWithText("Testing Banani (amo5001)").assertIsDisplayed()
        compose.onNodeWithText("২০২৬-১০-০৫", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(HomeTags.HEADER).assertContentDescriptionEquals("Testing Banani (amo5001), ব্যবসায়িক তারিখ: ২০২৬-১০-০৫")
        compose.onNodeWithTag(HomeTags.MODE).assertIsDisplayed()
        // offline unlock with an offline bundle check: the chip and the banner already say it, the bundle line is not repeated
        compose.onNodeWithText(context.getString(HomeR.string.home_bundle_offline)).assertDoesNotExist()
        compose.onNodeWithTag(RoleHomeTags.HERO).assertExists()
        AmoMenu.tiles(AmoReach(3, 0)).forEach { compose.onNodeWithTag(RoleHomeTags.tile(it.id)).assertExists() }
        compose.onNodeWithTag(RoleHomeTags.tile("astha")).assertDoesNotExist()
        compose.onNodeWithTag(RoleHomeTags.tile(RoleHomes.AmoTile.ATTENDANCE)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(HomeR.string.rh_amo_control_call)).assertExists()
        // the Outlet badge is announced with Bengali digits
        val badge = context.getString(CoreR.string.core_ui_tile_badge, "৩")
        compose.onNodeWithTag(RoleHomeTags.tile(RoleHomes.AmoTile.OUTLET)).performScrollTo()
        compose.onNode(hasContentDescription(badge), useUnmergedTree = true).assertExists()
        // the language toggle and logout are no longer on Home
        compose.onNodeWithTag(HomeTags.LOGOUT).assertDoesNotExist()
    }

    @Test
    fun aTileOpensItsDesignedPlaceholderAndBackReturnsHome() {
        val context = show(AppLanguage.BN)
        compose.onNodeWithTag(RoleHomeTags.tile(RoleHomes.AmoTile.CONTROL_CALL)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RoleHomeTags.EMPTY).assertExists()
        compose.onNodeWithText("কন্ট্রোল কল শিগগিরই আসছে").assertExists()
        compose.onNodeWithText(context.getString(HomeR.string.rh_empty_hint)).assertExists()
        compose.onNodeWithText(context.getString(CoreR.string.core_ui_chip_synced_at, "10:42")).assertExists()
        compose.onNodeWithContentDescription(context.getString(CoreR.string.core_ui_back)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(HomeTags.HEADER).assertIsDisplayed()
        compose.onNodeWithTag(RoleHomeTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun chipOpensTheSyncSheetOnlyWhenTheHostHasOne() {
        var opened = 0
        show(AppLanguage.BN, onSync = { opened++ })
        compose.onNodeWithTag(RoleHomeTags.SYNC).performScrollTo().assertHasClickAction().performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
    }

    @Test
    fun chipIsNotTappableWithoutASyncSheet() {
        show(AppLanguage.BN)
        compose.onNodeWithTag(RoleHomeTags.SYNC).performScrollTo().assertIsDisplayed().assertHasNoClickAction()
    }

    @Test
    fun settingsSunlightRowTogglesAsOneRow() {
        var on: Boolean? = null
        val context = show(AppLanguage.BN, onSunlight = { on = it })
        compose.onNodeWithTag(RoleHomeTags.tile("settings")).performScrollTo().performClick()
        compose.waitForIdle()
        // the row announces itself as a switch with its state (UI-SR-6), not as a plain button
        compose.onNodeWithTag(RoleHomeTags.SET_SUNLIGHT).performScrollTo().assertIsOff().assertHasClickAction().performClick()
        compose.waitForIdle()
        assertEquals(true, on)
    }

    @Test
    fun settingsOpensFromTheGearAndLogoutAsksFirst() {
        var loggedOut = false
        val context = show(AppLanguage.EN, user = user.copy(offline = false), onLogout = { loggedOut = true })
        compose.onNodeWithTag(HomeTags.MODE).assertDoesNotExist()
        compose.onNodeWithText(context.getString(HomeR.string.home_bundle_offline)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(RoleHomeTags.tile("settings")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RoleHomeTags.SET_VERSION).assertExists()
        compose.onNodeWithText("Version 0.1.0 (1)").assertExists()
        // screenshot 75: the language tile opens the drop-down with "en" and "বাংলা"
        compose.onNodeWithTag(AmoHomeTags.LANGUAGE).assertExists().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RoleHomeTags.SET_LANG_BN).assertExists()
        compose.onNodeWithTag(RoleHomeTags.SET_LANG_EN).assertExists()
        compose.onNodeWithTag(RoleHomeTags.SET_LANG_EN).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RoleHomeTags.SET_SUNLIGHT).assertExists()
        compose.onNodeWithTag(HomeTags.LOGOUT).performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(loggedOut)
        compose.onNodeWithText(context.getString(HomeR.string.set_logout_message)).assertExists()
        // the dialog's confirm shares its label with the Settings button behind it: pick the clickable that is not the tagged button
        compose.onNode(hasText(context.getString(HomeR.string.set_logout_confirm)) and hasClickAction() and !hasTestTag(HomeTags.LOGOUT)).performClick()
        compose.waitForIdle()
        assertTrue(loggedOut)
    }

    @Test
    fun aTileOpensTheScreenTheAppSuppliesAndBackReturnsHome() {
        val language = AppLanguage.EN
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AronTheme(language) {
                    AmoApp(
                        user, "2026-10-05", BundleStatus.Offline, "0.1.0 (1)", onLanguageSelect = {}, onLogout = {},
                        screens = mapOf(AmoScreen.ATTENDANCE to { back -> androidx.compose.material3.TextButton(onClick = back, modifier = androidx.compose.ui.Modifier.testTag("amo-att")) { androidx.compose.material3.Text("att") } }),
                    )
                }
            }
        }
        compose.onNodeWithText(context.getString(HomeR.string.rh_amo_attendance)).performClick()
        compose.onNodeWithTag("amo-att").assertIsDisplayed().performClick()
        compose.onNodeWithTag(HomeTags.HEADER).assertIsDisplayed()
    }
}
