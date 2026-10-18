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
import com.aktcl.aron.feature.tso.feedback.FeedbackCategory
import com.aktcl.aron.feature.tso.feedback.FeedbackFormScreen
import com.aktcl.aron.feature.tso.feedback.FeedbackFormState
import com.aktcl.aron.feature.tso.feedback.FeedbackItem
import com.aktcl.aron.feature.tso.feedback.FeedbackListScreen
import com.aktcl.aron.feature.tso.feedback.FeedbackListState
import com.aktcl.aron.feature.tso.feedback.FeedbackSource
import com.aktcl.aron.feature.tso.feedback.FeedbackStatus
import com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitApi
import com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitScreen
import com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitState
import com.aktcl.aron.feature.tso.finalsubmit.FsPreview
import com.aktcl.aron.feature.tso.finalsubmit.FsPreviewOutcome
import com.aktcl.aron.feature.tso.finalsubmit.FsResult
import com.aktcl.aron.feature.tso.finalsubmit.FsRoute
import com.aktcl.aron.feature.tso.finalsubmit.FsSubmitOutcome
import com.aktcl.aron.feature.tso.leave.LeaveItem
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
import com.aktcl.aron.feature.tso.leave.LeaveListScreen
import com.aktcl.aron.feature.tso.leave.LeaveListState
import com.aktcl.aron.feature.tso.leave.LeaveSource
import com.aktcl.aron.feature.tso.leave.LeaveStatus
import com.aktcl.aron.feature.tso.mycall.Criterion
import com.aktcl.aron.feature.tso.mycall.PlanOutlet
import com.aktcl.aron.feature.tso.mycall.PlanRoute
import com.aktcl.aron.feature.tso.mycall.PlanSource
import com.aktcl.aron.feature.tso.mycall.PlanStatus
import com.aktcl.aron.feature.tso.mycall.PlannedOutlet
import com.aktcl.aron.feature.tso.mycall.Rubric
import com.aktcl.aron.feature.tso.mycall.RouteSr
import com.aktcl.aron.feature.tso.mycall.SetPlanScreen
import com.aktcl.aron.feature.tso.mycall.SetPlanState
import com.aktcl.aron.feature.tso.mycall.VisitPlanItem
import com.aktcl.aron.feature.tso.mycall.VisitQueryScreen
import com.aktcl.aron.feature.tso.mycall.VisitQueryState
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
 * Goldens of the TSO screens (leave, feedback, My Call, Final Submit, change password, Home and Settings) in their states: content,
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

    // ---- leave
    private fun leave(vararg i: LeaveItem, cached: Boolean = false, fresh: Boolean = true, unsent: List<LeaveItem> = emptyList()) = object : LeaveSource {
        override suspend fun cached() = if (cached) LeaveSource.Cached(i.toList(), "09:30") else null
        override suspend fun unsent() = unsent
        override suspend fun refresh() = if (fresh) LeaveSource.Cached(i.toList(), "10:42") else null
    }
    private val longReason = "পারিবারিক জরুরি প্রয়োজনে গ্রামের বাড়িতে যেতে হচ্ছে, তাই আগামী সপ্তাহের সব কাজ অন্য সহকর্মীর হাতে বুঝিয়ে দিয়ে যাচ্ছি এবং ফিরে এসে আবার যোগ দেব"
    private val leaves = arrayOf(
        LeaveItem("a", "casual", "2026-10-20", "2026-10-22", 3, "family", LeaveStatus.PENDING),
        LeaveItem("b", "sick", "2026-10-01", "2026-10-02", 2, longReason, LeaveStatus.APPROVED),
        LeaveItem("c", "earn", "2026-09-10", "2026-09-10", 1, "trip", LeaveStatus.REJECTED),
    )

    @Test fun leave_list_bn() = shot("tso_leave_list_bn", AppLanguage.BN) { LeaveListScreen(LeaveListState(leave(*leaves, unsent = listOf(LeaveItem("u", "casual", "2026-10-25", "2026-10-25", 1, "অসুস্থ", LeaveStatus.PENDING, sent = false)))), onBack = {}, onApply = {}) }
    @Test fun leave_list_sunlight_en() = shot("tso_leave_list_sunlight_en", AppLanguage.EN, sunlight = true) { LeaveListScreen(LeaveListState(leave(*leaves)), onBack = {}, onApply = {}) }
    @Test fun leave_empty_en() = shot("tso_leave_empty_en", AppLanguage.EN) { LeaveListScreen(LeaveListState(leave()), onBack = {}, onApply = {}) }
    @Test fun leave_offline_bn() = shot("tso_leave_offline_bn", AppLanguage.BN) { LeaveListScreen(LeaveListState(leave(*leaves, cached = true, fresh = false)), onBack = {}) }
    @Test fun leave_error_en() = shot("tso_leave_error_en", AppLanguage.EN) { LeaveListScreen(LeaveListState(leave(fresh = false)), onBack = {}) }

    // ---- feedback
    private val cats = listOf(FeedbackCategory("suggestion", "পরামর্শ", "Suggestion", 1), FeedbackCategory("complaint", "অভিযোগ", "Complaint", 2), FeedbackCategory("bug", null, "Bug", 3))
    private fun fb(items: List<FeedbackItem>, fresh: Boolean = true) = object : FeedbackSource {
        override suspend fun categories() = cats
        override suspend fun cached(): FeedbackSource.Cached? = null
        override suspend fun unsent() = emptyList<FeedbackItem>()
        override suspend fun refresh() = if (fresh) FeedbackSource.Cached(items, "10:42") else null
    }
    @Test fun feedback_list_bn() = shot("tso_feedback_list_bn", AppLanguage.BN) {
        FeedbackListScreen(FeedbackListState(fb(listOf(FeedbackItem("a", "suggestion", "অ্যাপ আরও দ্রুত হলে ভালো হয়", "d", "2026-10-10T00:00:00Z", FeedbackStatus.IN_PROGRESS), FeedbackItem("b", "complaint", "Printer problem", "d", "2026-10-02T00:00:00Z", FeedbackStatus.RESOLVED)))), cats, onBack = {}, onNew = {})
    }
    @Test fun feedback_form_en() = shot("tso_feedback_form_en", AppLanguage.EN) {
        val st = FeedbackFormState({ _, _, _ -> true }, cats, { "x" }); st.edit { it.copy(categoryCode = "suggestion", title = "Faster sync", description = "Sync takes long on 2G.") }
        FeedbackFormScreen(st, onBack = {}, photosEnabled = true)
    }
    @Test fun feedback_empty_en() = shot("tso_feedback_empty_en", AppLanguage.EN) { FeedbackListScreen(FeedbackListState(fb(emptyList())), cats, onBack = {}, onNew = {}) }

    // ---- My Call
    private val routes = listOf(PlanRoute(1, "DHK-R1", "Route One", 10, "(Sat, Mon, Wed)", RouteSr(7, "Rahim")), PlanRoute(2, "DHK-R2", "Route Two", 10, null, null))
    private val outlets = (1L..4L).map { PlanOutlet(it, "DHK-344-01$it", "Shop $it", if (it == 1L) "রহমান স্টোর" else null, "Owner $it", "Cluster A", if (it == 2L) "01711000000" else null) }
    private val planSrc = object : PlanSource {
        override suspend fun routes() = routes
        override suspend fun outlets(routeId: Long) = outlets
        override suspend fun plans(date: String) = listOf(VisitPlanItem("p", date, 1, listOf(PlannedOutlet(1, PlanStatus.PENDING), PlannedOutlet(2, PlanStatus.COMPLETED))))
        override suspend fun locallyDone(planUuid: String) = emptySet<Long>()
        override suspend fun taskPending(planUuid: String) = emptySet<Long>()
    }
    @Test fun set_plan_bn() = shot("tso_set_plan_bn", AppLanguage.BN) {
        val st = SetPlanState(planSrc, { 1 }, { "2026-10-17" }, { "2026-10-18" }); runBlocking { st.loadRoutes(); st.pickZone(10); st.pickRoute(1); st.showOutlets(); st.toggle(1); st.toggle(3) }
        SetPlanScreen(st, onBack = {})
    }
    @Test fun set_plan_unavailable_en() = shot("tso_set_plan_unavailable_en", AppLanguage.EN) {
        val st = SetPlanState(object : PlanSource by planSrc { override suspend fun routes(): List<PlanRoute>? = null }, { 1 }, { "2026-10-17" })
        SetPlanScreen(st, onBack = {})
    }
    @Test fun visit_query_bn() = shot("tso_visit_query_bn", AppLanguage.BN) {
        val st = VisitQueryState(Rubric(5, 1, listOf(Criterion(1, "এসআর কি নিয়মিত আপনার দোকানে ভিজিট করে?", "Does the SR visit regularly?"), Criterion(2, "এসআর কি নিয়মিত মেমো প্রিন্ট করে?", "Does the SR print memos regularly?"))), "p", 1, 1) { true }
        st.answer(1, true); VisitQueryScreen(st, "রহমান স্টোর", onDone = {}, onBack = {})
    }

    // ---- Final Submit
    private fun fs(already: Boolean = false) = object : FinalSubmitApi {
        override suspend fun zones() = listOf(10L, 11L)
        override suspend fun preview(zoneId: Long, date: String) = FsPreviewOutcome.Ok(FsPreview(zoneId, date, already, null, null, listOf(
            FsRoute(1, "DHK-R1", "Route One", "sr", "Rahim Uddin", "sales_submitted", true, 12, 4_391_000, 0), FsRoute(2, "DHK-R2", "Route Two", "amo", null, "in_field", true, 0, 0, null)), listOf("routes_not_logged_in")))
        override suspend fun submit(clientUuid: String, zoneId: Long, date: String) = FsSubmitOutcome.Ok(FsResult(zoneId, date, "t", 2, 12, 4_391_000))
    }
    @Test fun final_submit_pick_bn() = shot("tso_final_submit_pick_bn", AppLanguage.BN) { val st = FinalSubmitState(fs(), 7) { "2026-10-17" }; runBlocking { st.loadZones(); st.pickZone(10) }; FinalSubmitScreen(st, onBack = {}) }
    @Test fun final_submit_review_en() = shot("tso_final_submit_review_en", AppLanguage.EN) { val st = FinalSubmitState(fs(), 7) { "2026-10-17" }; runBlocking { st.loadZones(); st.pickZone(10); st.getSalesData() }; FinalSubmitScreen(st, onBack = {}) }
    @Test fun final_submit_review_sunlight_bn() = shot("tso_final_submit_review_sunlight_bn", AppLanguage.BN, sunlight = true) { val st = FinalSubmitState(fs(), 7) { "2026-10-17" }; runBlocking { st.loadZones(); st.pickZone(10); st.getSalesData() }; FinalSubmitScreen(st, onBack = {}) }
    @Test fun final_submit_done_en() = shot("tso_final_submit_done_en", AppLanguage.EN) { val st = FinalSubmitState(fs(), 7) { "2026-10-17" }; runBlocking { st.loadZones(); st.pickZone(10); st.getSalesData(); st.askConfirm(); st.confirmSubmit() }; FinalSubmitScreen(st, onBack = {}) }

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

    // ---- menu hubs (sr/outlet-menu.png look)
    @Test fun my_call_hub_bn() = shot("tso_my_call_hub_bn", AppLanguage.BN) { com.aktcl.aron.feature.tso.mycall.MyCallHub({}, {}, {}) }
    @Test fun periphery_hub_en() = shot("tso_periphery_hub_en", AppLanguage.EN) { com.aktcl.aron.feature.tso.periphery.PeripheryHub({}, {}, {}) }
}
