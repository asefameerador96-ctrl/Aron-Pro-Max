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
import com.aktcl.aron.feature.home.RoleHomeTags
import com.aktcl.aron.feature.home.RoleHomes
import com.aktcl.aron.feature.tso.feedback.FeedbackCategory
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
import com.aktcl.aron.feature.tso.mycall.VisitPlanItem
import com.aktcl.aron.feature.tso.password.CpOutcome
import com.aktcl.aron.feature.tso.periphery.FixResult
import com.aktcl.aron.feature.tso.periphery.Nearby
import com.aktcl.aron.feature.tso.periphery.PeripheryTags
import com.aktcl.aron.feature.tso.periphery.PeripherySource
import com.aktcl.aron.feature.tso.periphery.Stamped
import com.aktcl.aron.feature.tso.periphery.TeamMember
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * End to end through the TSO shell with every host dependency present: every Home tile and every drawer entry opens a REAL
 * screen (no placeholder, no dead tile) and the Home button comes back. The dashboard is supplied through the host `screens` map as the app does.
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

    private fun show() {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AronTheme(AppLanguage.EN) {
                    TsoApp(
                        user, "2026-10-17", BundleStatus.Ready("b-1", "2026-10-17"), "0.1.0 (1)", {}, {},
                        leave = leave, leaveQueue = { _, _ -> true }, today = { "2026-10-17" },
                        feedback = feedback, feedbackQueue = { _, _, _ -> true },
                        myCall = MyCallDeps(plan, { 0 }, { true }, { true }, { null }, { emptyList() }),
                        finalSubmit = fs, periphery = periphery, userId = 7,
                        changePassword = { _, _ -> CpOutcome.Ok }, support = { _ -> Text("support", Modifier_testTag("tso_nav_support")) },
                        screens = mapOf(TsoScreen.DASHBOARD to { _ -> Text("dashboard", Modifier_testTag("tso_nav_dashboard")) }),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun Modifier_testTag(tag: String) = androidx.compose.ui.Modifier.testTag(tag)

    /** The tag of the real screen of each destination (never the placeholder's RoleHomeTags.EMPTY). */
    private val realScreenTag = mapOf(
        TsoScreen.DASHBOARD to "tso_nav_dashboard", TsoScreen.LEAVE to LeaveTags.APPLY, TsoScreen.FINAL_SUBMIT to FsTags.GET, TsoScreen.PERIPHERY to PeripheryTags.TEAM,
        TsoScreen.MY_CALL to MyCallTags.SET_PLAN, TsoScreen.FEEDBACK to FeedbackTags.NEW, TsoScreen.SETTINGS to TsoSettingsTags.CHANGE_PASSWORD,
    )

    private fun assertRealScreen(screen: TsoScreen) {
        try { compose.waitUntil(5_000) { compose.onAllNodesWithTagCount(realScreenTag.getValue(screen)) > 0 } } catch (e: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("no real screen for $screen (" + realScreenTag.getValue(screen) + ")\n" + compose.onRoot().printToString().take(1500), e) }
        compose.onNodeWithTag(RoleHomeTags.EMPTY).assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test fun everyHomeTileOpensARealScreenAndHomeComesBack() {
        show()
        for (tile in RoleHomes.tsoTiles().filter { it.id != RoleHomes.TsoTile.DEVICE_OTP }) {
            val screen = TsoScreen.of(tile)
            compose.onNodeWithTag(RoleHomeTags.tile(tile.id)).performScrollTo().performClick()
            assertRealScreen(screen)
            compose.onNodeWithTag(TsoChromeTags.HOME_BUTTON).performClick(); compose.waitForIdle()
            compose.onNodeWithTag(TsoChromeTags.GREETING).assertIsDisplayed()
        }
    }

    @Test fun everyDrawerEntryOpensARealScreen() {
        show()
        for (e in tsoDrawerEntries()) {
            compose.onNodeWithTag(TsoChromeTags.MENU).performClick(); compose.waitForIdle()
            compose.onNodeWithTag(TsoChromeTags.entry(e.screen)).performScrollTo().performClick()
            assertRealScreen(e.screen)
            compose.onNodeWithTag(TsoChromeTags.HOME_BUTTON).performClick(); compose.waitForIdle()
        }
    }

    @Test fun theDrawerAndTheTilesReachTheSameSet() {
        val tiles = RoleHomes.tsoTiles().filter { it.id != RoleHomes.TsoTile.DEVICE_OTP }.map { TsoScreen.of(it) }.toSet()
        val drawer = tsoDrawerEntries().map { it.screen }.toSet() - TsoScreen.SETTINGS
        assertEquals(tiles, drawer)
    }

    @Test fun everyTsoScreenIsAccessible() {
        show()
        val problems = mutableListOf<String>()
        fun sweep(where: String) { compose.waitForIdle(); compose.a11yProblems().forEach { problems += "$where: $it" } }
        sweep("home")
        for (tile in RoleHomes.tsoTiles().filter { it.id != RoleHomes.TsoTile.DEVICE_OTP }) {
            val screen = TsoScreen.of(tile)
            compose.onNodeWithTag(RoleHomeTags.tile(tile.id)).performScrollTo().performClick()
            assertRealScreen(screen)
            sweep(screen.name)
            compose.onNodeWithTag(TsoChromeTags.HOME_BUTTON).performClick(); compose.waitForIdle()
        }
        compose.onNodeWithTag(TsoChromeTags.MENU).performClick(); sweep("drawer")
        assertEquals("accessibility problems:\n" + problems.joinToString("\n"), emptyList<String>(), problems)
    }
}
