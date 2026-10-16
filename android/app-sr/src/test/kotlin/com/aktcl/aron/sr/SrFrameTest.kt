package com.aktcl.aron.sr

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.aktcl.aron.feature.home.R as HomeR

/**
 * The frame's back button is the system Back (screens-sr s0): a feature's own `BackHandler` (a memo open on the Memo
 * screen, the Sales Submit cancel) runs first, and the app's parent mapping only when no deeper handler is enabled.
 * The app ships no test manifest (an exported test activity would fail AppCoexistenceTest), so the host activity is
 * registered with Robolectric's package manager here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SrFrameTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun theBarsBackRunsTheDeepestEnabledBackHandlerFirst() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        var inner = 0
        var outer = 0
        val innerEnabled = mutableStateOf(true)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    AronTheme(AppLanguage.EN) {
                        BackHandler(enabled = true) { outer++ } // the app's own handler (SrNav.parent)
                        val goBack = rememberSystemBack { outer++ }
                        SrFrame(stringResource(HomeR.string.hm_tile_memo), goBack, null, null) {
                            BackHandler(enabled = innerEnabled.value) { inner++ } // the feature's handler (a memo is open)
                        }
                    }
                }
            }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
            assertEquals(1, inner); assertEquals(0, outer)
            compose.runOnIdle { innerEnabled.value = false }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
            assertEquals(1, inner); assertEquals(1, outer)
        }
    }
}
