package com.aktcl.aron.tso

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.sync.push.PushMessage
import com.aktcl.aron.core.sync.push.PushNotice
import com.aktcl.aron.core.sync.push.PushNotices
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.tso.CardLoad
import com.aktcl.aron.feature.tso.TsoApp
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoLoginTheme
import com.aktcl.aron.feature.tso.TsoOpenRequest
import com.aktcl.aron.feature.tso.TsoRoute
import com.aktcl.aron.feature.tso.TsoScreen
import com.aktcl.aron.feature.tso.TsoScreenContent
import com.aktcl.aron.feature.tso.notifications.TsoInboxContent
import com.aktcl.aron.feature.tso.notifications.TsoInboxPage
import com.aktcl.aron.feature.tso.notifications.TsoInboxTags
import com.aktcl.aron.feature.tso.notifications.TsoNotification
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * tso-manual X4b (R-W-12 (1)), end to end on the phone side: the FCM data message, the notification's launch intent that
 * [PushNotices] builds, [TsoPushOpen] (what MainActivity reads in onCreate and onNewIntent), and the route TsoApp opens on the
 * Dashboard. A `ticket_updated` push opens the Ticket Dashboard through [TicketPush.open] with its argument (online read on
 * open); a `notification` push opens the inbox, whose ticket row opens TICKET_DETAIL with the ticket uuid; back returns to the
 * Dashboard. A ticket screen on show reads again on a ticket or inbox push ([RefreshOnTicketPush]), never on a timer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TicketPushNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val user = HomeUser("Apsis", "tso1001", "TSO", offline = false, reauthRequired = false, updateRequired = false)
    private val ticketUuid = "6f1c0b9e-1111-4c4c-9a9a-000000000003"
    private val drawn = mutableListOf<TsoRoute>()

    /** The intent a tap on the notice of this FCM data message launches, built by the push layer as on the phone. */
    private fun tapped(data: Map<String, String>): Intent {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val notice = checkNotNull(PushMessage.parse(data)?.notice) { "no notice for $data" }
        val pending = checkNotNull(PushNotices.openIntent(ctx, notice)) { "no launch intent for the app" }
        return shadowOf(pending).savedIntent
    }

    private val inbox = TsoInboxPage(
        "2026-10-29T09:30:00Z", 1,
        listOf(
            TsoNotification(7, "ticket_updated", "support_ticket", ticketUuid, "Ticket INC-0001 updated", null, "Support replied", null, "2026-10-29T09:00:00Z", "2026-10-29", null),
        ),
        null,
    )

    private fun screens(): Map<TsoScreen, TsoScreenContent> = mapOf(
        TsoScreen.DASHBOARD to { _, _ -> Text("dashboard", Modifier.testTag("t_dashboard")) },
        TsoScreen.TICKET_DASHBOARD to { nav, arg ->
            SideEffect { drawn += TsoRoute(TsoScreen.TICKET_DASHBOARD, arg) }
            TsoFrame("Ticket Dashboard", nav, homeButton = false) { Text("online read on open: " + TicketPush.refreshOnOpen(arg), Modifier.testTag("t_tickets")) }
        },
        TsoScreen.TICKET_DETAIL to { nav, arg ->
            SideEffect { drawn += TsoRoute(TsoScreen.TICKET_DETAIL, arg) }
            TsoFrame("Ticket", nav, homeButton = false) { Text("ticket $arg", Modifier.testTag("t_detail")) }
        },
        // as TsoInboxHost wires it: a row with a screen of its own opens it with its argument
        TsoScreen.NOTIFICATIONS to { nav, _ ->
            TsoFrame("Notifications", nav, homeButton = false) {
                TsoInboxContent(CardLoad(inbox), emptyList(), 0L, refreshing = false, pendingRead = false, onRefresh = {}, onOpen = { t -> nav.open(t.screen, t.arg) }, onOlder = null)
            }
        },
    )

    private fun launch(body: (ActivityScenario<ComponentActivity>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        ActivityScenario.launch(ComponentActivity::class.java).use(body)
    }

    private fun show(scenario: ActivityScenario<ComponentActivity>, request: TsoOpenRequest?) {
        val pending = mutableStateOf(request)
        scenario.onActivity { activity ->
            activity.setContent {
                TsoLoginTheme(AppLanguage.EN, loggedOut = false) {
                    TsoApp(
                        user, "2026-10-29", BundleStatus.Ready("b-1", "2026-10-29"), "0.1.0 (1)", {}, {},
                        screens = screens(), openRequest = pending.value, onOpenRequestHandled = { pending.value = null },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun back(scenario: ActivityScenario<ComponentActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test fun aTappedTicketPushOpensTheTicketDashboardThroughTicketPush() = launch { scenario ->
        val intent = tapped(mapOf("kind" to "sync_nudge", "reason" to "ticket_updated"))
        assertEquals(PushNotices.OPEN_TICKETS, PushNotices.opens(intent))
        show(scenario, TsoPushOpen.request(intent))
        compose.onNodeWithTag("t_tickets").assertIsDisplayed()
        compose.onNodeWithText("online read on open: true").assertExists() // D-A2-30: the push reason is the argument
        assertEquals(TsoRoute(TsoScreen.TICKET_DASHBOARD, TicketPush.REASON), drawn.last())
        back(scenario)
        compose.onNodeWithTag("t_dashboard").assertIsDisplayed()
    }

    @Test fun anInboxPushOpensTheInboxAndItsTicketRowOpensThatTicket() = launch { scenario ->
        val intent = tapped(mapOf("kind" to "sync_nudge", "reason" to "notification"))
        assertEquals(PushNotices.OPEN_INBOX, PushNotices.opens(intent))
        show(scenario, TsoPushOpen.request(intent))
        compose.onNodeWithTag(TsoInboxTags.row(7)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("t_detail").assertIsDisplayed()
        compose.onNodeWithText("ticket $ticketUuid").assertExists()
        assertEquals(TsoRoute(TsoScreen.TICKET_DETAIL, ticketUuid), drawn.last())
        back(scenario)
        compose.onNodeWithTag(TsoInboxTags.row(7)).assertIsDisplayed()
        back(scenario)
        compose.onNodeWithTag("t_dashboard").assertIsDisplayed()
    }

    @Test fun otherPushesOpenNothing() {
        assertNull(TsoPushOpen.request(tapped(mapOf("kind" to "sync_nudge", "reason" to "task_assigned")))) // the SR task list
        assertNull(TsoPushOpen.request(tapped(mapOf("kind" to "sync_nudge", "reason" to "day_final_submitted")))) // no extra
        assertNull(TsoPushOpen.request(null as Intent?))
        assertNull(TsoPushOpen.request("something_else"))
    }

    @Test fun aTicketScreenOnShowReadsAgainOnATicketOrInboxPushOnly() = launch { scenario ->
        val pushes = MutableSharedFlow<PushNotice>(extraBufferCapacity = 8)
        val shown = mutableStateOf(true)
        val reads = mutableIntStateOf(0)
        scenario.onActivity { activity ->
            activity.setContent { if (shown.value) RefreshOnTicketPush(pushes) { reads.intValue++ } }
        }
        compose.waitForIdle()
        assertEquals(0, reads.intValue) // nothing on show: the screen's own load does the first read
        pushes.tryEmit(PushNotice.TicketUpdated); compose.waitForIdle()
        assertEquals(1, reads.intValue)
        pushes.tryEmit(PushNotice.InboxUpdated); compose.waitForIdle() // B1's desk update today (reason notification)
        assertEquals(2, reads.intValue)
        pushes.tryEmit(PushNotice.DayFinalSubmitted); pushes.tryEmit(PushNotice.TaskAssigned); compose.waitForIdle()
        assertEquals(2, reads.intValue)
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        pushes.tryEmit(PushNotice.TicketUpdated); compose.waitForIdle()
        assertEquals(2, reads.intValue) // left the screen: no read
    }

    @Test fun aFailedReadKeepsListening() = launch { scenario ->
        val pushes = MutableSharedFlow<PushNotice>(extraBufferCapacity = 8)
        var calls = 0
        scenario.onActivity { activity ->
            activity.setContent { RefreshOnTicketPush(pushes) { calls++; if (calls == 1) error("offline") } }
        }
        compose.waitForIdle()
        pushes.tryEmit(PushNotice.TicketUpdated); compose.waitForIdle()
        pushes.tryEmit(PushNotice.TicketUpdated); compose.waitForIdle()
        assertEquals(2, calls)
    }
}
