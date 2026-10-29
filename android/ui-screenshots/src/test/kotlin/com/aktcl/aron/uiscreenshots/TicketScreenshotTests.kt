package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.contract.SupportTicketEventPayload
import com.aktcl.aron.contract.SupportTicketPayload
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.feature.tickets.CommentDraft
import com.aktcl.aron.feature.tickets.CreateTicketScreen
import com.aktcl.aron.feature.tickets.Delivery
import com.aktcl.aron.feature.tickets.DetailRead
import com.aktcl.aron.feature.tickets.IssueType
import com.aktcl.aron.feature.tickets.LocalEvent
import com.aktcl.aron.feature.tickets.PhotoOwner
import com.aktcl.aron.feature.tickets.PhotoResult
import com.aktcl.aron.feature.tickets.QuickTicketScreen
import com.aktcl.aron.feature.tickets.Ticket
import com.aktcl.aron.feature.tickets.TicketDashboardScreen
import com.aktcl.aron.feature.tickets.TicketDashboardState
import com.aktcl.aron.feature.tickets.TicketDeps
import com.aktcl.aron.feature.tickets.TicketDetail
import com.aktcl.aron.feature.tickets.TicketDetailScreen
import com.aktcl.aron.feature.tickets.TicketDetailState
import com.aktcl.aron.feature.tickets.TicketEvent
import com.aktcl.aron.feature.tickets.TicketFrame
import com.aktcl.aron.feature.tickets.TicketListScreen
import com.aktcl.aron.feature.tickets.TicketListState
import com.aktcl.aron.feature.tickets.TicketOrigin
import com.aktcl.aron.feature.tickets.TicketOutbox
import com.aktcl.aron.feature.tickets.TicketPhotos
import com.aktcl.aron.feature.tickets.TicketQueue
import com.aktcl.aron.feature.tickets.TicketRules
import com.aktcl.aron.feature.tickets.TicketSnapshot
import com.aktcl.aron.feature.tickets.TicketSource
import com.aktcl.aron.feature.tickets.TicketTab
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.TsoScreen
import com.aktcl.aron.rules.TicketCounts
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

/**
 * Goldens of the Tickets screens (tso-manual A2; screenshots 11 to 18 of the owner's TSO manual) in bn, en and at 200 %
 * font, plus the offline, empty, open-thread and Sunlight states. Every screen is drawn in the frame the TSO host passes
 * (app-tso TicketsHost.kt: `TsoFrame(title, nav, homeButton = false)`, lane A1's top bar; s8 gives 11 to 18 no Home
 * button), so the goldens recorded after A1's shell is merged show the real bar. The night page is a stand-in until A1's
 * `AronLook.TsoNight` is on this branch (one line, [night]). Screenshot 10 (the drawer's Tickets group) is A1's
 * TsoChrome golden. Record on Linux only: `./gradlew :android:ui-screenshots:testDebugUnitTest -Proborazzi.record=true
 * --tests '*TicketScreenshotTests*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class TicketScreenshotTests {
    @get:Rule val rule = createComposeRule()

    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    /** The TSO night look stand-in (D-TSO-MAN-13 page #040C3A) until A1's AronLook.TsoNight lands; Sunlight keeps the kit's. */
    private val night = com.aktcl.aron.core.ui.AronLook.Default.copy(page = Color(0xFF03103A), card = Color(0xFF0A183F), ink = Color.White, inkMuted = Color(0xFFA3ABBD))

    private fun shot(name: String, language: AppLanguage, sunlight: Boolean = false, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true) {
                    if (sunlight) content() else CompositionLocalProvider(LocalAronLook provides night) { content() }
                }
            }
        }
        rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    private object NoNav : TsoNav {
        override fun open(screen: TsoScreen, arg: String?) = Unit
        override fun back() = Unit
        override fun home() = Unit
    }

    /** The TSO host's frame for the ticket screens, exactly as app-tso TicketsHost.kt builds it (tso-manual A2 step 3). */
    private val tsoFrame: TicketFrame = { title, content -> TsoFrame(title, NoNav, homeButton = false) { content() } }

    // ---- fixtures: screenshot 11 to 14 (the vendor's INC0107xx numbers, display only)

    private val now = 1_792_928_040_000L // 2026-10-25 17:34 Dhaka
    private val day = 86_400_000L

    /** The 31 issue types of Annex A in order (V0131), icon (sort - 1) mod 8. */
    private val annexA = listOf(
        "app_crash_order_submit" to "App crashes on order submit", "app_version_update" to "Application Version Update",
        "astha_gift_disbursement" to "Astha Gift Choice & Disbursement", "astha_target" to "Astha Target", "auto_logout" to "Auto Logout",
        "camera_black_screen" to "Camera Black Screen", "campaign" to "Campaign", "change_request" to "Change Request",
        "check_in_out" to "Check in Check out issue", "cluster_mapping" to "Cluster Mapping", "discount" to "Discount",
        "ff_geo_location_map" to "FF Geo Location Map", "final_submission" to "Final Submission", "gps_inaccuracy" to "GPS location inaccuracy",
        "login_otp" to "Login OTP issue", "memo_printing" to "Memo Printing issue", "new_application_needed" to "New Application Needed (Tso, Amo, SR)",
        "others" to "Others", "outlet_related" to "Outlet Related Issue", "outlet_request" to "Outlet request (Close, New, & Info Update)",
        "outlet_request_approval" to "Outlet Request Approval", "password_reset" to "Password Reset", "printer_connection" to "Printer Connection issue",
        "reporting" to "Reporting", "sales_adjustment" to "Sales Adjustment", "sales_order_failing" to "Sales order creation failing",
        "sales_submit" to "Sales Submit (Bikroy Joma)", "sync_stuck" to "Sync stuck / not updating", "target_panel" to "Target Panel",
        "user_login" to "User Login issue", "web_entry" to "Web Entry",
    ).mapIndexed { i, (code, label) -> IssueType(code, label, null, i + 1, icon = i % 8) }

    private fun type(code: String) = annexA.first { it.code == code }

    private fun t(uuid: String, no: String, type: String, title: String, status: String, ageDays: Long) =
        Ticket(uuid, no, type, type(type).labelEn, null, title, "", "p1", status, now - ageDays * day - 3_600_000L)

    private val recent = listOf(
        t("a", "INC010700", "login_otp", "Login OTP issue", "resolved", 4),
        t("b", "INC010576", "final_submission", "Final Submission", "closed", 11),
        t("c", "INC010519", "reporting", "For test", "resolved", 14),
        t("d", "INC010393", "final_submission", "Final Submission", "cancelled", 35),
        t("e", "INC010358", "astha_target", "test", "cancelled", 38),
        t("f", "INC010357", "final_submission", "Final submit nicche na", "resolved", 39),
        t("g", "INC010356", "login_otp", "Login OTP issue", "resolved", 39),
        t("h", "INC010332", "memo_printing", "Memo Printing issue", "resolved", 41),
        t("i", "INC010319", "sync_stuck", "dd", "resolved", 42),
        t("j", "INC010318", "check_in_out", "hh", "closed", 42),
    )
    private val summary = TicketCounts(total = 723, new = 0, inProgress = 0, resolved = 18, closed = 690, cancelled = 15)

    private val resolution = TicketEvent(
        "res", "resolution", "[TicketID: INC010700] - Resolved:\nResolution Code: Resolved\nResolution Notes:\n\nChecked.", true, "Support", now, "resolved",
    )

    private class Source(
        private val types: List<IssueType>,
        private val snapshot: TicketSnapshot?,
        private val online: Boolean = true,
        private val details: Map<String, TicketDetail> = emptyMap(),
        private val outbox: TicketOutbox = TicketOutbox.EMPTY,
    ) : TicketSource, TicketQueue {
        override suspend fun issueTypes() = types
        override suspend fun cached() = snapshot
        override suspend fun list() = if (online) snapshot else null
        override suspend fun detail(ticketUuid: String) = if (!online) DetailRead.Unavailable else details[ticketUuid]?.let { DetailRead.Found(it) } ?: DetailRead.NotFound
        override suspend fun cachedDetail(ticketUuid: String) = details[ticketUuid]
        override suspend fun outbox() = outbox
        override suspend fun ticket(uuid: String, payload: SupportTicketPayload, photoUuids: List<String>, local: Ticket) = true
        override suspend fun event(uuid: String, payload: SupportTicketEventPayload, photoUuids: List<String>, local: LocalEvent) = true
    }

    private object Photos : TicketPhotos {
        override val max = 3
        override suspend fun capture(photoUuid: String) = true
        @Composable override fun rememberGallery(onPicked: (List<String>) -> Unit): (maxItems: Int) -> Unit = { }
        override suspend fun import(photoUuid: String, picked: String, owner: PhotoOwner) = PhotoResult.Added
        override suspend fun discard(photoUuid: String) = Unit
        @Composable override fun Thumbnail(photoUuid: String, modifier: Modifier) { Box(modifier.background(Color(0xFF5B6B8C))) }
    }

    private fun deps(src: Source) = TicketDeps(src, src, Photos, now = { now }, newUuid = { "00000000-0000-4000-8000-000000000001" })
    private fun dashboardDeps(online: Boolean = true) = deps(Source(annexA, TicketSnapshot(recent, complete = false, summary = summary, asOfMs = now - 3 * 3_600_000L), online))
    private val unsent = Ticket("u", null, "outlet_related", "Outlet Related Issue", null, "Outlet Related Issue", "DHK-344-011 no sales", "p1", "new", now - 120_000L, delivery = Delivery.Pending)

    // ---- 11 dashboard
    @Test fun dashboard_en() = shot("tickets_dashboard_en", AppLanguage.EN) { TicketDashboardScreen(TicketDashboardState(dashboardDeps()), tsoFrame, {}, {}, {}) }
    @Test fun dashboard_bn() = shot("tickets_dashboard_bn", AppLanguage.BN) { TicketDashboardScreen(TicketDashboardState(dashboardDeps()), tsoFrame, {}, {}, {}) }
    @Test @Config(fontScale = 2.0f) fun dashboard_200_bn() = shot("tickets_dashboard_200_bn", AppLanguage.BN) { TicketDashboardScreen(TicketDashboardState(dashboardDeps()), tsoFrame, {}, {}, {}) }
    @Test fun dashboard_offline_unsent_en() = shot("tickets_dashboard_offline_unsent_en", AppLanguage.EN) {
        TicketDashboardScreen(TicketDashboardState(deps(Source(annexA, TicketSnapshot(recent.take(3), true, null, now - 3 * 3_600_000L), online = false, outbox = TicketOutbox(listOf(unsent))))), tsoFrame, {}, {}, {})
    }
    @Test fun dashboard_empty_bn() = shot("tickets_dashboard_empty_bn", AppLanguage.BN) { TicketDashboardScreen(TicketDashboardState(deps(Source(annexA, TicketSnapshot(emptyList(), true, null, now)))), tsoFrame, {}, {}, {}) }
    @Test fun dashboard_sunlight_en() = shot("tickets_dashboard_sunlight_en", AppLanguage.EN, sunlight = true) { TicketDashboardScreen(TicketDashboardState(dashboardDeps()), tsoFrame, {}, {}, {}) }

    // ---- 12, 13 all tickets
    @Test fun list_en() = shot("tickets_list_en", AppLanguage.EN) { TicketListScreen(TicketListState(dashboardDeps()), tsoFrame, {}) }
    @Test fun list_bn() = shot("tickets_list_bn", AppLanguage.BN) { TicketListScreen(TicketListState(dashboardDeps()), tsoFrame, {}) }
    @Test @Config(fontScale = 2.0f) fun list_200_en() = shot("tickets_list_200_en", AppLanguage.EN) { TicketListScreen(TicketListState(dashboardDeps()), tsoFrame, {}) }
    @Test fun list_resolved_en() = shot("tickets_list_resolved_en", AppLanguage.EN) { TicketListScreen(TicketListState(dashboardDeps(), TicketTab.RESOLVED), tsoFrame, {}) }
    @Test fun list_resolved_bn() = shot("tickets_list_resolved_bn", AppLanguage.BN) { TicketListScreen(TicketListState(dashboardDeps(), TicketTab.RESOLVED), tsoFrame, {}) }
    @Test @Config(fontScale = 2.0f) fun list_resolved_200_bn() = shot("tickets_list_resolved_200_bn", AppLanguage.BN) { TicketListScreen(TicketListState(dashboardDeps(), TicketTab.RESOLVED), tsoFrame, {}) }
    @Test fun list_no_match_en() = shot("tickets_list_no_match_en", AppLanguage.EN) { TicketListScreen(TicketListState(dashboardDeps(), TicketTab.ALL, "zzz"), tsoFrame, {}) }

    // ---- 14 details
    private fun detailDeps(status: String = "resolved", events: List<TicketEvent> = listOf(resolution), online: Boolean = true) =
        deps(Source(annexA, null, online, mapOf("a" to TicketDetail(recent[0].copy(status = status), events))))

    @Composable private fun Detail(d: TicketDeps) = TicketDetailScreen(TicketDetailState(d, "a", mutableStateOf(CommentDraft("00000000-0000-4000-8000-0000000000c1"))), tsoFrame)

    @Test fun details_en() = shot("tickets_details_en", AppLanguage.EN) { Detail(detailDeps()) }
    @Test fun details_bn() = shot("tickets_details_bn", AppLanguage.BN) { Detail(detailDeps()) }
    @Test @Config(fontScale = 2.0f) fun details_200_en() = shot("tickets_details_200_en", AppLanguage.EN) { Detail(detailDeps()) }
    @Test fun details_open_thread_en() = shot("tickets_details_open_thread_en", AppLanguage.EN) {
        val reply = TicketEvent("r1", "comment", "Please send a screenshot of the OTP screen.", true, "Support", now - 3_600_000L)
        val mine = TicketEvent("m1", "comment", "Sent from the AMO phone, see the photo.", false, "Testing Banani", now - 1_800_000L, attachments = listOf("p"))
        Detail(detailDeps("in_progress", listOf(reply, mine)))
    }
    @Test fun details_offline_bn() = shot("tickets_details_offline_bn", AppLanguage.BN) { Detail(detailDeps(online = false)) }

    // ---- 15, 16 quick ticket
    @Test fun quick_en() = shot("tickets_quick_en", AppLanguage.EN) { QuickTicketScreen(dashboardDeps(), tsoFrame, {}, {}) }
    @Test fun quick_bn() = shot("tickets_quick_bn", AppLanguage.BN) { QuickTicketScreen(dashboardDeps(), tsoFrame, {}, {}) }
    @Test @Config(fontScale = 2.0f) fun quick_200_bn() = shot("tickets_quick_200_bn", AppLanguage.BN) { QuickTicketScreen(dashboardDeps(), tsoFrame, {}, {}) }
    @Test fun quick_sheet_en() = shot("tickets_quick_sheet_en", AppLanguage.EN) {
        QuickTicketScreen(dashboardDeps(), tsoFrame, {}, {}, initialSheet = TicketRules.newDraft("00000000-0000-4000-8000-0000000000q1", TicketOrigin.QUICK, "outlet_related"))
    }
    @Test fun quick_sheet_bn() = shot("tickets_quick_sheet_bn", AppLanguage.BN) {
        QuickTicketScreen(dashboardDeps(), tsoFrame, {}, {}, initialSheet = TicketRules.newDraft("00000000-0000-4000-8000-0000000000q1", TicketOrigin.QUICK, "login_otp"))
    }

    @Test @Config(fontScale = 2.0f) fun quick_sheet_200_en() = shot("tickets_quick_sheet_200_en", AppLanguage.EN) {
        QuickTicketScreen(dashboardDeps(), tsoFrame, {}, {}, initialSheet = TicketRules.newDraft("00000000-0000-4000-8000-0000000000q1", TicketOrigin.QUICK, "outlet_related"))
    }

    // ---- 17, 18 create ticket
    @Test fun create_en() = shot("tickets_create_en", AppLanguage.EN) { CreateTicketScreen(dashboardDeps(), tsoFrame, {}) }
    @Test fun create_bn() = shot("tickets_create_bn", AppLanguage.BN) { CreateTicketScreen(dashboardDeps(), tsoFrame, {}) }
    @Test @Config(fontScale = 2.0f) fun create_200_en() = shot("tickets_create_200_en", AppLanguage.EN) { CreateTicketScreen(dashboardDeps(), tsoFrame, {}) }
    @Test fun issue_types_en() = shot("tickets_issue_types_en", AppLanguage.EN) { CreateTicketScreen(dashboardDeps(), tsoFrame, {}, issueListOpen = true) }
    @Test fun issue_types_bn() = shot("tickets_issue_types_bn", AppLanguage.BN) { CreateTicketScreen(dashboardDeps(), tsoFrame, {}, issueListOpen = true) }
    @Test @Config(fontScale = 2.0f) fun issue_types_200_bn() = shot("tickets_issue_types_200_bn", AppLanguage.BN) { CreateTicketScreen(dashboardDeps(), tsoFrame, {}, issueListOpen = true) }
}
