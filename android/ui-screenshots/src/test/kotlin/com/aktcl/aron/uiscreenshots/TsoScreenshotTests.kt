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
import com.aktcl.aron.feature.tso.TsoHomeContent
import com.aktcl.aron.feature.tso.TsoDashboardContent
import com.aktcl.aron.feature.tso.TsoDashboardState
import com.aktcl.aron.feature.tso.TsoDashboards
import com.aktcl.aron.feature.tso.TsoFailure
import com.aktcl.aron.feature.tso.ZoneRef
import com.aktcl.aron.feature.tso.TsoSettingsContent
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.RoleHero
import com.aktcl.aron.feature.home.RoleHomes
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.feature.tso.password.ChangePasswordApi
import com.aktcl.aron.feature.tso.password.ChangePasswordScreen
import com.aktcl.aron.feature.tso.password.ChangePasswordState
import com.aktcl.aron.feature.tso.password.CpOutcome
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of the TSO screens (change password, Home, Settings and the dashboard; the area screens are in TsoAreaScreenshotTests) in their states: content,
 * empty, kept copy (offline), error, long Bangla, Sunlight (docs/32 s2a). Record on Linux only:
 * `./gradlew :android:ui-screenshots:testDebugUnitTest -Proborazzi.record=true --tests '*TsoScreenshotTests*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class TsoScreenshotTests {
    @get:Rule val rule = createComposeRule()

    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, language: AppLanguage, sunlight: Boolean = false, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true) { content() } } }
        rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    // ---- password, chrome
    @Test fun change_password_bn() = shot("tso_change_password_bn", AppLanguage.BN) {
        val st = ChangePasswordState(ChangePasswordApi { _, _ -> CpOutcome.WrongCurrent }); st.setCurrent("old-pass-1"); st.setNew("new-pass-22"); st.setConfirm("new-pass-22"); runBlocking { st.submit() }
        ChangePasswordScreen(st, onBack = {})
    }

    // ---- home and settings (existing-Aron look, PARITY-SPEC s2.3 and s2.4)
    private val tsoUser = HomeUser("টেস্টিং ঢাকা নর্থ", "tso1001", "TSO", offline = false, reauthRequired = false, updateRequired = false)
    @Composable private fun TsoHomeShot(user: HomeUser = tsoUser, bundle: BundleStatus = BundleStatus.Ready("b-17", "2026-10-05"), sync: SyncChipState = SyncChipState.SyncedAt("10:42")) = TsoHomeContent(
        RoleHomes.header(RoleHomes.TSO_PREFIX, user, "2026-10-05"), user, bundle,
        RoleHomes.tsoTiles(mapOf(RoleHomes.TsoTile.LEAVE to 1)).filter { it.id != RoleHomes.TsoTile.DEVICE_OTP }, sync, onTile = {}, onSettings = {}, hero = RoleHero.Tso(3, 12),
    )
    @Test fun home_parity_bn() = shot("tso_home_parity_bn", AppLanguage.BN) { TsoHomeShot() }
    @Test fun home_parity_offline_en() = shot("tso_home_parity_offline_en", AppLanguage.EN) { TsoHomeShot(tsoUser.copy(offline = true), BundleStatus.Offline, SyncChipState.Offline) }
    @Test fun home_parity_sunlight_bn() = shot("tso_home_parity_sunlight_bn", AppLanguage.BN, sunlight = true) { TsoHomeShot() }
    @Test fun settings_parity_bn() = shot("tso_settings_parity_bn", AppLanguage.BN) {
        TsoSettingsContent("0.1.0 (1)", "লগআউট করলে এই ফোন থেকে অ্যাপের সব তথ্য মুছে যাবে।", {}, {}, {}, sunlight = false, onSunlight = {}, onUpdate = {}, onSupport = {}, onChangePassword = {})
    }

    // ---- dashboard (kit frame: gradient top bar, grey page)
    private fun dashMoney(bangla: Boolean): (Long) -> String = { com.aktcl.aron.rules.Formats.money(it, if (bangla) com.aktcl.aron.rules.UiLocale.BN else com.aktcl.aron.rules.UiLocale.EN) }
    private fun kitShot(name: String, language: AppLanguage, title: String, sunlight: Boolean = false, content: @Composable () -> Unit) = shot(name, language, sunlight) {
        com.aktcl.aron.core.ui.aron.AronScaffold(topBar = {
            com.aktcl.aron.core.ui.aron.AronTopBar(title, icon = androidx.compose.ui.res.painterResource(com.aktcl.aron.core.ui.R.drawable.aron_art_kpi), onBack = {}, backLabel = "Back")
        }) { content() }
    }
    private val tsoJson = """{"as_of":"2026-10-17T08:30:00Z","node":{"type":"territory","id":3,"name":"T3"},
      "kpis":{"target_routes":4,"logged_in_routes":1,"login_pct":25.0,"sales_submitted_routes":0,"submit_pct_of_logged_in":0.0,"target_outlets":44,"visited_outlets":30,"successful_calls":2,"strike_rate_pct":6.67,"active_memo_count":2,"gross_mtk":900000,"net_mtk":850000,"mock_visits":0,"suspicious_visits":0},
      "by_category":[{"category_code":"cigarette","base_unit":"stick","qty_base":36100000,"report_unit":"stick","qty_report":"36100000.000","net_mtk":7000000},{"category_code":"bidi","base_unit":"stick","qty_base":6507000,"net_mtk":900000},{"category_code":"lighter","base_unit":"piece","qty_base":111000,"report_unit":"box","qty_report":1110.5,"net_mtk":100000},{"category_code":"match","base_unit":"dozen","qty_base":28067,"net_mtk":50000}],
      "by_channel":[{"code":"GT","name":"General Trade","net_mtk":6000000,"memo_count":10,"successful_calls":10},{"code":"DCC","name":"DCC","net_mtk":2500000,"memo_count":4,"successful_calls":4},{"code":"MT","name":"Modern Trade","net_mtk":500000,"memo_count":1,"successful_calls":1}],
      "by_segment":[{"code":"S1","name":"Premium","net_mtk":4000000,"qty_base":1000,"memo_count":3,"successful_calls":3},{"code":"S2","name":"Value","net_mtk":2000000,"qty_base":9000,"memo_count":9,"successful_calls":9}],
      "by_brand":[{"code":"B1","name":"Gold Leaf","net_mtk":1,"memo_count":1,"successful_calls":1,"memo_ratio_pct":50.0},{"code":"B2","name":"Navy","net_mtk":1,"memo_count":0,"successful_calls":0,"memo_ratio_pct":0.0}],"children":[]}"""
    private val tsoLogin = TsoDashboards.loginSubmit(
        """{"as_of":"2026-10-17T08:30:00Z","kpis":{"target_routes":4,"logged_in_routes":1,"login_pct":25.0,"sales_submitted_routes":0,"submit_pct_of_logged_in":0.0},"not_logged_in":[{"route_id":2,"route_code":"R2"},{"route_id":3},{"route_id":4}],"logged_in_not_submitted":[{"route_id":1,"route_code":"R1"}],"submitted":[]}""",
    )
    @Test @Config(qualifiers = "w360dp-h800dp") fun dash_tso_dashboard_en() = kitShot("tso_dashboard_en", AppLanguage.EN, "Dashboard") { TsoDashboardContent(TsoDashboardState(TsoDashboards.summary(tsoJson), tsoLogin, false, TsoFailure.NONE, zones = listOf(ZoneRef(11, "Zone 11")), businessDate = "2026-10-17"), "All my zones", dashMoney(false), {}, {}, {}, {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun dash_tso_dashboard_bn() = kitShot("tso_dashboard_bn", AppLanguage.BN, "ড্যাশবোর্ড") { TsoDashboardContent(TsoDashboardState(TsoDashboards.summary(tsoJson), tsoLogin, true, TsoFailure.NONE, businessDate = "2026-10-17"), "আমার সব জোন", dashMoney(true), {}, {}, {}, {}) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun dash_tso_dashboard_empty_offline_en() = kitShot("tso_dashboard_empty_offline_en", AppLanguage.EN, "Dashboard") { TsoDashboardContent(TsoDashboardState(null, null, false, TsoFailure.OFFLINE, loginFailure = TsoFailure.OFFLINE, businessDate = "2026-10-17"), "All my zones", dashMoney(false), {}, {}, {}, {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun dash_tso_dashboard_sunlight_bn() = kitShot("tso_dashboard_sunlight_bn", AppLanguage.BN, "ড্যাশবোর্ড", sunlight = true) { TsoDashboardContent(TsoDashboardState(TsoDashboards.summary(tsoJson), tsoLogin, false, TsoFailure.NONE, businessDate = "2026-10-17"), "আমার সব জোন", dashMoney(true), {}, {}, {}, {}) }

    // ---- dashboard drill-downs (route list behind a count, zone filter)
    private val routeRows = listOf(
        com.aktcl.aron.feature.tso.RouteDayRow(1, "DHK-R1", "ঢাকা উত্তর রুট এক", "not_logged_in", srName = "রহিম উদ্দিন"),
        com.aktcl.aron.feature.tso.RouteDayRow(2, "DHK-R2", "Route Two", "not_logged_in"),
    )
    @Test fun dash_route_list_bn() = kitShot("tso_dashboard_routes_bn", AppLanguage.BN, "ড্যাশবোর্ড") {
        com.aktcl.aron.feature.tso.TsoRouteListContent(routeRows, "2026-10-17", "2026-10-17T08:30:00Z", stale = false, failure = TsoFailure.NONE)
    }
    @Test fun dash_route_list_empty_en() = kitShot("tso_dashboard_routes_empty_en", AppLanguage.EN, "Dashboard") {
        com.aktcl.aron.feature.tso.TsoRouteListContent(emptyList(), "2026-10-17", null, stale = false, failure = TsoFailure.OFFLINE)
    }
    @Test fun dash_zone_filter_en() = kitShot("tso_dashboard_zones_en", AppLanguage.EN, "Dashboard") {
        com.aktcl.aron.feature.tso.TsoZoneFilterContent(listOf(ZoneRef(10, "Zone 10"), ZoneRef(11, "Zone 11")), 11L, {})
    }
}
