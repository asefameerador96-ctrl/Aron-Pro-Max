package com.aktcl.aron.feature.sale.ui

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.repo.AttendanceRefusal
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.sale.domain.ReviewProblem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-LEAD-PREREQ-04 ruling 4: a refused save says what happened and what to do, in Bangla and English, never the generic "save failed". */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class AttendanceRefusalTextTest {
    @get:Rule val compose = createComposeRule()

    @Composable private fun Lang(language: AppLanguage, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { content() } }
    }

    @Test fun english() {
        compose.setContent { Lang(AppLanguage.EN) { androidx.compose.foundation.layout.Column {
            Text(problemText(ReviewProblem.AttendanceRefused(AttendanceRefusal.NOT_CHECKED_IN)))
            Text(problemText(ReviewProblem.AttendanceRefused(AttendanceRefusal.CHECKED_OUT)))
            Text(problemText(ReviewProblem.AttendanceRefused(AttendanceRefusal.EARLIER_CALL_OPEN)))
            Text(problemText(ReviewProblem.ClockMovedBack))
        } } }
        compose.onNodeWithText("You are not checked in for this day, so nothing was saved.", substring = true).assertExists()
        // D-AGATE-22 (item 8): an earlier day's open call is its own text: the advice is to finish that call, not to check in
        compose.onNodeWithText("An earlier day's call is still open, so nothing was saved. Finish that call first", substring = true).assertExists()
        compose.onNodeWithText("You have already checked out for this day", substring = true).assertExists()
        compose.onNodeWithText("clock was set back", substring = true).assertExists()
    }

    @Test fun bangla() {
        compose.setContent { Lang(AppLanguage.BN) { androidx.compose.foundation.layout.Column {
            Text(problemText(ReviewProblem.AttendanceRefused(AttendanceRefusal.NOT_CHECKED_IN)))
            Text(problemText(ReviewProblem.AttendanceRefused(AttendanceRefusal.EARLIER_CALL_OPEN)))
            Text(problemText(ReviewProblem.ClockMovedBack))
        } } }
        compose.onNodeWithText("আপনি হাজিরা দেননি", substring = true).assertExists()
        compose.onNodeWithText("আগের দিনের একটি কল এখনও খোলা", substring = true).assertExists()
        compose.onNodeWithText("ঘড়ি পিছনে", substring = true).assertExists()
    }
}
