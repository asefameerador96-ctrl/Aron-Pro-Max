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
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.tso.TsoApp
import com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitApi
import com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitScreen
import com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitState
import com.aktcl.aron.feature.tso.finalsubmit.FsPreview
import com.aktcl.aron.feature.tso.finalsubmit.FsPreviewOutcome
import com.aktcl.aron.feature.tso.finalsubmit.FsResult
import com.aktcl.aron.feature.tso.finalsubmit.FsRoute
import com.aktcl.aron.feature.tso.finalsubmit.FsSubmitOutcome
import com.aktcl.aron.feature.tso.leave.LeaveApplyScreen
import com.aktcl.aron.feature.tso.leave.LeaveApplyState
import com.aktcl.aron.feature.tso.mycall.Criterion
import com.aktcl.aron.feature.tso.mycall.Rubric
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
 * 200 percent font scale goldens (docs/32 s2a: nothing clips, every action stays reachable) of the TSO Dashboard root, drawer and
 * login (A1, in the TSO look), Leave apply and Final Submit, and the longest Bangla labels wrapping to three lines. Linux only (`-Proborazzi.record=true`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp", fontScale = 2.0f)
class TsoLargeTextScreenshotTests {
    @get:Rule val rule = createComposeRule()
    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, language: AppLanguage, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language, dark = true, sunlight = false, tier = GlassTier.B, reducedMotion = true, tso = true) { content() } } }
        rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    private val user = HomeUser("টেস্টিং ঢাকা নর্থ টেরিটরি অফিসার", "tso1001", "TSO", offline = false, reauthRequired = false, updateRequired = false)

    // tso-manual A1: the Dashboard root (top bar, cards, Home button), the drawer and the login at 200 percent
    private val stamp = "\"as_of\":\"2026-10-29T08:30:00Z\""
    private val dash = com.aktcl.aron.feature.tso.TsoDashboardState(
        sales = com.aktcl.aron.feature.tso.CardLoad(com.aktcl.aron.feature.tso.TsoDashboards.sales("""{$stamp,"working_days":25,"cards":[{"category_code":"cigarette","base_unit":"stick","total_sales":1234567,"monthly_target_qty":42000}]}""")),
        cpr = com.aktcl.aron.feature.tso.CardLoad(com.aktcl.aron.feature.tso.TsoDashboards.cpr("""{$stamp,"target_outlets":1186,"successful_calls":29}""")),
        login = com.aktcl.aron.feature.tso.CardLoad(com.aktcl.aron.feature.tso.TsoDashboards.loginSubmit("""{$stamp,"target_routes":5,"successful_login":2,"submitted":1}""")),
    )
    @Test fun dashboard_200_bn() = shot("tso_dashboard_200_bn", AppLanguage.BN) {
        TsoApp(
            user, "2026-10-29", BundleStatus.Ready("b-1", "2026-10-29"), "0.1.0 (1)", {}, {}, unread = 12,
            screens = mapOf(com.aktcl.aron.feature.tso.TsoScreen.DASHBOARD to { _, _ -> com.aktcl.aron.feature.tso.TsoDashboardContent(dash, false, com.aktcl.aron.feature.tso.ChannelMode.STD, {}, {}, {}) }),
        )
    }
    @Test fun drawer_200_bn() = shot("tso_drawer_200_bn", AppLanguage.BN) {
        com.aktcl.aron.feature.tso.TsoDrawerContent(user.fullName, {}, {}, {}, initiallyExpanded = "tickets")
    }
    @Test fun login_200_en() = shot("tso_login_200_en", AppLanguage.EN) {
        com.aktcl.aron.feature.auth.TsoLoginContent(com.aktcl.aron.feature.auth.LoginUiState(message = com.aktcl.aron.feature.auth.LoginMessage.OfflineNeverOnline), {}, {}, {}, {}, {})
    }

    @Test fun leave_apply_200_bn() = shot("tso_leave_apply_200_bn", AppLanguage.BN) {
        val st = LeaveApplyState({ _, _ -> true }, { "2026-10-17" }); st.edit { it.copy(days = 3, reason = "পারিবারিক জরুরি প্রয়োজনে গ্রামের বাড়িতে যেতে হচ্ছে") }
        LeaveApplyScreen(st, onBack = {})
    }

    private val api = object : FinalSubmitApi {
        override suspend fun zones() = listOf(10L, 11L)
        override suspend fun preview(zoneId: Long, date: String) = FsPreviewOutcome.Ok(FsPreview(zoneId, date, false, null, null, listOf(
            FsRoute(1, "DHK-R1", "ঢাকা উত্তর রুট এক", "sr", "রহিম উদ্দিন আহমেদ", "sales_submitted", true, 12, 4_391_000, 0), FsRoute(2, "DHK-R2", "Route Two", "amo", null, "in_field", true, 0, 0, null)), emptyList()))
        override suspend fun submit(clientUuid: String, zoneId: Long, date: String) = FsSubmitOutcome.Ok(FsResult(zoneId, date, "t", 2, 12, 4_391_000))
    }

    @Test fun final_submit_pick_200_bn() = shot("tso_final_submit_pick_200_bn", AppLanguage.BN) { val st = FinalSubmitState(api, 7) { "2026-10-17" }; runBlocking { st.loadZones(); st.pickZone(10) }; FinalSubmitScreen(st, onBack = {}) }
    @Test fun final_submit_review_200_en() = shot("tso_final_submit_review_200_en", AppLanguage.EN) { val st = FinalSubmitState(api, 7) { "2026-10-17" }; runBlocking { st.loadZones(); st.pickZone(10); st.getSalesData() }; FinalSubmitScreen(st, onBack = {}) }

    @Test fun visit_query_three_line_bn() = shot("tso_visit_query_3line_bn", AppLanguage.BN) {
        val long = "এসআর কি নিয়মিত আপনার দোকানে এসে ভিজিট করে এবং প্রতিটি ভিজিটে দোকানের স্টক দেখে প্রয়োজনমতো নতুন অর্ডার নিয়ে যায় কিনা বলুন?"
        val st = VisitQueryState(Rubric(5, 1, listOf(Criterion(1, long, "Does the SR visit regularly?"))), "p", 1, 1) { true }
        VisitQueryScreen(st, "রহমান স্টোর অ্যান্ড জেনারেল ট্রেডার্স কাওরান বাজার ঢাকা", onDone = {}, onBack = {})
    }

    // tso-manual A1 step 3: Target Status, Target Details and the inbox at 200 percent (figures shrink in their cells, labels wrap)
    private fun tgtRow(level: String, id: Long, name: String, code: String, t: Long, a: Long, td: Long?) =
        """{"product_level":"$level","product_id":$id,"name":"$name","unit":"stick","target_qty":"$t.000","achieved_qty":"$a.000","tilldate_target_qty":"0.000","remaining_qty":"0.000","category_code":"$code","target_qty_base":$t,"achieved_qty_base":$a,"tilldate_target_qty_base":${td ?: "null"},"remaining_qty_base":0,"remaining_tilldate_qty_base":null,"achievement_pct_bp":null,"tilldate_achievement_pct_bp":null}"""
    private val tgtHead = "\"month\":\"2026-10\",\"as_of\":\"2026-10-25T09:35:00Z\",\"day\":\"2026-10-25\",\"tilldate_basis\":\"calendar_days\",\"last_updated_at\":null"
    private val tgtNav = object : com.aktcl.aron.feature.tso.TsoNav { override fun open(screen: com.aktcl.aron.feature.tso.TsoScreen, arg: String?) {}; override fun back() {}; override fun home() {} }
    @Test fun target_status_200_bn() = shot("tso_target_status_200_bn", AppLanguage.BN) {
        val rows = listOf(tgtRow("category", 1, "Cigarette", "cigarette", 1234567, 1030, 995617), tgtRow("category", 2, "Bidi", "bidi", 11000, 50, 8871),
            tgtRow("category", 3, "Lighter", "lighter", 100, 85, 81), tgtRow("category", 4, "Match", "match", 170, 72, 137)).joinToString(",")
        val a = com.aktcl.aron.feature.tso.targets.TargetStatus.parse("""{$tgtHead,"node":{"type":"territory","id":3,"name":"ঢাকা উত্তর টেরিটরি"},"elapsed_days":25,"period_days":31,"rows":[$rows],
            "children":[{"node":{"type":"zone","id":9,"name":"বনানী - টেস্ট জোন"},"elapsed_days":25,"period_days":31,"rows":[$rows]}]}""")
        com.aktcl.aron.feature.tso.TsoFrame(androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.tso.R.string.tso_drawer_target_status), tgtNav, homeButton = false) {
            com.aktcl.aron.feature.tso.targets.TargetStatusContent(com.aktcl.aron.feature.tso.CardLoad(a), com.aktcl.aron.feature.tso.targets.TargetTab.TILL_DATE, com.aktcl.aron.rules.Bands.DEFAULT, false, {}, {}, {})
        }
    }
    @Test fun target_details_200_en() = shot("tso_target_details_200_en", AppLanguage.EN) {
        val rows = listOf(tgtRow("variant", 101, "Black Diamond Double Filter", "cigarette", 1234567, 350, 995617), tgtRow("variant", 102, "Maxim Bolt", "cigarette", 1000, 330, 806),
            tgtRow("variant", 301, "Aster", "lighter", 100, 85, 81)).joinToString(",")
        val a = com.aktcl.aron.feature.tso.targets.TargetStatus.parse("""{$tgtHead,"node":{"type":"territory","id":3,"name":"Apsis"},"elapsed_days":25,"period_days":31,"rows":[$rows]}""")
        com.aktcl.aron.feature.tso.TsoFrame("Apsis", tgtNav, homeButton = false) {
            com.aktcl.aron.feature.tso.targets.TargetDetailsContent(com.aktcl.aron.feature.tso.CardLoad(a), com.aktcl.aron.feature.tso.targets.TargetTab.TILL_DATE, com.aktcl.aron.rules.Bands.DEFAULT, null, false, {}, {}, {})
        }
    }
    @Test fun inbox_200_bn() = shot("tso_inbox_200_bn", AppLanguage.BN) {
        val page = com.aktcl.aron.feature.tso.notifications.TsoInboxes.parse("""{"as_of":"2026-10-29T08:30:00Z","unread_count":1,"next_before_id":null,"items":[
            {"id":31,"kind":"ticket_updated","ref_type":"support_ticket","ref_id":"6f1c0b9e-1111-4c4c-9a9a-000000000031","title_en":"Ticket INC100123 resolved","title_bn":"টিকিট INC100123 সমাধান হয়েছে","body_en":"Support resolved your ticket.","body_bn":"সাপোর্ট আপনার প্রিন্টার সংযোগ না হওয়ার টিকিটটি সমাধান করেছে, বিস্তারিত দেখতে টিকিট খুলুন।","created_at":"2026-10-29T08:10:00Z","business_date":"2026-10-29","read_at":null}]}""")
        com.aktcl.aron.feature.tso.TsoFrame(androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.tso.R.string.tso_chrome_notifications), tgtNav, homeButton = false) {
            com.aktcl.aron.feature.tso.notifications.TsoInboxContent(com.aktcl.aron.feature.tso.CardLoad(page), emptyList(), java.time.Instant.parse("2026-10-29T09:00:00Z").toEpochMilli(), false, false, {}, {}, null)
        }
    }
}
