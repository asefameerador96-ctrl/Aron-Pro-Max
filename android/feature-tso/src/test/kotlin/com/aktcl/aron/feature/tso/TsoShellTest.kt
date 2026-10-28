package com.aktcl.aron.feature.tso

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.tso.password.CpOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The TSO shell (D-TSO-MAN-14, build contract s5 A1 and s8): the drawer's order, labels and subtitles from screenshots 9, 10, 27,
 * 30 and 44; the Dashboard as the root with the top bar, the bell and the Home button; back from a drawer screen to the Dashboard;
 * the s8 Home-button column; the gear to Settings; a screen without an entry shows the placeholder frame.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class TsoShellTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val user = HomeUser("Apsis", "tso1001", "TSO", offline = false, reauthRequired = false, updateRequired = false)

    private fun frame(screen: TsoScreen, label: String, tag: String, next: TsoScreen? = null): TsoScreenContent = { nav, _ ->
        TsoFrame(label, nav, homeButton = TsoFab.of(screen)) {
            androidx.compose.foundation.layout.Column {
                Text(label, Modifier.testTag(tag))
                if (next != null) androidx.compose.material3.TextButton(onClick = { nav.open(next) }, modifier = Modifier.testTag("$tag-next")) { Text("next") }
            }
        }
    }

    private fun show(language: AppLanguage = AppLanguage.EN, unread: Int = 0, onLogout: () -> Unit = {}): Context {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                TsoLoginTheme(language, loggedOut = false) {
                    TsoApp(
                        user, "2026-10-29", BundleStatus.Ready("b-1", "2026-10-29"), "0.1.0 (1)", {}, onLogout,
                        changePassword = { _, _ -> CpOutcome.Ok }, unread = unread,
                        screens = mapOf(
                            TsoScreen.DASHBOARD to { _, _ -> Text("dashboard", Modifier.testTag("t_dashboard")) },
                            TsoScreen.LEAVE to frame(TsoScreen.LEAVE, "Leave", "t_leave"),
                            TsoScreen.MY_TEAM to frame(TsoScreen.MY_TEAM, "My Team", "t_team", next = TsoScreen.RETAILER),
                            TsoScreen.RETAILER to frame(TsoScreen.RETAILER, "Retailer", "t_retailer"),
                        ),
                    )
                }
            }
        }
        compose.waitForIdle()
        return context
    }

    private fun systemBack() { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
    private fun openDrawer() { compose.onNodeWithTag(TsoShellTags.MENU).performClick(); compose.waitForIdle() }
    private fun row(id: String) = compose.onNodeWithTag(TsoShellTags.row(id), useUnmergedTree = true)
    private fun tap(id: String) { row(id).performScrollTo().performClick(); compose.waitForIdle() }
    private fun rowHas(id: String, title: String, subtitle: String?) {
        compose.onNode(hasTestTag(TsoShellTags.row(id)) and hasText(title), useUnmergedTree = false).assertExists()
        if (subtitle != null) compose.onNode(hasTestTag(TsoShellTags.row(id)) and hasText(subtitle)).assertExists()
    }

    @Test fun drawerEntriesKeepTheContractOrder() {
        assertEquals(
            listOf("dashboard", "tickets", "leave", "final_submit", "periphery", "my_call", "target_status", "feedback", "logout"),
            TsoDrawer.entries.map { it.id },
        )
        assertEquals(listOf("ticket_dashboard", "all_tickets", "quick_ticket", "create_ticket"), TsoDrawer.entries[1].children.map { it.id })
        assertEquals(listOf("my_team", "retailer"), TsoDrawer.entries[4].children.map { it.id })
        assertEquals(listOf("visit_plan", "set_plan"), TsoDrawer.entries[5].children.map { it.id })
        assertEquals(listOf("feedback_new", "feedback_list"), TsoDrawer.entries[7].children.map { it.id })
        assertEquals(
            listOf(
                TsoScreen.DASHBOARD, TsoScreen.TICKET_DASHBOARD, TsoScreen.TICKET_LIST, TsoScreen.TICKET_QUICK, TsoScreen.TICKET_CREATE, TsoScreen.LEAVE,
                TsoScreen.FINAL_SUBMIT, TsoScreen.MY_TEAM, TsoScreen.RETAILER, TsoScreen.MY_VISIT_PLAN, TsoScreen.SET_PLAN, TsoScreen.TARGET_STATUS,
                TsoScreen.FEEDBACK_NEW, TsoScreen.FEEDBACK_LIST,
            ),
            TsoDrawer.screens,
        )
    }

    @Test fun drawerLabelsAndSubtitlesAreTheScreenshotWords() {
        show()
        openDrawer()
        compose.onNodeWithText("TSO").assertExists()
        compose.onNodeWithText("Apsis").assertExists()
        // screenshot 9
        rowHas("dashboard", "Dashboard", "View Dashboard")
        rowHas("tickets", "Tickets", "Raise and manage tickets")
        rowHas("leave", "Leave", "Manage your leaves")
        rowHas("final_submit", "Final Submit", "Submit Sales Data")
        rowHas("periphery", "My Periphery", "Check your team and outlet")
        rowHas("my_call", "My Call", "Create and manage plans")
        rowHas("target_status", "Target Status", "Check targets")
        rowHas("feedback", "My Feedback", "Manage your feedbacks")
        rowHas("logout", "Log Out", "Logout from the app")
        // screenshot 10
        tap("tickets")
        rowHas("ticket_dashboard", "Ticket Dashboard", "Get a quick overview")
        rowHas("all_tickets", "All Tickets", "View all tickets and manage")
        rowHas("quick_ticket", "Quick Ticket", "Create ticket fast")
        rowHas("create_ticket", "Create Ticket", "Create ticket manually")
        // screenshot 27: one group open at a time
        tap("periphery")
        row("ticket_dashboard").assertDoesNotExist()
        rowHas("my_team", "My Team", "Periphery")
        rowHas("retailer", "Retailer", "Periphery")
        // screenshot 30
        tap("my_call")
        rowHas("visit_plan", "My Visit Plan", "View your visit plan")
        rowHas("set_plan", "Set Plan", "Set a new visit plan")
        // screenshot 44 (and "My feedback", the improvement)
        tap("feedback")
        rowHas("feedback_new", "Add new feedback", null)
        rowHas("feedback_list", "My feedback", null)
    }

    @Test fun banglaDrawerUsesTheLocalizationLayer() {
        show(AppLanguage.BN)
        openDrawer()
        rowHas("dashboard", "ড্যাশবোর্ড", "ড্যাশবোর্ড দেখুন")
        rowHas("logout", "লগ আউট", "অ্যাপ থেকে লগ আউট করুন")
    }

    @Test fun theDashboardIsTheRootWithTheTopBarTheBellAndHome() {
        show(unread = 1)
        compose.onNodeWithTag("t_dashboard").assertIsDisplayed()
        compose.onNodeWithText("Good day, Apsis").assertIsDisplayed()
        compose.onNodeWithTag(TsoShellTags.BADGE, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(TsoKitTags.HOME).assertExists()
        // the bell opens the inbox; with no inbox screen yet, the placeholder frame
        compose.onNodeWithTag(TsoShellTags.BELL).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(TsoShellTags.PLACEHOLDER).assertExists()
        systemBack()
        compose.onNodeWithTag("t_dashboard").assertIsDisplayed()
    }

    @Test fun noBadgeWithoutUnreadItems() {
        show(unread = 0)
        compose.onNodeWithTag(TsoShellTags.BADGE, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun backFromADrawerScreenReturnsToTheDashboard() {
        show()
        openDrawer(); tap("leave")
        compose.onNodeWithTag("t_leave").assertIsDisplayed()
        systemBack()
        compose.onNodeWithTag("t_dashboard").assertIsDisplayed()
        // a sub-screen pushes on its drawer screen: back pops it, then the Dashboard
        openDrawer(); tap("periphery"); tap("my_team")
        compose.onNodeWithTag("t_team-next").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("t_retailer").assertIsDisplayed()
        systemBack()
        compose.onNodeWithTag("t_team").assertIsDisplayed()
        systemBack()
        compose.onNodeWithTag("t_dashboard").assertIsDisplayed()
    }

    @Test fun theDashboardRowGoesHome() {
        show()
        openDrawer(); tap("dashboard")
        compose.onNodeWithTag("t_dashboard").assertIsDisplayed()
    }

    @Test fun theGearOpensSettingsAndBackReturns() {
        show()
        openDrawer()
        compose.onNodeWithTag(TsoShellTags.GEAR).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(TsoSettingsTags.CHANGE_PASSWORD).performScrollTo().assertExists()
        compose.onNodeWithTag(TsoKitTags.HOME).assertDoesNotExist()
        systemBack()
        compose.onNodeWithTag("t_dashboard").assertIsDisplayed()
    }

    @Test fun theHomeButtonColumnFollowsSection8() {
        assertEquals(
            setOf(
                TsoScreen.DASHBOARD, TsoScreen.LOGIN_SUBMIT_STATUS, TsoScreen.MY_TEAM, TsoScreen.RETAILER, TsoScreen.SET_PLAN,
                TsoScreen.MY_VISIT_PLAN, TsoScreen.FEEDBACK_NEW, TsoScreen.FEEDBACK_LIST,
            ),
            TsoFab.screens,
        )
        listOf(
            TsoScreen.TICKET_DASHBOARD, TsoScreen.TICKET_LIST, TsoScreen.TICKET_QUICK, TsoScreen.TICKET_CREATE, TsoScreen.TICKET_DETAIL, TsoScreen.LEAVE,
            TsoScreen.FINAL_SUBMIT, TsoScreen.TARGET_STATUS, TsoScreen.TARGET_DETAILS, TsoScreen.SETTINGS, TsoScreen.CHANGE_PASSWORD,
            TsoScreen.SUPPORT_UPLOAD, TsoScreen.NOTIFICATIONS,
        ).forEach { assertFalse("$it has no Home button (s8)", TsoFab.of(it)) }
        assertTrue(TsoFab.of(TsoScreen.MY_TEAM))
    }

    @Test fun theHomeButtonShowsOnlyWhereTheTableSaysAndGoesHome() {
        show()
        openDrawer(); tap("leave")
        compose.onNodeWithTag(TsoKitTags.HOME).assertDoesNotExist()
        systemBack()
        openDrawer(); tap("periphery"); tap("my_team")
        compose.onNodeWithTag("t_team-next").performClick(); compose.waitForIdle()
        compose.onNodeWithTag(TsoKitTags.HOME).performClick(); compose.waitForIdle()
        compose.onNodeWithTag("t_dashboard").assertIsDisplayed()
    }

    @Test fun aScreenWithNoEntryShowsThePlaceholderFrame() {
        show()
        openDrawer(); tap("target_status")
        compose.onNodeWithTag(TsoShellTags.PLACEHOLDER).assertExists()
        compose.onNodeWithText("Target Status is coming soon").assertExists()
        systemBack()
        compose.onNodeWithTag("t_dashboard").assertIsDisplayed()
    }

    @Test fun theStackSurvivesRecreationAsPlainNames() {
        val nav = TsoNavState()
        nav.openFromDrawer(TsoScreen.MY_TEAM)
        nav.open(TsoScreen.RETAILER)
        assertEquals(listOf(TsoRoute(TsoScreen.MY_TEAM), TsoRoute(TsoScreen.RETAILER)), nav.stack)
        nav.openFromDrawer(TsoScreen.LEAVE)
        assertEquals(listOf(TsoRoute(TsoScreen.LEAVE)), nav.stack)
        nav.openFromDrawer(TsoScreen.DASHBOARD)
        assertTrue(nav.stack.isEmpty())
    }
}
