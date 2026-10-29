package com.aktcl.aron.sr

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.repo.LockAcks
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * tso-manual A3 (D-TSO-MAN-31, s8 row 26 "SR phone shows lock"): after the zone's Final Submit the SR day shows a banner:
 * the route-day is closed for new sales, edits and collections, printing still works, and how many records met the lock.
 * The capture refusal itself is RouteDayLockTest (core-database).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SrDayLockBannerTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun show(language: AppLanguage, lock: DayLock?, asText: Boolean = false) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            val ctx = AppLocale.wrap(activity, language)
            activity.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { if (asText) androidx.compose.material3.Text(srDayLockText(lock) ?: "no banner") else SrDayLockBannerContent(lock) } } }
        }
        compose.waitForIdle()
    }

    @Test fun aClosedRouteDaySaysSoAndThatPrintingStillWorks() {
        show(AppLanguage.EN, DayLock(locked = true, acks = LockAcks(held = 2, refused = 1)))
        compose.onNodeWithTag(SrDayLockTags.BANNER).assertExists()
        compose.onNodeWithText("Day closed by Final Submit", substring = true).assertExists()
        compose.onNodeWithText("Printing still works.", substring = true).assertExists()
        compose.onNodeWithText("2 records from before the Final Submit are held for review", substring = true).assertExists()
        compose.onNodeWithText("1 records made after the Final Submit were not accepted.", substring = true).assertExists()
    }

    @Test fun banglaUsesBanglaWordsAndDigits() {
        show(AppLanguage.BN, DayLock(locked = true, acks = LockAcks(held = 3, refused = 0)))
        compose.onNodeWithText("ফাইনাল সাবমিটে দিন বন্ধ", substring = true).assertExists()
        compose.onNodeWithText("প্রিন্ট করা যাবে।", substring = true).assertExists()
        compose.onNodeWithText("৩টি রেকর্ড", substring = true).assertExists()
    }

    @Test fun anOpenDayShowsNothing() {
        show(AppLanguage.EN, null)
        compose.onNodeWithTag(SrDayLockTags.BANNER).assertDoesNotExist()
    }

    /** Fix round 1 (ask 1): the same words as one text, for the SR Home's banner slot; nothing while the day is open. */
    @Test fun theOneTextBannerCarriesTheTitleAndTheLines() {
        show(AppLanguage.EN, DayLock(locked = true, acks = LockAcks(held = 0, refused = 2)), asText = true)
        compose.onNodeWithText("Day closed by Final Submit\nYour TSO has done the Final Submit", substring = true).assertExists()
        compose.onNodeWithText("New calls cannot start", substring = true).assertExists()
        compose.onNodeWithText("2 records made after the Final Submit were not accepted.", substring = true).assertExists()
    }

    @Test fun theOneTextBannerIsNullWhileTheDayIsOpen() {
        show(AppLanguage.EN, null, asText = true)
        compose.onNodeWithText("no banner").assertExists()
    }
}
