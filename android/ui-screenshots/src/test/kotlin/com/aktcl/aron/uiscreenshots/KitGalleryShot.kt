package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.core.ui.KitGallery
import com.aktcl.aron.core.ui.aron.AronLookGallery
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
 * Look-and-fix captures of the whole kit gallery (not goldens, never verified): Bangla light, Bangla sunlight and English
 * light, tier B, 360 dp wide and 3800 dp tall so the scrolling column shows every component at once. The PNGs go to
 * `-Daron.kitshot.dir` (default `build/kitshots`). Run: `./gradlew :android:ui-screenshots:testDebugUnitTest --tests '*KitGalleryShot*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h3800dp")
class KitGalleryShot {
    @get:Rule val rule = createComposeRule()

    private val dir: File = File(System.getProperty("aron.kitshot.dir") ?: "build/kitshots").also { it.mkdirs() }
    private val options = RoborazziOptions(taskType = RoborazziTaskType.Record)

    private fun shot(name: String, language: AppLanguage, sunlight: Boolean = false, dark: Boolean = false, look: Boolean = false) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language, dark = dark, sunlight = sunlight, tier = GlassTier.B, reducedMotion = false) { if (look) AronLookGallery() else KitGallery() }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(dir, "$name.png").path, roborazziOptions = options)
    }

    @Test @Config(qualifiers = "bn-w360dp-h3800dp") fun kit_bn_light() = shot("kit_bn_light", AppLanguage.BN)
    @Test @Config(qualifiers = "bn-w360dp-h3800dp") fun kit_bn_sunlight() = shot("kit_bn_sunlight", AppLanguage.BN, sunlight = true)
    @Test fun kit_en_light() = shot("kit_en_light", AppLanguage.EN)
    @Test @Config(qualifiers = "bn-w360dp-h3800dp") fun kit_bn_dark() = shot("kit_bn_dark", AppLanguage.BN, dark = true)

    // the parity kit in the existing-Aron look (OWNER DIRECTIVE 2026-10-18, docs/ui-reference/PARITY-SPEC.md)
    @Test @Config(qualifiers = "bn-w360dp-h3600dp") fun look_bn() = shot("look_bn", AppLanguage.BN, look = true)
    @Test @Config(qualifiers = "w360dp-h3600dp") fun look_en() = shot("look_en", AppLanguage.EN, look = true)
    @Test @Config(qualifiers = "bn-w360dp-h3600dp") fun look_bn_sunlight() = shot("look_bn_sunlight", AppLanguage.BN, sunlight = true, look = true)
    @Test @Config(qualifiers = "bn-w411dp-h3600dp", fontScale = 1.3f) fun look_bn_411_fs13() = shot("look_bn_411_fs13", AppLanguage.BN, look = true)
}
