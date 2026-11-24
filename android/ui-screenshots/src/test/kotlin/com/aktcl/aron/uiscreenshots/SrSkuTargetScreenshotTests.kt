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
import com.aktcl.aron.feature.programmes.SkuTargetScreen
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
/**
 * Goldens of the SR SKU target screen (manual 10, lane android-sr-programmes slice 2): the manual's K-13 fixture (Maxim 500, Avon 900 ...), English, Sunlight, font scale 2.0, a part-way month with
 * the phone's own lines, the first of the month, no calendar, empty and offline. Same frame, tier B, 360 x 800 dp. Record on Linux only: `-Daron.screenshots.recordTo=src/test/screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "bn-w360dp-h800dp")
class SrSkuTargetScreenshotTests {
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

    @Composable private fun Frame(content: @Composable () -> Unit) = AronScreen(topBar = { AronTopBar(title = stringResource(ProgR.string.pg_tgt_title), onBack = {}) }) { content() }

    private val skus = mapOf(
        1L to SkuInfo(1, "Maxim", "ম্যাক্সিম", "cigarette", "stick", 1, "stick"), 2L to SkuInfo(2, "Avon", "অ্যাভন", "cigarette", "stick", 1, "stick"),
        3L to SkuInfo(3, "Marise", "মারিস", "cigarette", "stick", 20, "pack"), 4L to SkuInfo(4, "Safe Match", "সেফ ম্যাচ", "match", "dozen", 1, "dozen"),
        5L to SkuInfo(5, "Super Long Brand Name Cigarette Special Edition", "অত্যন্ত দীর্ঘ নামের একটি ব্র্যান্ডের বিশেষ সংস্করণ সিগারেট", "cigarette", "stick", 10, "pack"),
    )
    private fun t(id: Long, q: String) = """{"scope_type":"route","scope_id":7,"month":"2026-11","product_level":"sku","product_id":$id,"std_target":"$q","std_unit":"stick","memo_target":0}"""
    private fun a(id: Long, q: Long) = """{"scope_type":"route","scope_id":7,"month":"2026-11","product_level":"sku","product_id":$id,"achieved_qty_base":$q,"memo_count":1,"as_of":"2026-11-18T01:00:00Z","through_date":"2026-11-17"}"""
    private fun cal(through: String) = """"calendar":{"month":"2026-11","working_days":26,"elapsed_working_days_before_today":14,"remaining_working_days_after_today":11,"today_is_working":true,"last_month_working_days":26,"through_date":"$through","as_of":"2026-11-18T01:00:00Z"}"""
    private fun route(targets: List<String>, achieved: List<String> = emptyList(), calendar: String? = cal("2026-11-17")) =
        RouteFigures.parse("""{"route_id":7,"targets":[${targets.joinToString(",")}],"achievement_mtd":[${achieved.joinToString(",")}]${calendar?.let { ",$it" } ?: ""}}""")!!
    // the manual's fixture: all targets, nothing achieved yet (screenshot 10)
    private val k13 get() = SkuTargetModel.build(listOf(route(listOf(t(1, "500.000"), t(2, "900.000"), t(3, "9000.000"), t(4, "30.000")))), skus)
    private val partWay get() = SkuTargetModel.build(listOf(route(listOf(t(1, "500.000"), t(2, "900.000"), t(3, "9000.000"), t(4, "30.000")), listOf(a(1, 620), a(2, 410), a(3, 3300), a(4, 12)))), skus)
    private val today = mapOf(3L to LocalToday(400, 3, 40_000, mapOf(20 to 400L)), 1L to LocalToday(50, 1, 5_000, mapOf(1 to 50L)))

    @Test fun sku_target_bn_k13() = shot("sku_target_bn_k13") { Frame { SkuTargetScreen(k13, UiLocale.BN) } }
    @Test fun sku_target_bn_part_way() = shot("sku_target_bn_part_way") { Frame { SkuTargetScreen(partWay, UiLocale.BN, todayBySku = today, unconfirmedEarlier = 2) } }
    @Test fun sku_target_en_part_way() = shot("sku_target_en_part_way", AppLanguage.EN) { Frame { SkuTargetScreen(partWay, UiLocale.EN, todayBySku = today, unconfirmedEarlier = 2) } }
    @Test fun sku_target_bn_sunlight() = shot("sku_target_bn_sunlight", sunlight = true) { Frame { SkuTargetScreen(partWay, UiLocale.BN, todayBySku = today) } }
    @Test fun sku_target_bn_font20() = shot("sku_target_bn_font20", fontScale = 2.0f) { Frame { SkuTargetScreen(partWay, UiLocale.BN, todayBySku = today, unconfirmedEarlier = 2) } }
    @Test fun sku_target_bn_long_name() = shot("sku_target_bn_long_name") { Frame { SkuTargetScreen(SkuTargetModel.build(listOf(route(listOf(t(5, "4000.000")), listOf(a(5, 1500)))), skus), UiLocale.BN) } }
    @Test fun sku_target_bn_first_of_month() = shot("sku_target_bn_first_of_month") { Frame { SkuTargetScreen(SkuTargetModel.build(listOf(route(listOf(t(1, "500.000")), calendar = cal("2026-10-31"))), skus), UiLocale.BN) } }
    @Test fun sku_target_bn_no_calendar() = shot("sku_target_bn_no_calendar") { Frame { SkuTargetScreen(SkuTargetModel.build(listOf(route(listOf(t(1, "500.000")), calendar = null)), skus), UiLocale.BN) } }
    @Test fun sku_target_bn_empty() = shot("sku_target_bn_empty") { Frame { SkuTargetScreen(null, UiLocale.BN) } }
    @Test fun sku_target_en_offline() = shot("sku_target_en_offline", AppLanguage.EN) { Frame { SkuTargetScreen(partWay, UiLocale.EN, offline = true) } }
}
