package com.aktcl.aron.feature.tickets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aktcl.aron.contract.SupportTicketEventPayload
import com.aktcl.aron.contract.SupportTicketPayload
import com.aktcl.aron.core.common.ClientIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where the ticket screens read from (build contract s5 A2). Every read of the network happens only on open and on
 * pull-to-refresh; nothing polls (constraint 3). The scope is the token's: the host never sends a user or scope id.
 */
interface TicketSource {
    /** The issue types from the bundle's `ticket_issue_type` code list (offline; [IssueTypes.parse]). */
    suspend fun issueTypes(): List<IssueType>

    /** The phone's copy of the own tickets as last read; null before the first read. Never touches the network. */
    suspend fun cached(): TicketSnapshot?

    /**
     * One online read of the own tickets (`GET /v1/support/tickets`, every page up to the host's cap, plus
     * `GET /v1/support/tickets/summary`), stored as the copy. The host drops the mirror row of every unsent ticket the read
     * returns. Null when offline or refused (the copy stays).
     */
    suspend fun list(): TicketSnapshot?

    /** One online read of one ticket and its thread (`GET /v1/support/tickets/{uuid}`), stored. */
    suspend fun detail(ticketUuid: String): DetailRead

    /** The stored copy of one ticket's thread; null when never read. */
    suspend fun cachedDetail(ticketUuid: String): TicketDetail?

    /** The tickets and events this phone made that the server has not listed yet, and the refused ones (outbox + mirror). */
    suspend fun outbox(): TicketOutbox
}

/** The answer of [TicketSource.detail]. */
sealed interface DetailRead {
    data class Found(val detail: TicketDetail) : DetailRead

    /** 404: not the caller's ticket, or not on the server yet (an unsent ticket). */
    data object NotFound : DetailRead

    /** Offline or refused: the stored copy stays. */
    data object Unavailable : DetailRead
}

/**
 * The host outbox (s5 A2: records `support_ticket` and `support_ticket_event`, D-TSO-MAN-18: there is no online create).
 * Each call first claims [photoUuids] for the record (media ref `support`/`support_ticket` or `support_ticket_event`,
 * D-TSO-MAN-23; the media layer's rule: claim before the record commits, so a committed record never lacks its photos),
 * then writes the outbox row and the mirror row ([TicketMirror]) in ONE transaction, then asks for a debounced sync. The
 * claim is NOT in that transaction: a failed commit (full disk) or a kill after the claim leaves claimed photos and no
 * row. Such a photo never uploads (the media layer waits for its record and drops it after its grace period), the retry
 * commits it with the record, and [TicketPhotos.discard] drops it at once when the rep removes it or leaves the form.
 * Idempotent by the client uuid: a uuid already queued changes nothing and returns false. Throws when the phone cannot
 * store it (full disk); the form then shows the failure and keeps its uuid for the retry.
 */
interface TicketQueue {
    suspend fun ticket(uuid: String, payload: SupportTicketPayload, photoUuids: List<String>, local: Ticket): Boolean

    suspend fun event(uuid: String, payload: SupportTicketEventPayload, photoUuids: List<String>, local: LocalEvent): Boolean
}

/** The record a photo will belong to (media `ref_type` and the record's client uuid). */
data class PhotoOwner(val refType: String, val refUuid: String) {
    companion object {
        fun ticket(uuid: String) = PhotoOwner("support_ticket", uuid)
        fun event(uuid: String) = PhotoOwner("support_ticket_event", uuid)
    }
}

/** The result of staging one gallery picture. */
sealed interface PhotoResult {
    data object Added : PhotoResult

    /** Not added; [messageRes] is the reason's string resource (the host's, e.g. core-media `media_gallery_too_large`), or null. */
    data class Failed(val messageRes: Int?) : PhotoResult
}

/**
 * Ticket photos (D-TSO-MAN-23): the camera or the gallery (photo picker, no storage permission), both through the shared
 * compressor. A photo is STAGED under its uuid (compressed, EXIF stripped, not yet claimed); [TicketQueue] claims it with
 * the record, and [discard] drops a staged photo the user removed or abandoned. A host must not claim at pick time: a
 * removed picture would then still be sent with the ticket.
 */
interface TicketPhotos {
    /** `cfg.support.ticket_max_photos` (0..3) per ticket and per comment; 0 hides the photo actions. */
    val max: Int

    /** One camera photo staged under [photoUuid]; false when cancelled or failed (the camera layer shows why). */
    suspend fun capture(photoUuid: String): Boolean

    /** A launcher for the gallery picker; it returns up to `maxItems` pictures as opaque handles (content URIs) to [onPicked]. */
    @Composable
    fun rememberGallery(onPicked: (List<String>) -> Unit): (maxItems: Int) -> Unit

    /** Stages one picked picture under [photoUuid] for [owner] (read once, right after the pick). Idempotent by [photoUuid]. */
    suspend fun import(photoUuid: String, picked: String, owner: PhotoOwner): PhotoResult

    /**
     * Drops a photo the rep removed, or one of a form that was left unsaved: a staged one, and one claimed for a record
     * that was never saved (its save failed or was killed after the claim). A photo of a saved record is kept.
     */
    suspend fun discard(photoUuid: String)

    /** The staged photo's thumbnail (or a placeholder) in the form. */
    @Composable
    fun Thumbnail(photoUuid: String, modifier: Modifier)
}

/**
 * Everything the ticket screens need from a host app. [now] is the trusted clock (epoch ms) for the age texts and the
 * unsent rows; [photos] null hides every photo action. [background] runs the work that must outlive a screen (dropping
 * the staged photos of a form the rep left); a host may pass its session scope.
 */
class TicketDeps(
    val source: TicketSource,
    val queue: TicketQueue,
    val photos: TicketPhotos? = null,
    val now: () -> Long,
    val newUuid: () -> String = ClientIds::newUuid,
    val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** The photo limit in force: `cfg.support.ticket_max_photos` clamped to 0..3, 0 without a photo port. */
    val maxPhotos: Int get() = photos?.max?.coerceIn(0, TicketLimits.PHOTOS_MAX) ?: 0

    /** Drops [photoUuids] (a left form's staged photos) on [background], so leaving the screen never cancels it. */
    fun discardLater(photoUuids: List<String>) {
        val p = photos ?: return
        if (photoUuids.isEmpty()) return
        background.launch { photoUuids.forEach { attempt { p.discard(it) } } }
    }
}
