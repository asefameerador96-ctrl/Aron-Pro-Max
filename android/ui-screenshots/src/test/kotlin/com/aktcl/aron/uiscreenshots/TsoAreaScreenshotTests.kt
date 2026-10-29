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
import com.aktcl.aron.feature.tso.finalsubmit.FsFilterNode
import com.aktcl.aron.feature.tso.finalsubmit.FsFilterOptions
import com.aktcl.aron.feature.tso.finalsubmit.FsUploadState
import com.aktcl.aron.feature.tso.leave.LeaveApplyScreen
import com.aktcl.aron.feature.tso.leave.LeaveApplyState
import com.aktcl.aron.feature.tso.leave.LeaveItem
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
 * Goldens of the TSO area screens (lane A3, build contract s5: Leave, Final Submit, My Periphery, My Call, My Feedback) in
 * their states: content, empty, kept copy (offline), error, long Bangla, Sunlight (docs/32 s2a). Moved out of
 * TsoScreenshotTests (A1) unchanged, golden names kept. Record on Linux only:
 * `./gradlew :android:ui-screenshots:testDebugUnitTest -Proborazzi.record=true --tests '*TsoAreaScreenshotTests*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class TsoAreaScreenshotTests {
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
    @Test fun final_submit_done_en() = shot("tso_final_submit_done_en", AppLanguage.EN) { val st = FinalSubmitState(fs(), 7) { "2026-10-17" }; runBlocking { st.loadZones(); st.pickZone(10); st.getSalesData(); st.submit() }; FinalSubmitScreen(st, onBack = {}) }

    // ---- tso-manual A3: screenshots 19 to 26 (D-TSO-MAN-27, 28, 32, 33)
    private val s19 = arrayOf(
        LeaveItem("1", "casual", "2026-02-25", "2026-02-26", 2, "Family emergency", LeaveStatus.PENDING),
        LeaveItem("2", "casual", "2026-03-04", "2026-03-04", 1, "test", LeaveStatus.PENDING),
        LeaveItem("3", "casual", "2026-03-05", "2026-03-05", 1, "only one day can apply!", LeaveStatus.PENDING),
        LeaveItem("4", "sick", "2026-04-26", "2026-04-26", 1, "testing", LeaveStatus.PENDING),
        LeaveItem("5", "casual", "2026-08-25", "2026-08-25", 1, "Multiple date a leave apply kora jay na", LeaveStatus.PENDING),
        LeaveItem("6", "casual", "2026-08-28", "2026-08-28", 1, "Friday leave", LeaveStatus.PENDING),
        LeaveItem("7", "sick", "2026-09-20", "2026-09-20", 1, "Sick", LeaveStatus.PENDING),
    )
    @Test fun leave_s19_en() = shot("tso_leave_s19_en", AppLanguage.EN) { LeaveListScreen(LeaveListState(leave(*s19)), onBack = {}, onApply = {}) }
    @Test fun leave_s19_bn() = shot("tso_leave_s19_bn", AppLanguage.BN) { LeaveListScreen(LeaveListState(leave(*s19)), onBack = {}, onApply = {}) }
    @Test fun leave_refused_en() = shot("tso_leave_refused_en", AppLanguage.EN) {
        val refused = LeaveItem("r", "casual", "2026-10-21", "2026-10-21", 1, "Family Programme", LeaveStatus.PENDING, sent = false, refusalCode = "business_date_out_of_window")
        val unsent = LeaveItem("u", "casual", "2026-10-26", "2026-10-26", 1, "Family Programme", LeaveStatus.PENDING, sent = false)
        LeaveListScreen(LeaveListState(leave(s19[0], unsent = listOf(refused, unsent))), onBack = {}, onApply = {})
    }
    @Test fun leave_s20_en() = shot("tso_leave_s20_en", AppLanguage.EN) { LeaveApplyScreen(LeaveApplyState({ _, _ -> true }, { "2026-10-25" }), onBack = {}) }
    @Test fun leave_s20_bn() = shot("tso_leave_s20_bn", AppLanguage.BN) { LeaveApplyScreen(LeaveApplyState({ _, _ -> true }, { "2026-10-25" }), onBack = {}) }
    private fun s22() = LeaveApplyState({ _, _ -> true }, { "2026-10-25" }).also { it.edit { f -> f.copy(type = "casual", fromDate = "2026-10-26", days = 1, reason = "Family Programme") } }
    @Test fun leave_s22_en() = shot("tso_leave_s22_en", AppLanguage.EN) { LeaveApplyScreen(s22(), onBack = {}) }
    @Test fun leave_s22_bn() = shot("tso_leave_s22_bn", AppLanguage.BN) { LeaveApplyScreen(s22(), onBack = {}) }
    @Test fun leave_s20_errors_en() = shot("tso_leave_s20_errors_en", AppLanguage.EN) {
        val st = LeaveApplyState({ _, _ -> true }, { "2026-10-25" }); runBlocking { st.submit() }; LeaveApplyScreen(st, onBack = {})
    }
    @Test @Config(fontScale = 2.0f) fun leave_s22_200_bn() = shot("tso_leave_s22_200_bn", AppLanguage.BN) { LeaveApplyScreen(s22(), onBack = {}) }
    @Test @Config(fontScale = 2.0f) fun leave_s19_200_bn() = shot("tso_leave_s19_200_bn", AppLanguage.BN) { LeaveListScreen(LeaveListState(leave(*s19)), onBack = {}, onApply = {}) }

    private val apsis = FsFilterOptions(
        wings = listOf(FsFilterNode(1, "Apsis Wing", null)), divisions = listOf(FsFilterNode(11, "Apsis Division", 1)),
        territories = listOf(FsFilterNode(21, "Apsis Territory", 11)), houses = listOf(FsFilterNode(31, "Apsis House", 21, 21)),
        zones = listOf(FsFilterNode(41, "Banani - Test", 31, 21), FsFilterNode(42, "Gulshan - Test", 31, 21)),
    )
    private val twoWings = apsis.copy(wings = apsis.wings + FsFilterNode(2, "Second Wing", null))
    private val s25 = listOf(
        FsRoute(1, "AMO-1", "AMO-Apsis Route", "amo", null, "in_field", false, 0, 0, null, uploadState = FsUploadState.NOT_DUE),
        FsRoute(2, "AMO-2", "AMO-Apsis Route", "amo", null, "in_field", false, 0, 0, null, uploadState = FsUploadState.NOT_DUE),
        FsRoute(3, "AMO-3", "Apsis AMO", "amo", null, "in_field", false, 0, 0, null, uploadState = FsUploadState.NOT_DUE),
        FsRoute(4, "R-1", "Apsis Route", "sr", "SR - Testing Banani", "sales_submitted", true, 12, 4_391_000, 0, true, "normal", "daily", "SR", FsUploadState.UPLOADED),
        FsRoute(5, "R-2", "Apsis Route 2", "sr", "SR - Testing Banani 2", "in_field", true, 3, 90_000, null, true, "normal", "daily", "SR", FsUploadState.NOT_UPLOADED),
        FsRoute(6, "R-3", "Apsis Route 3", "sr", null, "not_started", false, 0, 0, null, true, "normal", "daily", null, FsUploadState.WEB_ENTRY),
        FsRoute(7, "R-4", "Apsis Route 4", "sr", null, "not_started", false, 0, 0, null, true, "normal", "daily", null, FsUploadState.WEB_ENTRY),
        FsRoute(8, "BR", "BR", "sr", null, "not_started", false, 0, 0, null, true, "dummy_br", "daily", null, FsUploadState.NOT_DUE),
        FsRoute(9, "INS", "Institutional Sales", "sr", null, "not_started", false, 0, 0, null, true, "dummy_institutional", "daily", null, FsUploadState.NOT_DUE),
        FsRoute(10, "MG", "akt-mgmt", "sr", "AKT Management", "sales_submitted", true, 1, 1_000, 0, true, "manual", "daily", "SR", FsUploadState.UPLOADED),
    )
    private fun fs25(options: FsFilterOptions = apsis) = object : FinalSubmitApi {
        override suspend fun zones() = listOf(41L, 42L)
        override suspend fun filterOptions() = options
        override suspend fun preview(zoneId: Long, date: String) = FsPreviewOutcome.Ok(FsPreview(zoneId, date, false, null, null, s25, emptyList(), 0, listOf(5L)))
        override suspend fun submit(clientUuid: String, zoneId: Long, date: String) = FsSubmitOutcome.Ok(FsResult(zoneId, date, "t", 10, 16, 4_482_000))
    }
    private fun fsAt(stage: Int, options: FsFilterOptions = apsis): FinalSubmitState = FinalSubmitState(fs25(options), 7) { "2026-10-26" }.also { st ->
        runBlocking {
            st.loadCascade()
            if (stage >= 24) st.pickLevel(4, 41)
            if (stage >= 25) st.getSalesData()
            if (stage >= 26) { st.submit(); st.confirmSubmit() }
        }
    }
    @Test fun fs_s23_en() = shot("tso_fs_s23_en", AppLanguage.EN) { FinalSubmitScreen(fsAt(23, twoWings), onBack = {}) }
    @Test fun fs_s23_bn() = shot("tso_fs_s23_bn", AppLanguage.BN) { FinalSubmitScreen(fsAt(23, twoWings), onBack = {}) }
    @Test fun fs_s24_en() = shot("tso_fs_s24_en", AppLanguage.EN) { FinalSubmitScreen(fsAt(24), onBack = {}) }
    @Test fun fs_s24_bn() = shot("tso_fs_s24_bn", AppLanguage.BN) { FinalSubmitScreen(fsAt(24), onBack = {}) }
    @Test fun fs_s25_en() = shot("tso_fs_s25_en", AppLanguage.EN) { FinalSubmitScreen(fsAt(25), onBack = {}) }
    @Test fun fs_s25_bn() = shot("tso_fs_s25_bn", AppLanguage.BN) { FinalSubmitScreen(fsAt(25), onBack = {}) }
    @Test fun fs_warning_en() = shot("tso_fs_warning_en", AppLanguage.EN) { val st = fsAt(25); runBlocking { st.submit() }; FinalSubmitScreen(st, onBack = {}) }
    @Test fun fs_s26_en() = shot("tso_fs_s26_en", AppLanguage.EN) { FinalSubmitScreen(fsAt(26), onBack = {}) }
    @Test fun fs_s26_bn() = shot("tso_fs_s26_bn", AppLanguage.BN) { FinalSubmitScreen(fsAt(26), onBack = {}) }
    @Test @Config(fontScale = 2.0f) fun fs_s24_200_bn() = shot("tso_fs_s24_200_bn", AppLanguage.BN) { FinalSubmitScreen(fsAt(24), onBack = {}) }
    @Test @Config(fontScale = 2.0f) fun fs_s25_200_en() = shot("tso_fs_s25_200_en", AppLanguage.EN) { FinalSubmitScreen(fsAt(25), onBack = {}) }

    // ---- menu hubs (sr/outlet-menu.png look)
    @Test fun my_call_hub_bn() = shot("tso_my_call_hub_bn", AppLanguage.BN) { com.aktcl.aron.feature.tso.mycall.MyCallHub({}, {}, {}) }
    @Test fun periphery_hub_en() = shot("tso_periphery_hub_en", AppLanguage.EN) { com.aktcl.aron.feature.tso.periphery.PeripheryHub({}, {}, {}) }
}
