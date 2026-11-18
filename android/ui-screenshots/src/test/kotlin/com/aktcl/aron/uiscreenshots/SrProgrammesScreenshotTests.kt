package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.feature.programmes.AsthaMenuScreen
import com.aktcl.aron.feature.programmes.AsthaModel
import com.aktcl.aron.feature.programmes.AsthaSelection
import com.aktcl.aron.feature.programmes.AsthaTargetScreen
import com.aktcl.aron.feature.programmes.ShopInfo
import com.aktcl.aron.feature.programmes.R as ProgR
import com.aktcl.aron.rules.UiLocale
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
 * Goldens of the SR Astha screens (lane android-sr-programmes, manual 48 to 50): menu, target table, empty, Sunlight and font scale 2.0.
 * Same frame, tier B, 360 x 800 dp. Record on Linux only: `-Daron.screenshots.recordTo=src/test/screenshots` (docs/status/android-core-ui.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "bn-w360dp-h800dp")
class SrProgrammesScreenshotTests {
    @get:Rule val rule = createComposeRule()

    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, language: AppLanguage = AppLanguage.BN, sunlight: Boolean = false, fontScale: Float = 1.0f, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true) { content() }
            }
        }
        rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") {
            assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        }
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    @Composable private fun Frame(content: @Composable () -> Unit) = AronScreen(topBar = { AronTopBar(title = stringResource(ProgR.string.pg_astha_title), onBack = {}) }) { content() }

    private val programmes = """{"astha_as_of":"2026-11-17T10:00:00Z","astha_truncated":false,"astha_targets":[
      {"route_id":7,"outlet_id":null,"brand_id":11,"month":"2026-11","std_target":"21600","std_achieved":"4680","memo_target":0,"memo_achieved":0,"value_target_mtk":"86400000","value_achieved_mtk":"18720000","by_pack":[{"pack_factor":20,"std_achieved":"3600"},{"pack_factor":10,"std_achieved":"1080"}]},
      {"route_id":7,"outlet_id":null,"brand_id":12,"month":"2026-11","std_target":"10800","std_achieved":"0","memo_target":0,"memo_achieved":0,"value_target_mtk":"43200000","value_achieved_mtk":"0","by_pack":[]},
      {"route_id":7,"outlet_id":null,"brand_id":null,"month":"2026-11","std_target":"0","std_achieved":"0","memo_target":884,"memo_achieved":787,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]}]}"""
    private val products = """{"nodes":[{"id":11,"level":"brand","name":"Marise","name_bn":"মারিস","sort":1},{"id":12,"level":"brand","name":"Avon","name_bn":"অ্যাভন","sort":2}],"skus":[]}"""
    private val data get() = AsthaModel.parse(programmes, products)
    private val picked = AsthaSelection(2026, 4, "2026-11")

    @Test fun astha_menu_bn() = shot("astha_menu_bn") { Frame { AsthaMenuScreen({}, null) } }
    @Test fun astha_target_bn_std() = shot("astha_target_bn_std") { Frame { AsthaTargetScreen(data, UiLocale.BN, 2026, initial = picked) } }
    @Test fun astha_target_bn_memo() = shot("astha_target_bn_memo") { Frame { AsthaTargetScreen(data, UiLocale.BN, 2026, initial = picked.copy(memoTab = true)) } }
    @Test fun astha_target_en_std() = shot("astha_target_en_std", AppLanguage.EN) { Frame { AsthaTargetScreen(data, UiLocale.EN, 2026, initial = picked) } }
    @Test fun astha_target_bn_sunlight() = shot("astha_target_bn_sunlight", sunlight = true) { Frame { AsthaTargetScreen(data, UiLocale.BN, 2026, initial = picked) } }
    @Test fun astha_target_bn_font20() = shot("astha_target_bn_font20", fontScale = 2.0f) { Frame { AsthaTargetScreen(data, UiLocale.BN, 2026, initial = picked) } }
    @Test fun astha_target_bn_empty_no_programme() = shot("astha_target_bn_empty_no_programme") { Frame { AsthaTargetScreen(null, UiLocale.BN, 2026) } }
    @Test fun astha_target_bn_empty_month() = shot("astha_target_bn_empty_month") { Frame { AsthaTargetScreen(data, UiLocale.BN, 2026, initial = AsthaSelection(2026, 1, "2026-01")) } }

    private val shops = listOf(
        ShopInfo(501, "Ajij Tea Store (3700634-01716184335-Pink City Road)", "3700634", "Md.Ajij", "01716184335", "Gold"),
        ShopInfo(502, "Rubel Tea Store (2681254-01334363419-Agora Road, Gulshan-2)", "2681254", "Abdul Mannan", "01334363419", "Diamond"),
    )
    @Test fun astha_shops_bn() = shot("astha_shops_bn") { Frame { AsthaTargetScreen(data, UiLocale.BN, 2026, shops = shops, startOnShops = true) } }
    @Test fun astha_shops_bn_empty() = shot("astha_shops_bn_empty") { Frame { AsthaTargetScreen(data, UiLocale.BN, 2026, shops = emptyList(), startOnShops = true) } }
    @Test fun astha_shops_bn_font20() = shot("astha_shops_bn_font20", fontScale = 2.0f) { Frame { AsthaTargetScreen(data, UiLocale.BN, 2026, shops = shops, startOnShops = true) } }
}
