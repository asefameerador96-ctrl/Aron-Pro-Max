package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import com.aktcl.aron.feature.tso.leave.LeaveApplyTags
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

    /** A shot with a dropdown open (screenshots 29 and 32): [open] taps the field, and the whole screen is captured with the popup window. */
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    private fun menuShot(name: String, language: AppLanguage, openTag: String, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language, dark = false, sunlight = false, tier = GlassTier.B, reducedMotion = true) { content() } } }
        rule.waitForIdle()
        rule.onNodeWithTag(openTag).performClick(); rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        com.github.takahirom.roborazzi.captureScreenRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
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
    @Test @Config(fontScale = 2.0f) fun leave_s20_200_bn() = shot("tso_leave_s20_200_bn", AppLanguage.BN) { LeaveApplyScreen(LeaveApplyState({ _, _ -> true }, { "2026-10-25" }), onBack = {}) }
    // screenshot 21: the leave type list open (the bundle's types; Casual, Sick, Earn when the bundle has none)
    @Test fun leave_s21_en() = menuShot("tso_leave_s21_en", AppLanguage.EN, LeaveApplyTags.TYPE) { LeaveApplyScreen(LeaveApplyState({ _, _ -> true }, { "2026-10-25" }), onBack = {}) }
    @Test fun leave_s21_bn() = menuShot("tso_leave_s21_bn", AppLanguage.BN, LeaveApplyTags.TYPE) { LeaveApplyScreen(LeaveApplyState({ _, _ -> true }, { "2026-10-25" }), onBack = {}) }
    @Test @Config(fontScale = 2.0f) fun leave_s21_200_bn() = menuShot("tso_leave_s21_200_bn", AppLanguage.BN, LeaveApplyTags.TYPE) { LeaveApplyScreen(LeaveApplyState({ _, _ -> true }, { "2026-10-25" }), onBack = {}) }

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
    @Test @Config(fontScale = 2.0f) fun fs_s23_200_bn() = shot("tso_fs_s23_200_bn", AppLanguage.BN) { FinalSubmitScreen(fsAt(23, twoWings), onBack = {}) }
    @Test @Config(fontScale = 2.0f) fun fs_s24_200_bn() = shot("tso_fs_s24_200_bn", AppLanguage.BN) { FinalSubmitScreen(fsAt(24), onBack = {}) }
    @Test @Config(fontScale = 2.0f) fun fs_s25_200_en() = shot("tso_fs_s25_200_en", AppLanguage.EN) { FinalSubmitScreen(fsAt(25), onBack = {}) }
    @Test @Config(fontScale = 2.0f) fun fs_s26_200_en() = shot("tso_fs_s26_200_en", AppLanguage.EN) { FinalSubmitScreen(fsAt(26), onBack = {}) }

    // ---- tso-manual A3 step 2: screenshots 28, 29 (My Periphery), 31 to 38 (My Call) and 43 (feedback); D-TSO-MAN-34, 35, 36
    private val banani = 41L
    private val teamNow = 1_792_212_000_000L // 2026-10-26 10:40 Dhaka
    private val teamSrc = object : com.aktcl.aron.feature.tso.periphery.PeripherySource {
        override suspend fun zones() = listOf(banani)
        override suspend fun nearby(lat: Double, lng: Double, radiusM: Int, zoneId: Long?): com.aktcl.aron.feature.tso.periphery.Stamped<com.aktcl.aron.feature.tso.periphery.Nearby>? = null
        override suspend fun team(zoneId: Long?, date: String) = com.aktcl.aron.feature.tso.periphery.Stamped(listOf(
            com.aktcl.aron.feature.tso.periphery.TeamMember(7, "SR - Testing Banani", listOf(4), 23.7937, 90.4066, teamNow - 18 * 60_000, "visit"),
            com.aktcl.aron.feature.tso.periphery.TeamMember(8, "SR - Testing Banani 2", listOf(5), 23.7939, 90.4071, teamNow - 150 * 60_000, "check_in", mockFixesToday = 2),
            com.aktcl.aron.feature.tso.periphery.TeamMember(9, "AKT Management", listOf(10), null, null, null, null),
        ), "15:33", false)
        override suspend fun routeCodes() = mapOf(4L to "Apsis Route", 5L to "Apsis Route 2", 10L to "akt-mgmt")
        override suspend fun bundleOutlets(zoneId: Long?) = (1L..6L).map {
            com.aktcl.aron.feature.tso.periphery.BundleOutlet(it, "DHK-344-00$it", "outlet name $it", 23.7937 + it * 0.0001, 90.4066 + it * 0.00005, 4, banani, true, true, "Apsis Cluster", "GT", null)
        }
    }
    private val names = mapOf(banani to "Banani - Test")
    @Composable private fun named(content: @Composable () -> Unit) = CompositionLocalProvider(com.aktcl.aron.feature.tso.LocalZoneNames provides names) { content() }
    private fun teamState() = com.aktcl.aron.feature.tso.periphery.TeamState(teamSrc) { "2026-10-26" }
    @Composable private fun team() = named { com.aktcl.aron.feature.tso.periphery.TeamScreen(teamState(), online = { false }, nowMs = { teamNow }, userKey = "s28", onBack = {}) }
    private fun retailer() = com.aktcl.aron.feature.tso.periphery.RetailerState(teamSrc, { "15:34" }) { com.aktcl.aron.feature.tso.periphery.FixResult.Ok(23.7937, 90.4066, 8.0, false) }
    @Composable private fun outletsScreen(st: com.aktcl.aron.feature.tso.periphery.RetailerState) = named { com.aktcl.aron.feature.tso.periphery.RetailerScreen(st, online = { false }, nowMs = { teamNow }, userKey = "s29", onBack = {}, findOnOpen = true) }

    @Test fun pe_s28_en() = shot("tso_pe_s28_en", AppLanguage.EN) { team() }
    @Test fun pe_s28_bn() = shot("tso_pe_s28_bn", AppLanguage.BN) { team() }
    @Test @Config(fontScale = 2.0f) fun pe_s28_200_bn() = shot("tso_pe_s28_200_bn", AppLanguage.BN) { team() }
    @Test fun pe_s29_en() = shot("tso_pe_s29_en", AppLanguage.EN) { outletsScreen(retailer()) }
    @Test fun pe_s29_bn() = shot("tso_pe_s29_bn", AppLanguage.BN) { outletsScreen(retailer()) }
    @Test fun pe_s29_radius_en() = menuShot("tso_pe_s29_radius_en", AppLanguage.EN, com.aktcl.aron.feature.tso.periphery.PeripheryTags.RADIUS) { outletsScreen(retailer()) }
    @Test @Config(fontScale = 2.0f) fun pe_s29_200_en() = shot("tso_pe_s29_200_en", AppLanguage.EN) { outletsScreen(retailer()) }

    private val apsisRoutes = listOf(
        PlanRoute(4, "R-1", "Apsis Route", banani, null, RouteSr(7, "SR - Testing Banani")), PlanRoute(5, "R-2", "Apsis Route 2", banani, null, RouteSr(8, "SR - Testing Banani 2")),
        PlanRoute(6, "R-3", "Apsis Route 3", banani, null, null), PlanRoute(7, "R-4", "Apsis Route 4", banani, null, null), PlanRoute(10, "MG", "akt-mgmt", banani, null, null),
    )
    private val apsisOutlets = listOf(
        PlanOutlet(1, "DHK-344-001", "outlet name 1", null, "customer name 1", "Apsis Cluster", "1913654091", "GT"),
        PlanOutlet(2, "DHK-344-002", "outlet 2", null, "customer name 3", "Apsis Cluster", "01984872193", "GT"),
        PlanOutlet(3, "DHK-344-003", "outlet name 3", null, "customer name 3", "Apsis Cluster", "1913654093", "GT"),
        PlanOutlet(4, "DHK-344-004", "outlet name 4", null, "Rifat", "Apsis Cluster", "1913654094", "GT"),
        PlanOutlet(5, "DHK-344-005", "Moin Store", null, "Moin", "Apsis Cluster", "01913654095", "GT"),
        PlanOutlet(10, "DHK-344-010", "outlet name 10", null, "customer name 10", "Apsis Cluster", "01787782457", "GT"),
        PlanOutlet(11, "DHK-344-011", "outlet name 11", null, "customer name 11", "Apsis Cluster", "1913654101", "GT"),
    )
    private fun apsis(plans: List<VisitPlanItem>) = object : PlanSource {
        override suspend fun routes() = apsisRoutes
        override suspend fun outlets(routeId: Long) = apsisOutlets
        override suspend fun plans(date: String) = plans
        override suspend fun locallyDone(planUuid: String) = emptySet<Long>()
        override suspend fun taskPending(planUuid: String) = emptySet<Long>()
    }
    private val d26 = "2026-10-26"
    private fun setPlan(route: Boolean = true) = SetPlanState(apsis(emptyList()), { 1 }, { d26 }).also { runBlocking { it.loadRoutes(); if (route) it.pickRoute(4) } }
    @Test fun mc_s31_en() = shot("tso_mc_s31_en", AppLanguage.EN) { named { SetPlanScreen(setPlan(), onBack = {}) } }
    @Test fun mc_s31_bn() = shot("tso_mc_s31_bn", AppLanguage.BN) { named { SetPlanScreen(setPlan(), onBack = {}) } }
    @Test @Config(fontScale = 2.0f) fun mc_s31_200_bn() = shot("tso_mc_s31_200_bn", AppLanguage.BN) { named { SetPlanScreen(setPlan(), onBack = {}) } }
    @Test fun mc_s32_en() = menuShot("tso_mc_s32_en", AppLanguage.EN, com.aktcl.aron.feature.tso.mycall.MyCallTags.ROUTE) { named { SetPlanScreen(setPlan(route = false), onBack = {}) } }
    @Test fun mc_s32_bn() = menuShot("tso_mc_s32_bn", AppLanguage.BN, com.aktcl.aron.feature.tso.mycall.MyCallTags.ROUTE) { named { SetPlanScreen(setPlan(route = false), onBack = {}) } }
    @Test @Config(fontScale = 2.0f) fun mc_s32_200_bn() = menuShot("tso_mc_s32_200_bn", AppLanguage.BN, com.aktcl.aron.feature.tso.mycall.MyCallTags.ROUTE) { named { SetPlanScreen(setPlan(route = false), onBack = {}) } }
    private fun outletsPicked(vararg ids: Long) = setPlan().also { st -> runBlocking { st.showOutlets() }; ids.forEach { st.toggle(it) } }
    @Test fun mc_s33_en() = shot("tso_mc_s33_en", AppLanguage.EN) { com.aktcl.aron.feature.tso.mycall.SelectOutletsScreen(outletsPicked(), onBack = {}, onSaved = {}) }
    @Test fun mc_s33_selected_bn() = shot("tso_mc_s33_selected_bn", AppLanguage.BN) { com.aktcl.aron.feature.tso.mycall.SelectOutletsScreen(outletsPicked(1), onBack = {}, onSaved = {}) }
    @Test @Config(fontScale = 2.0f) fun mc_s33_200_en() = shot("tso_mc_s33_200_en", AppLanguage.EN) { com.aktcl.aron.feature.tso.mycall.SelectOutletsScreen(outletsPicked(1), onBack = {}, onSaved = {}) }
    private fun visitPlan(plans: List<VisitPlanItem>, route: Boolean = true, show: Boolean = false, tab: Int = 0) =
        com.aktcl.aron.feature.tso.mycall.VisitPlanState(apsis(plans)) { d26 }.also { runBlocking { it.loadRoutes(); if (route) it.pickRoute(5); if (show) it.show(); it.tab(tab) } }
    @Test fun mc_s34_en() = shot("tso_mc_s34_en", AppLanguage.EN) { named { com.aktcl.aron.feature.tso.mycall.VisitPlanScreen(visitPlan(emptyList(), route = false), onShow = {}, onBack = {}) } }
    @Test fun mc_s34_bn() = shot("tso_mc_s34_bn", AppLanguage.BN) { named { com.aktcl.aron.feature.tso.mycall.VisitPlanScreen(visitPlan(emptyList(), route = false), onShow = {}, onBack = {}) } }
    @Test fun mc_s35_pending_en() = shot("tso_mc_s35_pending_en", AppLanguage.EN) { com.aktcl.aron.feature.tso.mycall.VisitPlanOutletsScreen(visitPlan(emptyList(), show = true), { _, _, _ -> }, { _, _, _ -> }, onBack = {}) }
    @Test fun mc_s35_completed_bn() = shot("tso_mc_s35_completed_bn", AppLanguage.BN) { com.aktcl.aron.feature.tso.mycall.VisitPlanOutletsScreen(visitPlan(emptyList(), show = true, tab = 1), { _, _, _ -> }, { _, _, _ -> }, onBack = {}) }
    @Test @Config(fontScale = 2.0f) fun mc_s34_200_bn() = shot("tso_mc_s34_200_bn", AppLanguage.BN) { named { com.aktcl.aron.feature.tso.mycall.VisitPlanScreen(visitPlan(emptyList(), route = false), onShow = {}, onBack = {}) } }
    @Test @Config(fontScale = 2.0f) fun mc_s35_200_en() = shot("tso_mc_s35_200_en", AppLanguage.EN) { com.aktcl.aron.feature.tso.mycall.VisitPlanOutletsScreen(visitPlan(emptyList(), show = true), { _, _, _ -> }, { _, _, _ -> }, onBack = {}) }
    private val plan36 = listOf(VisitPlanItem("p36", d26, 5, listOf(PlannedOutlet(11, PlanStatus.PENDING))))
    @Test fun mc_s36_en() = shot("tso_mc_s36_en", AppLanguage.EN) { com.aktcl.aron.feature.tso.mycall.VisitPlanOutletsScreen(visitPlan(plan36, show = true), { _, _, _ -> }, { _, _, _ -> }, onBack = {}) }
    @Test fun mc_s36_bn() = shot("tso_mc_s36_bn", AppLanguage.BN) { com.aktcl.aron.feature.tso.mycall.VisitPlanOutletsScreen(visitPlan(plan36, show = true), { _, _, _ -> }, { _, _, _ -> }, onBack = {}) }
    @Test @Config(fontScale = 2.0f) fun mc_s36_200_bn() = shot("tso_mc_s36_200_bn", AppLanguage.BN) { com.aktcl.aron.feature.tso.mycall.VisitPlanOutletsScreen(visitPlan(plan36, show = true), { _, _, _ -> }, { _, _, _ -> }, onBack = {}) }
    private val seeded = Rubric(3, 1, listOf(
        Criterion(1, "SR আপনার দোকান নিয়মিত ভিজিট করে?", "Does the SR visit your shop regularly?", text = true),
        Criterion(2, "SR নিয়মিত মেমো প্রিন্ট করে দেয়?", "Does the SR give you a printed memo regularly?", text = true),
    ))
    @Test fun mc_s37_en() = shot("tso_mc_s37_en", AppLanguage.EN) { VisitQueryScreen(VisitQueryState(seeded, "p36", 11, 5) { true }, "outlet name 11", onDone = {}, onBack = {}) }
    @Test fun mc_s37_bn() = shot("tso_mc_s37_bn", AppLanguage.BN) { VisitQueryScreen(VisitQueryState(seeded, "p36", 11, 5) { true }, "outlet name 11", onDone = {}, onBack = {}) }
    @Test @Config(fontScale = 2.0f) fun mc_s37_200_bn() = shot("tso_mc_s37_200_bn", AppLanguage.BN) { VisitQueryScreen(VisitQueryState(seeded, "p36", 11, 5) { true }, "outlet name 11", onDone = {}, onBack = {}) }
    private fun task38() = com.aktcl.aron.feature.tso.mycall.AssignTaskState(
        listOf(com.aktcl.aron.feature.tso.mycall.TaskType("oos", null, "OOS", 1), com.aktcl.aron.feature.tso.mycall.TaskType("irregular_visit", null, "Irregular Visit", 3)),
        apsisRoutes[1], 11, { true }, { "2026-04-28" },
    ).also { it.pickType("irregular_visit") }
    @Test fun mc_s38_en() = shot("tso_mc_s38_en", AppLanguage.EN) { com.aktcl.aron.feature.tso.mycall.AssignTaskScreen(task38(), onDone = {}, outlet = apsisOutlets.last()) }
    @Test fun mc_s38_bn() = shot("tso_mc_s38_bn", AppLanguage.BN) { com.aktcl.aron.feature.tso.mycall.AssignTaskScreen(task38(), onDone = {}, outlet = apsisOutlets.last()) }
    @Test @Config(fontScale = 2.0f) fun mc_s38_200_en() = shot("tso_mc_s38_200_en", AppLanguage.EN) { com.aktcl.aron.feature.tso.mycall.AssignTaskScreen(task38(), onDone = {}, outlet = apsisOutlets.last()) }

    private val fbCats = listOf(FeedbackCategory("suggestion", null, "Suggestion", 1), FeedbackCategory("feedback", null, "Feedback", 2))
    private val gallery = object : com.aktcl.aron.feature.tso.feedback.FeedbackPhotos {
        override suspend fun take(photoUuid: String): String? = null
        override suspend fun discard(photoUuid: String) = Unit
    }
    private fun fb43() = FeedbackFormState({ _, _, _ -> true }, fbCats, { "x" }, gallery, 1)
    @Test fun fb_s43_en() = shot("tso_fb_s43_en", AppLanguage.EN) { FeedbackFormScreen(fb43(), onBack = {}, photosEnabled = true) }
    @Test fun fb_s43_bn() = shot("tso_fb_s43_bn", AppLanguage.BN) { FeedbackFormScreen(fb43(), onBack = {}, photosEnabled = true) }
    @Test @Config(fontScale = 2.0f) fun fb_s43_200_bn() = shot("tso_fb_s43_200_bn", AppLanguage.BN) { FeedbackFormScreen(fb43(), onBack = {}, photosEnabled = true) }
    @Test fun fb_s43_filled_en() = shot("tso_fb_s43_filled_en", AppLanguage.EN) {
        val st = fb43(); st.edit { it.copy(categoryCode = "feedback", title = "Zone names on the map", description = "Show the zone names on My Team.", photoUuid = "p1") }
        FeedbackFormScreen(st, onBack = {}, photosEnabled = true)
    }
    @Test fun fb_list_en() = shot("tso_fb_list_en", AppLanguage.EN) {
        FeedbackListScreen(FeedbackListState(fb(listOf(
            FeedbackItem("a", "suggestion", "Faster sync on 2G", "d", "2026-10-25T04:00:00Z", FeedbackStatus.NEW, businessDate = "2026-10-25"),
            FeedbackItem("b", "feedback", "Zone names on the map", "d", "2026-10-20T04:00:00Z", FeedbackStatus.RESOLVED, businessDate = "2026-10-20"),
        ))), fbCats, onBack = {}, onNew = {})
    }
}
