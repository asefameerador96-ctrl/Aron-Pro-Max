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
import com.aktcl.aron.feature.programmes.AsthaFetched
import com.aktcl.aron.feature.programmes.AsthaMenuScreen
import com.aktcl.aron.feature.programmes.CachedMonth
import com.aktcl.aron.feature.programmes.QuarterFetch
import com.aktcl.aron.feature.programmes.AsthaModel
import com.aktcl.aron.feature.programmes.AsthaSelection
import com.aktcl.aron.feature.programmes.AsthaTargetScreen
import com.aktcl.aron.feature.programmes.LoyaltyMenuScreen
import com.aktcl.aron.feature.programmes.LoyaltyModel
import com.aktcl.aron.feature.programmes.OutletPointsScreen
import com.aktcl.aron.feature.programmes.GiftChoiceModel
import com.aktcl.aron.feature.programmes.GiftSelectionScreen
import com.aktcl.aron.feature.programmes.LocalGiftChoice
import com.aktcl.aron.feature.programmes.RedemptionScreen
import com.aktcl.aron.feature.programmes.SaveOutcome
import com.aktcl.aron.feature.programmes.ShopInfo
import com.aktcl.aron.feature.programmes.ShopOutlet
import com.aktcl.aron.feature.home.R as HomeR
import com.aktcl.aron.feature.programmes.R as ProgR
import com.aktcl.aron.feature.programmes.RouteStar
import com.aktcl.aron.feature.programmes.StarScreen
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
 * Goldens of the SR Match Box Lifting Star screen (manual 62, lane android-sr-programmes): the manual's screenshot figures, English, Sunlight, font scale 2.0, a slab reached, a last month not yet closed,
 * the figures the server could not compute (dashes) and the empty state. Tier B, 360 dp wide. Record on Linux only: `-Paron.screenshots.recordTo=<abs>/src/test/screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "bn-w360dp-h800dp")
class SrStarScreenshotTests {
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

    @Composable private fun Frame(content: @Composable () -> Unit) = AronScreen(topBar = { AronTopBar(title = stringResource(ProgR.string.pg_star_title), onBack = {}) }) { content() }

    private fun star(through: String = "2026-11-10", ewd: Int = 10, projection: Long? = 7_366, pads: Long? = 283, growth: Long? = -3_987, ach: Long? = 3_683, lmads: Long? = 471, slab: Long? = null, slabName: String? = null, projected: Long? = null) = RouteStar(
        3, "Match Box Lifting Star", "ম্যাচ বক্স লিফটিং স্টার", "2026-11", through, "2026-11-11T01:00:00Z", "MD. ABDUR RAHMAN EMON", "sr22233", "Agora Goli-22233", 26, 26, ewd,
        20_000, 2_833, projection, ach, 12_250, lmads, pads, growth, "Keep Pushing", "চালিয়ে যান", slabName, slab, slabName, projected,
    )

    @Test fun star_bn() = shot("star_bn") { Frame { StarScreen(listOf(star()), UiLocale.BN) } }
    @Test fun star_en() = shot("star_en", AppLanguage.EN) { Frame { StarScreen(listOf(star()), UiLocale.EN) } }
    @Test fun star_bn_sunlight() = shot("star_bn_sunlight", sunlight = true) { Frame { StarScreen(listOf(star()), UiLocale.BN) } }
    @Test @Config(qualifiers = "bn-w360dp-h1900dp") fun star_bn_font20() = shot("star_bn_font20", fontScale = 2.0f) { Frame { StarScreen(listOf(star()), UiLocale.BN) } }
    @Test fun star_en_slab_reached() = shot("star_en_slab_reached", AppLanguage.EN) { Frame { StarScreen(listOf(star(slabName = "Gold", slab = 1_500_000, projected = 2_000_000)), UiLocale.EN) } }
    @Test fun star_bn_last_month_open() = shot("star_bn_last_month_open") { Frame { StarScreen(listOf(star(through = "2026-10-28")), UiLocale.BN) } }
    @Test fun star_bn_no_figures() = shot("star_bn_no_figures") { Frame { StarScreen(listOf(star(ewd = 0, projection = null, ach = null, lmads = null, pads = null, growth = null)), UiLocale.BN) } }
    // contract 1.26.0: "today on this phone" under the server's figures, separate from them
    @Test @Config(qualifiers = "bn-w360dp-h1100dp") fun star_bn_today() = shot("star_bn_today") { Frame { StarScreen(listOf(star()), UiLocale.BN, today = listOf(com.aktcl.aron.feature.programmes.StarToday(50, 12, 2, 0, false))) } }
    @Test @Config(qualifiers = "bn-w360dp-h1100dp") fun star_en_today_partial_unconfirmed() = shot("star_en_today_partial_unconfirmed", AppLanguage.EN) { Frame { StarScreen(listOf(star()), UiLocale.EN, today = listOf(com.aktcl.aron.feature.programmes.StarToday(50, 12, 2, 2, true))) } }
    @Test fun star_bn_empty() = shot("star_bn_empty") { Frame { StarScreen(emptyList(), UiLocale.BN, offline = true) } }
}
