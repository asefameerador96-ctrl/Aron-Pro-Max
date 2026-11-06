package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.feature.amo.AmoHomeContent
import com.aktcl.aron.feature.amo.AmoHomeHeader
import com.aktcl.aron.feature.amo.AmoMenu
import com.aktcl.aron.feature.amo.AmoReach
import com.aktcl.aron.feature.amo.CallTarget
import com.aktcl.aron.feature.amo.CallTargets
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.DeviceHealthModel
import com.aktcl.aron.feature.home.HomeUser
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of the AMO shell in the AMO look (lane android-amo-shell, D-AMO-MAN-03): Home in Bangla, English, 200 % font and Sunlight, with
 * the target cards set and unset ("—"). Record on Linux only: `./gradlew :android:ui-screenshots:testDebugUnitTest -Proborazzi.record=true --tests '*AmoShellScreenshotTests*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class AmoShellScreenshotTests {
    @get:Rule val rule = createComposeRule()

    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, language: AppLanguage, sunlight: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true, amo = true) { content() }
            }
        }
        rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    private val amo = HomeUser("AMO-Dariapur", "amo5864", "AMO", offline = false, reauthRequired = false, updateRequired = false)
    private val reach = AmoReach(subordinateSrs = 4, pendingVerifications = 8, zoneName = "Dariapur")

    @Composable private fun Home(targets: CallTargets?, health: Boolean = false) = AmoHomeContent(
        AmoHomeHeader.of(amo, "2026-10-26", reach.zoneName), amo, BundleStatus.Ready("b-17", "2026-10-26"),
        AmoMenu.tiles(reach, AmoMenu.badges(reach)), onTile = {}, onSettings = {}, targets = targets,
        health = if (health) DeviceHealthModel.of(62, 1200, 3, 15) else null,
    )

    private val set = CallTargets(CallTarget(3, 5), CallTarget(42, 130), CallTarget(12, 80), CallTarget(4, 20))

    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_home_bn_light() = shot("amo_home_bn_light", AppLanguage.BN) { Home(null) }
    @Test fun amo_home_en_light() = shot("amo_home_en_light", AppLanguage.EN) { Home(null) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_home_bn_fs2() = shot("amo_home_bn_fs2", AppLanguage.BN, fontScale = 2f) { Home(null, health = true) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_home_bn_sunlight() = shot("amo_home_bn_sunlight", AppLanguage.BN, sunlight = true) { Home(null) }
    // the whole page, so the four target cards under the grid are in the frame: values set, then unset ("—")
    @Test @Config(qualifiers = "bn-w360dp-h1500dp") fun amo_home_targets_bn() = shot("amo_home_targets_bn", AppLanguage.BN) { Home(set) }
    @Test @Config(qualifiers = "w360dp-h1500dp") fun amo_home_targets_unset_en() = shot("amo_home_targets_unset_en", AppLanguage.EN) { Home(null) }
    @Test @Config(qualifiers = "bn-w360dp-h1500dp") fun amo_home_targets_sunlight_bn() = shot("amo_home_targets_sunlight_bn", AppLanguage.BN, sunlight = true) { Home(set) }
}
