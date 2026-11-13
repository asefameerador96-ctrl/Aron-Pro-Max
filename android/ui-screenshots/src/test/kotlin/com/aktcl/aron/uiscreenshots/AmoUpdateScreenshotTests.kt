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
import com.aktcl.aron.core.system.update.DownloadUi
import com.aktcl.aron.core.system.update.NetworkStatus
import com.aktcl.aron.core.system.update.ReleaseInfo
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.feature.amo.AmoUpdateScreen
import com.aktcl.aron.feature.amo.ManualOverrideContent
import com.aktcl.aron.feature.outlet.GeoPhotoState
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
 * Goldens of the AMO update screens (optional, mandatory, downloading; owner screenshots 05 and 07) and of the Manual Override fallback states
 * (waiting, camera off), in the AMO look and in Sunlight. Linux only: `./gradlew :android:ui-screenshots:testDebugUnitTest -Proborazzi.record=true --tests '*AmoUpdateScreenshotTests*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class AmoUpdateScreenshotTests {
    @get:Rule val rule = createComposeRule()

    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))
    private val release = ReleaseInfo("1.0.4", 104, "arm64-v8a", "x", 1_000_000, "https://example.invalid/a.apk", "y")

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

    @Composable private fun Update(required: Boolean, download: DownloadUi = DownloadUi.Idle, network: NetworkStatus = NetworkStatus.WIFI) =
        AmoUpdateScreen(release, required, network, download, {}, {}, {}, {}, currentVersion = "1.0.2")

    @Test fun amo_update_optional_en() = shot("amo_update_optional_en", AppLanguage.EN) { Update(required = false) }
    @Test fun amo_update_optional_offline_en() = shot("amo_update_optional_offline_en", AppLanguage.EN) { Update(required = false, network = NetworkStatus.OFFLINE) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_update_optional_sunlight_bn() = shot("amo_update_optional_sunlight_bn", AppLanguage.BN, sunlight = true) { Update(required = false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_update_mandatory_bn() = shot("amo_update_mandatory_bn", AppLanguage.BN) { Update(required = true) }
    @Test fun amo_update_mandatory_sunlight_en() = shot("amo_update_mandatory_sunlight_en", AppLanguage.EN, sunlight = true) { Update(required = true) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_update_mandatory_bn_fs2() = shot("amo_update_mandatory_bn_fs2", AppLanguage.BN, fontScale = 2f) { Update(required = true) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_update_downloading_bn() = shot("amo_update_downloading_bn", AppLanguage.BN) { Update(required = true, download = DownloadUi.Downloading(46)) }
    @Test fun amo_update_downloading_sunlight_en() = shot("amo_update_downloading_sunlight_en", AppLanguage.EN, sunlight = true) { Update(required = false, download = DownloadUi.Downloading(46)) }

    @Test fun amo_manual_override_waiting_en() = shot("amo_manual_override_waiting_en", AppLanguage.EN) { ManualOverrideContent(GeoPhotoState(), emptySet(), {}, {}, waiting = true) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_manual_override_camera_off_bn() = shot("amo_manual_override_camera_off_bn", AppLanguage.BN) { ManualOverrideContent(GeoPhotoState(), emptySet(), {}, {}, cameraDenied = true) }
}
