package com.aktcl.aron.feature.amo

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-AMO-018 (screenshot 37): a completed task card keeps its resolution date, note and the SR who did it; a running one has none of them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskCardResolutionTest {
    @get:Rule val compose = createComposeRule()

    private val running = AssignedTask("t1", "stock_check", "Stock check", 1, 7, "ongoing", "2026-10-19", null, null, "2026-10-17T01:00:00Z")
    private val done = running.copy(uuid = "t2", status = "completed", resolvedAt = "2026-10-17T09:00:00Z", resolutionNote = "Done, stock was fine")

    @Test fun aCompletedCardShowsTheResolutionDateTheNoteAndTheAssignee() {
        compose.setContent {
            AronTheme(AppLanguage.EN, amo = true) {
                AssignedTasksContent(listOf(running, done), mapOf(7L to "Rahim Uddin"), mapOf(1L to "Rahim Store"), { it }, false, false, {}, today = "2026-10-17")
            }
        }
        compose.onNodeWithTag(TaskTags.RESOLUTION + "t2").assertTextEquals("Done 2026-10-17 · Done, stock was fine")
        compose.onNodeWithTag(TaskTags.ASSIGNEE + "t2").assertTextContains("Rahim Uddin", substring = true)
        compose.onNodeWithTag(TaskTags.RESOLUTION + "t1").assertDoesNotExist()
        compose.onNodeWithTag(TaskTags.ASSIGNEE + "t1").assertDoesNotExist()
    }
}
