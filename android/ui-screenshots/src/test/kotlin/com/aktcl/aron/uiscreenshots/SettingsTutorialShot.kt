package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.feature.home.SettingsContent
import com.aktcl.aron.feature.home.TutorialItem
import com.aktcl.aron.feature.home.TutorialListContent
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Look-and-fix captures of the SR Settings and Tutorial list (no reference screenshot exists for either: layout from the kit),
 * not goldens, never verified. PNGs go to `-Daron.kitshot.dir` (default `build/kitshots`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "bn-w360dp-h800dp")
class SettingsTutorialShot {
    @get:Rule val rule = createComposeRule()
    private val dir: File = File(System.getProperty("aron.kitshot.dir") ?: "build/kitshots").also { it.mkdirs() }
    private val options = RoborazziOptions(taskType = RoborazziTaskType.Record)

    private fun shot(name: String, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.BN)
        rule.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(AppLanguage.BN, tier = GlassTier.B, reducedMotion = true) { content() } } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(dir, "$name.png").path, roborazziOptions = options)
    }

    @Test fun settings_bn() = shot("settings_bn") {
        SettingsContent("0.1.0 (12)", {}, {}, onUpdate = {}, updateAvailable = true, photosWifiOnly = true, onSupport = {}, onBack = {}, sunlight = false)
    }

    @Test fun tutorial_bn() = shot("tutorial_bn") {
        TutorialListContent(listOf(TutorialItem("1", "কীভাবে বিক্রি করবেন", "https://x/v.mp4", false, 180), TutorialItem("2", "ব্যবহার নির্দেশিকা", "https://x/m.pdf", true, null)), true, {}, onBack = {})
    }
}
