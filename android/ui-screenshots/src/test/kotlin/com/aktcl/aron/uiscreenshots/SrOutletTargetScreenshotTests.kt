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
import com.aktcl.aron.feature.programmes.LocalToday
import com.aktcl.aron.feature.programmes.RouteFigures
import com.aktcl.aron.feature.programmes.SkuInfo
import com.aktcl.aron.feature.programmes.SkuTargetModel
import com.aktcl.aron.feature.programmes.CampaignFigure
import com.aktcl.aron.feature.programmes.OutletCampaignView
import com.aktcl.aron.feature.programmes.OutletTargetData
import com.aktcl.aron.feature.programmes.OutletTargetPopupCard
import com.aktcl.aron.feature.programmes.OutletTargetScreen
import com.aktcl.aron.feature.programmes.OutletTargetSku
import com.aktcl.aron.feature.programmes.OutletTargetTotal
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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
 * Goldens of the SR outlet target (manual 21, lane android-sr-programmes round 2): the page (Bangla, English, Sunlight, font scale 2.0, a long Bangla name, empty, cut list, offline) and the body of the pop-up (figures, phone lines, empty, loading, failed). Tier B, 360 x 800 dp. Record on Linux only: `-Daron.screenshots.recordTo=src/test/screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "bn-w360dp-h800dp")
class SrOutletTargetScreenshotTests {
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

    @Composable private fun Frame(content: @Composable () -> Unit) = AronScreen(topBar = { AronTopBar(title = stringResource(ProgR.string.pg_ot_title), onBack = {}) }) { content() }
    @Composable private fun PopupFrame(content: @Composable () -> Unit) = AronScreen { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(16.dp)) { content() } }

    private val rows = listOf(
        OutletTargetSku(900, "Maxim", "ম্যাক্সিম", "cigarette", "packet", 20, 500_000, 120_000, 24), OutletTargetSku(901, "Avon", "অ্যাভন", "cigarette", "packet", 10, 300_000, 100_000, 33),
        OutletTargetSku(903, "Lighter", "লাইটার", "lighter", "box", 25, 100_000, 50_000, 50), OutletTargetSku(902, "Safe Match", "সেফ ম্যাচ", "match", "dozen", 1, 24_000, 6_000, 25),
    )
    private val totals = listOf(
        OutletTargetTotal("cigarette", "packet", 1_100_000, 320_000, 20, 28), OutletTargetTotal("lighter", "box", 100_000, 50_000, 25, 50), OutletTargetTotal("match", "dozen", 24_000, 6_000, 1, 25),
    )
    private fun view(label: String = "Super Star Low", labelBn: String? = "সুপার স্টার লো", today: LocalToday = LocalToday(0, 0, 0, emptyMap()), unconfirmed: Int = 0, fromFirst: Boolean = false, cats: List<com.aktcl.aron.feature.programmes.OutletTodayCategory> = emptyList(), incomplete: Boolean = false) = OutletCampaignView(
        1, label, labelBn, "2026-11", if (fromFirst) "2026-10-31" else "2026-11-17", "2026-11-18T01:00:00Z", fromFirst, CampaignFigure(1000, 300, 700), CampaignFigure(10, 4, 6), rows, if (incomplete) totals.map { it.copy(targetNum = null, soldNum = null, pct = null) } else totals, false, today, unconfirmed, 0, cats, incomplete,
    )
    private val data get() = OutletTargetData(listOf(view()), false)
    private val phone = LocalToday(65, 3, 40_000, mapOf(20 to 40L))
    private val phoneCats = listOf(com.aktcl.aron.feature.programmes.OutletTodayCategory("cigarette", "packet", 2_000, 1), com.aktcl.aron.feature.programmes.OutletTodayCategory("lighter", "box", 1_000, 1))
    private val label = "রুকু স্টোর (২০৩৪)"
    private val callLabel = "Ruku Store (2681268-01778553683-Agora Goli, Gulshan-2)"

    @Test fun outlet_target_bn_page() = shot("outlet_target_bn_page") { Frame { OutletTargetScreen(label, "রুট ৭", data, UiLocale.BN) } }
    @Test fun outlet_target_en_page() = shot("outlet_target_en_page", AppLanguage.EN) { Frame { OutletTargetScreen("Ruku Store (2034)", "Route 7", data, UiLocale.EN) } }
    @Test fun outlet_target_bn_page_phone_lines() = shot("outlet_target_bn_page_phone_lines") { Frame { OutletTargetScreen(label, "রুট ৭", OutletTargetData(listOf(view(today = phone, unconfirmed = 2, cats = phoneCats)), false), UiLocale.BN) } }
    @Test fun outlet_target_bn_page_sunlight() = shot("outlet_target_bn_page_sunlight", sunlight = true) { Frame { OutletTargetScreen(label, "রুট ৭", data, UiLocale.BN) } }
    @Test fun outlet_target_bn_page_font20() = shot("outlet_target_bn_page_font20", fontScale = 2.0f) { Frame { OutletTargetScreen(label, "রুট ৭", OutletTargetData(listOf(view(today = phone, unconfirmed = 2, cats = phoneCats)), false), UiLocale.BN) } }
    @Test fun outlet_target_bn_page_long_name() = shot("outlet_target_bn_page_long_name") {
        Frame { OutletTargetScreen("অত্যন্ত দীর্ঘ নামের একটি আউটলেট যার নাম কয়েক লাইনে ভাঙবে (৯৯৯৯)", "অত্যন্ত দীর্ঘ রুটের নাম - ঢাকা উত্তর", OutletTargetData(listOf(view(labelBn = "অত্যন্ত দীর্ঘ নামের একটি ক্যাম্পেইন যা কয়েক লাইনে ভাঙবে")), false), UiLocale.BN) }
    }
    @Test fun outlet_target_bn_page_first_of_month() = shot("outlet_target_bn_page_first_of_month") { Frame { OutletTargetScreen(label, null, OutletTargetData(listOf(view(fromFirst = true)), false), UiLocale.BN) } }
    @Test fun outlet_target_bn_page_empty() = shot("outlet_target_bn_page_empty") { Frame { OutletTargetScreen(label, null, null, UiLocale.BN) } }
    @Test fun outlet_target_bn_page_cut() = shot("outlet_target_bn_page_cut") { Frame { OutletTargetScreen(label, null, OutletTargetData(emptyList(), true), UiLocale.BN) } }
    @Test fun outlet_target_en_page_offline() = shot("outlet_target_en_page_offline", AppLanguage.EN) { Frame { OutletTargetScreen("Ruku Store (2034)", null, data, UiLocale.EN, offline = true) } }

    @Test fun outlet_target_bn_page_cut_rows() = shot("outlet_target_bn_page_cut_rows") { Frame { OutletTargetScreen(label, "রুট ৭", OutletTargetData(listOf(view(incomplete = true)), true), UiLocale.BN) } }
    @Test fun outlet_target_en_popup_cut() = shot("outlet_target_en_popup_cut", AppLanguage.EN) { PopupFrame { OutletTargetPopupCard("Ruku Store (2034)", OutletTargetData(listOf(view(incomplete = true)), true), UiLocale.EN, {}) } }
    @Test fun outlet_target_bn_popup() = shot("outlet_target_bn_popup") { PopupFrame { OutletTargetPopupCard(callLabel, data, UiLocale.BN, {}, onDetails = {}) } }
    @Test fun outlet_target_en_popup_phone_lines() = shot("outlet_target_en_popup_phone_lines", AppLanguage.EN) { PopupFrame { OutletTargetPopupCard("Ruku Store (2034)", OutletTargetData(listOf(view(today = phone, unconfirmed = 2, cats = phoneCats)), false), UiLocale.EN, {}, offline = true) } }
    @Test fun outlet_target_bn_popup_font20() = shot("outlet_target_bn_popup_font20", fontScale = 2.0f) { PopupFrame { OutletTargetPopupCard(callLabel, data, UiLocale.BN, {}, onDetails = {}) } }
    @Test fun outlet_target_bn_popup_empty() = shot("outlet_target_bn_popup_empty") { PopupFrame { OutletTargetPopupCard(callLabel, null, UiLocale.BN, {}) } }
    @Test fun outlet_target_bn_popup_loading() = shot("outlet_target_bn_popup_loading") { PopupFrame { OutletTargetPopupCard(callLabel, null, UiLocale.BN, {}, loading = true) } }
    @Test fun outlet_target_bn_popup_failed() = shot("outlet_target_bn_popup_failed") { PopupFrame { OutletTargetPopupCard(callLabel, null, UiLocale.BN, {}, failed = true) } }
}
