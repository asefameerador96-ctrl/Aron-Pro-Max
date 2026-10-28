package com.aktcl.aron.feature.tso

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The TSO top bar (screenshot 6): menu, "Good day, <full_name>", the bell with its unread count; and the manual's date formats. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TsoChromeTest {
    @get:Rule val compose = createComposeRule()
    private var menus = 0
    private var bells = 0

    private fun show(language: AppLanguage, unread: Int) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                TsoLoginTheme(language, loggedOut = false) { TsoRootTopBar("Testing Dhaka North", unread, onMenu = { menus++ }, onBell = { bells++ }) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun theBarGreetsAndCountsUnread() {
        show(AppLanguage.EN, unread = 3)
        compose.onNodeWithText("Good day, Testing Dhaka North").assertIsDisplayed()
        compose.onNodeWithContentDescription("Notifications, 3 unread").assertExists()
        compose.onNodeWithText("3", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(TsoShellTags.MENU).assertExists()
    }

    @Test fun banglaDigitsAndAHundredIsNinetyNinePlus() {
        show(AppLanguage.BN, unread = 120)
        compose.onNodeWithText("শুভ দিন, Testing Dhaka North").assertIsDisplayed()
        compose.onNodeWithText("99+", useUnmergedTree = true).assertExists()
    }

    @Test fun noUnreadNoBadge() {
        show(AppLanguage.EN, unread = 0)
        compose.onNodeWithTag(TsoShellTags.BADGE, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Notifications").assertExists()
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
