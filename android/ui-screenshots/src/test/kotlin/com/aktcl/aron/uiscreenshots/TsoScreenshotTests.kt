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
import com.aktcl.aron.feature.tso.CardLoad
import com.aktcl.aron.feature.tso.ChannelMode
import com.aktcl.aron.feature.tso.LoginSubmitTab
import com.aktcl.aron.feature.tso.LoginSubmitUsers
import com.aktcl.aron.feature.tso.TsoApp
import com.aktcl.aron.feature.tso.TsoDrawerContent
import com.aktcl.aron.feature.tso.TsoFab
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoLoginSubmitStatusContent
import com.aktcl.aron.feature.tso.TsoLogoutDialogContent
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.TsoScreen
import com.aktcl.aron.feature.auth.LoginMessage
import com.aktcl.aron.feature.auth.LoginUiState
import com.aktcl.aron.feature.auth.TsoLoginContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.aktcl.aron.feature.tso.TsoDashboardContent
import com.aktcl.aron.feature.tso.TsoDashboardState
import com.aktcl.aron.feature.tso.TsoDashboards
import com.aktcl.aron.feature.tso.TsoFailure
import com.aktcl.aron.feature.tso.TsoSettingsContent
// tso-manual A1 step 3
import com.aktcl.aron.feature.tso.notifications.TsoInboxContent
import com.aktcl.aron.feature.tso.notifications.TsoInboxPage
import com.aktcl.aron.feature.tso.notifications.TsoInboxes
import com.aktcl.aron.feature.tso.targets.TargetAnswer
import com.aktcl.aron.feature.tso.targets.TargetDetailsContent
import com.aktcl.aron.feature.tso.targets.TargetStatusContent
import com.aktcl.aron.feature.tso.targets.TargetTab
import com.aktcl.aron.rules.Bands
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.HomeUser
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
 * Goldens of the TSO screens in the TSO look (D-TSO-MAN-13; AronTheme(tso = true)): A1's login, Dashboard, Login & Bikroy Joma
 * Status, drawer, logout and Settings (screenshots 5 to 10, 27, 30, 44), and the area screens (leave, feedback, My Call, Final Submit,
 * change password) until A3 moves them, in their states: content, empty, kept copy (offline), error, long Bangla, Sunlight (docs/32 s2a). Record on Linux only:
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
        rule.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language, dark = !sunlight, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true, tso = true) { content() } } }
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

    // ---- settings (A1: the TSO look; the drawer's gear opens it)
    @Test fun settings_parity_bn() = shot("tso_settings_parity_bn", AppLanguage.BN) {
        TsoSettingsContent("0.1.0 (1)", "", {}, {}, {}, sunlight = false, onSunlight = {}, onUpdate = {}, onSupport = {}, onChangePassword = {})
    }

    // ---- menu hubs (A3's area screens until A3 moves them to TsoAreaScreenshotTests)
    @Test fun my_call_hub_bn() = shot("tso_my_call_hub_bn", AppLanguage.BN) { com.aktcl.aron.feature.tso.mycall.MyCallHub({}, {}, {}) }
    @Test fun periphery_hub_en() = shot("tso_periphery_hub_en", AppLanguage.EN) { com.aktcl.aron.feature.tso.periphery.PeripheryHub({}, {}, {}) }

    // ---- tso-manual A1: login (5), Dashboard (6, 7), Login & Bikroy Joma Status (8), drawer (9, 10, 27, 30), logout (44)
    private val stamp = "\"as_of\":\"2026-10-29T08:30:00Z\",\"date\":\"2026-10-29\",\"till_time\":true,\"last_updated_at\":null"
    /** The figures of screenshots 6 and 7 (the build contract s4.2 fixtures). */
    private fun dash(kept: Boolean = false): TsoDashboardState {
        fun <T> l(v: T?) = CardLoad(v, kept = kept, failure = if (kept) TsoFailure.OFFLINE else TsoFailure.NONE)
        return TsoDashboardState(
            sales = l(TsoDashboards.sales("""{$stamp,"working_days":25,"cards":[
                {"category_code":"cigarette","base_unit":"stick","total_sales":40,"monthly_target_qty":42000},
                {"category_code":"bidi","base_unit":"stick","total_sales":2991,"monthly_target_qty":26000},
                {"category_code":"lighter","base_unit":"piece","total_sales":0,"monthly_target_qty":100},
                {"category_code":"match","base_unit":"dozen","total_sales":0,"monthly_target_qty":175}]}""")),
            geo = l(TsoDashboards.geo("""{$stamp,"total_calls":4,"geo_validated":4}""")),
            channelStd = l(TsoDashboards.channels("""{$stamp,"channels":[{"channel":"Astha","value":40}],"total":40}""")),
            channelCalls = l(TsoDashboards.channels("""{$stamp,"channels":[{"channel":"Astha","value":2},{"channel":"GT","value":1}],"total":3}""")),
            cpr = l(TsoDashboards.cpr("""{$stamp,"target_outlets":119,"successful_calls":3}""")),
            brand = l(TsoDashboards.brandCpr("""{$stamp,"target_outlets":119,"successful_calls":3,"denominator":"successful_calls","brands":[
                {"brand_code":"MX","brand_name":"Maxim","outlets_bought":2},{"brand_code":"BG","brand_name":"Bidi Gold","outlets_bought":1}]}""")),
            login = l(TsoDashboards.loginSubmit("""{$stamp,"target_routes":5,"successful_login":2,"submitted":1}""")),
            segment = l(TsoDashboards.segments("""{$stamp,"total_value_mtk":1000,"families":[{"base_unit":"stick","total_qty_base":100}],"segments":[
                {"segment_code":"BI","segment_name":"Bidi","category_code":"bidi","base_unit":"stick","value_mtk":820,"qty_base":91},
                {"segment_code":"ME","segment_name":"Medium","category_code":"cigarette","base_unit":"stick","value_mtk":180,"qty_base":9}]}""")),
            businessDate = "2026-10-29",
        )
    }
    private val apsis = HomeUser("Apsis", "tso1001", "TSO", offline = false, reauthRequired = false, updateRequired = false)
    @Composable private fun Root(state: TsoDashboardState?, mode: ChannelMode = ChannelMode.STD, unread: Int = 1, user: HomeUser = apsis) = TsoApp(
        user, "2026-10-29", BundleStatus.Ready("b", "2026-10-29"), "0.1.0 (1)", {}, {}, unread = unread,
        screens = mapOf(TsoScreen.DASHBOARD to { _, _ -> TsoDashboardContent(state, false, mode, {}, {}, {}) }),
    )

    @Test fun login_en() = shot("tso_login_en", AppLanguage.EN) { TsoLoginContent(LoginUiState(), {}, {}, {}, {}, {}) }
    @Test fun login_bn_refused() = shot("tso_login_refused_bn", AppLanguage.BN) { TsoLoginContent(LoginUiState(username = "tso1001", password = "x", message = LoginMessage.InvalidCredentials), {}, {}, {}, {}, {}) }
    @Test fun login_sunlight_en() = shot("tso_login_sunlight_en", AppLanguage.EN, sunlight = true) { TsoLoginContent(LoginUiState(username = "tso1001"), {}, {}, {}, {}, {}) }

    @Test fun dashboard_top_en() = shot("tso_dashboard_top_en", AppLanguage.EN) { Root(dash()) }
    @Test @Config(qualifiers = "w360dp-h2600dp") fun dashboard_full_en() = shot("tso_dashboard_full_en", AppLanguage.EN) { Root(dash()) }
    @Test @Config(qualifiers = "bn-w360dp-h2600dp") fun dashboard_full_bn() = shot("tso_dashboard_full_bn", AppLanguage.BN) { Root(dash(), user = apsis.copy(fullName = "টেস্টিং ঢাকা নর্থ")) }
    @Test @Config(qualifiers = "w360dp-h2600dp") fun dashboard_full_sunlight_en() = shot("tso_dashboard_full_sunlight_en", AppLanguage.EN, sunlight = true) { Root(dash()) }
    @Test @Config(qualifiers = "w360dp-h2600dp") fun dashboard_calls_kept_en() = shot("tso_dashboard_calls_kept_en", AppLanguage.EN) { Root(dash(kept = true), ChannelMode.CALLS, unread = 0) }
    private fun <T> off() = CardLoad<T>(null, failure = TsoFailure.OFFLINE)
    @Test fun dashboard_offline_empty_en() = shot("tso_dashboard_offline_empty_en", AppLanguage.EN) {
        Root(TsoDashboardState(off(), off(), off(), off(), off(), off(), off(), off(), "2026-10-29"))
    }
    @Test fun dashboard_loading_en() = shot("tso_dashboard_loading_en", AppLanguage.EN) { Root(null) }

    private val users = TsoDashboards.users("""{$stamp,"tab":"not_logged_in","route_count":2,"truncated":false,"items":[
        {"route_id":2,"route_code":"R2","route_name":"Apsis Route 2","zone_id":9,"dep_name":"Banani - Test","user_id":4,"username":"sr334002","display_name":"SR - Testing Banani 2","role":"SR"},
        {"route_id":2,"route_code":"R2","route_name":"Apsis Route 2","zone_id":9,"dep_name":"Banani - Test","user_id":7,"username":"amo6334","display_name":"AMO-6334","role":"AMO"},
        {"route_id":3,"route_code":"R3","route_name":"Apsis Route 3","zone_id":9,"dep_name":"Banani - Test","user_id":null,"username":null,"display_name":null,"role":"SR"}]}""")
    @Composable private fun Status(tab: LoginSubmitTab, load: CardLoad<LoginSubmitUsers>?) {
        val nav = object : TsoNav { override fun open(screen: TsoScreen, arg: String?) {}; override fun back() {}; override fun home() {} }
        TsoFrame(androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.tso.R.string.tso_dash_login_submit), nav, homeButton = TsoFab.of(TsoScreen.LOGIN_SUBMIT_STATUS)) {
            TsoLoginSubmitStatusContent(tab, load, false, {}, {})
        }
    }
    @Test fun login_status_en() = shot("tso_login_status_en", AppLanguage.EN) { Status(LoginSubmitTab.NOT_LOGGED_IN, CardLoad(users)) }
    @Test fun login_status_bn() = shot("tso_login_status_bn", AppLanguage.BN) { Status(LoginSubmitTab.NOT_UPLOADED, CardLoad(users)) }
    @Test fun login_status_offline_en() = shot("tso_login_status_offline_en", AppLanguage.EN) { Status(LoginSubmitTab.NOT_LOGGED_IN, CardLoad(null, failure = TsoFailure.OFFLINE)) }

    @Composable private fun Drawer(open: String?, name: String = "Apsis") {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
            Root(dash())
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f)))
            TsoDrawerContent(name, {}, {}, {}, androidx.compose.ui.Modifier.fillMaxWidth(0.85f).fillMaxHeight(), initiallyExpanded = open)
        }
    }
    @Test fun drawer_en() = shot("tso_drawer_en", AppLanguage.EN) { Drawer(null) }
    @Test fun drawer_tickets_en() = shot("tso_drawer_tickets_en", AppLanguage.EN) { Drawer("tickets") }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun drawer_periphery_bn() = shot("tso_drawer_periphery_bn", AppLanguage.BN) { Drawer("periphery", "টেস্টিং ঢাকা নর্থ") }
    @Test fun drawer_my_call_sunlight_en() = shot("tso_drawer_my_call_sunlight_en", AppLanguage.EN, sunlight = true) { Drawer("my_call") }
    @Composable private fun LogoutOverDrawer() {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
            Drawer("feedback")
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f)))
            TsoLogoutDialogContent({}, {}, androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.Center).padding(horizontal = 32.dp))
        }
    }
    @Test fun logout_dialog_en() = shot("tso_logout_dialog_en", AppLanguage.EN) { LogoutOverDrawer() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun logout_dialog_bn() = shot("tso_logout_dialog_bn", AppLanguage.BN) { LogoutOverDrawer() }

    // ---- tso-manual A1 step 3: Target Status (39, 40), Target Details (41, 42), the bell inbox (no screenshot) -------------------
    private val tgtStamp = "\"month\":\"2026-10\",\"as_of\":\"2026-10-25T09:35:00Z\",\"day\":\"2026-10-25\",\"tilldate_basis\":\"calendar_days\",\"last_updated_at\":null"
    private fun tgtRow(level: String, id: Long, name: String, code: String, t: Long, a: Long, td: Long?) =
        """{"product_level":"$level","product_id":$id,"name":"$name","unit":"stick","target_qty":"$t.000","achieved_qty":"$a.000","tilldate_target_qty":"0.000","remaining_qty":"0.000","category_code":"$code","target_qty_base":$t,"achieved_qty_base":$a,"tilldate_target_qty_base":${td ?: "null"},"remaining_qty_base":0,"remaining_tilldate_qty_base":null,"achievement_pct_bp":null,"tilldate_achievement_pct_bp":null}"""
    private val tgtCategories = listOf(
        tgtRow("category", 1, "Cigarette", "cigarette", 40999, 1030, 33064), tgtRow("category", 2, "Bidi", "bidi", 11000, 50, 8871),
        tgtRow("category", 3, "Lighter", "lighter", 100, 85, 81), tgtRow("category", 4, "Match", "match", 170, 72, 137),
    ).joinToString(",")
    /** Screenshots 39 and 40: the territory Apsis and its zone Banani - Test, the s4.3 figures. */
    private val tgtStatus = com.aktcl.aron.feature.tso.targets.TargetStatus.parse("""{$tgtStamp,"node":{"type":"territory","id":3,"name":"Apsis"},"elapsed_days":25,"period_days":31,"rows":[$tgtCategories],
        "children":[{"node":{"type":"zone","id":9,"name":"Banani - Test"},"elapsed_days":25,"period_days":31,"rows":[$tgtCategories]}]}""")
    /** Screenshots 41 and 42: the eight Cigarette items, Bidi, Aster and the match (T' at 25/31 per item). */
    private val tgtDetails = com.aktcl.aron.feature.tso.targets.TargetStatus.parse("""{$tgtStamp,"node":{"type":"territory","id":3,"name":"Apsis"},"elapsed_days":25,"period_days":31,"rows":[""" + listOf(
        tgtRow("variant", 101, "Maxim Regular", "cigarette", 1000, 350, 806), tgtRow("variant", 102, "Maxim Bolt", "cigarette", 1000, 330, 806),
        tgtRow("variant", 103, "Maxim Double Burst", "cigarette", 10000, 20, 8065), tgtRow("variant", 104, "Marise Special Blend", "cigarette", 10000, 200, 8065),
        tgtRow("variant", 105, "Black Diamond Double Filter", "cigarette", 5000, 30, 4032), tgtRow("variant", 106, "Black Diamond Advanced", "cigarette", 6000, 30, 4839),
        tgtRow("variant", 107, "Sunmoon American blend", "cigarette", 2999, 40, 2419), tgtRow("variant", 108, "Supreme Signature", "cigarette", 5000, 30, 4032),
        tgtRow("variant", 201, "Ananda Bidi 25", "bidi", 11000, 50, 8871), tgtRow("variant", 301, "Aster", "lighter", 100, 85, 81),
        tgtRow("variant", 401, "Dhaka Safety Match", "match", 170, 72, 137),
    ).joinToString(",") + "]}")
    private val nav0 = object : TsoNav { override fun open(screen: TsoScreen, arg: String?) {}; override fun back() {}; override fun home() {} }
    @Composable private fun TargetStatusShot(tab: TargetTab, load: CardLoad<TargetAnswer>? = CardLoad(tgtStatus)) =
        TsoFrame(androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.tso.R.string.tso_drawer_target_status), nav0, homeButton = TsoFab.of(TsoScreen.TARGET_STATUS)) {
            TargetStatusContent(load, tab, Bands.DEFAULT, false, {}, {}, {})
        }
    @Composable private fun TargetDetailsShot(tab: TargetTab, filter: String?) =
        TsoFrame("Apsis", nav0, homeButton = TsoFab.of(TsoScreen.TARGET_DETAILS)) {
            TargetDetailsContent(CardLoad(tgtDetails), tab, Bands.DEFAULT, filter, false, {}, {}, {})
        }
    @Test fun target_monthly_en() = shot("tso_target_monthly_en", AppLanguage.EN) { TargetStatusShot(TargetTab.MONTHLY) }
    @Test fun target_till_date_en() = shot("tso_target_till_date_en", AppLanguage.EN) { TargetStatusShot(TargetTab.TILL_DATE) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun target_monthly_bn() = shot("tso_target_monthly_bn", AppLanguage.BN) { TargetStatusShot(TargetTab.MONTHLY) }
    @Test fun target_sunlight_en() = shot("tso_target_sunlight_en", AppLanguage.EN, sunlight = true) { TargetStatusShot(TargetTab.TILL_DATE) }
    @Test fun target_kept_en() = shot("tso_target_kept_en", AppLanguage.EN) { TargetStatusShot(TargetTab.MONTHLY, CardLoad(tgtStatus, kept = true, failure = TsoFailure.OFFLINE)) }
    @Test fun target_offline_empty_en() = shot("tso_target_offline_empty_en", AppLanguage.EN) { TargetStatusShot(TargetTab.MONTHLY, CardLoad(null, failure = TsoFailure.OFFLINE)) }
    @Test fun target_loading_en() = shot("tso_target_loading_en", AppLanguage.EN) { TargetStatusShot(TargetTab.MONTHLY, null) }
    @Test @Config(qualifiers = "w360dp-h1200dp") fun target_details_cigarette_en() = shot("tso_target_details_cigarette_en", AppLanguage.EN) { TargetDetailsShot(TargetTab.MONTHLY, "cigarette") }
    @Test fun target_details_lighter_till_en() = shot("tso_target_details_lighter_till_en", AppLanguage.EN) { TargetDetailsShot(TargetTab.TILL_DATE, "lighter") }
    @Test @Config(qualifiers = "w360dp-h1600dp") fun target_details_till_en() = shot("tso_target_details_till_en", AppLanguage.EN) { TargetDetailsShot(TargetTab.TILL_DATE, null) }
    @Test @Config(qualifiers = "bn-w360dp-h1600dp") fun target_details_bn() = shot("tso_target_details_bn", AppLanguage.BN) { TargetDetailsShot(TargetTab.MONTHLY, null) }

    private val inboxPage = TsoInboxes.parse("""{"as_of":"2026-10-29T08:30:00Z","unread_count":2,"next_before_id":12,"items":[
        {"id":31,"kind":"ticket_updated","ref_type":"support_ticket","ref_id":"6f1c0b9e-1111-4c4c-9a9a-000000000031","title_en":"Ticket INC100123 resolved","title_bn":"টিকিট INC100123 সমাধান হয়েছে","body_en":"Support resolved your ticket Printer not connecting.","body_bn":"সাপোর্ট আপনার টিকিট সমাধান করেছে।","created_at":"2026-10-29T08:10:00Z","business_date":"2026-10-29","read_at":null},
        {"id":30,"kind":"leave_decided","ref_type":"leave_application","ref_id":"77","title_en":"Leave approved","title_bn":null,"body_en":"Your leave of 2026-11-02 to 2026-11-03 was approved.","body_bn":null,"created_at":"2026-10-28T11:00:00Z","business_date":"2026-10-28","read_at":null},
        {"id":29,"kind":"final_submit_auto","ref_type":"zone_day","ref_id":"9:2026-10-27","title_en":"Final Submit done at midnight","title_bn":"মধ্যরাতে ফাইনাল সাবমিট হয়েছে","body_en":"Zone Banani - Test was final submitted automatically for 2026-10-27.","body_bn":"জোন বনানী - টেস্ট ২০২৬-১০-২৭ তারিখের জন্য স্বয়ংক্রিয়ভাবে ফাইনাল সাবমিট হয়েছে।","created_at":"2026-10-28T18:00:00Z","business_date":"2026-10-28","read_at":"2026-10-28T19:00:00Z"}]}""")!!
    private val inboxNow = java.time.Instant.parse("2026-10-29T09:00:00Z").toEpochMilli()
    @Composable private fun InboxShot(load: CardLoad<TsoInboxPage>?, pending: Boolean = false) =
        TsoFrame(androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.tso.R.string.tso_chrome_notifications), nav0, homeButton = TsoFab.of(TsoScreen.NOTIFICATIONS)) {
            TsoInboxContent(load, emptyList(), inboxNow, false, pending, {}, {}, if (load?.value?.nextBeforeId != null) ({}) else null)
        }
    @Test fun inbox_en() = shot("tso_inbox_en", AppLanguage.EN) { InboxShot(CardLoad(inboxPage)) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun inbox_bn() = shot("tso_inbox_bn", AppLanguage.BN) { InboxShot(CardLoad(inboxPage)) }
    @Test fun inbox_offline_pending_en() = shot("tso_inbox_offline_pending_en", AppLanguage.EN) { InboxShot(CardLoad(inboxPage, kept = true, failure = TsoFailure.OFFLINE), pending = true) }
    @Test fun inbox_empty_en() = shot("tso_inbox_empty_en", AppLanguage.EN) { InboxShot(CardLoad(inboxPage.copy(unreadCount = 0, items = emptyList(), nextBeforeId = null))) }
    @Test fun inbox_sunlight_bn() = shot("tso_inbox_sunlight_bn", AppLanguage.BN, sunlight = true) { InboxShot(CardLoad(inboxPage)) }
}
