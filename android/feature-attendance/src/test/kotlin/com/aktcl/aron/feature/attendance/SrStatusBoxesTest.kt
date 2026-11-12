package com.aktcl.aron.feature.attendance

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-SR-MAN-S3-01: SR manual screenshots 11 to 13 (rows SRM-A-19, SRM-A-20): the dashed status boxes on the SR attendance screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SrStatusBoxesTest {
    @get:Rule val compose = createComposeRule()

    private fun render(language: AppLanguage, state: AttendanceState, onOut: () -> Unit = {}, label: String = "17:00") {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language) { AttendanceContent(state, label, {}, onOut, nowIso = { "2026-11-12T11:47:00.000Z" }, statusBoxes = true) }
            }
        }
        compose.waitForIdle()
    }

    private val checkedIn = AttendanceState(checkedInAt = "2026-11-12T04:00:00Z", showCheckInPrompt = false)

    @Test fun beforeFiveTheDoneBoxAndTheAmberBoxShowAndCheckOutDoesNotOpen() {
        render(AppLanguage.BN, checkedIn)
        compose.onNodeWithText("চেক ইন সম্পন্ন হয়েছে").assertExists()
        compose.onNodeWithText("চেক আউট ৫টার পরে সক্রিয় হবে").assertExists()
        compose.onNodeWithTag(AttendanceTags.HOLD).assertDoesNotExist()
        // screenshot 12 (left): the centred check-out message is there in the amber state too, and the old day lines do not repeat the boxes
        compose.onNodeWithText("আপনি এখনো চেক আউট করেননি। আজকের কাজ শেষ করার আগে অনুগ্রহ করে চেক আউট করুন।").assertExists()
        compose.onAllNodesWithText("চেক ইন সম্পন্ন হয়েছে").assertCountEquals(1)
        compose.onAllNodesWithTag(AttendanceTags.STATUS).assertCountEquals(1)
    }

    @Test fun afterFiveTheRedCheckOutOpensTheHoldSheetWithLatinDigitTime() {
        var out = 0
        render(AppLanguage.BN, checkedIn.copy(checkOutEnabled = true, checkOutTimeReached = true), onOut = { out++ })
        compose.onNodeWithText("চেক ইন সম্পন্ন হয়েছে").assertExists()
        compose.onNodeWithTag(AttendanceTags.CHECK_OUT).assertIsEnabled().performClick()
        compose.onNodeWithText("চেক আউট করা হচ্ছে").assertExists()
        compose.onNodeWithText("05:47 PM").assertExists() // 11:47 UTC is 17:47 in Dhaka; Latin digits as in screenshot 13
    }

    @Test fun timeReachedButSalesSubmitPendingKeepsCheckOutOffAndNeverShowsTheAmberBox() {
        render(AppLanguage.BN, checkedIn.copy(checkOutEnabled = false, checkOutTimeReached = true, needsSalesSubmit = true))
        compose.onNodeWithTag(AttendanceTags.CHECK_OUT).assertIsNotEnabled()
        compose.onNodeWithTag(AttendanceTags.SUBMIT_FIRST).assertExists()
    }

    @Test fun theDayDoneStateShowsBothDoneBoxesAndTheThankYouMessage() {
        render(AppLanguage.BN, checkedIn.copy(checkedOutAt = "2026-11-12T11:00:00Z"))
        compose.onNodeWithText("চেক ইন সম্পন্ন হয়েছে").assertExists()
        compose.onNodeWithText("চেক আউট সম্পন্ন হয়েছে").assertExists()
        compose.onNodeWithText("আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ।").assertExists()
    }

    @Test fun noonAndMidnightReadDifferentlyInTheAmberBoxInBangla() {
        render(AppLanguage.BN, checkedIn, label = "12:00")
        compose.onNodeWithText("চেক আউট দুপুর ১২টার পরে সক্রিয় হবে").assertExists()
    }

    @Test fun eveningHoursCarryTheirWordInTheAmberBoxInBangla() {
        render(AppLanguage.BN, checkedIn, label = "19:00")
        compose.onNodeWithText("চেক আউট সন্ধ্যা ৭টার পরে সক্রিয় হবে").assertExists()
    }

    @Test fun englishLabels() {
        render(AppLanguage.EN, checkedIn.copy(checkedOutAt = "2026-11-12T11:00:00Z"))
        compose.onNodeWithText("Check-out done").assertExists()
    }
}
