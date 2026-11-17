package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.system.update.DownloadUi
import com.aktcl.aron.core.system.update.NetworkStatus
import com.aktcl.aron.core.system.update.ReleaseInfo
import com.aktcl.aron.core.system.update.SrUpdateContent
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.feature.auth.LoginUiState
import com.aktcl.aron.feature.auth.OtpManualContent
import com.aktcl.aron.feature.auth.OtpState
import com.aktcl.aron.feature.auth.SrLoginContent
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
 * Lane P-SR1 goldens (D-PAR-SR1-03): the SR sign-in journey states that had no verified image: Login as the manual shows it (empty),
 * OTP empty and filled, update available. Bangla light, 360 x 800 dp. Verified with the 0.2 percent tolerance; re-record with
 * `-Proborazzi.record=true --tests '*SrParity1ScreenshotTests*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "bn-w360dp-h800dp")
class SrParity1ScreenshotTests {
    @get:Rule val rule = createComposeRule()
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))
    private val release = ReleaseInfo("1.0.25", 25, "arm64-v8a", "a".repeat(64), 25_000_000, "https://x/y.apk", "c".repeat(64), "New promotion modality., Login time 2FA verification., Sync file update without deleting.", "নতুন প্রচার পদ্ধতি। লগইনের সময় ২এফএ যাচাই। ফাইল আপডেট, কিছু মুছে না।")

    private fun shot(name: String, language: AppLanguage = AppLanguage.BN, sunlight: Boolean = false, fontScale: Float = 1.0f, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent { CompositionLocalProvider(LocalContext provides ctx, androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, fontScale = fontScale)) { AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true) { content() } } }
        rule.waitForIdle()
        if (System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        rule.onRoot().captureRoboImage("$goldens/$name.png", roborazziOptions = options)
    }

    @Test fun login_bn_empty() = shot("srp1_login_bn_empty") { SrLoginContent(LoginUiState(), "1.0.25", {}, {}, {}, {}, {}) }
    @Test fun login_en_empty() = shot("srp1_login_en_empty", AppLanguage.EN) { SrLoginContent(LoginUiState(), "1.0.25", {}, {}, {}, {}, {}) }
    @Test fun login_bn_empty_sunlight() = shot("srp1_login_bn_empty_sunlight", sunlight = true) { SrLoginContent(LoginUiState(), "1.0.25", {}, {}, {}, {}, {}, null, true, {}) }
    @Test fun otp_bn_empty() = shot("srp1_otp_bn_empty") { OtpManualContent(OtpState(), {}, {}, {}) }
    @Test fun otp_bn_filled() = shot("srp1_otp_bn_filled") { OtpManualContent(OtpState("3560"), {}, {}, {}) }
    @Test fun update_bn_available() = shot("srp1_update_bn_available") { SrUpdateContent(release, false, NetworkStatus.WIFI, DownloadUi.Idle, {}, {}, {}, {}) }
    @Test fun update_bn_downloading() = shot("srp1_update_bn_downloading") { SrUpdateContent(release, true, NetworkStatus.WIFI, DownloadUi.Downloading(24), {}, {}, {}, {}) }

    // D-PAR-SR1-04: the gradient bar keeps a long Bangla title to two lines then an ellipsis, at font scale 2.0, with back chevron and a trailing slot
    @Test fun bar_bn_long_title_font20() = shot("srp1_bar_bn_long_title_font20", fontScale = 2.0f) {
        com.aktcl.aron.core.ui.AronScreen(topBar = { com.aktcl.aron.core.ui.AronTopBar("আজকের বিক্রয় ও সংগ্রহের বিস্তারিত সারাংশ প্রতিবেদন এবং পূর্ববর্তী দিনের তুলনা", onBack = {}) }) {}
    }
}
