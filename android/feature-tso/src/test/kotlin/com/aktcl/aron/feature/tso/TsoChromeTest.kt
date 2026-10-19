package com.aktcl.aron.feature.tso

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.HomeTags
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.RoleHomes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TsoChromeTest {
    @get:Rule val compose = createComposeRule()
    private val user = HomeUser("Testing Dhaka North", "tso1001", "TSO", offline = false, reauthRequired = false, updateRequired = false)

    private fun show(language: AppLanguage, onLogout: () -> Unit = {}): Context {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AronTheme(language) { TsoApp(user, "2026-10-05", BundleStatus.Ready("b-1", "2026-10-05"), "0.1.0 (1)", {}, onLogout) }
            }
        }
        compose.waitForIdle(); return context
    }

    @Test fun homeTilesKeepTheManualOrderAndNoTargetStatus() {
        // the drawer of the manual (S-06) is now the tile grid: the same six destinations in the same order, Settings behind the gear
        val tiles = RoleHomes.tsoTiles().filter { it.id != RoleHomes.TsoTile.DEVICE_OTP }
        assertEquals(listOf(TsoScreen.DASHBOARD, TsoScreen.LEAVE, TsoScreen.FINAL_SUBMIT, TsoScreen.PERIPHERY, TsoScreen.MY_CALL, TsoScreen.FEEDBACK), tiles.map { TsoScreen.of(it) })
        assertFalse(tiles.any { it.id.contains("target") })
    }

    @Test fun homeShowsTheGradientHeaderLineAndTheGear() {
        val ctx = show(AppLanguage.EN)
        compose.onNodeWithTag(HomeTags.HEADER).assertIsDisplayed()
        compose.onNodeWithText("TSO - Testing Dhaka North (tso1001)").assertIsDisplayed()
        compose.onNodeWithContentDescription(ctx.getString(com.aktcl.aron.feature.home.R.string.set_open)).assertIsDisplayed()
    }

    @Test fun banglaHeaderLine() { show(AppLanguage.BN); compose.onNodeWithText("TSO - Testing Dhaka North (tso1001)").assertIsDisplayed() }

    @Test fun logoutInSettingsAsksBeforeLoggingOut() {
        var out = false
        val ctx = show(AppLanguage.EN) { out = true }
        compose.onNodeWithContentDescription(ctx.getString(com.aktcl.aron.feature.home.R.string.set_open)).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(HomeTags.LOGOUT).performScrollTo().performClick(); compose.waitForIdle()
        assertFalse(out)
        compose.onAllNodesWithText(ctx.getString(com.aktcl.aron.feature.home.R.string.set_logout_confirm)).filter(hasClickAction() and !hasTestTag(HomeTags.LOGOUT)).onFirst().performClick(); compose.waitForIdle()
        assertTrue(out)
    }

    @Test fun dateFormatsOfTheManual() {
        assertEquals("2026-04-26", TsoDates.iso("2026-04-26", AppLanguage.EN))
        assertEquals("২০২৬-০৪-২৬", TsoDates.iso("2026-04-26", AppLanguage.BN))
        assertEquals("April 26, 2026", TsoDates.long("2026-04-26", AppLanguage.EN, EN_MONTHS))
        assertEquals("২৬ এপ্রিল ২০২৬", TsoDates.long("2026-04-26", AppLanguage.BN, BN_MONTHS))
        assertEquals("not a date", TsoDates.long("not a date", AppLanguage.EN, EN_MONTHS))
    }
}

private val EN_MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
private val BN_MONTHS = listOf("জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন", "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর")
