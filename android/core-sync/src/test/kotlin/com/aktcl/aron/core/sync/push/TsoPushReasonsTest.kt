package com.aktcl.aron.core.sync.push

import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * tso-manual A1 (D-TSO-MAN-26, 31; build contract s5 A1): the push reasons `notification`, `ticket_updated` and
 * `day_final_submitted` arrive as `kind = sync_nudge` with no other data, show a fixed Bangla/English text (no business data), still
 * pull the bundle (the SR and AMO day lock refreshes from it), open the inbox or the ticket dashboard when tapped, and are announced
 * in process so the TSO app re-reads the bell without polling.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TsoPushReasonsTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val bangla: Context = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag("bn")) })
    private val manager = app.getSystemService(NotificationManager::class.java)

    @org.junit.Before fun launcher() {
        val main = android.content.ComponentName(app.packageName, "com.aktcl.aron.test.Main")
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(main)
        pm.addIntentFilterForActivity(main, android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply { addCategory(android.content.Intent.CATEGORY_LAUNCHER) })
    }

    @Test fun theThreeReasonsGiveTheirNoticeAndStillPullTheBundle() {
        val cases = mapOf(
            "notification" to PushNotice.InboxUpdated, "ticket_updated" to PushNotice.TicketUpdated, "day_final_submitted" to PushNotice.DayFinalSubmitted,
        )
        cases.forEach { (reason, notice) ->
            val m = PushMessage.parse(mapOf("kind" to "sync_nudge", "reason" to reason))!!
            assertEquals(reason, notice, m.notice)
            assertTrue(reason, PushPullKind.BUNDLE in m.pulls)
            assertFalse("$reason is not urgent: the midnight fan-out keeps the 120 s spread", m.urgent)
        }
        assertEquals(setOf(PushPullKind.BUNDLE), PushMessage.parse(mapOf("kind" to "sync_nudge", "reason" to "notification"))!!.pulls)
        assertEquals(setOf(PushPullKind.BUNDLE), PushMessage.parse(mapOf("kind" to "sync_nudge", "reason" to "ticket_updated"))!!.pulls)
        // X4a fix round (checker low): the lock push also pulls the config, so a locking policy set before the close arrives with it
        assertEquals(setOf(PushPullKind.BUNDLE, PushPullKind.CONFIG), PushMessage.parse(mapOf("kind" to "sync_nudge", "reason" to "day_final_submitted"))!!.pulls)
        // business data in the push is never read: the notice is the fixed one
        assertEquals(PushNotice.InboxUpdated, PushMessage.parse(mapOf("kind" to "sync_nudge", "reason" to "notification", "title_en" to "Leave approved for X"))!!.notice)
        assertEquals(PushNotice.TaskAssigned, PushMessage.noticeFor("task_assigned"))
        assertNull(PushMessage.noticeFor("route_changed"))
        assertNull(PushMessage.noticeFor(null))
    }

    @Test fun theFixedBanglaTextsTheirChannelsAndWhatATapOpens() {
        shadowOf(app as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        PushNotices.ensureChannels(bangla)
        assertEquals("আপডেট", manager.getNotificationChannel(PushNotices.CHANNEL_UPDATES).name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(PushNotices.CHANNEL_DAY).importance)

        assertTrue(PushNotices.show(bangla, PushNotice.InboxUpdated, bangla = true))
        val inbox = shadowOf(manager).getNotification(PushNotices.ID_INBOX)
        assertNotNull(inbox)
        assertEquals(PushNotices.CHANNEL_UPDATES, inbox.channelId)
        assertEquals("নতুন নোটিফিকেশন", shadowOf(inbox).contentTitle.toString())
        assertEquals(PushNotices.OPEN_INBOX, PushNotices.opens(shadowOf(inbox.contentIntent).savedIntent))
        assertFalse(PushNotices.opensTasks(shadowOf(inbox.contentIntent).savedIntent))

        assertTrue(PushNotices.show(app, PushNotice.TicketUpdated, bangla = false))
        val ticket = shadowOf(manager).getNotification(PushNotices.ID_TICKET)
        assertEquals("Ticket updated", shadowOf(ticket).contentTitle.toString())
        assertEquals(PushNotices.OPEN_TICKETS, PushNotices.opens(shadowOf(ticket.contentIntent).savedIntent))

        assertTrue(PushNotices.show(bangla, PushNotice.DayFinalSubmitted, bangla = true))
        val day = shadowOf(manager).getNotification(PushNotices.ID_DAY)
        assertEquals(PushNotices.CHANNEL_DAY, day.channelId)
        assertEquals("দিন বন্ধ হয়েছে", shadowOf(day).contentTitle.toString())
        assertNull("the day notice opens the app, no screen", PushNotices.opens(shadowOf(day.contentIntent).savedIntent))
    }

    /**
     * X4a fix round (checker high; owner decision 2026-11-04, D-TSO-MAN-49): a zone-day close under accept_and_flag (the
     * shipped default) arrives as `day_state_changed` (backend `finalSubmitPushReason`): no notification and no in-process
     * notice, so no rep is told that sales are closed while the server accepts them; the bundle pull still brings the day
     * state.
     *
     * Fix round 4 (checker medium): the server also sends `day_state_changed` to the closed zone-day's SR and AMO phones when
     * an admin returns the policy to accept_and_flag after a locked close (backend `LatePolicyPush`). So it pulls the config
     * too (the phone's held quarantine or reject is replaced and the lock lifts), and it takes the earlier "Day closed ... new
     * sales are closed" notice off the tray, as PushShell wires it. The other reasons keep the tray as it is.
     */
    @Test fun aCloseUnderAcceptAndFlagShowsNothingAndPullsTheDayStateAndThePolicy() {
        val m = PushMessage.parse(mapOf("kind" to "sync_nudge", "reason" to PushMessage.REASON_DAY_STATE_CHANGED))!!
        assertNull("no notice", m.notice)
        assertEquals(setOf(PushPullKind.BUNDLE, PushPullKind.CONFIG), m.pulls)
        assertFalse(m.urgent)
        assertTrue(m.withdrawsDayNotice)
        listOf("day_final_submitted", "notification", "ticket_updated", "task_assigned").forEach { r ->
            assertFalse("$r withdraws nothing", PushMessage.parse(mapOf("kind" to "sync_nudge", "reason" to r))!!.withdrawsDayNotice)
        }
    }

    /** Fix round 4: what [aCloseUnderAcceptAndFlagShowsNothingAndPullsTheDayStateAndThePolicy] says, on the tray, as PushShell wires the dispatcher. */
    @Test fun dayStateChangedWithdrawsTheDayNoticeAndNothingElse() {
        shadowOf(app as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        PushNotices.ensureChannels(app)
        val shown = mutableListOf<PushNotice>()
        val scheduled = mutableListOf<PushPullKind>()
        // as PushShell wires the dispatcher
        val dispatcher = PushDispatcher(
            show = { shown += it; PushNotices.show(app, it, bangla = false) }, schedule = { k, _ -> scheduled += k },
            withdrawDayNotice = { PushNotices.withdrawDay(app) },
        )
        // the earlier close under quarantine: the "Day closed" notice is on the tray, and an inbox notice beside it
        dispatcher.onMessage(mapOf("kind" to "sync_nudge", "reason" to "day_final_submitted"))
        dispatcher.onMessage(mapOf("kind" to "sync_nudge", "reason" to "notification"))
        assertNotNull(shadowOf(manager).getNotification(PushNotices.ID_DAY))
        // a later push of another reason keeps it
        dispatcher.onMessage(mapOf("kind" to "sync_nudge", "reason" to "ticket_updated"))
        assertNotNull("ticket_updated keeps the day notice", shadowOf(manager).getNotification(PushNotices.ID_DAY))
        shown.clear(); scheduled.clear()
        // the admin returns the policy to accept_and_flag: day_state_changed
        dispatcher.onMessage(mapOf("kind" to "sync_nudge", "reason" to "day_state_changed"))
        assertNull("the day notice is withdrawn", shadowOf(manager).getNotification(PushNotices.ID_DAY))
        assertNotNull("the inbox notice stays", shadowOf(manager).getNotification(PushNotices.ID_INBOX))
        assertEquals("and nothing is shown", emptyList<PushNotice>(), shown)
        assertTrue("the pulls are scheduled as before", PushPullKind.BUNDLE in scheduled)
    }

    @Test fun everyNoticeIsAnnouncedInProcessEvenWithNotificationsOff() = runBlocking {
        val next = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { PushNotices.arrived.first { it == PushNotice.InboxUpdated } }
        // no POST_NOTIFICATIONS granted on API 36: nothing shows, the running app still hears it
        PushDispatcher(show = { PushNotices.show(app, it, bangla = false) }, schedule = { _, _ -> }).onMessage(mapOf("kind" to "sync_nudge", "reason" to "notification"))
        assertEquals(PushNotice.InboxUpdated, next.await())
    }
}
