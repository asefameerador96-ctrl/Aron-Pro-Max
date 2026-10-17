package com.aktcl.aron.feature.tso

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.HomeTags
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.RoleHero
import com.aktcl.aron.feature.home.RoleHomeTags
import com.aktcl.aron.feature.home.RoleHomes
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
class TsoAppTest {
    @get:Rule val compose = createComposeRule()

    private val user = HomeUser("Testing Dhaka North", "tso1001", "TSO", offline = false, reauthRequired = true, updateRequired = false)

    private fun show(language: AppLanguage, badges: Map<String, Int> = emptyMap(), onLogout: () -> Unit = {}, webOrigin: String? = null): Context {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AronTheme(language) {
                    TsoApp(
                        user, "2026-10-05", BundleStatus.Ready("b-17", "2026-10-05"), "0.1.0 (1)", onLanguageSelect = {}, onLogout = onLogout,
                        hero = RoleHero.Tso(3, 12), badges = badges, webOrigin = webOrigin,
                    )
                }
            }
        }
        return context
    }

    @Test
    fun catalogueKeepsTheDrawerOrderWithIconsAndHidesTargetStatus() {
        val tiles = RoleHomes.tsoTiles(mapOf(RoleHomes.TsoTile.LEAVE to 1))
        assertEquals(listOf("dashboard", "leave", "final_submit", "periphery", "my_call", "feedback", "device_otp"), tiles.map { it.id })
        assertFalse(tiles.any { it.id.contains("target") })
        assertEquals(AronIcons.Dashboard, tiles[0].icon); assertEquals(AronIcons.Leave, tiles[1].icon); assertEquals(AronIcons.Verified, tiles[2].icon)
        assertEquals(AronIcons.Explore, tiles[3].icon); assertEquals(AronIcons.Place, tiles[4].icon); assertEquals(AronIcons.Feedback, tiles[5].icon)
        assertEquals(AronIcons.Lock, tiles[6].icon)
        assertEquals(1, tiles.first { it.id == "leave" }.badge)
        tiles.forEach { assertEquals(it.id, TsoScreen.of(it).tileId) }
    }

    @Test
    fun banglaHomeShowsHeaderDateReauthBannerTilesAndBadge() {
        val context = show(AppLanguage.BN, badges = mapOf(RoleHomes.TsoTile.LEAVE to 1))
        compose.onNodeWithTag(HomeTags.HEADER).assertIsDisplayed()
        compose.onNodeWithText("TSO - Testing Dhaka North (tso1001)").assertIsDisplayed()
        compose.onNodeWithTag(HomeTags.DATE).assertIsDisplayed().assertTextEquals("২০২৬-১০-০৫").assertContentDescriptionEquals("ব্যবসায়িক তারিখ: ২০২৬-১০-০৫")
        compose.onNodeWithTag(HomeTags.MODE).assertDoesNotExist()
        compose.onNodeWithText(context.getString(HomeR.string.home_reauth_required)).assertExists()
        // the bundle version is an identifier and keeps ASCII; the date takes Bengali digits
        compose.onNodeWithText(context.getString(HomeR.string.home_bundle_ready, "২০২৬-১০-০৫", "b-17")).assertExists()
        compose.onNodeWithTag(RoleHomeTags.HERO).assertExists()
        RoleHomes.tsoTiles().filter { it.id != RoleHomes.TsoTile.DEVICE_OTP }.forEach { compose.onNodeWithTag(RoleHomeTags.tile(it.id)).assertExists() }
        // the TSO app has no bind OTP (D-AUTH-TSO-NOBIND): no tile for the page that read it
        compose.onNodeWithTag(RoleHomeTags.tile(RoleHomes.TsoTile.DEVICE_OTP)).assertDoesNotExist()
        compose.onNodeWithTag(RoleHomeTags.tile("target_status")).assertDoesNotExist()
        compose.onNodeWithTag(RoleHomeTags.tile(RoleHomes.TsoTile.LEAVE)).performScrollTo()
        compose.onNode(hasContentDescription(context.getString(CoreR.string.core_ui_tile_badge, "১")), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(HomeTags.LOGOUT).assertDoesNotExist()
    }

    @Test
    fun anotherTileOpensThePlaceholderWithItsLabel() {
        show(AppLanguage.BN)
        compose.onNodeWithTag(RoleHomeTags.tile(RoleHomes.TsoTile.FINAL_SUBMIT)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RoleHomeTags.EMPTY).assertExists()
        compose.onNodeWithText("ফাইনাল সাবমিট শিগগিরই আসছে").assertExists()
    }

    @Test
    fun settingsWarnsThatLogoutWipesTheData() {
        var loggedOut = false
        val context = show(AppLanguage.EN, onLogout = { loggedOut = true })
        compose.onNodeWithTag(RoleHomeTags.SETTINGS).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Version 0.1.0 (1)").assertExists()
        compose.onNodeWithTag(HomeTags.LOGOUT).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(HomeR.string.rh_set_logout_wipe)).assertExists()
        compose.onNode(hasText(context.getString(HomeR.string.set_cancel)) and hasClickAction()).performClick()
        compose.waitForIdle()
        assertFalse(loggedOut)
        compose.onNodeWithTag(HomeTags.LOGOUT).performClick()
        compose.waitForIdle()
        compose.onNode(hasText(context.getString(HomeR.string.set_logout_confirm)) and hasClickAction() and !hasTestTag(HomeTags.LOGOUT)).performClick()
        compose.waitForIdle()
        assertTrue(loggedOut)
    }
}
