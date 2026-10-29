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
            assertEquals(reason, setOf(PushPullKind.BUNDLE), m.pulls)
            assertFalse("$reason is not urgent: the midnight fan-out keeps the 120 s spread", m.urgent)
        }
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

    @Test fun everyNoticeIsAnnouncedInProcessEvenWithNotificationsOff() = runBlocking {
        val next = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { PushNotices.arrived.first { it == PushNotice.InboxUpdated } }
        // no POST_NOTIFICATIONS granted on API 36: nothing shows, the running app still hears it
        PushDispatcher(show = { PushNotices.show(app, it, bangla = false) }, schedule = { _, _ -> }).onMessage(mapOf("kind" to "sync_nudge", "reason" to "notification"))
        assertEquals(PushNotice.InboxUpdated, next.await())
    }
}
