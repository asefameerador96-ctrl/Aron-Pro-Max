package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.DeviceHealthModel
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.R
import com.aktcl.aron.feature.home.RoleDeviceOtpContent
import com.aktcl.aron.feature.home.RoleHero
import com.aktcl.aron.feature.home.RoleHomeContent
import com.aktcl.aron.feature.home.RoleHomes
import com.aktcl.aron.feature.home.RoleSectionPlaceholder
import com.aktcl.aron.feature.home.RoleSettingsContent
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
 * Look-and-fix captures (not goldens, never verified) of the AMO and TSO Home frame, Settings, a section placeholder and
 * the TSO Device OTP page: Bangla light and sunlight, tier B, 360 x 800 dp. The PNGs go to `-Daron.kitshot.dir`
 * (default `build/kitshots`). Run: `./gradlew :android:ui-screenshots:testDebugUnitTest --tests '*RoleHomeShot*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class RoleHomeShot {
    @get:Rule val rule = createComposeRule()

    private val dir: File = File(System.getProperty("aron.kitshot.dir") ?: "build/kitshots").also { it.mkdirs() }
    private val options = RoborazziOptions(taskType = RoborazziTaskType.Record)

    private val amo = HomeUser("Testing Banani", "amo5001", "AMO", offline = false, reauthRequired = false, updateRequired = false)
    private val tso = HomeUser("Testing Dhaka North", "tso1001", "TSO", offline = true, reauthRequired = false, updateRequired = false)
    private val health = DeviceHealthModel.of(62, 1200, 3, 15)

    private fun shot(name: String, language: AppLanguage, sunlight: Boolean = false, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = false) { content() }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(dir, "$name.png").path, roborazziOptions = options)
    }

    @Composable private fun AmoHome(sunlight: Boolean) = com.aktcl.aron.feature.amo.AmoHomeContent(
        RoleHomes.header(RoleHomes.AMO_PREFIX, amo, "2026-10-05"), amo, BundleStatus.Ready("b-17", "2026-10-05"),
        RoleHomes.amoTiles(mapOf(RoleHomes.AmoTile.OUTLET to 3)), onTile = {}, onSettings = {},
        hero = RoleHero.Amo(4_391_000, 6, 60), health = health,
    )

    @Composable private fun TsoHome(sunlight: Boolean) = RoleHomeContent(
        RoleHomes.header(RoleHomes.TSO_PREFIX, tso, "2026-10-05"), tso, BundleStatus.Offline,
        RoleHomes.tsoTiles(mapOf(RoleHomes.TsoTile.LEAVE to 1)), SyncChipState.Offline, onTile = {}, onSettings = {},
        hero = RoleHero.Tso(3, 12), health = health, sunlight = sunlight,
    )

    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_home_bn_light() = shot("rolehome_amo_bn_light", AppLanguage.BN) { AmoHome(false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_home_bn_sunlight() = shot("rolehome_amo_bn_sunlight", AppLanguage.BN, sunlight = true) { AmoHome(true) }
    @Test fun amo_home_en_light() = shot("rolehome_amo_en_light", AppLanguage.EN) { AmoHome(false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun tso_home_bn_light() = shot("rolehome_tso_bn_light", AppLanguage.BN) { TsoHome(false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun tso_home_bn_sunlight() = shot("rolehome_tso_bn_sunlight", AppLanguage.BN, sunlight = true) { TsoHome(true) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun empty_bn_light() = shot("rolehome_empty_bn_light", AppLanguage.BN) {
        RoleSectionPlaceholder(stringResource(R.string.rh_amo_control_call), AronIcons.Place, onBack = {}, syncedAt = localizedDigits("10:42"))
    }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun otp_bn_light() = shot("rolehome_otp_bn_light", AppLanguage.BN) { RoleDeviceOtpContent(onBack = {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun settings_bn_light() = shot("rolehome_settings_bn_light", AppLanguage.BN) {
        RoleSettingsContent("0.1.0 (1)", stringResource(R.string.rh_set_logout_wipe), {}, {}, onBack = {}, sunlight = false, onSunlight = {}, onUpdate = {}, updateAvailable = true)
    }
}
