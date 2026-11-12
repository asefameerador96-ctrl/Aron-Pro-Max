package com.aktcl.aron.sr

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.attendance.AttendanceState
import com.aktcl.aron.feature.attendance.AttendanceTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * D-SR-MAN-S3-01 (lead review): the REAL SR wiring (`SrAttendanceContent`, the function SrApp calls) against the manual notes 12 and 13:
 * the centred message above the amber box, the box texts once, the configured check-out time in the amber box. The trusted-time gate itself
 * is proven by AttendanceFlowTest in feature-attendance (check-out refused before the configured Dhaka minute on the corrected clock).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SrAttendanceWiringTest {
    @get:Rule val compose = hostComposeRule()

    private fun show(state: AttendanceState, label: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            activity.setContent {
                AronTheme(AppLanguage.BN) {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides AppLocale.wrap(activity, AppLanguage.BN)) {
                        SrAttendanceContent(state, label, {}, {}, "SR - Test (sr1)", "Route, 2026-11-12", {}, offline = false)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private val checkedIn = AttendanceState(checkedInAt = "2026-11-12T04:00:00Z", showCheckInPrompt = false)

    @Test fun amberStateShowsTheCheckOutMessageTheDoneBoxOnceAndTheAmberBox() {
        show(checkedIn, "17:00")
        compose.onNodeWithText("আপনি এখনো চেক আউট করেননি। আজকের কাজ শেষ করার আগে অনুগ্রহ করে চেক আউট করুন।").assertExists()
        compose.onAllNodesWithText("চেক ইন সম্পন্ন হয়েছে").assertCountEquals(1) // no day line repeating the box text
        compose.onAllNodesWithTag(AttendanceTags.STATUS).assertCountEquals(1)
        compose.onNodeWithText("চেক আউট ৫টার পরে সক্রিয় হবে").assertExists()
    }

    @Test fun theAmberBoxReadsTheConfiguredTimeNotAFixedFive() {
        show(checkedIn, "18:30")
        compose.onNodeWithText("চেক আউট সন্ধ্যা ৬:৩০টার পরে সক্রিয় হবে").assertExists()
    }

    @Test fun doneStateShowsBothBoxesAndTheThankYou() {
        show(checkedIn.copy(checkedOutAt = "2026-11-12T11:00:00Z"), "17:00")
        compose.onNodeWithText("চেক আউট সম্পন্ন হয়েছে").assertExists()
        compose.onAllNodesWithText("চেক ইন সম্পন্ন হয়েছে").assertCountEquals(1)
        compose.onNodeWithText("আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ।").assertExists()
    }

    @Test fun checkLabelFormat() { assertEquals("17:30", checkoutLabel(17 * 60 + 30)); assertEquals("09:05", checkoutLabel(9 * 60 + 5)) }
}
