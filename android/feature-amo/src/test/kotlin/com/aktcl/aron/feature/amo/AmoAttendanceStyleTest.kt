package com.aktcl.aron.feature.amo

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasNoClickAction
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
import com.aktcl.aron.feature.attendance.AttendanceContent
import com.aktcl.aron.feature.attendance.AttendanceState
import com.aktcl.aron.feature.attendance.AttendanceTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Attendance parity 14 to 16 (amo-shell A1, R7), host level: the AMO presentation of the shared screen. The check-out time gate (rows 15,
 * 16): before the configured time the dashed orange box says when, after it the red Check Out opens the hold sheet with the 12-hour clock.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AmoAttendanceStyleTest {
    @get:Rule val compose = createComposeRule()

    private val none = AttendanceState(coordinatesText = "23.792580, 90.407830", addressText = "Gulshan 1, Dhaka", fixAccuracyM = 18)
    private val checkedIn = none.copy(checkedInAt = "2026-11-06T04:05:00.000Z", showCheckInPrompt = false, checkInEnabled = false)

    private fun show(language: AppLanguage, state: AttendanceState, onIn: () -> Unit = {}, onOut: () -> Unit = {}, checkoutAt: String = "17:00") {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AronTheme(language, amo = true) {
                    AttendanceContent(state, checkoutAt, onIn, onOut, onRefreshFix = {}, userTitle = "AMO (amo5864)", userSubtitle = "Dariapur AMO, 2026-11-06", amoStyle = true)
                }
            }
        }
    }

    @Test fun beforeCheckInTheGreenTileIsOpenAndTheGreyOneIsNot() {
        show(AppLanguage.BN, none)
        compose.onNodeWithTag(AttendanceTags.CHECK_IN).assertIsEnabled()
        compose.onNodeWithTag(AttendanceTags.CHECK_OUT).assertIsNotEnabled()
        compose.onNodeWithText("আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন।").assertIsDisplayed()
    }

    @Test fun aTapOpensTheHoldSheetAndOnlyTheHoldCommitsWithATwelveHourClock() {
        var inCount = 0
        show(AppLanguage.EN, none, onIn = { inCount++ })
        compose.onNodeWithTag(AttendanceTags.CHECK_IN).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(AttendanceTags.SHEET, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(AttendanceTags.HOLD, useUnmergedTree = true).assertExists()
        assertEquals("a tap never commits", 0, inCount)
        compose.onNodeWithText("Checking in", substring = true, ignoreCase = true).assertExists()
        // the time chip is on the 12-hour clock with AM or PM (screenshot 15: 04:05 PM)
        val chip = Regex("^\\d{2}:\\d{2} (AM|PM)$")
        val texts = compose.onAllNodesWithTextMatching(chip)
        assertTrue("a 12-hour time chip is shown", texts >= 1)
    }

    @Test fun beforeTheConfiguredTimeTheOrangeBoxSaysWhenInBanglaAndEnglish() {
        show(AppLanguage.BN, checkedIn)
        compose.onNodeWithText("চেক ইন সম্পন্ন হয়েছে").assertIsDisplayed()
        compose.onNodeWithText("চেক আউট ৫টার পরে সক্রিয় হবে").assertIsDisplayed()
        // a status, not a button: nothing to tap before the configured time
        compose.onNodeWithTag(AttendanceTags.CHECK_OUT).assertHasNoClickAction()
    }

    @Test fun theEnglishOrangeBoxNamesTheTwelveHourTime() {
        show(AppLanguage.EN, checkedIn, checkoutAt = "17:30")
        compose.onNodeWithText("Check Out will be active after 5:30 PM").assertIsDisplayed()
    }

    @Test fun afterTheConfiguredTimeCheckOutIsTheRedTileAndOpensTheSheet() {
        var out = 0
        show(AppLanguage.BN, checkedIn.copy(checkOutEnabled = true, checkOutTimeReached = true), onOut = { out++ })
        compose.onNodeWithTag(AttendanceTags.CHECK_OUT).assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(AttendanceTags.SHEET, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("চেক আউট করা হচ্ছে").assertExists()
        assertEquals(0, out)
    }

    @Test fun theFinishedDayShowsTheTwoAlreadyBoxesInTheUsersLanguage() {
        val done = checkedIn.copy(checkedOutAt = "2026-11-06T11:30:00.000Z", checkOutEnabled = false)
        show(AppLanguage.EN, done)
        compose.onNodeWithText("Already Checked-In").assertIsDisplayed()
        compose.onNodeWithText("Already Checked-Out").assertIsDisplayed()
    }

    @Test fun theBanglaLocaleNeverShowsTheVendorsEnglishAlreadyBoxes() {
        val done = checkedIn.copy(checkedOutAt = "2026-11-06T11:30:00.000Z", checkOutEnabled = false)
        show(AppLanguage.BN, done)
        compose.onNodeWithText("ইতিমধ্যে চেক ইন করা হয়েছে").assertIsDisplayed()
        compose.onNodeWithText("ইতিমধ্যে চেক আউট করা হয়েছে").assertIsDisplayed()
        compose.onNodeWithText("Already Checked-In").assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextMatching(re: Regex): Int =
        onAllNodes(androidx.compose.ui.test.SemanticsMatcher("text matches $re") { n ->
            androidx.compose.ui.semantics.SemanticsProperties.Text.let { key -> n.config.contains(key) && n.config[key].any { t -> re.matches(t.text) } }
        }, useUnmergedTree = true).fetchSemanticsNodes().size
}
