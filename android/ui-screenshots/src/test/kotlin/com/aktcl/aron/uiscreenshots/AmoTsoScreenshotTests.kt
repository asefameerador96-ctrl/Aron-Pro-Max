package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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

    private fun shot(name: String, language: AppLanguage, title: String, sunlight: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true, amo = true) { // D-AMO-MAN-03
                    com.aktcl.aron.feature.amo.AmoFrame(title, onBack = {}) { content() }
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

    private fun verifyForm(bn: Boolean, detail: VerifyDetailState, errors: Set<com.aktcl.aron.feature.amo.AmoFormError> = emptySet(), decidable: Boolean = true) = @Composable {
        val none = emptySet<com.aktcl.aron.feature.amo.AmoFormError>()
        val items = listOf(
            com.aktcl.aron.feature.amo.routeItem(com.aktcl.aron.feature.amo.Pick(listOf(com.aktcl.aron.feature.amo.AmoOption(1, "Savar BazarDaily")), 1, {}, false), none),
            com.aktcl.aron.feature.amo.clusterItem(com.aktcl.aron.feature.amo.Pick(listOf(com.aktcl.aron.feature.amo.AmoOption(77, "Madrasa Road")), 77, {}, false), none),
        ) + com.aktcl.aron.feature.amo.shopReadOnlyItems("Rahim Store", "Abdur Rahim", "01712345678") +
            com.aktcl.aron.feature.amo.classificationItems(com.aktcl.aron.feature.amo.Pick(subs.map { com.aktcl.aron.feature.amo.AmoOption(it.id, if (bn) it.labelBn ?: it.labelEn else it.labelEn) }, 2, {}), "Urban", {}, errors) +
            com.aktcl.aron.feature.amo.captureItem(com.aktcl.aron.feature.outlet.GeoPhotoState(), {}, errors)
        VerifyFormContent(pendingNew, detail, emptyList<FieldChange>(), emptyList(), items, decidable, false, false, false, {}, {})
    }

    private fun verifyFormOf(type: String, bn: Boolean) = @Composable {
        val none = emptySet<com.aktcl.aron.feature.amo.AmoFormError>()
        val items = listOf(
            com.aktcl.aron.feature.amo.routeItem(com.aktcl.aron.feature.amo.Pick(listOf(com.aktcl.aron.feature.amo.AmoOption(1, "Savar BazarDaily")), 1, {}, false), none),
            com.aktcl.aron.feature.amo.clusterItem(com.aktcl.aron.feature.amo.Pick(listOf(com.aktcl.aron.feature.amo.AmoOption(77, "Madrasa Road")), 77, {}, false), none),
        ) + com.aktcl.aron.feature.amo.shopReadOnlyItems("Siyam", "Robiul", "01832714554") + if (type == "close") emptyList() else
            com.aktcl.aron.feature.amo.classificationItems(com.aktcl.aron.feature.amo.Pick(subs.map { com.aktcl.aron.feature.amo.AmoOption(it.id, if (bn) it.labelBn ?: it.labelEn else it.labelEn) }, 1, {}), "Urban", {}, none)
        VerifyFormContent(pendingNew.copy(type = type), VerifyDetailState.Unavailable, emptyList<FieldChange>(), emptyList(), items, true, false, false, false, {}, {})
    }

    private fun listRows(vararg r: PendingRequest) = r.map {
        com.aktcl.aron.feature.amo.VerifyListRow(it, "Savar BazarDaily", "Madrasa Road", it.outletName, "Abdur Rahim", "01712345678")
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


    // ---- Verify
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_verify_form_new_en() = shot("amo_verify_form_new_en", AppLanguage.EN, "Verify requests", content = verifyForm(false, newDetail))
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_verify_form_new_bn_long() = shot("amo_verify_form_new_bn", AppLanguage.BN, "অনুরোধ যাচাই", content = verifyForm(true, newDetail, setOf(com.aktcl.aron.feature.amo.AmoFormError.GEO_CLASS)))
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_verify_form_close_bn() = shot("amo_verify_form_close_bn", AppLanguage.BN, "স্থায়ী বন্ধ", content = verifyFormOf("close", true))
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_verify_form_info_bn() = shot("amo_verify_form_info_bn", AppLanguage.BN, "তথ্য পরিবর্তন করুন", content = verifyFormOf("info", true))
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_verify_form_offline_en() = shot("amo_verify_form_offline_en", AppLanguage.EN, "Verify requests", content = verifyForm(false, VerifyDetailState.Unavailable))
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_verify_list_en() = shot("amo_verify_list_en", AppLanguage.EN, "Verify requests") { VerifyListContent(listRows(pendingNew, pendingNew.copy(uuid = "x", type = "close", outletName = "Pan Ghar")), {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_verify_list_empty_bn() = shot("amo_verify_list_empty_bn", AppLanguage.BN, "অনুরোধ যাচাই") { VerifyListContent(emptyList(), {}) }

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

    // ---- Loading (the AMO hosts show the quiet kit skeleton, not a spinner)
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_loading_bn() = shot("amo_loading_bn", AppLanguage.BN, "কাজ বণ্টন") { com.aktcl.aron.core.ui.aron.AronLoadingScreen() }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_loading_sunlight_en() = shot("amo_loading_sunlight_en", AppLanguage.EN, "SR Stock", sunlight = true) { com.aktcl.aron.core.ui.aron.AronLoadingScreen() }

    // ---- Team stock, live dashboard, performance
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_stock_list_en() = shot("amo_team_stock_list_en", AppLanguage.EN, "SR Stock") { TeamStockListContent(TeamStock.parse(stockJson), mapOf(7L to "017****678"), false, false, {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_stock_detail_bn() = shot("amo_team_stock_detail_bn", AppLanguage.BN, "এসআর স্টক") {
        val sr = TeamStock.parse(stockJson).first { it.userId == 7L }; TeamStockDetailContent(sr, skuInfo, TeamStock.totals(sr.lines, skuInfo), true)
    }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_performance_bn() = shot("amo_team_performance_bn", AppLanguage.BN, "টিমের পারফরম্যান্স") {
        TeamPerformanceContent(listOf(PerfBar("সিগারেট", "stick", 1000.0, 850.0, 150.0, 85.0), PerfBar("বিড়ি", "stick", 500.0, 230.0, 270.0, 46.0), PerfBar("লাইটার", "piece", 100.0, 10.0, 90.0, 10.0), PerfBar("ম্যাচ", "dozen", 0.0, 3.0, 0.0, null)), false, {}, false, false)
    }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_performance_empty_en() = shot("amo_team_performance_empty_en", AppLanguage.EN, "Team Performance") { TeamPerformanceContent(emptyList(), true, {}, false, false) }

    // ---- Reports
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_std_memo_en() = shot("amo_std_memo_en", AppLanguage.EN, "STD Memo Report") { StdMemoReportContent("2026-10-01" to "2026-10-16", stdRows, Reports.stdByRoute(stdRows), false, money(false), false, false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_std_memo_empty_bn() = shot("amo_std_memo_empty_bn", AppLanguage.BN, "এসটিডি মেমো রিপোর্ট") { StdMemoReportContent("2026-10-01" to "2026-10-16", null, emptyList(), true, money(true), false, false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_sales_summary_bn() = shot("amo_sales_summary_bn", AppLanguage.BN, "বিক্রির সারসংক্ষেপ") { SalesSummaryContent(summaryCards, money(true), true, false, {}) }

    // ---- TSO dashboard

    // ---- Sunlight mode (outdoor-first: solid surfaces for figures, key numbers at 7:1)
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_verify_form_sunlight_en() = shot("amo_verify_form_sunlight_en", AppLanguage.EN, "Verify requests", sunlight = true, content = verifyForm(false, newDetail))

    // ---- long Bangla at 200% font scale (3-line wrap) and Sunlight for every screen with a figure, a status or a primary action
    private val longVerify = listOf(
        pendingNew.copy(outletName = "রহিম স্টোর অ্যান্ড জেনারেল ট্রেডার্স, কাওরান বাজার, তেজগাঁও শিল্প এলাকা সংলগ্ন, ঢাকা-১২১৫"),
        pendingNew.copy(uuid = "x", type = "close", outletName = "পান ঘর"),
    )
    private fun teamHub(sunlight: Boolean = false) = @Composable {
        com.aktcl.aron.feature.amo.HubListContent(listOf(com.aktcl.aron.feature.amo.HubSection(null, listOf(
            com.aktcl.aron.feature.amo.HubRow("টিম লোকেশন", icon = com.aktcl.aron.core.ui.R.drawable.aron_ic_team_location), com.aktcl.aron.feature.amo.HubRow("টিম পারফর্মেন্স", icon = com.aktcl.aron.core.ui.R.drawable.aron_ic_team_performance), com.aktcl.aron.feature.amo.HubRow("টাস্ক ডেলিগেশন", icon = com.aktcl.aron.core.ui.R.drawable.aron_art_task_delegation),
            com.aktcl.aron.feature.amo.HubRow("লাইভ ডেটা", icon = com.aktcl.aron.core.ui.R.drawable.aron_ic_live_dashboard), com.aktcl.aron.feature.amo.HubRow("এসআর স্টক", icon = com.aktcl.aron.core.ui.R.drawable.aron_ic_sr_stock), com.aktcl.aron.feature.amo.HubRow("ব্যতিক্রম", icon = com.aktcl.aron.core.ui.R.drawable.aron_ic_exceptions),
        ))))
    }
    private fun outletHub() = @Composable {
        com.aktcl.aron.feature.amo.AmoOutletMenuContent(
            mapOf(com.aktcl.aron.feature.amo.VerifyGroup.NEW to 3, com.aktcl.aron.feature.amo.VerifyGroup.CLOSE to 1), {}, {}, onOwn = {},
        )
    }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_hub_bn_fs2() = shot("amo_team_hub_bn_fs2", AppLanguage.BN, "টিম", fontScale = 2f, content = teamHub())
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_hub_sunlight_bn() = shot("amo_team_hub_sunlight_bn", AppLanguage.BN, "টিম", sunlight = true, content = teamHub())
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_outlet_hub_bn() = shot("amo_outlet_hub_bn", AppLanguage.BN, "আউটলেট", content = outletHub())
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_outlet_hub_bn_fs2() = shot("amo_outlet_hub_bn_fs2", AppLanguage.BN, "আউটলেট", fontScale = 2f, content = outletHub())
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_outlet_hub_sunlight_bn() = shot("amo_outlet_hub_sunlight_bn", AppLanguage.BN, "আউটলেট", sunlight = true, content = outletHub())
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_verify_list_bn_fs2() = shot("amo_verify_list_bn_fs2", AppLanguage.BN, "অনুরোধ যাচাই", fontScale = 2f) { VerifyListContent(listRows(*longVerify.toTypedArray()), {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_verify_list_sunlight_bn() = shot("amo_verify_list_sunlight_bn", AppLanguage.BN, "অনুরোধ যাচাই", sunlight = true) { VerifyListContent(listRows(*longVerify.toTypedArray()), {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_verify_form_bn_fs2() = shot("amo_verify_form_bn_fs2", AppLanguage.BN, "অনুরোধ যাচাই", fontScale = 2f, content = verifyForm(true, newDetail, setOf(com.aktcl.aron.feature.amo.AmoFormError.GEO_CLASS)))
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_tasks_sunlight_bn() = shot("amo_tasks_sunlight_bn", AppLanguage.BN, "টাস্ক ডেলিগেশন", sunlight = true) { AssignedTasksContent(tasks, mapOf(7L to "রহিম উদ্দিন"), mapOf(1L to "রহিম স্টোর"), taskType, false, false, {}) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_task_assign_sunlight_en() = shot("amo_task_assign_sunlight_en", AppLanguage.EN, "Task Delegation", sunlight = true) {
        TaskAssignContent("Rahim Uddin", listOf("Rahim Store", "R-1042", "Kawran Bazar"), listOf(TaskType("stock_check", "Stock check", "স্টক পরীক্ষা"), TaskType("price", "Price check", "দাম যাচাই")), false, "stock_check", listOf("2026-10-18", "2026-10-19"), "2026-10-18", "", emptySet(), false, false, {}, {}, {}, {})
    }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_exceptions_list_sunlight_en() = shot("amo_exceptions_list_sunlight_en", AppLanguage.EN, "Exceptions", sunlight = true) { ExceptionsListContent(signals, mapOf(7L to "Rahim Uddin", 8L to "Karim"), {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_exception_detail_sunlight_bn() = shot("amo_exception_detail_sunlight_bn", AppLanguage.BN, "ব্যতিক্রম", sunlight = true) { ExceptionDetailContent(signals[0], "রহিম উদ্দিন", "", false, true, {}, {}) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_stock_list_sunlight_en() = shot("amo_team_stock_list_sunlight_en", AppLanguage.EN, "SR Stock", sunlight = true) { TeamStockListContent(TeamStock.parse(stockJson), mapOf(7L to "017****678"), false, false, {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_stock_detail_sunlight_bn() = shot("amo_team_stock_detail_sunlight_bn", AppLanguage.BN, "এসআর স্টক", sunlight = true) {
        val sr = TeamStock.parse(stockJson).first { it.userId == 7L }; TeamStockDetailContent(sr, skuInfo, TeamStock.totals(sr.lines, skuInfo), true)
    }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_performance_sunlight_bn() = shot("amo_team_performance_sunlight_bn", AppLanguage.BN, "টিম পারফর্মেন্স", sunlight = true) {
        TeamPerformanceContent(listOf(PerfBar("সিগারেট", "stick", 1000.0, 850.0, 150.0, 85.0), PerfBar("বিড়ি", "stick", 500.0, 230.0, 270.0, 46.0)), false, {}, false, false)
    }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_std_memo_sunlight_en() = shot("amo_std_memo_sunlight_en", AppLanguage.EN, "STD Memo Report", sunlight = true) { StdMemoReportContent("2026-10-01" to "2026-10-16", stdRows, Reports.stdByRoute(stdRows), false, money(false), false, false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_sales_summary_sunlight_bn() = shot("amo_sales_summary_sunlight_bn", AppLanguage.BN, "এখন পর্যন্ত বিক্রয় সারসংক্ষেপ", sunlight = true) { SalesSummaryContent(summaryCards, money(true), true, false, {}) }
}
