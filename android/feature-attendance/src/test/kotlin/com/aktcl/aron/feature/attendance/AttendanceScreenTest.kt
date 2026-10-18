package com.aktcl.aron.feature.attendance

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SR-011 / F-SR-012 screen (screens-sr s3): prompt, the one bar primary, the hold sheet, Bangla and English, the dhaka clock. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AttendanceScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun render(language: AppLanguage, state: AttendanceState, onIn: () -> Unit = {}, onOut: () -> Unit = {}) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language) { AttendanceContent(state, "17:00", onIn, onOut, nowIso = { "2026-10-07T03:05:00.000Z" }) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun beforeCheckInThePromptShowsInBanglaAndTheBarIsCheckIn() {
        render(AppLanguage.BN, AttendanceState())
        compose.onNodeWithText("আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন।").assertExists()
        compose.onNodeWithTag(AttendanceTags.CHECK_IN).assertIsEnabled()
        // one primary per screen: check-out only appears in the bar once the rep has checked in
        compose.onNodeWithTag(AttendanceTags.CHECK_OUT).assertDoesNotExist()
        compose.onNodeWithTag(AttendanceTags.HOLD).assertDoesNotExist()
    }

    @Test fun checkInOpensTheHoldSheetAndTheHoldCallsBackOnceEnglish() {
        var n = 0
        render(AppLanguage.EN, AttendanceState(), onIn = { n++ })
        compose.onNodeWithTag(AttendanceTags.CHECK_IN).performClick()
        compose.waitForIdle()
        // the tap never commits (parity SR-S-13): the hold sheet opens with its time chip from the corrected Dhaka clock
        assertEquals(0, n)
        compose.onNodeWithText("Checking in").assertExists()
        compose.onNodeWithText("09:05 AM").assertExists()
        compose.onNodeWithTag(AttendanceTags.HOLD).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertEquals(1, n)
        compose.onNodeWithTag(AttendanceTags.HOLD).assertDoesNotExist()
        compose.onNodeWithText("You have not checked in yet. Please check in before you start work.").assertExists()
    }

    @Test fun cancelClosesTheHoldSheetWithoutCommitting() {
        var n = 0
        render(AppLanguage.EN, AttendanceState(), onIn = { n++ })
        compose.onNodeWithTag(AttendanceTags.CHECK_IN).performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(AttendanceTags.HOLD).assertDoesNotExist()
        assertEquals(0, n)
    }

    @Test fun afterCheckInTheBarIsCheckOutDisabledWithItsReasonAndTheDhakaTimeShows() {
        render(AppLanguage.EN, AttendanceState(checkedInAt = "2026-10-07T03:05:00.000Z", showCheckInPrompt = false, checkInEnabled = false, addressText = "23.793812, 90.404112", fixAccuracyM = 18))
        compose.onNodeWithTag(AttendanceTags.CHECK_IN).assertDoesNotExist()
        compose.onNodeWithTag(AttendanceTags.CHECK_OUT).assertIsNotEnabled()
        compose.onAllNodesWithText("Check-out opens at 05:00 PM.").assertCountEquals(2) // the day-card row and the bar reason
        compose.onNodeWithText("Checked in at 09:05 AM").assertExists()
        compose.onNodeWithText("23.793812, 90.404112").assertExists()
        compose.onNodeWithText("Good · ±18 m").assertExists()
    }

    @Test fun checkOutHoldCallsBackOnceAndTheDayCompleteBannerHidesTheBar() {
        var n = 0
        render(AppLanguage.EN, AttendanceState(checkedInAt = "2026-10-07T03:05:00.000Z", showCheckInPrompt = false, checkInEnabled = false, checkOutEnabled = true), onOut = { n++ })
        compose.onNodeWithText("You have not checked out yet. Please check out when today's work is done.").assertExists()
        compose.onNodeWithTag(AttendanceTags.CHECK_OUT).assertIsEnabled().performClick()
        compose.onNodeWithText("Checking out").assertExists()
        compose.onNodeWithTag(AttendanceTags.HOLD).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertEquals(1, n)
    }

    @Test fun dayCompleteShowsTheSuccessBannerAndNoBar() {
        render(AppLanguage.BN, AttendanceState(checkedInAt = "2026-10-07T03:05:00.000Z", checkedOutAt = "2026-10-07T11:10:00.000Z", showCheckInPrompt = false, checkInEnabled = false))
        compose.onNodeWithText("আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ।").assertExists()
        compose.onNodeWithTag(AttendanceTags.CHECK_IN).assertDoesNotExist()
        compose.onNodeWithTag(AttendanceTags.CHECK_OUT).assertDoesNotExist()
        compose.onNodeWithText("চেক আউট সম্পন্ন হয়েছে ০৫:১০ PM").assertExists()
    }

    @Test fun mockLocationShowsTheWarningAndWeakFixTheWarningChip() {
        render(AppLanguage.EN, AttendanceState(lastFixMock = true, fixAccuracyM = 140, coordinatesText = "23.792580, 90.407830"))
        compose.onNodeWithText("Mock location found. Turn it off.").assertExists()
        compose.onNodeWithText("Weak · ±140 m").assertExists()
    }

    @Test fun dhakaClockAddsSixHoursAndWrapsPastMidnight() {
        assertEquals("09:05", dhakaClock("2026-10-07T03:05:00.000Z"))
        assertEquals("01:30", dhakaClock("2026-10-07T19:30:00.000Z"))
        assertFalse(dhakaClock("bad").isEmpty())
        assertEquals("04:47 PM", clock12("16:47")); assertEquals("12:05 AM", clock12("00:05")); assertEquals("12:00 PM", clock12("12:00")); assertEquals("bad", clock12("bad"))
    }

    @Test fun fixQualityThresholds() {
        assertEquals(FixQuality.Good, FixQuality.of(30))
        assertEquals(FixQuality.Fair, FixQuality.of(31))
        assertEquals(FixQuality.Fair, FixQuality.of(100))
        assertEquals(FixQuality.Weak, FixQuality.of(101))
    }
}
