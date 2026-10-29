package com.aktcl.aron.feature.tso

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.HomeTags
import com.aktcl.aron.feature.home.RoleHomeTags
import com.aktcl.aron.feature.home.RoleHomes
import com.aktcl.aron.feature.tso.feedback.FeedbackCategory
import com.aktcl.aron.feature.tso.feedback.FeedbackListEntry
import com.aktcl.aron.feature.tso.feedback.FeedbackNewEntry
import com.aktcl.aron.feature.tso.feedback.FeedbackItem
import com.aktcl.aron.feature.tso.feedback.FeedbackSource
import com.aktcl.aron.feature.tso.feedback.FeedbackTags
import com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitApi
import com.aktcl.aron.feature.tso.finalsubmit.FsPreviewOutcome
import com.aktcl.aron.feature.tso.finalsubmit.FsSubmitOutcome
import com.aktcl.aron.feature.tso.finalsubmit.FsTags
import com.aktcl.aron.feature.tso.leave.LeaveItem
import com.aktcl.aron.feature.tso.leave.LeaveSource
import com.aktcl.aron.feature.tso.leave.LeaveTags
import com.aktcl.aron.feature.tso.mycall.MyCallDeps
import com.aktcl.aron.feature.tso.mycall.MyCallTags
import com.aktcl.aron.feature.tso.mycall.PlanOutlet
import com.aktcl.aron.feature.tso.mycall.PlanRoute
import com.aktcl.aron.feature.tso.mycall.PlanSource
import com.aktcl.aron.feature.tso.mycall.SetPlanEntry
import com.aktcl.aron.feature.tso.mycall.VisitPlanEntry
import com.aktcl.aron.feature.tso.mycall.VisitPlanItem
import com.aktcl.aron.feature.tso.password.CpOutcome
import com.aktcl.aron.feature.tso.periphery.FixResult
import com.aktcl.aron.feature.tso.periphery.MyTeamEntry
import com.aktcl.aron.feature.tso.periphery.Nearby
import com.aktcl.aron.feature.tso.periphery.PeripheryDeps
import com.aktcl.aron.feature.tso.periphery.PeripheryTags
import com.aktcl.aron.feature.tso.periphery.PeripherySource
import com.aktcl.aron.feature.tso.periphery.RetailerEntry
import com.aktcl.aron.feature.tso.periphery.Stamped
import com.aktcl.aron.feature.tso.periphery.TeamMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * End to end through the TSO shell with every host dependency present: every drawer entry that has an area screen today opens a REAL
 * screen (no placeholder) and back returns to the Dashboard (D-TSO-MAN-14). The dashboard is supplied through the host `screens` map
 * as the app does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TsoNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val user = HomeUser("Testing Dhaka North", "tso1001", "TSO", offline = false, reauthRequired = false, updateRequired = false)

    private val cats = listOf(FeedbackCategory("suggestion", null, "Suggestion", 1))
    private val feedback = object : FeedbackSource {
        override suspend fun categories() = cats
        override suspend fun cached(): FeedbackSource.Cached? = null
        override suspend fun unsent() = emptyList<FeedbackItem>()
        override suspend fun refresh() = FeedbackSource.Cached(emptyList(), "10:00")
    }
    private val leave = object : LeaveSource {
        override suspend fun cached(): LeaveSource.Cached? = null
        override suspend fun unsent() = emptyList<LeaveItem>()
        override suspend fun refresh() = LeaveSource.Cached(emptyList(), "10:00")
    }
    private val plan = object : PlanSource {
        override suspend fun routes() = listOf(PlanRoute(1, "R", "Route", 10, null, null))
        override suspend fun outlets(routeId: Long) = emptyList<PlanOutlet>()
        override suspend fun plans(date: String) = emptyList<VisitPlanItem>()
        override suspend fun locallyDone(planUuid: String) = emptySet<Long>()
        override suspend fun taskPending(planUuid: String) = emptySet<Long>()
    }
    private val periphery = PeripheryDeps(
        object : PeripherySource {
            override suspend fun zones() = listOf(10L)
            override suspend fun nearby(lat: Double, lng: Double, radiusM: Int, zoneId: Long?): Stamped<Nearby>? = null
            override suspend fun team(zoneId: Long?, date: String): Stamped<List<TeamMember>>? = null
            override suspend fun routeCodes() = emptyMap<Long, String>()
        },
        { FixResult.Failed }, online = { false }, nowMs = { 0L },
    )
    private val fs = object : FinalSubmitApi {
        override suspend fun zones() = listOf(10L)
        override suspend fun preview(zoneId: Long, date: String): FsPreviewOutcome = FsPreviewOutcome.Failed(com.aktcl.aron.feature.tso.finalsubmit.FsFailure.OFFLINE)
        override suspend fun submit(clientUuid: String, zoneId: Long, date: String): FsSubmitOutcome = FsSubmitOutcome.AlreadySubmitted
    }

    private var back: androidx.activity.OnBackPressedDispatcher? = null

    /** The system back: every drawer screen returns to the Dashboard (the top-bar back button calls the same path). */
    private fun goHome() { compose.runOnUiThread { back!!.onBackPressed() }; compose.waitForIdle() }

    /** The drawer path of each screen: the group to expand (if any) and the row. */
    private val drawerPath = mapOf(
        TsoScreen.LEAVE to listOf("leave"), TsoScreen.FINAL_SUBMIT to listOf("final_submit"),
        TsoScreen.MY_TEAM to listOf("periphery", "my_team"), TsoScreen.RETAILER to listOf("periphery", "retailer"),
        TsoScreen.MY_VISIT_PLAN to listOf("my_call", "visit_plan"), TsoScreen.SET_PLAN to listOf("my_call", "set_plan"),
        TsoScreen.FEEDBACK_NEW to listOf("feedback", "feedback_new"), TsoScreen.FEEDBACK_LIST to listOf("feedback", "feedback_list"),
    )

    private fun openFromDrawer(screen: TsoScreen) {
        compose.onNodeWithTag(TsoShellTags.MENU).performClick(); compose.waitForIdle()
        if (screen == TsoScreen.SETTINGS) { compose.onNodeWithTag(TsoShellTags.GEAR).performScrollTo().performClick(); compose.waitForIdle(); return }
        val path = drawerPath.getValue(screen)
        fun click(id: String) { compose.onNodeWithTag(TsoShellTags.row(id), useUnmergedTree = true).performScrollTo().performClick(); compose.waitForIdle() }
        // the drawer keeps the last open group: expand only when the row is not shown yet
        if (path.size == 2 && compose.onAllNodesWithTag(TsoShellTags.row(path[1]), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) click(path[0])
        click(path.last())
    }

    private fun show() {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                TsoLoginTheme(AppLanguage.EN, loggedOut = false) {
                    back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                    // the area screens as the app's AreasHost wires them: one entry per drawer row (A3's per-screen entries, R-W-12 (2))
                    val today = { "2026-10-17" }
                    val myCall = MyCallDeps(plan, { 0 }, { true }, { true }, { null }, { emptyList() })
                    TsoApp(
                        user, "2026-10-17", BundleStatus.Ready("b-1", "2026-10-17"), "0.1.0 (1)", {}, {},
                        changePassword = { _, _ -> CpOutcome.Ok }, support = { _ -> Text("support", Modifier_testTag("tso_nav_support")) },
                        screens = mapOf<TsoScreen, TsoScreenContent>(
                            TsoScreen.DASHBOARD to { _, _ -> Text("dashboard", Modifier_testTag("tso_nav_dashboard")) },
                            TsoScreen.LEAVE to { nav, _ -> com.aktcl.aron.feature.tso.leave.LeaveFlow(leave, { _, _ -> true }, user.username, today, { nav.back() }) },
                            TsoScreen.FINAL_SUBMIT to { nav, _ -> com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitFlow(fs, user.username, 7, today, { nav.back() }) },
                            TsoScreen.MY_TEAM to { nav, _ -> MyTeamEntry(periphery, user.username, today, nav) },
                            TsoScreen.RETAILER to { nav, _ -> RetailerEntry(periphery, user.username, nav) },
                            TsoScreen.MY_VISIT_PLAN to { nav, _ -> VisitPlanEntry(myCall, user.username, today, nav) },
                            TsoScreen.SET_PLAN to { nav, _ -> SetPlanEntry(myCall, user.username, today, nav) },
                            TsoScreen.FEEDBACK_NEW to { nav, _ -> FeedbackNewEntry(feedback, { _, _, _ -> true }, null, user.username, { java.time.Instant.now().toString() }, nav) },
                            TsoScreen.FEEDBACK_LIST to { nav, _ -> FeedbackListEntry(feedback, canWrite = true, user.username, nav) },
                        ),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun Modifier_testTag(tag: String) = androidx.compose.ui.Modifier.testTag(tag)

    /** The tag of the real screen of each destination (never the placeholder's RoleHomeTags.EMPTY). */
    private val realScreenTag = mapOf(
        TsoScreen.DASHBOARD to "tso_nav_dashboard", TsoScreen.LEAVE to LeaveTags.APPLY, TsoScreen.FINAL_SUBMIT to FsTags.GET,
        TsoScreen.MY_TEAM to PeripheryTags.ZONE, TsoScreen.RETAILER to PeripheryTags.RADIUS,
        TsoScreen.MY_VISIT_PLAN to MyCallTags.SHOW, TsoScreen.SET_PLAN to MyCallTags.SHOW,
        TsoScreen.FEEDBACK_NEW to FeedbackTags.SEND, TsoScreen.FEEDBACK_LIST to FeedbackTags.NEW, TsoScreen.SETTINGS to TsoSettingsTags.CHANGE_PASSWORD,
    )

    /** The title each entry's frame prints (the two My Call forms share their tags, so the title tells them apart). */
    private val titleRes = mapOf(
        TsoScreen.MY_TEAM to R.string.tso_pe_my_teams, TsoScreen.RETAILER to R.string.tso_pe_outlets_title,
        TsoScreen.MY_VISIT_PLAN to R.string.tso_mc_visit_plan, TsoScreen.SET_PLAN to R.string.tso_mc_set_plan,
        TsoScreen.FEEDBACK_NEW to R.string.tso_fb_create_title,
    )

    private fun assertRealScreen(screen: TsoScreen) {
        try { compose.waitUntil(5_000) { compose.onAllNodesWithTagCount(realScreenTag.getValue(screen)) > 0 } } catch (e: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("no real screen for $screen (" + realScreenTag.getValue(screen) + ")\n" + compose.onRoot().printToString().take(1500), e) }
        compose.onNodeWithTag(RoleHomeTags.EMPTY).assertDoesNotExist()
        compose.onNodeWithTag(TsoShellTags.PLACEHOLDER).assertDoesNotExist()
        titleRes[screen]?.let { res ->
            val t = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN).getString(res)
            assertTrue("$screen shows its title $t", compose.onAllNodes(androidx.compose.ui.test.hasText(t) and androidx.compose.ui.test.isHeading()).fetchSemanticsNodes().isNotEmpty())
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private val screensWithAreas = listOf(
        TsoScreen.LEAVE, TsoScreen.FINAL_SUBMIT, TsoScreen.MY_TEAM, TsoScreen.RETAILER, TsoScreen.MY_VISIT_PLAN, TsoScreen.SET_PLAN,
        TsoScreen.FEEDBACK_NEW, TsoScreen.FEEDBACK_LIST, TsoScreen.SETTINGS,
    )

    @Test fun everyDrawerScreenWithAnAreaOpensARealScreenAndBackReturnsToTheDashboard() {
        show()
        assertRealScreen(TsoScreen.DASHBOARD)
        for (screen in screensWithAreas) {
            openFromDrawer(screen)
            assertRealScreen(screen)
            goHome()
            compose.onNodeWithTag("tso_nav_dashboard").assertIsDisplayed()
        }
    }

    @Test fun everyTsoScreenIsAccessible() {
        show()
        val problems = mutableListOf<String>()
        fun sweep(where: String) { compose.waitForIdle(); compose.a11yProblems().forEach { problems += "$where: $it" } }
        sweep("dashboard")
        compose.onNodeWithTag(TsoShellTags.MENU).performClick(); sweep("drawer"); goHome()
        for (screen in screensWithAreas) {
            openFromDrawer(screen)
            assertRealScreen(screen)
            sweep(screen.name)
            goHome()
        }
        assertEquals("accessibility problems: " + problems.joinToString("; "), emptyList<String>(), problems)
    }
}
