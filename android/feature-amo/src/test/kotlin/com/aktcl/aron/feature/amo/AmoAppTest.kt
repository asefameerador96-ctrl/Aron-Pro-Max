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
                        hero = RoleHero.Amo(4_391_000, 6, 60), badges = badges, syncedAt = "10:42", onSync = onSync, onSunlight = onSunlight,
                    )
                }
            }
        }
        return context
    }

    @Test
    fun catalogueKeepsTheReferenceOrderWithIconsAndHidesAstha() {
        val tiles = RoleHomes.amoTiles(mapOf(RoleHomes.AmoTile.OUTLET to 3))
        assertEquals(
            listOf("attendance", "control_call", "joint_call", "sale", "team", "outlet", "sales_submit", "report"),
            tiles.map { it.id },
        )
        assertFalse(tiles.any { it.id.contains("astha") || it.id.contains("target") })
        assertEquals(AronIcons.Attendance, tiles[0].icon); assertEquals(AronIcons.Place, tiles[1].icon); assertEquals(AronIcons.Team, tiles[2].icon)
        assertEquals(AronIcons.Sale, tiles[3].icon); assertEquals(AronIcons.Team, tiles[4].icon); assertEquals(AronIcons.Outlet, tiles[5].icon)
        assertEquals(AronIcons.Submit, tiles[6].icon); assertEquals(AronIcons.Trend, tiles[7].icon)
        assertEquals(3, tiles.first { it.id == "outlet" }.badge)
        assertEquals(0, tiles.first { it.id == "sale" }.badge)
        // the six daily tiles, then the day-close group (Sales Submit, Report)
        assertEquals(listOf(RoleTileGroup.Today, RoleTileGroup.DayClose), tiles.map { it.group }.distinct())
        assertEquals(6, tiles.count { it.group == RoleTileGroup.Today })
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
        val context = show(AppLanguage.BN, badges = mapOf(RoleHomes.AmoTile.OUTLET to 3))
        compose.onNodeWithTag(HomeTags.HEADER).assertIsDisplayed()
        compose.onNodeWithText("AMO - Testing Banani (amo5001)").assertIsDisplayed()
        // the bare ISO date on its own caption line (screens-sr s2); TalkBack reads the full sentence
        compose.onNodeWithTag(HomeTags.DATE).assertIsDisplayed().assertTextEquals("২০২৬-১০-০৫").assertContentDescriptionEquals("ব্যবসায়িক তারিখ: ২০২৬-১০-০৫")
        compose.onNodeWithTag(HomeTags.MODE).assertIsDisplayed()
        // offline unlock with an offline bundle check: the chip and the banner already say it, the bundle line is not repeated
        compose.onNodeWithTag(HomeTags.BUNDLE).assertDoesNotExist()
        compose.onNodeWithTag(RoleHomeTags.HERO).assertExists()
        RoleHomes.amoTiles().forEach { compose.onNodeWithTag(RoleHomeTags.tile(it.id)).assertExists() }
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
        compose.onNodeWithTag(RoleHomeTags.SYNC).assertHasClickAction().performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
    }

    @Test
    fun chipIsNotTappableWithoutASyncSheet() {
        show(AppLanguage.BN)
        compose.onNodeWithTag(RoleHomeTags.SYNC).assertIsDisplayed().assertHasNoClickAction()
    }

    @Test
    fun settingsSunlightRowTogglesAsOneRow() {
        var on: Boolean? = null
        show(AppLanguage.BN, onSunlight = { on = it })
        compose.onNodeWithTag(RoleHomeTags.SETTINGS).performClick()
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
        compose.onNodeWithTag(HomeTags.BUNDLE).assertIsDisplayed()
        compose.onNodeWithText(context.getString(HomeR.string.home_bundle_offline)).assertIsDisplayed()
        compose.onNodeWithTag(RoleHomeTags.SETTINGS).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RoleHomeTags.SET_VERSION).assertExists()
        compose.onNodeWithText("Version 0.1.0 (1)").assertExists()
        compose.onNodeWithTag(RoleHomeTags.SET_LANG_BN).assertExists()
        compose.onNodeWithTag(RoleHomeTags.SET_LANG_EN).assertExists()
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
