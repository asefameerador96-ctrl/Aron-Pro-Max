package com.aktcl.aron.core.sync.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aktcl.aron.core.sync.R

/**
 * The notifications a push shows (N-038): a task channel and an announcement channel, Bangla or English from the
 * localization layer, and a tap that opens the app on the task list ([EXTRA_OPEN] = [OPEN_TASKS]). Nothing here touches
 * the network; a phone where notifications are off simply shows nothing (the task still arrives with the pull).
 */
object PushNotices {
    const val CHANNEL_TASKS = "aron_tasks"
    const val CHANNEL_NOTICES = "aron_notices"
    const val EXTRA_OPEN = "com.aktcl.aron.open"
    const val OPEN_TASKS = "tasks"
    const val ID_TASK = 3801
    private const val ID_NOTICE_BASE = 3900

    /** [localized] is the context in the app's chosen language (channel names are shown in system Settings). */
    fun ensureChannels(localized: Context) {
        val manager = localized.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TASKS, localized.getString(R.string.push_channel_tasks), NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_NOTICES, localized.getString(R.string.push_channel_notices), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** Returns false when the notification could not be shown (notifications off, permission missing). */
    fun show(localized: Context, notice: PushNotice, bangla: Boolean): Boolean {
        val compat = NotificationManagerCompat.from(localized)
        if (!compat.areNotificationsEnabled()) return false
        val (channel, id, title, body) = when (notice) {
            PushNotice.TaskAssigned -> Quad(CHANNEL_TASKS, ID_TASK, localized.getString(R.string.push_task_title), localized.getString(R.string.push_task_body))
            is PushNotice.Announcement -> Quad(
                CHANNEL_NOTICES, ID_NOTICE_BASE + (notice.titleEn.hashCode() and 0x3f), notice.title(bangla), notice.body(bangla),
            )
        }
        val notification = NotificationCompat.Builder(localized, channel)
            .setSmallIcon(R.drawable.ic_aron_push)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (channel == CHANNEL_TASKS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .apply { openIntent(localized, notice)?.let { setContentIntent(it) } }
            .build()
        return try {
            compat.notify(id, notification)
            true
        } catch (_: SecurityException) { // POST_NOTIFICATIONS revoked on API 33+
            false
        }
    }

    /** The app's own launcher activity, brought to the front; a task opens the task list. */
    fun openIntent(context: Context, notice: PushNotice): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        if (notice == PushNotice.TaskAssigned) launch.putExtra(EXTRA_OPEN, OPEN_TASKS)
        return PendingIntent.getActivity(context, if (notice == PushNotice.TaskAssigned) ID_TASK else ID_NOTICE_BASE, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Whether an activity was opened from a task notification. */
    fun opensTasks(intent: Intent?): Boolean = intent?.getStringExtra(EXTRA_OPEN) == OPEN_TASKS

    private data class Quad(val channel: String, val id: Int, val title: String, val body: String)
}
