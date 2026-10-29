package com.aktcl.aron.tso

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.contract.RecordType
import com.aktcl.aron.contract.SupportTicketEventPayload
import com.aktcl.aron.contract.SupportTicketPayload
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.database.entity.OutboxEntity
import com.aktcl.aron.core.database.entity.OutboxState
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.media.GalleryImportException
import com.aktcl.aron.core.media.MediaComponents
import com.aktcl.aron.core.media.MediaRef
import com.aktcl.aron.core.media.MediaState
import com.aktcl.aron.core.media.rememberGalleryPicker
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.feature.tickets.CreateTicketScreen
import com.aktcl.aron.feature.tickets.Delivery
import com.aktcl.aron.feature.tickets.DetailRead
import com.aktcl.aron.feature.tickets.IssueType
import com.aktcl.aron.feature.tickets.IssueTypes
import com.aktcl.aron.feature.tickets.LocalEvent
import com.aktcl.aron.feature.tickets.PhotoOwner
import com.aktcl.aron.feature.tickets.PhotoResult
import com.aktcl.aron.feature.tickets.QuickTicketScreen
import com.aktcl.aron.feature.tickets.Ticket
import com.aktcl.aron.feature.tickets.TicketDashboardScreen
import com.aktcl.aron.feature.tickets.TicketDeps
import com.aktcl.aron.feature.tickets.TicketDetail
import com.aktcl.aron.feature.tickets.TicketDetailScreen
import com.aktcl.aron.feature.tickets.TicketFrame
import com.aktcl.aron.feature.tickets.TicketLimits
import com.aktcl.aron.feature.tickets.TicketListScreen
import com.aktcl.aron.feature.tickets.TicketMirror
import com.aktcl.aron.feature.tickets.TicketOutbox
import com.aktcl.aron.feature.tickets.TicketPhotos
import com.aktcl.aron.feature.tickets.TicketQueue
import com.aktcl.aron.feature.tickets.TicketSnapshot
import com.aktcl.aron.feature.tickets.TicketSource
import com.aktcl.aron.feature.tickets.TicketWire
import com.aktcl.aron.feature.tickets.rememberTicketDashboardState
import com.aktcl.aron.feature.tickets.rememberTicketDetailState
import com.aktcl.aron.feature.tickets.rememberTicketListState
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.TsoScreen
import com.aktcl.aron.feature.tso.TsoScreenContent
import com.aktcl.aron.core.sync.push.PushNotice
import com.aktcl.aron.core.sync.push.PushNotices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import com.aktcl.aron.core.media.R as MediaR

// tso-manual A2 (build contract s5 A2, D-TSO-MAN-17..25): the Tickets screens wired into the TSO app.

/**
 * The Tickets screens' host ports, built once per signed-in user (MainActivity remembers the map by [TsoHost]); each part is
 * created on first use. [host] carries every app handle a port needs (API client, outbox, media, trusted time), so the
 * tickets module stays role-agnostic and MainActivity is not edited (D-A1-05).
 */
class TicketsHostDeps(val host: TsoHost) {
    val api by lazy { TicketApi(host.components.apiClient, host.context, host.userId) }

    /** The outbox side (records `support_ticket` / `support_ticket_event`, mirror rows, photo claims) and the bundle reads. */
    val device by lazy {
        TicketDevice(host.databases, host.userId, host.outbox) { photo, ref -> (host.media() ?: host.mediaFor()).attach(photo, ref, stamp = null) }
    }

    /** `cfg.support.ticket_max_photos` (0..3, default 3), read from the bundle each time a ticket screen opens. */
    val maxPhotos: MutableIntState = mutableIntStateOf(TicketLimits.PHOTOS_MAX)

    val photos: TicketPhotos by lazy { MediaTicketPhotos(host.media, host.mediaFor, { maxPhotos.intValue }) { device.holds(it) } }

    /** One [TicketDeps] for the user: the screens' states are keyed on it, so it never changes identity. */
    val deps: TicketDeps by lazy { TicketDeps(HostTicketSource(api, device, host.nowMs), device, photos, now = host.nowMs) }

    suspend fun refreshConfig() { maxPhotos.intValue = device.maxPhotos(host.nowMs()) }
}

/**
 * The five ticket destinations (s5 A2; the drawer's Tickets group is A1's). Screenshots 11 to 18 show no Home button (s8),
 * so every screen is drawn in `TsoFrame(title, nav, homeButton = false)`. After a ticket is saved (Quick or Create), the
 * form is closed and All Tickets opens, where it shows as New (manual s6, screenshot 16). TICKET_DETAIL takes the ticket
 * uuid as its argument. SUPPORT_UPLOAD stays A1's (Settings > PDA to Support).
 */
fun ticketScreens(deps: TicketsHostDeps): Map<TsoScreen, TsoScreenContent> = mapOf<TsoScreen, TsoScreenContent>(
    TsoScreen.TICKET_DASHBOARD to { nav, arg ->
        WithTickets(deps) { d ->
            val state = rememberTicketDashboardState(d)
            RefreshOnTicketPush { state.load(refresh = true) } // tso-manual X4b (R-W-12 (1))
            TicketDashboardScreen(
                state, ticketFrame(nav),
                onOpen = { nav.open(TsoScreen.TICKET_DETAIL, it) }, onSeeAll = { nav.open(TsoScreen.TICKET_LIST) }, onQuick = { nav.open(TsoScreen.TICKET_QUICK) },
                // opened from a ticket push (TicketPush.open): read online even when the kept copy is fresh
                refreshOnOpen = TicketPush.refreshOnOpen(arg),
            )
        }
    },
    TsoScreen.TICKET_LIST to { nav, _ ->
        WithTickets(deps) { d ->
            val state = rememberTicketListState(d)
            RefreshOnTicketPush { state.load(refresh = true) } // tso-manual X4b (R-W-12 (1))
            TicketListScreen(state, ticketFrame(nav), onOpen = { nav.open(TsoScreen.TICKET_DETAIL, it) })
        }
    },
    TsoScreen.TICKET_QUICK to { nav, _ ->
        WithTickets(deps) { d -> QuickTicketScreen(d, ticketFrame(nav), onCreate = { nav.open(TsoScreen.TICKET_CREATE) }, onSubmitted = { TicketNav.afterSave(nav) }) }
    },
    TsoScreen.TICKET_CREATE to { nav, _ ->
        WithTickets(deps) { d -> CreateTicketScreen(d, ticketFrame(nav), onSubmitted = { TicketNav.afterSave(nav) }) }
    },
    TsoScreen.TICKET_DETAIL to { nav, arg ->
        if (arg == null) LaunchedEffect(Unit) { nav.back() } // a detail without its ticket (never opened so): back
        else WithTickets(deps) { d ->
            val state = rememberTicketDetailState(d, arg)
            RefreshOnTicketPush { state.load() } // tso-manual X4b (R-W-12 (1))
            TicketDetailScreen(state, ticketFrame(nav))
        }
    },
)

/** Where the ticket screens go (s5 A2 rules), apart so the paths are testable without the activity. */
object TicketNav {
    /** A saved ticket: the form closes and All Tickets opens (it shows there as New). */
    fun afterSave(nav: TsoNav) { nav.back(); nav.open(TsoScreen.TICKET_LIST) }
}

/**
 * s5 A2: a push with reason `ticket_updated` (D-TSO-MAN-26; support resolved, replied to or changed a ticket) deep-links to
 * the Ticket Dashboard, which reads the tickets as it opens (no read on the push itself). A1's push layer shows the
 * notification with its fixed text and passes the reason (or its open extra) here when the TSO taps it.
 */
object TicketPush {
    const val REASON = "ticket_updated"

    /**
     * The screen a tapped push opens, or null when the push is not a ticket push: the push reason, or the open extra
     * [PushNotices.OPEN_TICKETS] that A1's push layer puts on the notification's launch intent (tso-manual X4b).
     */
    fun target(reason: String?): TsoScreen? = when (reason?.trim()) {
        REASON, PushNotices.OPEN_TICKETS -> TsoScreen.TICKET_DASHBOARD
        else -> null
    }

    /** The Ticket Dashboard opened by [open] reads online even when its kept copy is fresh (D-A2-30). */
    fun refreshOnOpen(arg: String?): Boolean = arg == REASON

    /**
     * tso-manual X4b (R-W-12 (1)): the pushes that make a ticket screen on show read again. `ticket_updated` is the ticket
     * reason; B1 sends a desk update with reason `notification` today (`Inbox.PUSH_REASON`, R-W-06), which arrives as
     * [PushNotice.InboxUpdated]. No polling: one read per push, only while a ticket screen is shown.
     */
    fun refreshes(notice: PushNotice): Boolean = notice == PushNotice.TicketUpdated || notice == PushNotice.InboxUpdated

    /**
     * Opens the screen of a tapped push through [nav] (A1's tap handler calls this): the Ticket Dashboard with the reason as
     * its argument, so it reads online even when its kept copy is fresh (D-A2-30). False when the push is not a ticket push.
     */
    fun open(nav: TsoNav, reason: String?): Boolean {
        val screen = target(reason) ?: return false
        nav.open(screen, REASON)
        return true
    }
}

/**
 * tso-manual X4b (R-W-12 (1)): while a ticket screen is composed, every push of [TicketPush.refreshes] runs [refresh] once
 * (the screen's own read: kept copy, then online); leaving the screen stops listening. A failed read keeps what is shown.
 */
@Composable
internal fun RefreshOnTicketPush(arrived: Flow<PushNotice> = PushNotices.arrived, refresh: suspend () -> Unit) {
    val latest by rememberUpdatedState(refresh)
    LaunchedEffect(arrived) {
        arrived.filter { TicketPush.refreshes(it) }.collect {
            try {
                latest()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // the state already shows its offline line; the next push or a pull tries again
            }
        }
    }
}

@Composable
private fun ticketFrame(nav: TsoNav): TicketFrame = remember(nav) { { title, content -> TsoFrame(title, nav, homeButton = false) { content() } } }

/** Reads the photo limit from the bundle each time a ticket screen opens (a cheap local read), then draws it. */
@Composable
private fun WithTickets(deps: TicketsHostDeps, content: @Composable (TicketDeps) -> Unit) {
    LaunchedEffect(deps) { runCatching { deps.refreshConfig() } }
    content(deps.deps)
}

/**
 * [TicketSource] over [TicketApi] (network and kept copies) and [TicketDevice] (outbox, mirrors, bundle). A list read takes
 * every page up to [TicketApi.MAX_PAGES] and the summary; a read that fails half way keeps the old copy whole (D-A2-06).
 */
class HostTicketSource(private val api: TicketApi, private val device: TicketDevice, private val now: () -> Long) : TicketSource {
    override suspend fun issueTypes(): List<IssueType> = IssueTypes.parse(device.codeLists())

    override suspend fun cached(): TicketSnapshot? = withContext(Dispatchers.IO) { api.keptList()?.let { TicketCopy.decode(it) } }

    override suspend fun list(): TicketSnapshot? {
        val pages = mutableListOf<String>()
        var cursor: String? = null
        var complete = false
        for (i in 1..TicketApi.MAX_PAGES) {
            val body = when (val r = api.listSupportTickets(cursor)) {
                is TicketApi.Read.Ok -> r.body
                is TicketApi.Read.Refused -> { withContext(Dispatchers.IO) { api.forgetList() }; return null }
                TicketApi.Read.Unavailable -> return null
            }
            val (_, next) = TicketWire.page(body) ?: return null
            pages += body
            cursor = next
            if (cursor == null) { complete = true; break }
        }
        val summary = when (val r = api.summary()) {
            is TicketApi.Read.Ok -> r.body
            is TicketApi.Read.Refused -> { withContext(Dispatchers.IO) { api.forgetList() }; return null }
            TicketApi.Read.Unavailable -> return null
        }
        val readAt = now()
        val snap = TicketCopy.snapshot(pages, summary, complete, readAt)?.copy(readAtMs = readAt) ?: return null
        withContext(Dispatchers.IO) { api.keepList(TicketCopy.encode(pages, summary, complete, snap.asOfMs, readAt)) }
        // the server has these now: their "not sent yet" rows are done (an unread refused one stays)
        device.dropTicketMirrors(snap.tickets.map { it.uuid })
        return snap
    }

    override suspend fun detail(ticketUuid: String): DetailRead = when (val r = api.get(ticketUuid)) {
        is TicketApi.Read.Ok -> TicketWire.detail(r.body)?.let { d ->
            withContext(Dispatchers.IO) { api.keepDetail(ticketUuid, r.body) }
            device.dropTicketMirrors(listOf(d.ticket.uuid))
            device.dropEventMirrors(d.events.map { it.uuid })
            DetailRead.Found(d)
        } ?: DetailRead.Unavailable
        // 404: an unsent ticket, or not the caller's (no existence leak); 403 the same for a reporter
        is TicketApi.Read.Refused -> { withContext(Dispatchers.IO) { api.forgetDetail(ticketUuid) }; DetailRead.NotFound }
        TicketApi.Read.Unavailable -> DetailRead.Unavailable
    }

    override suspend fun cachedDetail(ticketUuid: String): TicketDetail? =
        withContext(Dispatchers.IO) { api.keptDetail(ticketUuid) }?.let { TicketWire.detail(it) }

    override suspend fun outbox(): TicketOutbox = device.outbox()
}

/**
 * The phone side of the tickets (s5 A2): [TicketQueue] through the one TSO outbox, the mirror rows that show an unsent
 * ticket or event ("Not sent yet"), and the bundle reads (issue types, photo limit).
 *
 * A save is idempotent by its client uuid: a uuid the outbox holds (or held, purged after its ack) changes nothing and
 * answers false. Otherwise the photos are claimed for the record first (media purpose `support`, ref_type
 * `support_ticket` / `support_ticket_event`, the photo layer's claim is idempotent; the media layer's rule is to claim
 * before the record commits), then the outbox row and the mirror row `tso.unsent.ticket.<uuid>`
 * (`tso.unsent.ticket_event.<uuid>`) are written in ONE transaction, and a debounced sync is asked for. The claim is NOT
 * in that transaction: a failed commit (full disk) or a kill between the claim and the commit leaves claimed photos and
 * no row. Such a photo never uploads (MediaUploader waits for a record it has seen and drops an unseen one after
 * `pending_photo_grace_days`); the form keeps its uuid, so the retry commits the one row; and a photo the rep removes or
 * leaves meanwhile is dropped by [MediaTicketPhotos.discard], which checks [holds].
 */
class TicketDevice(
    private val databases: UserDatabases,
    private val userId: Long,
    private val outbox: TsoOutbox,
    private val claim: suspend (photoUuid: String, ref: MediaRef) -> Unit,
) : TicketQueue {
    private suspend fun db() = databases.of(userId)

    override suspend fun ticket(uuid: String, payload: SupportTicketPayload, photoUuids: List<String>, local: Ticket): Boolean =
        save(RecordType.SUPPORT_TICKET, uuid, encode(SupportTicketPayload.serializer(), payload), photoUuids, PhotoOwner.ticket(uuid), TICKET_MIRROR + uuid, TicketMirror.ticketToJson(local))

    override suspend fun event(uuid: String, payload: SupportTicketEventPayload, photoUuids: List<String>, local: LocalEvent): Boolean =
        save(RecordType.SUPPORT_TICKET_EVENT, uuid, encode(SupportTicketEventPayload.serializer(), payload), photoUuids, PhotoOwner.event(uuid), EVENT_MIRROR + uuid, TicketMirror.eventToJson(local))

    private suspend fun save(type: RecordType, uuid: String, payload: JsonObject, photos: List<String>, owner: PhotoOwner, key: String, mirror: String): Boolean {
        require(photos.size <= TicketLimits.PHOTOS_MAX) { "at most ${TicketLimits.PHOTOS_MAX} photos per ticket or comment" }
        if (holds(uuid)) return false
        for (p in photos.distinct()) claim(p, MediaRef(MEDIA_PURPOSE, owner.refType, owner.refUuid))
        return outbox.enqueueAll(userId, listOf(TsoOutbox.Rec(type, uuid, payload)), mirrors = listOf(key to mirror)) > 0
    }

    /** True when the record [uuid] is saved: the outbox holds it, or held it (purged after its ack). */
    suspend fun holds(uuid: String): Boolean {
        val rows = db().outboxDao()
        return rows.byClientUuid(uuid) != null || rows.anyPurged(listOf(uuid))
    }

    /** The unsent tickets and events, each with its delivery from the outbox row (D-A2-15). */
    suspend fun outbox(): TicketOutbox {
        val db = db()
        val meta = db.referenceDao()
        val rows = db.outboxDao()
        val tickets = meta.metaWithPrefix(TICKET_MIRROR).mapNotNull { m ->
            TicketMirror.ticketFromJson(m.value, delivery(rows.byClientUuid(m.key.removePrefix(TICKET_MIRROR))))
        }
        val events = meta.metaWithPrefix(EVENT_MIRROR).mapNotNull { m ->
            TicketMirror.eventFromJson(m.value, delivery(rows.byClientUuid(m.key.removePrefix(EVENT_MIRROR))))
        }
        return TicketOutbox(tickets, events)
    }

    /** Drops the mirror rows of the tickets the server listed or returned (the server copy shows them from now on). */
    suspend fun dropTicketMirrors(ticketUuids: Collection<String>) = drop(TICKET_MIRROR, ticketUuids)

    /** Drops the mirror rows of the events a thread read returned. */
    suspend fun dropEventMirrors(eventUuids: Collection<String>) = drop(EVENT_MIRROR, eventUuids)

    private suspend fun drop(prefix: String, uuids: Collection<String>) {
        if (uuids.isEmpty()) return
        val wanted = uuids.mapTo(HashSet()) { it.lowercase() }
        runCatching {
            val meta = db().referenceDao()
            meta.metaWithPrefix(prefix).filter { it.key.removePrefix(prefix).lowercase() in wanted }.forEach { meta.deleteMeta(it.key) }
        }
    }

    /** The bundle section `code_lists` (the issue types live in `ticket_issue_type`); null before the first bundle. */
    suspend fun codeLists(): String? = try {
        ReferenceRepository(db()).section("code_lists")
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** `cfg.support.ticket_max_photos` in force at [nowMs], clamped to 0..3; 3 (the registry default) when absent or unreadable. */
    suspend fun maxPhotos(nowMs: Long): Int = try {
        ReferenceRepository(db()).config(KEY_MAX_PHOTOS, com.aktcl.aron.core.sync.SyncEngine.iso(nowMs))
            ?.let { (Json.parseToJsonElement(it) as? JsonPrimitive)?.content?.trim()?.toIntOrNull() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }?.coerceIn(0, TicketLimits.PHOTOS_MAX) ?: TicketLimits.PHOTOS_MAX

    companion object {
        const val TICKET_MIRROR = "tso.unsent.ticket."
        const val EVENT_MIRROR = "tso.unsent.ticket_event."
        const val KEY_MAX_PHOTOS = "cfg.support.ticket_max_photos"
        const val MEDIA_PURPOSE = "support"

        /** Absent members stay absent (a close review has no `body`; the contract's body is a string, never null). */
        private val wire = Json { explicitNulls = false; encodeDefaults = true }

        fun <T> encode(serializer: KSerializer<T>, value: T): JsonObject = wire.encodeToJsonElement(serializer, value).jsonObject

        /**
         * D-A2-15: pending or in flight is Pending; acked, or a row already purged after its ack, is Sent; rejected or
         * quarantined is Refused with the server's code.
         */
        fun delivery(row: OutboxEntity?): Delivery {
            if (row == null) return Delivery.Sent
            return when (row.state) {
                OutboxState.ACKED -> Delivery.Sent
                OutboxState.REJECTED, OutboxState.QUARANTINED -> Delivery.Refused(row.lastCode ?: row.state)
                else -> Delivery.Pending
            }
        }
    }
}

/**
 * Ticket photos (D-TSO-MAN-23) through the shared media layer: the in-app camera ([MediaComponents.capture], whose overlay
 * MainActivity draws for [media]) and the system photo picker ([rememberGalleryPicker], no storage permission), both
 * compressed to `cfg.media.*` with EXIF stripped. A picture is only STAGED here (`importFromGallery(claim = false)`, or a
 * camera capture); [TicketDevice] claims it with its record, and [discard] drops a removed one, so it is never sent.
 */
class MediaTicketPhotos(
    /** The media components whose camera overlay is drawn (a compose state read); null for the first moments of a sign-in. */
    private val media: () -> MediaComponents?,
    private val mediaFor: suspend () -> MediaComponents,
    private val maxNow: () -> Int,
    /** True when the ticket record (ticket or event uuid) is saved ([TicketDevice.holds]); unknown keeps a claimed photo. */
    private val recordSaved: suspend (recordUuid: String) -> Boolean = { true },
) : TicketPhotos {
    override val max: Int get() = maxNow().coerceIn(0, TicketLimits.PHOTOS_MAX)

    private suspend fun store(): MediaComponents = media() ?: mediaFor()

    /** Only the components whose overlay is on screen can open the camera; before they are ready the tap does nothing. */
    override suspend fun capture(photoUuid: String): Boolean = try {
        media()?.capture(photoUuid) != null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * The photo picker for up to `maxItems` pictures. The picker's limit is fixed when it is drawn, so a launch with a new
     * limit first redraws it with that limit and then opens it (one frame later).
     */
    @Composable
    override fun rememberGallery(onPicked: (List<String>) -> Unit): (maxItems: Int) -> Unit {
        val context = LocalContext.current
        val unavailable = stringResource(MediaR.string.media_gallery_unavailable)
        var limit by rememberSaveable { mutableIntStateOf(1) }
        var armed by remember { mutableStateOf(false) }
        val picked by rememberUpdatedState(onPicked)
        val launch = rememberGalleryPicker(limit, onUnavailable = { Toast.makeText(context, unavailable, Toast.LENGTH_LONG).show() }) { uris ->
            picked(uris.map(Uri::toString))
        }
        LaunchedEffect(armed, limit) { if (armed) { armed = false; launch() } }
        return remember { { n -> if (n > 0) { limit = n.coerceAtMost(TicketLimits.PHOTOS_MAX); armed = true } } }
    }

    override suspend fun import(photoUuid: String, picked: String, owner: PhotoOwner): PhotoResult = try {
        store().importFromGallery(photoUuid, Uri.parse(picked), MediaRef(TicketDevice.MEDIA_PURPOSE, owner.refType, owner.refUuid), claim = false)
        PhotoResult.Added
    } catch (e: CancellationException) {
        throw e
    } catch (e: GalleryImportException) {
        PhotoResult.Failed(e.messageRes)
    } catch (_: Exception) {
        PhotoResult.Failed(MediaR.string.media_gallery_failed)
    }

    /**
     * A staged photo is dropped. A photo already claimed for a ticket record that was never saved (the save failed or was
     * killed between the claim and the commit, [TicketDevice]) is dropped too, so a picture the rep removed is never sent
     * with the retry (D-TSO-MAN-23). A photo of a saved record, or of anything but a ticket, is kept.
     */
    override suspend fun discard(photoUuid: String) {
        try {
            val m = store()
            if (m.discard(photoUuid)) return
            val item = m.store.get(photoUuid) ?: return
            val ref = item.ref ?: return
            if (item.state != MediaState.ATTACHED || ref.purpose != TicketDevice.MEDIA_PURPOSE || ref.refType !in TICKET_REF_TYPES) return
            if (recordSaved(ref.refClientUuid)) return
            m.store.deleteIf(photoUuid) { it.state == MediaState.ATTACHED && it.ref == ref }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private companion object {
        val TICKET_REF_TYPES = setOf(PhotoOwner.ticket("").refType, PhotoOwner.event("").refType)
    }

    @Composable
    override fun Thumbnail(photoUuid: String, modifier: Modifier) {
        val m = media()
        val bitmap by produceState<ImageBitmap?>(null, photoUuid, m) {
            value = m?.let { c ->
                withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(c.store.thumbnailFile(photoUuid).path)?.asImageBitmap() }.getOrNull() }
            }
        }
        val b = bitmap
        if (b != null) Image(b, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
        else Box(modifier.background(LocalAronLook.current.inkMuted.copy(alpha = 0.25f)))
    }
}
