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
import com.aktcl.aron.feature.programmes.BrandKpiScreen
import com.aktcl.aron.feature.programmes.BrandName
import com.aktcl.aron.feature.programmes.FieldQty
import com.aktcl.aron.feature.programmes.KpiDashboardScreen
import com.aktcl.aron.feature.programmes.KpiKind
import com.aktcl.aron.feature.programmes.KpiModel
import com.aktcl.aron.feature.programmes.KpiToday
import com.aktcl.aron.feature.programmes.RouteFigures
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
 * Goldens of the SR KPI Dashboard and Brand wise KPI (manual 60 and 61, lane android-sr-programmes slice 3): the manual's numbers where the server defines them and the dashes where the owner has not
 * (achieved KPI, its percentage, KPI today, achieved CPR). Tier B, 360 dp wide; the dashboard is tall. Record on Linux only: `-Paron.screenshots.recordTo=<abs>/src/test/screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "bn-w360dp-h800dp")
class SrKpiScreenshotTests {
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

    @Composable private fun Frame(title: Int, content: @Composable () -> Unit) = AronScreen(topBar = { AronTopBar(title = stringResource(title), onBack = {}) }) { content() }

    private fun brand(id: Int, vt: Long, va: Long, mt: Long, ma: Long, today: String = "null") =
        """{"brand_id":$id,"volume_target":$vt,"volume_achieved":$va,"volume_bp":${if (vt > 0) va * 10000 / vt else "null"},"memo_target":$mt,"memo_achieved":$ma,"memo_bp":${if (mt > 0) ma * 10000 / mt else "null"},"kpi_alloc_mtk":100000,"kpi_today_mtk":$today,"by_pack":[{"pack_factor":20,"target":$vt,"achieved":$va}]}"""
    private val cats = """[{"category_code":"match","volume_target":600,"volume_achieved":150,"volume_bp":2500,"by_pack":[{"pack_factor":1,"target":600,"achieved":150}]},{"category_code":"lighter","volume_target":2500,"volume_achieved":1000,"volume_bp":4000,"by_pack":[{"pack_factor":25,"target":2500,"achieved":1000}]}]"""
    private fun kpi(through: String = "2026-11-17", brands: List<String> = listOf(brand(1, 21600, 4735, 119, 90), brand(2, 32000, 4121, 146, 68), brand(3, 0, 0, 5, 0), brand(4, 87000, 32400, 777, 237, "536000"), brand(5, 500, 0, 0, 0))) =
        KpiModel.build(listOf(RouteFigures.parse(
            """{"route_id":7,"kpi":{"month":"2026-11","through_date":"$through","as_of":"2026-11-18T01:00:00Z","allocated_mtk":5700000,"achieved_mtk":null,"achievement_bp":null,
              "volume":{"target":141100,"achieved":41256,"bp":2923},"memo":{"target":1102,"achieved":395,"bp":3584},"brands":[${brands.joinToString(",")}],"brands_truncated":false,"categories":$cats,
              "cpr":{"target_bp":5000,"alloc_mtk":600000,"achieved_bp":null}},
              "calendar":{"month":"2026-11","working_days":30,"elapsed_working_days_before_today":${if (through.take(7) < "2026-11") 0 else 10},"remaining_working_days_after_today":${if (through.take(7) < "2026-11") 25 else 20},"today_is_working":true,"last_month_working_days":26,"through_date":"$through","as_of":"x"}}""")!!),
            mapOf(1L to BrandName("ARIS", "অ্যারিস"), 2L to BrandName("Avon", "অ্যাভন"), 3L to BrandName("ESSE", null), 4L to BrandName("Black Diamond", "ব্ল্যাক ডায়মন্ড"), 5L to BrandName("Supreme", null), 6L to BrandName("Mixed Co", null)),
            mapOf(1L to "cigarette", 2L to "cigarette", 3L to "cigarette", 4L to "cigarette", 5L to "cigarette")) // brand 6 has no single category: its unit is not clear
    private val today = KpiToday(FieldQty(12_500, "packet"), 3)

    @Test @Config(qualifiers = "bn-w360dp-h1900dp") fun kpi_dashboard_bn() = shot("kpi_dashboard_bn") { Frame(ProgR.string.pg_kpi_title) { KpiDashboardScreen(kpi(), UiLocale.BN, volumeToday = today, memoToday = KpiToday(null, 3), unconfirmedEarlier = 2) } }
    @Test @Config(qualifiers = "bn-w360dp-h1900dp") fun kpi_dashboard_en() = shot("kpi_dashboard_en", AppLanguage.EN) { Frame(ProgR.string.pg_kpi_title) { KpiDashboardScreen(kpi(), UiLocale.EN, volumeToday = today, memoToday = KpiToday(null, 3)) } }
    @Test @Config(qualifiers = "bn-w360dp-h1900dp") fun kpi_dashboard_bn_sunlight() = shot("kpi_dashboard_bn_sunlight", sunlight = true) { Frame(ProgR.string.pg_kpi_title) { KpiDashboardScreen(kpi(), UiLocale.BN) } }
    @Test @Config(qualifiers = "bn-w360dp-h2800dp") fun kpi_dashboard_bn_font20() = shot("kpi_dashboard_bn_font20", fontScale = 2.0f) { Frame(ProgR.string.pg_kpi_title) { KpiDashboardScreen(kpi(), UiLocale.BN, volumeToday = today) } }
    @Test @Config(qualifiers = "bn-w360dp-h1900dp") fun kpi_dashboard_bn_first_of_month() = shot("kpi_dashboard_bn_first_of_month") { Frame(ProgR.string.pg_kpi_title) { KpiDashboardScreen(kpi("2026-10-31"), UiLocale.BN) } }
    @Test fun kpi_dashboard_bn_empty() = shot("kpi_dashboard_bn_empty") { Frame(ProgR.string.pg_kpi_title) { KpiDashboardScreen(null, UiLocale.BN, offline = true) } }
    @Test @Config(qualifiers = "bn-w360dp-h900dp") fun kpi_brands_bn_volume() = shot("kpi_brands_bn_volume") { Frame(ProgR.string.pg_kpi_brands_title) { BrandKpiScreen(kpi(), KpiKind.VOLUME, UiLocale.BN) } }
    @Test @Config(qualifiers = "bn-w360dp-h900dp") fun kpi_brands_bn_unit_unclear() = shot("kpi_brands_bn_unit_unclear") { Frame(ProgR.string.pg_kpi_brands_title) { BrandKpiScreen(kpi(brands = listOf(brand(1, 21600, 4735, 119, 90), brand(6, 2030, 300, 50, 20, "null"))), KpiKind.VOLUME, UiLocale.BN) } }
    @Test @Config(qualifiers = "bn-w360dp-h900dp") fun kpi_brands_bn_memo() = shot("kpi_brands_bn_memo") { Frame(ProgR.string.pg_kpi_brands_title) { BrandKpiScreen(kpi(), KpiKind.MEMO, UiLocale.BN) } }
    @Test @Config(qualifiers = "bn-w360dp-h900dp") fun kpi_brands_en_blm() = shot("kpi_brands_en_blm", AppLanguage.EN) { Frame(ProgR.string.pg_kpi_brands_title) { BrandKpiScreen(kpi(), KpiKind.BLM, UiLocale.EN) } }
    @Test @Config(qualifiers = "bn-w360dp-h1400dp") fun kpi_brands_bn_font20() = shot("kpi_brands_bn_font20", fontScale = 2.0f) { Frame(ProgR.string.pg_kpi_brands_title) { BrandKpiScreen(kpi(), KpiKind.VOLUME, UiLocale.BN) } }
}
