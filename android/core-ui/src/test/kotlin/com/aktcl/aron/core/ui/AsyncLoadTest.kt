package com.aktcl.aron.core.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.aron.AronLoaded
import com.aktcl.aron.core.ui.aron.rememberAsyncLoad
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A host's local read: answered, thrown (kit error state with retry, never a crash) and retried until it answers. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AsyncLoadTest {
    @get:Rule val compose = createComposeRule()

    private fun show(read: suspend () -> List<String>) {
        compose.setContent {
            AronTheme(AppLanguage.EN) {
                val load = rememberAsyncLoad { read() }
                AronLoaded(load, "Could not load", "Try again") { rows -> Text(rows.joinToString()) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun anAnsweredReadShowsTheContent() { show { listOf("a", "b") }; compose.onNodeWithText("a, b").assertExists() }

    @Test fun aThrownReadShowsTheErrorStateWithRetryInsteadOfCrashing() {
        show { error("room read failed") }
        compose.onNodeWithText("Could not load").assertExists(); compose.onNodeWithText("Try again").assertExists()
    }

    @Test fun retryReadsAgainAndShowsTheContentOnceItAnswers() {
        var calls = 0
        show { if (++calls == 1) error("first read fails") else listOf("ok") }
        compose.onNodeWithText("Try again").performClick(); compose.waitForIdle()
        compose.onNodeWithText("ok").assertExists()
        org.junit.Assert.assertEquals(2, calls)
    }
}
