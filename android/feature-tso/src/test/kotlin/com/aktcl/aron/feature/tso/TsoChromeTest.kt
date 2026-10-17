package com.aktcl.aron.feature.tso

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import com.aktcl.aron.feature.home.HomeUser
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

    @Test fun sevenEntriesInTheManualOrderAndNoTargetStatus() {
        val e = tsoDrawerEntries()
        assertEquals(listOf(TsoScreen.DASHBOARD, TsoScreen.LEAVE, TsoScreen.FINAL_SUBMIT, TsoScreen.PERIPHERY, TsoScreen.MY_CALL, TsoScreen.FEEDBACK, TsoScreen.SETTINGS), e.map { it.screen })
        assertEquals(7, e.size)
    }

    @Test fun homeShowsHamburgerGreetingAndLogoutIcon() {
        show(AppLanguage.EN)
        compose.onNodeWithTag(TsoChromeTags.MENU).assertIsDisplayed()
        compose.onNodeWithTag(TsoChromeTags.GREETING).assertIsDisplayed()
        compose.onNodeWithText("Good day, Testing Dhaka North").assertIsDisplayed()
        compose.onNodeWithTag(TsoChromeTags.LOGOUT).assertIsDisplayed()
    }

    @Test fun banglaGreeting() { show(AppLanguage.BN); compose.onNodeWithText("শুভ দিন, Testing Dhaka North").assertIsDisplayed() }

    @Test fun drawerListsAllSevenEntriesWithTheirSubtitles() {
        show(AppLanguage.EN)
        compose.onNodeWithTag(TsoChromeTags.MENU).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(TsoChromeTags.DRAWER_HEADER).assertExists()
        val subs = mapOf(
            TsoScreen.DASHBOARD to "View Dashboard", TsoScreen.LEAVE to "Manage your leaves", TsoScreen.FINAL_SUBMIT to "Submit Sales Data",
            TsoScreen.PERIPHERY to "Check your team and outlet", TsoScreen.MY_CALL to "Create and manage plans", TsoScreen.FEEDBACK to "Manage your feedbacks",
        )
        subs.forEach { (screen, text) -> compose.onNodeWithTag(TsoChromeTags.subtitle(screen), useUnmergedTree = true).assertExists(); compose.onNodeWithText(text, useUnmergedTree = true).assertExists() }
        compose.onNodeWithTag(TsoChromeTags.entry(TsoScreen.SETTINGS)).assertExists()
    }

    @Test fun drawerEntryOpensTheScreenAndTheHomeButtonReturns() {
        show(AppLanguage.EN)
        compose.onNodeWithTag(TsoChromeTags.MENU).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(TsoChromeTags.entry(TsoScreen.FINAL_SUBMIT)).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(TsoChromeTags.HOME_BUTTON).assertIsDisplayed()
        compose.onNodeWithTag(TsoChromeTags.HOME_BUTTON).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(TsoChromeTags.GREETING).assertIsDisplayed()
    }

    @Test fun logoutIconAsksBeforeLoggingOut() {
        var out = false
        val ctx = show(AppLanguage.EN) { out = true }
        compose.onNodeWithTag(TsoChromeTags.LOGOUT).performClick(); compose.waitForIdle()
        assertFalse(out)
        compose.onAllNodesWithText(ctx.getString(com.aktcl.aron.feature.home.R.string.set_logout_confirm)).filter(hasClickAction() and !hasTestTag(TsoChromeTags.LOGOUT)).onFirst().performClick(); compose.waitForIdle()
        assertTrue(out)
    }

    @Test fun dateFormatsOfTheManual() {
        assertEquals("2026-04-26", TsoDates.iso("2026-04-26", AppLanguage.EN))
        assertEquals("২০২৬-০৪-২৬", TsoDates.iso("2026-04-26", AppLanguage.BN))
        assertEquals("April 26, 2026", TsoDates.long("2026-04-26", AppLanguage.EN))
        assertEquals("২৬ এপ্রিল ২০২৬", TsoDates.long("2026-04-26", AppLanguage.BN))
        assertEquals("not a date", TsoDates.long("not a date", AppLanguage.EN))
    }
}
