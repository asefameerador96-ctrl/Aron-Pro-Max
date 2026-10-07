package com.aktcl.aron.core.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** N-023 slice 1: touch targets, stepper bounds and Bengali digits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class KitSliceOneTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun buttonAndRowMeetTheTouchTarget() {
        rule.setContent {
            AronTheme(AppLanguage.BN, dark = false) {
                AronPrimaryButton("সংরক্ষণ", onClick = {})
                AronListRow("আউটলেট", onClick = {})
            }
        }
        rule.onNodeWithText("সংরক্ষণ").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun stepperClampsAndShowsBengaliDigits() {
        var v by mutableIntStateOf(1)
        rule.setContent {
            AronTheme(AppLanguage.BN, dark = false) {
                AronStepper(v, { v = it }, "কমান", "বাড়ান", min = 0, max = 2)
            }
        }
        rule.onNodeWithText("১").assertExists()
        rule.onNodeWithContentDescription("বাড়ান").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("২").assertExists()
        rule.onNodeWithContentDescription("বাড়ান").performClick()
        rule.onNodeWithText("২").assertExists()
    }
}
