package com.aktcl.aron.feature.tasks

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
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

/** SRM-B-47, 48 (screenshot 47): the manual's card, one list, and a resolve that always asks first. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h1200dp")
class ManualTaskScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(language: AppLanguage, state: TaskListState, onResolve: (String) -> Unit = {}) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { TaskContent(state, onResolve, manual = true, typeLabels = mapOf("oos" to TaskTypeLabel("OOS", "OOS"))) } } }
        compose.waitForIdle()
    }

    private fun t(id: String, status: String = "ongoing") = TaskItem(id, "oos", "Visit $id", "রুকু স্টোরে দ্রুত প্রোডাক্ট দেও", 5, "Ruku Store", "2026-11-11", status, null)

    @Test fun cardShowsTypeOutletDescriptionStatusAndCompletionOnInOneList() {
        show(AppLanguage.BN, TaskListState(listOf(t("a"), t("b", "completed")), 1))
        compose.onNodeWithTag(TaskTags.TABS).assertDoesNotExist() // one list, no tabs
        compose.onNodeWithTag(TaskTags.type("a")).assertExists(); compose.onAllNodesWithText("OOS").assertCountEquals(2)
        compose.onNodeWithText("চলমান").assertExists(); compose.onNodeWithText("সম্পন্ন").assertExists()
        compose.onAllNodesWithText("রুকু স্টোরে দ্রুত প্রোডাক্ট দেও").assertCountEquals(2)
        compose.onAllNodesWithText("Completion on: 2026-11-11").assertCountEquals(2) // Latin ISO digits in both languages, as screenshot 47
        compose.onNodeWithText("আউটলেট: Ruku Store").assertDoesNotExist() // no "Outlet:" prefix
    }

    @Test fun aSwipeOnlyAsksAndTheResolveHappensAfterTheConfirmation() {
        var resolved = 0
        show(AppLanguage.EN, TaskListState(listOf(t("a")), 1), onResolve = { resolved++ })
        compose.onNodeWithTag(TaskTags.item("a")).performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals("a swipe alone must not resolve", 0, resolved)
        compose.onNodeWithText("Resolve this task?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, resolved)
    }

    @Test fun confirmResolvesExactlyOnceAndADoubleConfirmStillOnce() {
        val resolved = mutableListOf<String>()
        show(AppLanguage.EN, TaskListState(listOf(t("a")), 1), onResolve = { resolved += it })
        compose.onNodeWithTag(TaskTags.resolve("a")).performClick()
        assertEquals(emptyList<String>(), resolved)
        // the dialog's confirm carries the word "Resolve" too: it is the last node with that text
        val confirm = compose.onAllNodesWithText("Resolve").onLast()
        confirm.performClick(); confirm.performClick() // a double tap
        compose.waitForIdle()
        assertEquals(listOf("a"), resolved)
        compose.onNodeWithText("Resolve this task?").assertDoesNotExist()
    }

    @Test fun theSecondaryResolveAlsoAsksFirst() {
        var resolved = 0
        show(AppLanguage.EN, TaskListState(listOf(t("a")), 1), onResolve = { resolved++ })
        compose.onNodeWithTag(TaskTags.resolve("a")).performClick()
        assertEquals(0, resolved)
        compose.onNodeWithText("Resolve this task?").assertExists()
    }
}
