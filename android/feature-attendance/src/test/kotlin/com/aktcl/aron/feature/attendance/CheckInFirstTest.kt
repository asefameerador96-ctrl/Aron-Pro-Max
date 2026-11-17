package com.aktcl.aron.feature.attendance

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.repo.AttendanceRefusal
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The UI mirror of the check-in gate (D-OWNER-PREREQ-01 rule 1): the reason, and always a way out. No dead end. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class CheckInFirstTest {
    @get:Rule val compose = createComposeRule()

    @Composable private fun Lang(language: AppLanguage, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { content() } }
    }

    @Test fun notCheckedInSaysWhyAndLeadsToCheckIn() {
        var went = 0
        compose.setContent { Lang(AppLanguage.EN) { CheckInFirst(AttendanceRefusal.NOT_CHECKED_IN, onCheckIn = { went++ }) } }
        compose.onNodeWithText("Check in first").assertExists()
        compose.onNodeWithTag(CheckInFirstTags.GO).performClick()
        assertEquals(1, went)
    }

    @Test fun theSameScreenReadsInBangla() {
        compose.setContent { Lang(AppLanguage.BN) { CheckInFirst(AttendanceRefusal.NOT_CHECKED_IN, onCheckIn = {}) } }
        compose.onNodeWithText("আগে হাজিরা দিন").assertExists()
        compose.onNodeWithText("হাজিরায় যান").assertExists()
    }

    @Test fun afterCheckOutTheDayIsOverAndThereIsNoCheckInButton() {
        compose.setContent { Lang(AppLanguage.EN) { CheckInFirst(AttendanceRefusal.CHECKED_OUT, onCheckIn = {}) } }
        compose.onNodeWithText("You have checked out for today").assertExists()
        compose.onNodeWithTag(CheckInFirstTags.GO).assertDoesNotExist()
    }

    /** An already open call can still be closed: that close is exempt from the gate, so the rep is never trapped inside it. */
    @Test fun anOpenCallCanStillBeClosedFromTheGateScreen() {
        var closed = 0
        compose.setContent { Lang(AppLanguage.EN) { CheckInFirst(AttendanceRefusal.NOT_CHECKED_IN, onCheckIn = {}, onCloseCall = { closed++ }) } }
        compose.onNodeWithTag(CheckInFirstTags.CLOSE_CALL).performClick()
        assertEquals(1, closed)
    }
}
