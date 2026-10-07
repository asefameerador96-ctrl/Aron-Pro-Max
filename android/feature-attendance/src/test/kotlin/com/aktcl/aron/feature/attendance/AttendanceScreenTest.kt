package com.aktcl.aron.feature.attendance

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** F-SR-011 / F-SR-012 screen: prompt, disabled buttons, Bangla and English, the dhaka clock. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AttendanceScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun render(language: AppLanguage, state: AttendanceState, onIn: () -> Unit = {}, onOut: () -> Unit = {}) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language) { AttendanceContent(state, "17:00", onIn, onOut) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun beforeCheckInThePromptShowsInBanglaAndCheckOutIsDisabled() {
        render(AppLanguage.BN, AttendanceState())
        compose.onNodeWithText("আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন।").assertExists()
        compose.onNodeWithTag(AttendanceTags.CHECK_IN).assertIsEnabled()
        compose.onNodeWithTag(AttendanceTags.CHECK_OUT).assertIsNotEnabled()
    }

    @Test fun checkInPressCallsBackOnceEnglish() {
        var n = 0
        render(AppLanguage.EN, AttendanceState(), onIn = { n++ })
        compose.onNodeWithTag(AttendanceTags.CHECK_IN).performClick()
        assertEquals(1, n)
        compose.onNodeWithText("You have not checked in yet. Please check in before you start work.").assertExists()
    }

    @Test fun afterCheckInTheButtonIsDisabledAndTheDhakaTimeShows() {
        render(AppLanguage.EN, AttendanceState(checkedInAt = "2026-10-07T03:05:00.000Z", showCheckInPrompt = false, checkInEnabled = false, addressText = "23.793812, 90.404112"))
        compose.onNodeWithTag(AttendanceTags.CHECK_IN).assertIsNotEnabled()
        compose.onNodeWithText("Checked in at 09:05").assertExists()
        compose.onNodeWithText("23.793812, 90.404112").assertExists()
    }

    @Test fun dhakaClockAddsSixHoursAndWrapsPastMidnight() {
        assertEquals("09:05", dhakaClock("2026-10-07T03:05:00.000Z"))
        assertEquals("01:30", dhakaClock("2026-10-07T19:30:00.000Z"))
        assertFalse(dhakaClock("bad").isEmpty())
    }
}
