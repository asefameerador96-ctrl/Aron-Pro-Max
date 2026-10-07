package com.aktcl.aron.core.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** docs/32 s2: the tier is decided by one pure policy; the field-phone default has no blur tier. */
class GlassPolicyTest {
    private val a06 = GlassSignals(sdkInt = 34, totalMemMb = 2048, batterySaver = false)
    private val highEnd = GlassSignals(sdkInt = 34, totalMemMb = 8192, batterySaver = false)

    @Test fun fieldPhoneDefaultsToLite() = assertEquals(GlassTier.B, GlassPolicy.resolve(UiGlassConfig.AUTO, a06))
    @Test fun highEndPhoneGetsFullGlassOnAuto() = assertEquals(GlassTier.A, GlassPolicy.resolve(UiGlassConfig.AUTO, highEnd))
    @Test fun liteCapsAHighEndPhone() = assertEquals(GlassTier.B, GlassPolicy.resolve(UiGlassConfig.LITE, highEnd))
    @Test fun offIsSolid() = assertEquals(GlassTier.C, GlassPolicy.resolve(UiGlassConfig.OFF, highEnd))
    @Test fun batterySaverForcesSolid() = assertEquals(GlassTier.C, GlassPolicy.resolve(UiGlassConfig.AUTO, highEnd.copy(batterySaver = true)))
    @Test fun failedFrameProbeForcesSolid() = assertEquals(GlassTier.C, GlassPolicy.resolve(UiGlassConfig.LITE, a06.copy(frameProbeFailed = true)))
    @Test fun accessibilitySignalsForceSolid() {
        assertEquals(GlassTier.C, GlassPolicy.resolve(UiGlassConfig.AUTO, highEnd.copy(reduceTransparency = true)))
        assertEquals(GlassTier.C, GlassPolicy.resolve(UiGlassConfig.AUTO, highEnd.copy(highContrast = true)))
    }
    @Test fun configParsesUnknownAsAuto() {
        assertEquals(UiGlassConfig.AUTO, UiGlassConfig.parse(null))
        assertEquals(UiGlassConfig.LITE, UiGlassConfig.parse("Lite"))
        assertEquals(UiGlassConfig.OFF, UiGlassConfig.parse("off"))
    }
}

/** The status chip and primary button render in every tier, both themes, with the same words and size. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class GlassSurfaceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun chipAndPrimaryButtonInEveryTierAndTheme() {
        for (tier in GlassTier.values()) for (dark in listOf(false, true)) {
            rule.setContent {
                AronTheme(AppLanguage.EN, dark = dark, tier = tier) {
                    StatusChip(SyncChipState.Waiting(3))
                    AronPrimaryButton("Save", onClick = {})
                }
            }
            rule.onNodeWithContentDescription("3 waiting").assertExists()
            rule.onNodeWithText("Save").assertHeightIsAtLeast(56.dp)
        }
    }
}
