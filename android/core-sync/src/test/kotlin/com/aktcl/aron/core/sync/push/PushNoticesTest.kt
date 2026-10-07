package com.aktcl.aron.core.sync.push

import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/** N-038: the task notice in Bangla on its own channel, and its tap opens the task list. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PushNoticesTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val bangla: Context = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag("bn")) })
    private val manager = app.getSystemService(NotificationManager::class.java)

    @org.junit.Before fun launcher() {
        val main = android.content.ComponentName(app.packageName, "com.aktcl.aron.test.Main")
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(main)
        pm.addIntentFilterForActivity(main, android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply { addCategory(android.content.Intent.CATEGORY_LAUNCHER) })
    }

    @Test fun aTaskPushShowsTheBanglaNoticeOnTheTaskChannelAndOpensTasks() {
        shadowOf(app as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        PushNotices.ensureChannels(bangla)
        assertEquals("কাজ", manager.getNotificationChannel(PushNotices.CHANNEL_TASKS).name)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(PushNotices.CHANNEL_TASKS).importance)
        assertTrue(PushNotices.show(bangla, PushNotice.TaskAssigned, bangla = true))
        val shown = shadowOf(manager).getNotification(PushNotices.ID_TASK)
        assertNotNull(shown)
        assertEquals(PushNotices.CHANNEL_TASKS, shown.channelId)
        assertEquals("নতুন কাজ", shadowOf(shown).contentTitle.toString())
        val tap = shadowOf(shown.contentIntent).savedIntent
        assertTrue(PushNotices.opensTasks(tap))
    }

    @Test fun anAnnouncementUsesTheBanglaTextAndDoesNotOpenTasks() {
        shadowOf(app as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        PushNotices.ensureChannels(bangla)
        val notice = PushNotice.Announcement("Meeting", "সভা", "At 9", "৯টায়")
        assertTrue(PushNotices.show(bangla, notice, bangla = true))
        val shown = shadowOf(manager).allNotifications.single()
        assertEquals(PushNotices.CHANNEL_NOTICES, shown.channelId)
        assertEquals("সভা", shadowOf(shown).contentTitle.toString())
        assertTrue(!PushNotices.opensTasks(shadowOf(shown.contentIntent).savedIntent))
    }
}
