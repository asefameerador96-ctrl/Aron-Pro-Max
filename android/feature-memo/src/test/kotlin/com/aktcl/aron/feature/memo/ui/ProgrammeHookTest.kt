package com.aktcl.aron.feature.memo.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P-SR2: the hook pages of the deferred programmes name what the earlier app shows, invent no figure and only go back. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class ProgrammeHookTest {
    @get:Rule val compose = createComposeRule()

    private fun show(kind: ProgrammeHook, onBack: () -> Unit = {}) {
        compose.setContent { AronTheme(AppLanguage.EN) { ProgrammeHookContent(kind, onBack) } }
        compose.waitForIdle()
    }

    @Test fun targetHookListsTheEightColumnsAndSaysLater() {
        show(ProgrammeHook.TARGET)
        compose.onNodeWithText("target, achieved, remaining, achievement, ADS, TADS, PADS, RADS", substring = true).assertExists()
        compose.onAllNodesWithText("Coming later").assertCountEquals(1)
    }

    @Test fun kpiHookHasTheFourSectionsAndNoFigure() {
        show(ProgrammeHook.KPI)
        compose.onAllNodesWithText("Coming later").assertCountEquals(4)
        compose.onNodeWithText("CPR - Target Call vs Successful Call").assertExists()
        compose.onAllNodesWithText("৳", substring = true).assertCountEquals(0)
    }

    @Test fun theOnlyActionIsGoingBack() {
        var back = 0
        show(ProgrammeHook.BRAND_KPI) { back++ }
        compose.onNodeWithText("Go back").performClick()
        assertEquals(1, back)
    }
}
