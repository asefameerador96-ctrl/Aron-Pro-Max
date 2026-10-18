package com.aktcl.aron.feature.tasks

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SR-046 / F-SR-047 screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TaskScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(language: AppLanguage, state: TaskListState, onResolve: (String) -> Unit = {}) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { TaskContent(state, onResolve) } } }
        compose.waitForIdle()
    }

    private fun t(id: String, status: String = "ongoing") = TaskItem(id, "visit_outlet", "Visit $id", null, 5, "Rahim Store", "2026-10-09", status, null)

    @Test fun emptyStateIsTheBanglaMessageWithSyncedAt() {
        show(AppLanguage.BN, TaskListState(showEmptyState = true, syncedAt = "2026-10-07T03:00:00.000Z"))
        compose.onNodeWithText("আপনার এএমও (AMO) কোনো কাজ বরাদ্দ করেনি।").assertExists()
        compose.onNodeWithText("সিঙ্ক হয়েছে ২০২৬-১০-০৭ ০৯:০০").assertExists()
    }

    @Test fun resolveIsOnTheCardAndTapResolves() {
        var resolved: String? = null
        show(AppLanguage.EN, TaskListState(listOf(t("a")), 1), onResolve = { resolved = it })
        // Resolve is always on the card (screens-sr s12: discoverable and TalkBack-friendly); the swipe is the parity gesture beside it
        compose.onNodeWithTag(TaskTags.resolve("a")).assertExists()
        compose.onNodeWithTag(TaskTags.resolve("a")).performClick()
        assertEquals("a", resolved)
    }

    @Test fun swipeToTheEndResolvesOnce() {
        var resolved = 0
        show(AppLanguage.EN, TaskListState(listOf(t("a")), 1), onResolve = { resolved++ })
        // F-SR-047: a swipe to the end past 40 percent of the card resolves; the card springs back (no dismiss animation)
        compose.onNodeWithTag(TaskTags.item("a")).performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(1, resolved)
        compose.onNodeWithTag(TaskTags.item("a")).assertExists()
    }

    @Test fun dueDateReadsAsIsoWithTheYear() {
        show(AppLanguage.EN, TaskListState(listOf(t("a")), 1))
        compose.onNodeWithText("Due 2026-10-09").assertExists()
    }

    @Test fun dueDateReadsAsIsoWithTheYearInBangla() {
        show(AppLanguage.BN, TaskListState(listOf(t("a")), 1))
        compose.onNodeWithText("শেষ তারিখ ২০২৬-১০-০৯").assertExists()
    }

    @Test fun aLongListKeepsEveryCardAndItsResolveButton() {
        // past the eighth card the cards are flat (no shadow per row); every one still lists and resolves
        val many = (1..12).map { t("t$it") }
        show(AppLanguage.EN, TaskListState(many, many.size))
        compose.onNodeWithTag(TaskTags.LIST).assertExists()
        compose.onNodeWithTag(TaskTags.resolve("t1")).assertExists()
        compose.onNodeWithText("Ongoing 12").assertExists()
    }

    @Test fun completedTasksStayWithoutAResolveButton() {
        show(AppLanguage.EN, TaskListState(listOf(t("a", "completed")), 0))
        compose.onNodeWithText("Completed").assertExists(); compose.onNodeWithTag(TaskTags.resolve("a")).assertDoesNotExist()
    }
}
