package com.aktcl.aron.feature.attendance

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-AGATE-22 (item 5): the local counter of failed closes, and the plain lock-out screen (English and Bangla, one button, a short stable code, no payload). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class DayCloseFailedTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()
    private val failures = CloseFailures(db.referenceDao())
    private var clock = 1_000_000L
    /** One failed attempt a minute after the last (so each counts); [failures.failed] with the test clock. */
    private suspend fun fail(kind: String, date: String, t: Throwable): Int { clock += 60_000L; return failures.failed(kind, date, t, clock) }
    private val stillOpen: suspend (String, String) -> Boolean = { _, _ -> true }

    @Composable private fun Lang(language: AppLanguage, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(context, language)
        CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { content() } }
    }

    @Test fun theScreenIsShownOnlyAfterThreeFailuresOfTheSameDate() = runBlocking {
        val boom = IllegalStateException("secret payload 017xxxxxxxx")
        assertEquals(1, fail(CloseFailures.KIND_DAY, "2026-11-17", boom)); assertNull(failures.lockedOut(stillOpen))
        assertEquals(2, fail(CloseFailures.KIND_DAY, "2026-11-17", boom)); assertNull(failures.lockedOut(stillOpen))
        assertEquals(3, fail(CloseFailures.KIND_DAY, "2026-11-17", boom))
        val out = failures.lockedOut(stillOpen)
        assertNotNull(out); assertEquals("2026-11-17", out!!.businessDate); assertEquals(CloseFailures.KIND_DAY, out.kind)
    }

    /** D-AGATE-25: failures within seconds (the start effect and the first ON_RESUME at launch) are ONE attempt. */
    @Test fun threeFailuresWithinSecondsCountAsOneAttempt() = runBlocking {
        val boom = RuntimeException()
        assertEquals(1, failures.failed(CloseFailures.KIND_DAY, "2026-11-17", boom, 5_000L))
        assertEquals(1, failures.failed(CloseFailures.KIND_DAY, "2026-11-17", boom, 6_000L))
        assertEquals(1, failures.failed(CloseFailures.KIND_DAY, "2026-11-17", boom, 9_999L))
        assertNull(failures.lockedOut(stillOpen))
        assertEquals("a later attempt counts", 2, failures.failed(CloseFailures.KIND_DAY, "2026-11-17", boom, 5_000L + CloseFailures.SAME_ATTEMPT_MS))
    }

    @Test fun theCodeIsTheStepAndTheExceptionClassAndNeverTheMessage() = runBlocking {
        repeat(3) { fail(CloseFailures.KIND_CALL, "2026-11-17", IllegalStateException("secret payload 017xxxxxxxx")) }
        val code = failures.lockedOut(stillOpen)!!.code
        assertEquals("E-CLOSE-CALL/IllegalStateException", code)
        assertFalse("no message, no payload", code.contains("secret") || code.contains("017"))
    }

    @Test fun aSuccessResetsTheCounterAndADayClosedByAnotherPathNeverShows() = runBlocking {
        repeat(3) { fail(CloseFailures.KIND_DAY, "2026-11-17", RuntimeException()) }
        assertNotNull(failures.lockedOut(stillOpen))
        failures.succeeded(CloseFailures.KIND_DAY, "2026-11-17")
        assertNull("reset on success", failures.lockedOut(stillOpen))
        assertEquals("counting starts again", 1, fail(CloseFailures.KIND_DAY, "2026-11-17", RuntimeException()))
        repeat(2) { fail(CloseFailures.KIND_DAY, "2026-11-17", RuntimeException()) }
        assertNull("the day is no longer open (closed by the rep or another path): ignored and deleted", failures.lockedOut { _, _ -> false })
        assertNull(db.referenceDao().metaWithPrefix("day_close_fail:").firstOrNull())
    }

    @Test fun theOldestDateIsShownFirstAndEachDateCountsOnItsOwn() = runBlocking {
        repeat(3) { fail(CloseFailures.KIND_DAY, "2026-11-16", RuntimeException()) }
        repeat(3) { fail(CloseFailures.KIND_DAY, "2026-11-15", RuntimeException()) }
        fail(CloseFailures.KIND_DAY, "2026-11-14", RuntimeException())
        assertEquals("2026-11-15", failures.lockedOut(stillOpen)!!.businessDate)
    }

    @Test fun theScreenSaysItPlainlyInEnglishWithTheCodeAndTryAgain() {
        var tried = 0
        compose.setContent { Lang(AppLanguage.EN) { DayCloseFailedScreen("E-CLOSE-DAY/SQLiteException", busy = false, onTryAgain = { tried++ }) } }
        compose.onNodeWithText("The day could not be closed on this phone").assertExists()
        compose.onNodeWithText("Code: E-CLOSE-DAY/SQLiteException").assertExists()
        compose.onNodeWithTag(DayCloseFailedTags.RETRY).performClick()
        assertEquals(1, tried)
    }

    @Test fun theSameScreenInBangla() {
        compose.setContent { Lang(AppLanguage.BN) { DayCloseFailedScreen("E-CLOSE-DAY/SQLiteException", busy = false, onTryAgain = {}) } }
        compose.onNodeWithText("এই ফোনে দিনটি বন্ধ করা যায়নি").assertExists()
        compose.onNodeWithText("কোড: E-CLOSE-DAY/SQLiteException").assertExists()
        compose.onNodeWithText("আবার চেষ্টা করুন").assertExists()
    }

    /** D-AGATE-25: the lock-out screen takes every touch: a tap on an empty part of it never reaches what is underneath. */
    @Test fun aTapOnTheScreenNeverReachesWhatIsUnderneath() {
        var under = 0
        compose.setContent { Lang(AppLanguage.EN) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().clickable { under++ })
                DayCloseFailedScreen("E-CLOSE-DAY/X", busy = false, onTryAgain = {})
            }
        } }
        compose.onNodeWithTag(DayCloseFailedTags.ROOT).performTouchInput { click(androidx.compose.ui.geometry.Offset(4f, 4f)) }
        assertEquals("the tap stopped at the lock-out screen", 0, under)
    }

    @Test fun whileTryingAgainTheButtonIsOff() {
        compose.setContent { Lang(AppLanguage.EN) { DayCloseFailedScreen("E-CLOSE-DAY/X", busy = true, onTryAgain = {}) } }
        compose.onNodeWithTag(DayCloseFailedTags.RETRY).assertIsNotEnabled()
    }
}
