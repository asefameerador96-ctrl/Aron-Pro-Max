package com.aktcl.aron.core.sync.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aktcl.aron.core.sync.R
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The notifications a push shows (N-038): a task channel and an announcement channel, Bangla or English from the
 * localization layer, and a tap that opens the app on the task list ([EXTRA_OPEN] = [OPEN_TASKS]). Nothing here touches
 * the network; a phone where notifications are off simply shows nothing (the task still arrives with the pull).
 *
 * tso-manual A1 (D-TSO-MAN-26, 31): the reasons `notification` and `ticket_updated` show a fixed text on the updates channel and
 * open the inbox ([OPEN_INBOX]) or the ticket dashboard ([OPEN_TICKETS]); `day_final_submitted` shows a fixed text on the silent day
 * channel (the midnight auto Final Submit must not ring a phone at 00:00). Every notice is also announced in process on [arrived]
 * before it is shown (and even when notifications are off), so a running app re-reads what it is about (the TSO bell's unread count)
 * without any polling.
 */
object PushNotices {
    const val CHANNEL_TASKS = "aron_tasks"
    const val CHANNEL_NOTICES = "aron_notices"
    const val CHANNEL_UPDATES = "aron_updates"
    const val CHANNEL_DAY = "aron_day"
    const val EXTRA_OPEN = "com.aktcl.aron.open"
    const val OPEN_TASKS = "tasks"
    const val OPEN_INBOX = "inbox"
    const val OPEN_TICKETS = "tickets"
    const val ID_TASK = 3801
    const val ID_INBOX = 3802
    const val ID_TICKET = 3803
    const val ID_DAY = 3804
    private const val ID_NOTICE_BASE = 3900

    private val arrivedFlow = MutableSharedFlow<PushNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every notice a push brought, in this process (no replay: a screen that opens later reads on its own). */
    val arrived: SharedFlow<PushNotice> = arrivedFlow.asSharedFlow()

    /** [localized] is the context in the app's chosen language (channel names are shown in system Settings). */
    fun ensureChannels(localized: Context) {
        val manager = localized.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TASKS, localized.getString(R.string.push_channel_tasks), NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_NOTICES, localized.getString(R.string.push_channel_notices), NotificationManager.IMPORTANCE_DEFAULT),
        )
        // tso-manual A1
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, localized.getString(R.string.push_channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DAY, localized.getString(R.string.push_channel_day), NotificationManager.IMPORTANCE_LOW),
        )
    }

    /** Returns false when the notification could not be shown (notifications off, permission missing). */
    fun show(localized: Context, notice: PushNotice, bangla: Boolean): Boolean {
        arrivedFlow.tryEmit(notice)
        val compat = NotificationManagerCompat.from(localized)
        if (!compat.areNotificationsEnabled()) return false
        val (channel, id, title, body) = when (notice) {
            PushNotice.TaskAssigned -> Quad(CHANNEL_TASKS, ID_TASK, localized.getString(R.string.push_task_title), localized.getString(R.string.push_task_body))
            PushNotice.InboxUpdated -> Quad(CHANNEL_UPDATES, ID_INBOX, localized.getString(R.string.push_inbox_title), localized.getString(R.string.push_inbox_body))
            PushNotice.TicketUpdated -> Quad(CHANNEL_UPDATES, ID_TICKET, localized.getString(R.string.push_ticket_title), localized.getString(R.string.push_ticket_body))
            PushNotice.DayFinalSubmitted -> Quad(CHANNEL_DAY, ID_DAY, localized.getString(R.string.push_day_title), localized.getString(R.string.push_day_body))
            is PushNotice.Announcement -> Quad(
                CHANNEL_NOTICES, ID_NOTICE_BASE + (notice.titleEn.hashCode() and 0x3f), notice.title(bangla), notice.body(bangla),
            )
        }
        val notification = NotificationCompat.Builder(localized, channel)
            .setSmallIcon(R.drawable.ic_aron_push)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(
                when (channel) {
                    CHANNEL_TASKS -> NotificationCompat.PRIORITY_HIGH
                    CHANNEL_DAY -> NotificationCompat.PRIORITY_LOW
                    else -> NotificationCompat.PRIORITY_DEFAULT
                },
            )
            .setCategory(if (channel == CHANNEL_DAY) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_REMINDER)
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

    /** The app's own launcher activity, brought to the front; a task opens the task list, an inbox or ticket update its screen. */
    fun openIntent(context: Context, notice: PushNotice): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val (open, request) = when (notice) {
            PushNotice.TaskAssigned -> OPEN_TASKS to ID_TASK
            PushNotice.InboxUpdated -> OPEN_INBOX to ID_INBOX
            PushNotice.TicketUpdated -> OPEN_TICKETS to ID_TICKET
            PushNotice.DayFinalSubmitted -> null to ID_DAY
            is PushNotice.Announcement -> null to ID_NOTICE_BASE
        }
        if (open != null) launch.putExtra(EXTRA_OPEN, open)
        return PendingIntent.getActivity(context, request, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Whether an activity was opened from a task notification. */
    fun opensTasks(intent: Intent?): Boolean = intent?.getStringExtra(EXTRA_OPEN) == OPEN_TASKS

    /** tso-manual A1: what a tapped notification asks the app to open ([OPEN_TASKS], [OPEN_INBOX], [OPEN_TICKETS]), or null. */
    fun opens(intent: Intent?): String? = intent?.getStringExtra(EXTRA_OPEN)

    private data class Quad(val channel: String, val id: Int, val title: String, val body: String)
}
