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
import com.aktcl.aron.core.system.update.DownloadUi
import com.aktcl.aron.core.system.update.NetworkStatus
import com.aktcl.aron.core.system.update.ReleaseInfo
import com.aktcl.aron.core.system.update.SrUpdateContent
import com.aktcl.aron.feature.auth.OtpManualContent
import com.aktcl.aron.feature.auth.OtpState
import com.aktcl.aron.feature.auth.SrLoginContent
import com.aktcl.aron.feature.auth.LoginUiState
import com.aktcl.aron.feature.home.DataUpdateContent
import com.aktcl.aron.feature.home.DataUpdatePhase
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
 * Look-and-fix captures (not goldens, never verified) of the SR manual screens 03, 04, 06/07 and 08 (D-SR-MAN-S1): Bangla light,
 * 360 x 800 dp. PNGs go to `-Daron.kitshot.dir` (default `build/kitshots`). Run:
 * `./gradlew :android:ui-screenshots:testDebugUnitTest --tests '*SrManualShot*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "bn-w360dp-h800dp")
class SrManualShot {
    @get:Rule val rule = createComposeRule()
    private val dir: File = File(System.getProperty("aron.kitshot.dir") ?: "build/kitshots").also { it.mkdirs() }
    private val options = RoborazziOptions(taskType = RoborazziTaskType.Record)
    private val release = ReleaseInfo("1.0.25", 25, "arm64-v8a", "a".repeat(64), 25_000_000, "https://x/y.apk", "c".repeat(64), "New promotion modality., Login time 2FA verification., Sync file update without deleting.", "নতুন প্রচার পদ্ধতি। লগইনের সময় ২এফএ যাচাই। ফাইল আপডেট, কিছু মুছে না।")

    private fun shot(name: String, content: @Composable () -> Unit) {
        val language = AppLanguage.BN
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language, dark = false, sunlight = false, tier = GlassTier.B, reducedMotion = false) { content() } } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(dir, "$name.png").path, roborazziOptions = options)
    }

    @Test fun login() = shot("srmanual_03_login_bn") { SrLoginContent(LoginUiState(), "1.0.25", {}, {}, {}, {}, {}) }
    @Test fun otpEmpty() = shot("srmanual_04_otp_empty_bn") { OtpManualContent(OtpState(), {}, {}, {}) }
    @Test fun otpFilled() = shot("srmanual_04_otp_filled_bn") { OtpManualContent(OtpState("3560"), {}, {}, {}) }
    @Test fun updateAvailable() = shot("srmanual_06_update_bn") { SrUpdateContent(release, false, NetworkStatus.WIFI, DownloadUi.Idle, {}, {}, {}, {}) }
    @Test fun updateDownloading() = shot("srmanual_07_downloading_bn") { SrUpdateContent(release, true, NetworkStatus.WIFI, DownloadUi.Downloading(24), {}, {}, {}, {}) }
    @Test fun updateCompleted() = shot("srmanual_07_completed_bn") { SrUpdateContent(release, true, NetworkStatus.WIFI, DownloadUi.Ready, {}, {}, {}, {}) }
    @Test fun dataPrompt() = shot("srmanual_08_prompt_bn") { DataUpdateContent(DataUpdatePhase.PROMPT, "1.0.1", {}) }
    @Test fun dataRunning() = shot("srmanual_08_running_bn") { DataUpdateContent(DataUpdatePhase.RUNNING, "1.0.1", {}, done = 14, total = 87) }
}
