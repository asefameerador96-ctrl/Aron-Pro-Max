package com.aktcl.aron.tso

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.feature.tso.CardLoad
import com.aktcl.aron.feature.tso.TsoFab
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.TsoScreen
import com.aktcl.aron.feature.tso.TsoScreenContent
import com.aktcl.aron.feature.tso.notifications.TsoInboxContent
import com.aktcl.aron.feature.tso.notifications.TsoInboxPage
import com.aktcl.aron.feature.tso.notifications.TsoInboxes
import com.aktcl.aron.feature.tso.notifications.TsoNotification
import kotlinx.coroutines.launch
import com.aktcl.aron.feature.tso.R as TsoR

/**
 * The bell and its inbox for one signed-in TSO (D-TSO-MAN-26, SH-08). The unread count is read when the Dashboard opens, on its
 * pull-to-refresh and when a push with the reason `notification` (or `ticket_updated`) arrives: [refresh], never on a timer. Opening
 * the inbox reads it and marks everything up to the newest id read; offline, that read is kept in this user's `aron_ui_<id>`
 * preferences ([KEY_PENDING_READ], wiped at logout) and the bell already counts it ([TsoInboxes.badge]: the server's unread count
 * minus the pending reads); it is sent first on the next read and dropped only when the server answers (idempotent by `up_to_id`).
 * The newest page is kept by [TsoApi] so the inbox opens offline with its "as of".
 */
class TsoInboxModel(private val api: TsoApi, private val prefs: SharedPreferences?) {
    var page: CardLoad<TsoInboxPage>? by mutableStateOf(null)
        private set
    var older: List<TsoNotification> by mutableStateOf(emptyList())
        private set
    var olderCursor: Long? by mutableStateOf(null)
        private set
    var refreshing: Boolean by mutableStateOf(false)
        private set
    var pendingUpTo: Long? by mutableStateOf(prefs?.getLong(KEY_PENDING_READ, 0L)?.takeIf { it > 0L })
        private set

    /** The bell's count. */
    val unread: Int get() = TsoInboxes.badge(page?.value, pendingUpTo)

    /** Sends a pending read first, then reads the newest page (a second call while one runs is ignored). */
    suspend fun refresh() {
        if (refreshing) return
        refreshing = true
        try {
            flushPending()
            val load = api.notifications().toLoad(TsoInboxes::parse)
            val fresh = load.value
            // offline with no copy keeps what is on screen rather than blanking the bell
            if (fresh != null || page?.value == null) page = load
            if (fresh != null && !load.kept) { older = emptyList(); olderCursor = fresh.nextBeforeId }
        } finally {
            refreshing = false
        }
    }

    /** The inbox was opened: read it, then mark everything up to the newest id read (kept on the phone until the server confirms). */
    suspend fun open() {
        refresh()
        markAllRead()
    }

    suspend fun markAllRead() {
        val v = page?.value ?: return
        val newest = v.newestId ?: return
        if (pendingUpTo == null && v.unreadCount == 0 && v.items.none { it.unread }) return // nothing to mark: no request
        val up = maxOf(newest, pendingUpTo ?: 0L)
        setPending(up)
        flushPending()
    }

    /** "Show older": the page before the oldest row on screen (never kept). */
    suspend fun loadOlder() {
        val before = olderCursor ?: return
        val next = TsoInboxes.parse(api.olderNotifications(before).jsonOrNull()) ?: return
        older = older + next.items
        olderCursor = next.nextBeforeId
    }

    private suspend fun flushPending() {
        val up = pendingUpTo ?: return
        when (val r = api.markNotificationsRead(up)) {
            is TsoApi.MarkRead.Done -> {
                setPending(null)
                page = page?.let { p -> p.value?.let { v -> p.copy(value = v.copy(unreadCount = r.unread)) } ?: p }
            }
            TsoApi.MarkRead.Rejected -> setPending(null) // an id the server refuses is never retried
            TsoApi.MarkRead.NoAnswer -> Unit // kept; the bell already counts it
        }
    }

    private fun setPending(up: Long?) {
        pendingUpTo = up
        prefs?.edit()?.apply { if (up == null) remove(KEY_PENDING_READ) else putLong(KEY_PENDING_READ, up) }?.apply()
    }

    companion object {
        /** The read not sent yet, in this user's `aron_ui_<id>` preferences (wiped at logout, SH-17). */
        const val KEY_PENDING_READ = "tso_inbox_pending_read_up_to"
    }
}

/** The inbox screen: reads and marks read when it opens, pull-to-refresh, "Show older", a row opens its screen. */
@Composable
fun TsoInboxHost(model: TsoInboxModel, nav: TsoNav, nowMs: () -> Long) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.open() }
    TsoFrame(stringResource(TsoR.string.tso_chrome_notifications), nav, homeButton = TsoFab.of(TsoScreen.NOTIFICATIONS)) {
        TsoInboxContent(
            model.page, model.older, nowMs(), model.refreshing, pendingRead = model.pendingUpTo != null,
            onRefresh = { scope.launch { model.open() } },
            onOpen = { t -> nav.open(t.screen, t.arg) },
            onOlder = if (model.olderCursor != null) ({ scope.launch { model.loadOlder() } }) else null,
        )
    }
}

/** A1's inbox map entry. */
fun inboxScreens(host: TsoHost, model: TsoInboxModel): Map<TsoScreen, TsoScreenContent> =
    mapOf(TsoScreen.NOTIFICATIONS to { nav, _ -> TsoInboxHost(model, nav, host.nowMs) })
