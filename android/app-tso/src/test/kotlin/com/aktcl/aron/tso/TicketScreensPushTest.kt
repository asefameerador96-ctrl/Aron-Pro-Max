package com.aktcl.aron.tso

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SupportTicketEventPayload
import com.aktcl.aron.contract.SupportTicketPayload
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.sync.push.PushNotice
import com.aktcl.aron.feature.tickets.DetailRead
import com.aktcl.aron.feature.tickets.IssueType
import com.aktcl.aron.feature.tickets.LocalEvent
import com.aktcl.aron.feature.tickets.Ticket
import com.aktcl.aron.feature.tickets.TicketDeps
import com.aktcl.aron.feature.tickets.TicketDetail
import com.aktcl.aron.feature.tickets.TicketOutbox
import com.aktcl.aron.feature.tickets.TicketQueue
import com.aktcl.aron.feature.tickets.TicketSnapshot
import com.aktcl.aron.feature.tickets.TicketSource
import com.aktcl.aron.feature.tso.TsoLoginTheme
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.TsoScreen
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * tso-manual X4b fix round (R-W-12 (1), checker lows): the ticket screens as [ticketScreens] wires them read again on a push.
 * The Ticket Dashboard, All Tickets and a ticket's detail each make one more online read for a `ticket_updated` push and for an
 * inbox push (B1's desk update, reason `notification`), none for any other push, and none once the screen is left. While the
 * app is stopped a push makes no read: the pushes of that time are one read when the screen is visible again (docs/04).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TicketScreensPushTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val ticketUuid = "6f1c0b9e-1111-4c4c-9a9a-000000000003"

    /** A source that counts the online reads (list and detail); offline answers, nothing kept, so every open reads online. */
    private class CountingSource : TicketSource {
        var lists = 0
        var details = 0
        override suspend fun issueTypes(): List<IssueType> = emptyList()
        override suspend fun cached(): TicketSnapshot? = null
        override suspend fun list(): TicketSnapshot? { lists++; return null }
        override suspend fun detail(ticketUuid: String): DetailRead { details++; return DetailRead.Unavailable }
        override suspend fun cachedDetail(ticketUuid: String): TicketDetail? = null
        override suspend fun outbox(): TicketOutbox = TicketOutbox.EMPTY
    }

    private val queue = object : TicketQueue {
        override suspend fun ticket(uuid: String, payload: SupportTicketPayload, photoUuids: List<String>, local: Ticket) = false
        override suspend fun event(uuid: String, payload: SupportTicketEventPayload, photoUuids: List<String>, local: LocalEvent) = false
    }

    private val nav = object : TsoNav {
        override fun open(screen: TsoScreen, arg: String?) = Unit
        override fun back() = Unit
        override fun home() = Unit
    }

    private val source = CountingSource()
    private val pushes = MutableSharedFlow<PushNotice>(extraBufferCapacity = 8)

    /** The app's wiring with test ports: the real screens, a counting source and this test's push flow. */
    private val screens = ticketScreens(
        object : TicketScreenPorts {
            override val deps = TicketDeps(source, queue, photos = null, now = { 1_761_700_000_000L })
            override val arrived = pushes
            override suspend fun refreshConfig() = Unit
        },
    )

    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun launch(body: (ActivityScenario<ComponentActivity>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        ActivityScenario.launch(ComponentActivity::class.java).use(body)
    }

    private fun push(vararg notices: PushNotice) {
        notices.forEach { pushes.tryEmit(it) }
        compose.waitForIdle()
    }

    @Test fun theWiredTicketScreensReadAgainOnATicketOrInboxPushOnly() = launch { scenario ->
        val shown = mutableStateOf<Pair<TsoScreen, String?>?>(TsoScreen.TICKET_DASHBOARD to null)
        scenario.onActivity { activity ->
            activity.setContent {
                TsoLoginTheme(AppLanguage.EN, loggedOut = false) {
                    shown.value?.let { (screen, arg) -> screens.getValue(screen)(nav, arg) }
                }
            }
        }
        compose.waitForIdle()
        assertEquals("the Ticket Dashboard reads as it opens", 1, source.lists)
        push(PushNotice.TicketUpdated)
        assertEquals(2, source.lists)
        push(PushNotice.InboxUpdated)
        assertEquals(3, source.lists)
        push(PushNotice.DayFinalSubmitted, PushNotice.TaskAssigned)
        assertEquals("other pushes read nothing", 3, source.lists)

        compose.runOnIdle { shown.value = TsoScreen.TICKET_LIST to null }
        compose.waitForIdle()
        assertEquals("All Tickets reads as it opens", 4, source.lists)
        push(PushNotice.TicketUpdated)
        assertEquals(5, source.lists)
        push(PushNotice.InboxUpdated)
        assertEquals(6, source.lists)
        push(PushNotice.DayFinalSubmitted)
        assertEquals(6, source.lists)

        compose.runOnIdle { shown.value = TsoScreen.TICKET_DETAIL to ticketUuid }
        compose.waitForIdle()
        assertEquals("the detail reads as it opens", 1, source.details)
        push(PushNotice.TicketUpdated)
        assertEquals(2, source.details)
        push(PushNotice.InboxUpdated)
        assertEquals(3, source.details)
        push(PushNotice.DayFinalSubmitted)
        assertEquals(3, source.details)
        assertEquals("the screens left read nothing on these pushes", 6, source.lists)

        compose.runOnIdle { shown.value = null }
        compose.waitForIdle()
        push(PushNotice.TicketUpdated, PushNotice.InboxUpdated)
        assertEquals(6, source.lists); assertEquals(3, source.details)
    }

    @Test fun pushesWhileTheAppIsStoppedAreOneReadWhenItIsVisibleAgain() = launch { scenario ->
        val owner = TestOwner()
        scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    TsoLoginTheme(AppLanguage.EN, loggedOut = false) { screens.getValue(TsoScreen.TICKET_LIST)(nav, null) }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(1, source.lists)
        push(PushNotice.TicketUpdated)
        assertEquals("on show: one read per push", 2, source.lists)

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED } // the app went to the background
        compose.waitForIdle()
        push(PushNotice.TicketUpdated, PushNotice.InboxUpdated, PushNotice.TicketUpdated)
        assertEquals("no read in the background", 2, source.lists)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals("one read on return for the pushes of that time", 3, source.lists)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED } // visible behind a dialog: still reads
        compose.waitForIdle()
        push(PushNotice.InboxUpdated)
        assertEquals(4, source.lists)

        // a push that refreshes nothing while stopped: nothing due on return
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        push(PushNotice.DayFinalSubmitted)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(4, source.lists)
        // and the next push on show reads as before
        push(PushNotice.TicketUpdated)
        assertEquals(5, source.lists)
    }
}
