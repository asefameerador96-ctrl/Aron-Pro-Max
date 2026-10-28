package com.aktcl.aron.feature.tasks

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * tso-manual A3 (D-TSO-MAN-35, XA-01, s8 row 38): a task the TSO assigns reaches the SR's Task screen with its type's label
 * from the bundle code list `task_type`, never the code.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TaskTypeLabelTest {
    @get:Rule val compose = createComposeRule()

    @After fun clear() = TaskTypeLabels.hold(emptyMap())

    private val codeLists = """[
      {"list_key":"leave_type","items":[{"code":"casual","label_en":"Casual","label_bn":null,"sort":1}]},
      {"list_key":"task_type","items":[
        {"code":"stock_out","label_en":"Stock out","label_bn":"স্টক শেষ","sort":1,"attrs":{}},
        {"code":"memo_check","label_en":"Memo check","label_bn":null,"sort":2},
        {"code":"","label_en":"bad"},{"label_en":"no code"}
      ]}
    ]"""

    private fun task(id: String, type: String) = TaskItem(id, type, "Visit $id", null, 5, "Rahim Store", "2026-10-30", "ongoing", null)

    private fun show(language: AppLanguage, state: TaskListState, labels: Map<String, TaskTypeLabel>? = null) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language) { if (labels == null) TaskContent(state, {}) else TaskContent(state, {}, typeLabels = labels) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun theCodeListIsReadAndBadItemsDropped() {
        val labels = TaskTypeLabels.parse(codeLists)
        assertEquals(setOf("stock_out", "memo_check"), labels.keys)
        assertEquals(TaskTypeLabel("Stock out", "স্টক শেষ"), labels["stock_out"])
        assertEquals("Memo check", labels["memo_check"]!!.text(bangla = true)) // no Bangla label yet: English (s7.1 Q5)
        assertTrue(TaskTypeLabels.parse(null).isEmpty()); assertTrue(TaskTypeLabels.parse("not json").isEmpty())
        assertTrue(TaskTypeLabels.parse("""[{"list_key":"leave_type","items":[]}]""").isEmpty())
    }

    @Test fun theSrCardShowsTheLabelNotTheCode() {
        show(AppLanguage.EN, TaskListState(listOf(task("a", "stock_out"), task("b", "memo_check")), 2), TaskTypeLabels.parse(codeLists))
        compose.onNodeWithTag(TaskTags.type("a")).assertTextEquals("Stock out")
        compose.onNodeWithTag(TaskTags.type("b")).assertTextEquals("Memo check")
        compose.onNodeWithText("STOCK_OUT").assertDoesNotExist(); compose.onNodeWithText("stock_out").assertDoesNotExist()
    }

    @Test fun banglaShowsTheBanglaLabel() {
        show(AppLanguage.BN, TaskListState(listOf(task("a", "stock_out")), 1), TaskTypeLabels.parse(codeLists))
        compose.onNodeWithTag(TaskTags.type("a")).assertTextEquals("স্টক শেষ")
    }

    @Test fun theHeldLabelsOfTheDayAreTheDefault() {
        // the SR day object holds its bundle's labels after every reload; the screen needs no extra wiring
        TaskTypeLabels.hold(TaskTypeLabels.parse(codeLists))
        show(AppLanguage.EN, TaskListState(listOf(task("a", "stock_out")), 1))
        compose.onNodeWithTag(TaskTags.type("a")).assertTextEquals("Stock out")
    }

    @Test fun aCodeWithoutALabelYetStillShowsSomething() {
        show(AppLanguage.EN, TaskListState(listOf(task("a", "oos")), 1), emptyMap())
        compose.onNodeWithTag(TaskTags.type("a")).assertTextEquals("OOS")
    }
}
