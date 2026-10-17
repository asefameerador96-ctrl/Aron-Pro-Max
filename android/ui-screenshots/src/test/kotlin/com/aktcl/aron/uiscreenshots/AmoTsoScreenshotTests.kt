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
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.feature.amo.AssignedTask
import com.aktcl.aron.feature.amo.AssignedTasksContent
import com.aktcl.aron.feature.amo.ExceptionDetailContent
import com.aktcl.aron.feature.amo.ExceptionsListContent
import com.aktcl.aron.feature.amo.FieldChange
import com.aktcl.aron.feature.amo.LiveDashboard
import com.aktcl.aron.feature.amo.LiveDashboardContent
import com.aktcl.aron.feature.amo.PendingRequest
import com.aktcl.aron.feature.amo.PerfBar
import com.aktcl.aron.feature.amo.Reports
import com.aktcl.aron.feature.amo.RiskSignalRow
import com.aktcl.aron.feature.amo.SalesSummaryContent
import com.aktcl.aron.feature.amo.StdMemoReportContent
import com.aktcl.aron.feature.amo.SubChannel
import com.aktcl.aron.feature.amo.TaskAssignContent
import com.aktcl.aron.feature.amo.TaskType
import com.aktcl.aron.feature.amo.TeamPerformanceContent
import com.aktcl.aron.feature.amo.TeamStock
import com.aktcl.aron.feature.amo.TeamStockDetailContent
import com.aktcl.aron.feature.amo.TeamStockListContent
import com.aktcl.aron.feature.amo.VerifyDetail
import com.aktcl.aron.feature.amo.VerifyDetailState
import com.aktcl.aron.feature.amo.VerifyFormContent
import com.aktcl.aron.feature.amo.VerifyListContent
import com.aktcl.aron.feature.tso.TsoDashboardContent
import com.aktcl.aron.feature.tso.TsoDashboardState
import com.aktcl.aron.feature.tso.TsoDashboards
import com.aktcl.aron.rules.Formats
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
 * Goldens of the AMO screens and the TSO dashboard in their states (docs/32: empty, loading is a spinner, offline or kept copy, error, long Bangla).
 * Record on Linux only: `./gradlew :android:ui-screenshots:testDebugUnitTest -Proborazzi.record=true --tests '*AmoTsoScreenshotTests*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class AmoTsoScreenshotTests {
    @get:Rule val rule = createComposeRule()

    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, language: AppLanguage, title: String, sunlight: Boolean = false, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true) {
                    AronScreen(topBar = { AronTopBar(title = title, onBack = {}) }) { content() }
                }
            }
        }
        rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    private fun money(bn: Boolean): (Long) -> String = { Formats.money(it, if (bn) UiLocale.BN else UiLocale.EN) }

    // ---- fixtures
    private val pendingNew = PendingRequest("11111111-1111-4111-8111-111111111111", "new", null, "রহিম স্টোর অ্যান্ড জেনারেল ট্রেডার্স, কাওরান বাজার", 5, "2026-10-17T03:00:00Z", 10231, 77)
    private val subs = listOf(SubChannel(1, "General store", "সাধারণ দোকান"), SubChannel(2, "Pan shop", "পানের দোকান"), SubChannel(3, "Tong", "টং"))
    private val newDetail = VerifyDetailState.Loaded(
        VerifyDetail("new", "pending", 5, mapOf("name" to "Rahim Store", "owner_name" to "Abdur Rahim", "contact_number" to "01712345678", "address" to "Kawran Bazar, Dhaka"), null, 23.75, 90.39, 1, listOf("low_accuracy")), true,
    )

    private fun verifyForm(bn: Boolean, detail: VerifyDetailState, errors: Set<com.aktcl.aron.feature.amo.VerifyError> = emptySet(), decidable: Boolean = true) = @Composable {
        VerifyFormContent(
            pendingNew, detail, emptyList<FieldChange>(), emptyList(), true, decidable, subs, bn, 2, "Urban", "", errors, false, false, false,
            {}, {}, {}, {}, {}, {},
        )
    }

    private val task = AssignedTask("t1", "stock_check", "Stock check - Rahim Store", 1, 7, "ongoing", "2026-10-19", null, null, "2026-10-17T01:00:00Z")
    private val tasks = listOf(
        task, task.copy(uuid = "t2", status = "completed", resolvedAt = "2026-10-17T09:00:00Z", resolutionNote = "Done, stock was fine"),
        task.copy(uuid = "t3", waiting = true), task.copy(uuid = "t4", rejected = true),
    )
    private val taskType = { c: String -> if (c == "stock_check") "Stock check" else c }

    private val signals = listOf(
        RiskSignalRow(1, "GEO_TELEPORT", 4, "2026-10-17", "visit", "v1", 7, 10231, 120.0, listOf("distance_m" to "820", "minutes" to "4")),
        RiskSignalRow(2, "GEO_MOCK", 3, "2026-10-17", "user", "7", 7, null, 80.0, listOf("fixes" to "3")),
        RiskSignalRow(3, "SOME_NEW_CODE", 1, "2026-10-17", "user", "7", 8, null, 5.0, emptyList()),
    )

    private val stockJson = """{"items":[{"user_id":7,"full_name":"Rahim Uddin","by_sku":[
        {"sku_id":1,"base_unit":"stick","issued_qty_base":2000,"sold_qty_base":500,"returned_qty_base":0,"current_qty_base":1500},
        {"sku_id":2,"base_unit":"piece","issued_qty_base":120,"sold_qty_base":20,"returned_qty_base":0,"current_qty_base":100}]},
        {"user_id":8,"full_name":"করিম মিয়া","by_sku":[]}]}"""
    private val skuInfo = mapOf(1L to com.aktcl.aron.feature.amo.SkuInfo("Gold Leaf 10s", "গোল্ড লিফ ১০", "cigarette"), 2L to com.aktcl.aron.feature.amo.SkuInfo("Lighter", "লাইটার", "lighter"))

    private val live = LiveDashboard.parse(
        """{"as_of":"2026-10-17T08:30:00Z","kpis":{"target_routes":10,"logged_in_routes":8,"login_pct":80.0,"sales_submitted_routes":3,"submit_pct_of_logged_in":37.5,"target_outlets":200,"visited_outlets":81,"successful_calls":1,"strike_rate_pct":1.23,"active_memo_count":1,"gross_mtk":125000000,"net_mtk":120000000,"geo_valid_pct":null,"force_sale_pct":5.0,"mock_visits":2,"suspicious_visits":1},"not_logged_in":[{"route_id":1}],"logged_in_not_submitted":[{"route_id":3}],"submitted":[]}""",
        null,
    )

    private fun reportPage(vararg rows: String, total: Int = rows.size) =
        """{"as_of":"x","columns":[{"key":"memo_no","label_en":"Memo no","label_bn":"মেমো নং","type":"string"},{"key":"business_date","label_en":"Date","label_bn":"তারিখ","type":"date"},{"key":"route_code","label_en":"Route","label_bn":"রুট","type":"string"},{"key":"outlet_name","label_en":"Outlet","label_bn":"আউটলেট","type":"string"},{"key":"line_count","label_en":"Lines","label_bn":"লাইন","type":"integer"},{"key":"gross_mtk","label_en":"Gross","label_bn":"মোট","type":"mtk"},{"key":"net_mtk","label_en":"Net","label_bn":"নিট","type":"mtk"}],"rows":[${rows.joinToString(",")}],"total_rows":$total}"""
    private val stdRows = Reports.parse(reportPage(
        """{"memo_no":"sr1001-0001","business_date":"2026-10-16","route_code":"R1","outlet_name":"Rahim Store","line_count":3,"gross_mtk":450000,"net_mtk":430000}""",
        """{"memo_no":"sr1001-0002","business_date":"2026-10-16","route_code":"R1","outlet_name":"করিম জেনারেল স্টোর","line_count":2,"gross_mtk":120000,"net_mtk":120000}""",
        """{"memo_no":"sr1002-0001","business_date":"2026-10-16","route_code":"R2","outlet_name":"Pan Ghar","line_count":1,"gross_mtk":30000,"net_mtk":30000}""",
    ))!!
    private val summaryCards = Reports.routeCards(Reports.parse(
        """{"as_of":"x","columns":[],"rows":[{"route_code":"R1","route_name":"North","target_outlets":80,"successful_calls":20,"cpr_pct":25.0,"memos":18,"net_mtk":9000000,"brand_name":"Gold Leaf","brand_memos":10,"bsr_pct":55.56},{"route_code":"R2","route_name":"দক্ষিণ রুট","target_outlets":0,"successful_calls":0,"cpr_pct":null,"memos":0,"net_mtk":0,"brand_name":null,"brand_memos":null,"bsr_pct":null}],"total_rows":2}""",
    )!!)

    private val tsoJson = """{"as_of":"2026-10-17T08:30:00Z","node":{"type":"territory","id":3,"name":"T3"},
      "kpis":{"target_routes":4,"logged_in_routes":1,"login_pct":25.0,"sales_submitted_routes":0,"submit_pct_of_logged_in":0.0,"target_outlets":44,"visited_outlets":30,"successful_calls":2,"strike_rate_pct":6.67,"active_memo_count":2,"gross_mtk":900000,"net_mtk":850000,"mock_visits":0,"suspicious_visits":0},
      "by_category":[{"category_code":"cigarette","base_unit":"stick","qty_base":36100000,"report_unit":"stick","qty_report":"36100000.000","net_mtk":7000000},{"category_code":"bidi","base_unit":"stick","qty_base":6507000,"net_mtk":900000},{"category_code":"lighter","base_unit":"piece","qty_base":111000,"report_unit":"box","qty_report":1110.5,"net_mtk":100000},{"category_code":"match","base_unit":"dozen","qty_base":28067,"net_mtk":50000}],
      "by_channel":[{"code":"GT","name":"General Trade","net_mtk":6000000,"memo_count":10,"successful_calls":10},{"code":"DCC","name":"DCC","net_mtk":2500000,"memo_count":4,"successful_calls":4},{"code":"MT","name":"Modern Trade","net_mtk":500000,"memo_count":1,"successful_calls":1}],
      "by_segment":[{"code":"S1","name":"Premium","net_mtk":4000000,"qty_base":1000,"memo_count":3,"successful_calls":3},{"code":"S2","name":"Value","net_mtk":2000000,"qty_base":9000,"memo_count":9,"successful_calls":9}],
      "by_brand":[{"code":"B1","name":"Gold Leaf","net_mtk":1,"memo_count":1,"successful_calls":1,"memo_ratio_pct":50.0},{"code":"B2","name":"Navy","net_mtk":1,"memo_count":0,"successful_calls":0,"memo_ratio_pct":0.0}],"children":[]}"""
    private val tsoLogin = TsoDashboards.loginSubmit(
        """{"as_of":"2026-10-17T08:30:00Z","kpis":{"target_routes":4,"logged_in_routes":1,"login_pct":25.0,"sales_submitted_routes":0,"submit_pct_of_logged_in":0.0},"not_logged_in":[{"route_id":2,"route_code":"R2"},{"route_id":3},{"route_id":4}],"logged_in_not_submitted":[{"route_id":1,"route_code":"R1"}],"submitted":[]}""",
    )

    // ---- Verify
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_verify_form_new_en() = shot("amo_verify_form_new_en", AppLanguage.EN, "Verify requests", content = verifyForm(false, newDetail))
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_verify_form_new_bn_long() = shot("amo_verify_form_new_bn", AppLanguage.BN, "অনুরোধ যাচাই", content = verifyForm(true, newDetail, setOf(com.aktcl.aron.feature.amo.VerifyError.GEO_CLASS_REQUIRED)))
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_verify_form_offline_en() = shot("amo_verify_form_offline_en", AppLanguage.EN, "Verify requests", content = verifyForm(false, VerifyDetailState.Unavailable))
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_verify_list_en() = shot("amo_verify_list_en", AppLanguage.EN, "Verify requests") { VerifyListContent(listOf(pendingNew, pendingNew.copy(uuid = "x", type = "close", outletName = "Pan Ghar")), mapOf(5L to "Rahim Uddin"), {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_verify_list_empty_bn() = shot("amo_verify_list_empty_bn", AppLanguage.BN, "অনুরোধ যাচাই") { VerifyListContent(emptyList(), emptyMap(), {}) }

    // ---- Exceptions
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_exceptions_list_en() = shot("amo_exceptions_list_en", AppLanguage.EN, "Exceptions") { ExceptionsListContent(signals, mapOf(7L to "Rahim Uddin", 8L to "Karim"), {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_exceptions_empty_bn() = shot("amo_exceptions_empty_bn", AppLanguage.BN, "ব্যতিক্রম") { ExceptionsListContent(emptyList(), emptyMap(), {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_exception_detail_bn() = shot("amo_exception_detail_bn", AppLanguage.BN, "ব্যতিক্রম") { ExceptionDetailContent(signals[0], "রহিম উদ্দিন", "", false, true, {}, {}) }

    // ---- Tasks
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_tasks_en() = shot("amo_tasks_en", AppLanguage.EN, "Task Delegation") { AssignedTasksContent(tasks, mapOf(7L to "Rahim Uddin"), mapOf(1L to "Rahim Store"), taskType, false, false, {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_tasks_offline_bn() = shot("amo_tasks_offline_bn", AppLanguage.BN, "কাজ বণ্টন") { AssignedTasksContent(emptyList(), emptyMap(), emptyMap(), taskType, false, true, {}) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_task_assign_en() = shot("amo_task_assign_en", AppLanguage.EN, "Task Delegation") {
        TaskAssignContent("Rahim Uddin", listOf("Rahim Store", "R-1042", "Kawran Bazar"), listOf(TaskType("stock_check", "Stock check", "স্টক পরীক্ষা"), TaskType("price", "Price check", "দাম যাচাই")), false, "stock_check", listOf("2026-10-18", "2026-10-19"), "2026-10-18", "", emptySet(), false, false, {}, {}, {}, {})
    }

    // ---- Team stock, live dashboard, performance
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_stock_list_en() = shot("amo_team_stock_list_en", AppLanguage.EN, "SR Stock") { TeamStockListContent(TeamStock.parse(stockJson), mapOf(7L to "017****678"), false, false, {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_stock_detail_bn() = shot("amo_team_stock_detail_bn", AppLanguage.BN, "এসআর স্টক") {
        val sr = TeamStock.parse(stockJson).first { it.userId == 7L }; TeamStockDetailContent(sr, skuInfo, TeamStock.totals(sr.lines, skuInfo), true)
    }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_live_dashboard_en() = shot("amo_live_dashboard_en", AppLanguage.EN, "Live Dashboard") { LiveDashboardContent(live, "14:30", "All my zones", false, false, false, money(false), {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_live_dashboard_kept_bn() = shot("amo_live_dashboard_kept_bn", AppLanguage.BN, "লাইভ ড্যাশবোর্ড") { LiveDashboardContent(live, "14:30", "আমার সব জোন", true, true, false, money(true), {}) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_live_dashboard_empty_en() = shot("amo_live_dashboard_empty_en", AppLanguage.EN, "Live Dashboard") { LiveDashboardContent(null, null, "All my zones", false, false, true, money(false), {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_performance_bn() = shot("amo_team_performance_bn", AppLanguage.BN, "টিমের পারফরম্যান্স") {
        TeamPerformanceContent(listOf(PerfBar("সিগারেট", "stick", 1000.0, 850.0, 150.0, 85.0), PerfBar("বিড়ি", "stick", 500.0, 230.0, 270.0, 46.0), PerfBar("লাইটার", "piece", 100.0, 10.0, 90.0, 10.0), PerfBar("ম্যাচ", "dozen", 0.0, 3.0, 0.0, null)), false, {}, false, false)
    }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_performance_empty_en() = shot("amo_team_performance_empty_en", AppLanguage.EN, "Team Performance") { TeamPerformanceContent(emptyList(), true, {}, false, false) }

    // ---- Reports
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_std_memo_en() = shot("amo_std_memo_en", AppLanguage.EN, "STD Memo Report") { StdMemoReportContent("2026-10-01" to "2026-10-16", stdRows, Reports.stdByRoute(stdRows), false, money(false), false, false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_std_memo_empty_bn() = shot("amo_std_memo_empty_bn", AppLanguage.BN, "এসটিডি মেমো রিপোর্ট") { StdMemoReportContent("2026-10-01" to "2026-10-16", null, emptyList(), true, money(true), false, false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_sales_summary_bn() = shot("amo_sales_summary_bn", AppLanguage.BN, "বিক্রির সারসংক্ষেপ") { SalesSummaryContent(summaryCards, money(true), true, false, {}) }

    // ---- TSO dashboard
    @Test @Config(qualifiers = "w360dp-h800dp") fun tso_dashboard_en() = shot("tso_dashboard_en", AppLanguage.EN, "Dashboard") { TsoDashboardContent(TsoDashboardState(TsoDashboards.summary(tsoJson), tsoLogin, false, com.aktcl.aron.feature.tso.TsoFailure.NONE, zones = listOf(com.aktcl.aron.feature.tso.ZoneRef(11, "Zone 11")), businessDate = "2026-10-17"), "All my zones", money(false), {}, {}, {}, {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun tso_dashboard_bn() = shot("tso_dashboard_bn", AppLanguage.BN, "ড্যাশবোর্ড") { TsoDashboardContent(TsoDashboardState(TsoDashboards.summary(tsoJson), tsoLogin, true, com.aktcl.aron.feature.tso.TsoFailure.NONE, businessDate = "2026-10-17"), "আমার সব জোন", money(true), {}, {}, {}, {}) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun tso_dashboard_empty_offline_en() = shot("tso_dashboard_empty_offline_en", AppLanguage.EN, "Dashboard") { TsoDashboardContent(TsoDashboardState(null, null, false, com.aktcl.aron.feature.tso.TsoFailure.OFFLINE, loginFailure = com.aktcl.aron.feature.tso.TsoFailure.OFFLINE, businessDate = "2026-10-17"), "All my zones", money(false), {}, {}, {}, {}) }

    // ---- Sunlight mode (outdoor-first: solid surfaces for figures, key numbers at 7:1)
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun tso_dashboard_sunlight_bn() = shot("tso_dashboard_sunlight_bn", AppLanguage.BN, "ড্যাশবোর্ড", sunlight = true) { TsoDashboardContent(TsoDashboardState(TsoDashboards.summary(tsoJson), tsoLogin, false, com.aktcl.aron.feature.tso.TsoFailure.NONE, businessDate = "2026-10-17"), "আমার সব জোন", money(true), {}, {}, {}, {}) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_live_dashboard_sunlight_en() = shot("amo_live_dashboard_sunlight_en", AppLanguage.EN, "Live Dashboard", sunlight = true) { LiveDashboardContent(live, "14:30", "All my zones", false, false, false, money(false), {}) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_verify_form_sunlight_en() = shot("amo_verify_form_sunlight_en", AppLanguage.EN, "Verify requests", sunlight = true, content = verifyForm(false, newDetail))
}
