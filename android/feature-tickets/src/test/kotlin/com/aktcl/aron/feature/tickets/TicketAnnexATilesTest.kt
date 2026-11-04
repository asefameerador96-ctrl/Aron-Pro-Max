package com.aktcl.aron.feature.tickets

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot 15 with the whole list (conformance row 15): the 31 Annex A issue types (V0131) as tiles in Annex A order, the
 * "Report a different issue" button after them, and a tap on it opens Create Ticket (the `onCreate` callback).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp")
class TicketAnnexATilesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** Annex A, verbatim and in order (the same 31 as TsoManualV0130Test.theIssueTypesAreThe31AnnexATypesVerbatim). */
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
    )
    private val types = annexA.mapIndexed { i, (code, label) -> IssueType(code, label, null, i + 1, icon = i % 8) }
    private val device = FakeDevice(FakeServer(types), types)
    private val d = deps(device, FakePhotos())
    private val frame: TicketFrame = { _, content -> content() }

    private fun show(onCreate: () -> Unit) =
        rule.setContent { AronTheme(AppLanguage.EN, dark = false, sunlight = false, tier = GlassTier.B, reducedMotion = true) { QuickTicketScreen(d, frame, onCreate, {}) } }

    @Test fun thirtyOneTilesInAnnexAOrderAndTheDifferentIssueButtonOpensCreate() {
        var created = 0
        show { created++ }
        rule.waitForIdle()
        val seen = LinkedHashSet<String>()
        // the grid is lazy: scroll tile by tile; each tile sits after the one before it (row, then column) whenever both are composed
        fun pos(code: String) = rule.onAllNodes(hasTestTag(TicketTags.tile(code))).fetchSemanticsNodes().firstOrNull()?.positionInRoot
        annexA.forEachIndexed { i, (code, _) ->
            rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(TicketTags.tile(code)))
            val here = pos(code)
            assertTrue("tile $code is composed", here != null)
            if (i > 0) pos(annexA[i - 1].first)?.let { before -> assertTrue("$code after ${annexA[i - 1].first}", here!!.y > before.y || (here.y == before.y && here.x > before.x)) }
            seen += code
        }
        assertEquals(annexA.map { it.first }, seen.toList())
        assertEquals(31, seen.size)
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(TicketTags.REPORT_DIFFERENT))
        rule.onNodeWithTag(TicketTags.REPORT_DIFFERENT).performClick()
        assertEquals(1, created)
    }
}
