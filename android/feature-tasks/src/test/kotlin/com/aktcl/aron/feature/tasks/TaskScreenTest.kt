package com.aktcl.aron.feature.tasks

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performClick
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

    @Test fun swipeRevealsResolveAndTapResolves() {
        var resolved: String? = null
        show(AppLanguage.EN, TaskListState(listOf(t("a")), 1), onResolve = { resolved = it })
        compose.onNodeWithTag(TaskTags.resolve("a")).assertDoesNotExist()
        compose.onNodeWithTag(TaskTags.item("a")).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag(TaskTags.resolve("a")).performClick()
        assertEquals("a", resolved)
    }

    @Test fun completedTasksStayWithoutAResolveButton() {
        show(AppLanguage.EN, TaskListState(listOf(t("a", "completed")), 0))
        compose.onNodeWithText("Completed").assertExists(); compose.onNodeWithTag(TaskTags.resolve("a")).assertDoesNotExist()
    }
}
