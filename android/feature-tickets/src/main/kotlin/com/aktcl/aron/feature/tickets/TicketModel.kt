package com.aktcl.aron.feature.tickets

import com.aktcl.aron.contract.SupportTicketEventPayload
import com.aktcl.aron.contract.SupportTicketPayload
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.common.LocaleDigits
import com.aktcl.aron.rules.TicketCounts
import com.aktcl.aron.rules.TicketMath
import com.aktcl.aron.rules.TicketRow
import java.security.MessageDigest

/** Contract bounds (SupportTicketPayload, SupportTicketEventPayload, V0130) and the screens' fixed sizes (build contract s5 A2). */
object TicketLimits {
    const val TITLE_MAX = 120
    const val DESCRIPTION_MAX = 2000
    const val COMMENT_MAX = 2000

    /** `cfg.support.ticket_max_photos` is bounded 0..3 (V0134); a host value outside is clamped. */
    const val PHOTOS_MAX = 3

    /** Recent tickets on the Ticket Dashboard (s5 A2). */
    const val RECENT = 10

    /** A list copy younger than this is not read again when a screen opens ([TicketLists.fresh], D-A2-30). */
    const val FRESH_MS = 3 * 60_000L

    val AFFECTED_USERNAME = Regex("^[A-Za-z0-9._-]{3,40}$")
    val ISSUE_CODE = Regex("^[a-z][a-z0-9_]{1,40}$")
}

/** SupportTicketStatus. The reporter may comment only while the ticket is [NEW] or [IN_PROGRESS] (D-TSO-MAN-21). */
enum class TicketStatus(val wire: String) {
    NEW("new"), IN_PROGRESS("in_progress"), RESOLVED("resolved"), CLOSED("closed"), CANCELLED("cancelled");

    val open: Boolean get() = this == NEW || this == IN_PROGRESS

    companion object {
        fun of(wire: String?): TicketStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/** SupportTicketPriority: P1 - Highest .. P4 - Low (D-TSO-MAN-19). */
enum class TicketPriority(val wire: String) {
    P1("p1"), P2("p2"), P3("p3"), P4("p4");

    companion object {
        fun of(wire: String?): TicketPriority? = entries.firstOrNull { it.wire == wire }
    }
}

/** SupportTicketPayload.source: Quick Ticket tile or the Create Ticket form. */
enum class TicketOrigin(val wire: String) { QUICK("quick"), MANUAL("manual") }

/** The All Tickets tabs (screenshots 12, 13): All also shows Cancelled (and any status this version does not know). */
enum class TicketTab(val status: TicketStatus?) {
    ALL(null), NEW(TicketStatus.NEW), IN_PROGRESS(TicketStatus.IN_PROGRESS), RESOLVED(TicketStatus.RESOLVED), CLOSED(TicketStatus.CLOSED);

    fun shows(t: Ticket): Boolean = status == null || t.status == status.wire
}

/**
 * One issue type of the bundle code list `ticket_issue_type` (D-TSO-MAN-19; V0131: 31 Annex A labels, attrs priority,
 * category, urgency, icon 0..7, hint_en). `label_bn` stays null until AKTCL supplies it; the English label is shown then.
 */
data class IssueType(
    val code: String,
    val labelEn: String,
    val labelBn: String? = null,
    val sort: Int = 0,
    val priority: TicketPriority = TicketPriority.P1,
    val icon: Int = 0,
    val category: String = "tickets",
    val urgency: String = "high",
    val hintEn: String? = null,
) {
    fun label(language: AppLanguage): String = if (language == AppLanguage.BN && !labelBn.isNullOrBlank()) labelBn else labelEn

    /**
     * D-A2-08: the account issue types offer the optional affected username (D-TSO-MAN-24: a ticket raised from another
     * user's phone; the manual names "User Login issue" and "Login OTP issue"). Every other type hides the field, so the
     * Create form at rest is screenshot 17 exactly.
     */
    val asksAffectedUser: Boolean get() = code in AFFECTED_USER_TYPES

    companion object {
        val AFFECTED_USER_TYPES = setOf("user_login", "login_otp", "password_reset", "auto_logout")
    }
}

/**
 * Where a ticket or an event made on this phone stands. [Sent]: on the server (from the list, or acked and not yet read
 * back); [Pending]: still in the outbox, shown "Not sent yet"; [Refused]: the server rejected or quarantined it, with its
 * outcome code (shown "Not accepted: <reason>").
 */
sealed interface Delivery {
    data object Sent : Delivery
    data object Pending : Delivery
    data class Refused(val code: String) : Delivery
}

/** A ticket as the screens show it. [number] is null until the server assigned it (D-TSO-MAN-18). */
data class Ticket(
    val uuid: String,
    val number: String?,
    val issueTypeCode: String,
    val issueLabelEn: String,
    val issueLabelBn: String? = null,
    val title: String,
    val description: String = "",
    val priority: String = TicketPriority.P1.wire,
    val status: String = TicketStatus.NEW.wire,
    val capturedAtMs: Long,
    val version: Int = 1,
    val attachmentCount: Int = 0,
    val delivery: Delivery = Delivery.Sent,
    val resolutionNotes: String? = null,
) {
    val statusOf: TicketStatus? get() = TicketStatus.of(status)

    fun issueLabel(language: AppLanguage): String = if (language == AppLanguage.BN && !issueLabelBn.isNullOrBlank()) issueLabelBn else issueLabelEn
}

/** SupportTicketEventKind. */
object EventKind {
    const val CREATED = "created"
    const val COMMENT = "comment"
    const val CLOSE_REVIEW = "close_review"
    const val STATUS_CHANGE = "status_change"
    const val RESOLUTION = "resolution"
    const val PRIORITY_CHANGE = "priority_change"
    const val ASSIGN = "assign"
}

/** One entry of a ticket's thread. A support actor is always shown as "Support" (D-TSO-MAN-21). */
data class TicketEvent(
    val uuid: String,
    val kind: String,
    val body: String?,
    val actorIsSupport: Boolean,
    val actorDisplay: String,
    val atMs: Long,
    val toStatus: String? = null,
    val attachments: List<String> = emptyList(),
    val delivery: Delivery = Delivery.Sent,
    /** True for an event made on this phone (the reporter's own comment or close). */
    val mine: Boolean = false,
)

data class TicketDetail(val ticket: Ticket, val events: List<TicketEvent>, val attachments: List<String> = emptyList())

/** A reporter event still on this phone (record `support_ticket_event`: comment or close_review). */
data class LocalEvent(
    val uuid: String,
    val ticketUuid: String,
    val kind: String,
    val body: String?,
    val atMs: Long,
    val photoCount: Int = 0,
    val delivery: Delivery = Delivery.Pending,
) {
    fun asEvent(): TicketEvent = TicketEvent(uuid, kind, body, actorIsSupport = false, actorDisplay = "", atMs = atMs, delivery = delivery, mine = true)
}

/** What the outbox still holds for tickets: tickets and events not yet read back from the server, and the refused ones. */
data class TicketOutbox(val tickets: List<Ticket> = emptyList(), val events: List<LocalEvent> = emptyList()) {
    companion object {
        val EMPTY = TicketOutbox()
    }
}

/**
 * The phone's copy of the own tickets: [tickets] from the list read, [complete] when every page was read (the host may stop
 * at a page cap), [summary] the server's counts (getSupportTicketSummary), [asOfMs] the server stamp of the read,
 * [readAtMs] the phone's trusted time of that read (null when unknown: a copy kept before it was recorded).
 */
data class TicketSnapshot(val tickets: List<Ticket>, val complete: Boolean, val summary: TicketCounts?, val asOfMs: Long, val readAtMs: Long? = null)

/** The list rules every screen shares (merge, order, tabs, local search, counts). */
object TicketLists {
    private val newestFirst = compareByDescending<Ticket> { it.capturedAtMs }.thenByDescending { it.number ?: "" }.thenBy { it.uuid }

    /**
     * The server copy plus the phone's tickets the copy does not hold yet, one row per uuid (case-insensitive; the server row
     * wins, it has the number), newest first by capture time.
     */
    fun merge(server: List<Ticket>, phone: List<Ticket>): List<Ticket> {
        val byUuid = LinkedHashMap<String, Ticket>()
        for (t in server) byUuid.getOrPut(t.uuid.lowercase()) { t }
        for (t in phone) byUuid.getOrPut(t.uuid.lowercase()) { t }
        return byUuid.values.sortedWith(newestFirst)
    }

    /**
     * Local search (s5 A2): the ticket number, the title and the issue label (both languages), case-insensitive. Bengali
     * digits typed in the box match the number's ASCII digits. A blank query matches everything.
     */
    fun matches(t: Ticket, query: String): Boolean {
        val raw = query.trim().lowercase()
        if (raw.isEmpty()) return true
        val ascii = LocaleDigits.toAscii(raw)
        val fields = listOfNotNull(t.number, t.title, t.issueLabelEn, t.issueLabelBn).map { it.lowercase() }
        return fields.any { f -> f.contains(raw) || f.contains(ascii) || LocaleDigits.toAscii(f).contains(ascii) }
    }

    fun filter(list: List<Ticket>, tab: TicketTab, query: String): List<Ticket> = list.filter { tab.shows(it) && matches(it, query) }

    fun recent(list: List<Ticket>): List<Ticket> = list.take(TicketLimits.RECENT)

    /**
     * Data budget (constraint 3, docs/04): a copy read on this phone less than [TicketLimits.FRESH_MS] ago is shown as it
     * is when a list screen opens (moving between the Dashboard, All Tickets and Details reads nothing); a pull to refresh
     * and a ticket push always read. A clock that went back, or an unknown read time, is never fresh.
     */
    fun fresh(snapshot: TicketSnapshot?, nowMs: Long): Boolean {
        val at = snapshot?.readAtMs ?: return false
        return nowMs - at in 0 until TicketLimits.FRESH_MS
    }

    /**
     * The Ticket Dashboard counts (D-TSO-MAN-25, R-F-14), always through [TicketMath]: the copy plus the tickets the server
     * has not listed yet (pending, or acked and not read back) counted as New, merged by uuid. A refused ticket is not a
     * ticket on the server and is not counted. When the copy is a list cut at the host's page cap, the server's [summary]
     * counts are taken and only the unsent tickets the cut list does not hold are added as New.
     */
    fun counts(snapshot: TicketSnapshot?, outbox: TicketOutbox): TicketCounts {
        val unsent = outbox.tickets.filter { it.delivery !is Delivery.Refused }.map { it.uuid }
        val summary = snapshot?.summary
        if (snapshot == null || snapshot.complete || summary == null) {
            return TicketMath.counts(snapshot?.tickets.orEmpty().map { TicketRow(it.uuid, it.status) }, unsent)
        }
        val known = snapshot.tickets.mapTo(HashSet()) { it.uuid.lowercase() }
        val extra = unsent.map { it.lowercase() }.distinct().count { it !in known }.toLong()
        return summary.copy(total = summary.total + extra, new = summary.new + extra)
    }
}

/** A ticket form's state kept across recreation and process death (rememberSaveable): one record uuid per form. */
data class TicketDraft(
    val uuid: String,
    val origin: TicketOrigin,
    val issueTypeCode: String? = null,
    val title: String = "",
    val description: String = "",
    val affectedUsername: String = "",
    val photoUuids: List<String> = emptyList(),
    /** True once the outbox holds this uuid: the form is done, a second Submit does nothing. */
    val queued: Boolean = false,
)

/** A comment composer's state: one event uuid per comment. */
data class CommentDraft(val uuid: String, val body: String = "", val photoUuids: List<String> = emptyList())

enum class TicketFormError { ISSUE_TYPE, TITLE_EMPTY, TITLE_LONG, DESCRIPTION_EMPTY, DESCRIPTION_LONG, AFFECTED_USER, TOO_MANY_PHOTOS }

enum class CommentError { EMPTY, TOO_LONG, TOO_MANY_PHOTOS }

/** Validation and the wire payloads of the two forms (s5 A2 rules; contract SupportTicketPayload). */
object TicketRules {
    fun newDraft(uuid: String, origin: TicketOrigin, issueTypeCode: String? = null) = TicketDraft(uuid, origin, issueTypeCode)

    /** The typed affected username as sent: trimmed, Bengali digits as ASCII; null when the type does not ask or it is blank. */
    fun affected(d: TicketDraft, type: IssueType?): String? =
        if (type?.asksAffectedUser != true) null else LocaleDigits.toAscii(d.affectedUsername.trim()).ifEmpty { null }

    /**
     * Quick: the issue type is fixed, the title is its English label, the description is optional. Create: issue type,
     * title (1..120) and description (1..2000) are required. Both: at most [maxPhotos] photos, and a typed affected
     * username must be 3..40 of letters, digits, dot, dash, underscore.
     */
    fun validate(d: TicketDraft, types: List<IssueType>, maxPhotos: Int): List<TicketFormError> {
        val e = mutableListOf<TicketFormError>()
        val type = types.firstOrNull { it.code == d.issueTypeCode }
        if (type == null) e += TicketFormError.ISSUE_TYPE
        if (d.origin == TicketOrigin.MANUAL) {
            val t = d.title.trim()
            if (t.isEmpty()) e += TicketFormError.TITLE_EMPTY else if (t.length > TicketLimits.TITLE_MAX) e += TicketFormError.TITLE_LONG
            val ds = d.description.trim()
            if (ds.isEmpty()) e += TicketFormError.DESCRIPTION_EMPTY else if (ds.length > TicketLimits.DESCRIPTION_MAX) e += TicketFormError.DESCRIPTION_LONG
        } else if (d.description.trim().length > TicketLimits.DESCRIPTION_MAX) {
            e += TicketFormError.DESCRIPTION_LONG
        }
        affected(d, type)?.let { if (!TicketLimits.AFFECTED_USERNAME.matches(it)) e += TicketFormError.AFFECTED_USER }
        if (d.photoUuids.size > maxPhotos.coerceIn(0, TicketLimits.PHOTOS_MAX)) e += TicketFormError.TOO_MANY_PHOTOS
        return e
    }

    /** The record payload of a valid draft. Quick: title = the issue's label_en (s5 A2). */
    fun payload(d: TicketDraft, type: IssueType): SupportTicketPayload = SupportTicketPayload(
        issueTypeCode = type.code,
        title = if (d.origin == TicketOrigin.QUICK) type.labelEn.take(TicketLimits.TITLE_MAX) else d.title.trim(),
        description = d.description.trim(),
        affectedUsername = affected(d, type),
        source = d.origin.wire,
    )

    /** The row the screens show at once, before any sync: status New, no number, "Not sent yet". */
    fun local(d: TicketDraft, type: IssueType, nowMs: Long): Ticket {
        val p = payload(d, type)
        return Ticket(
            uuid = d.uuid, number = null, issueTypeCode = type.code, issueLabelEn = type.labelEn, issueLabelBn = type.labelBn,
            title = p.title, description = p.description, priority = type.priority.wire, status = TicketStatus.NEW.wire,
            capturedAtMs = nowMs, attachmentCount = d.photoUuids.size, delivery = Delivery.Pending,
        )
    }

    fun validateComment(c: CommentDraft, maxPhotos: Int): CommentError? {
        val b = c.body.trim()
        return when {
            b.isEmpty() -> CommentError.EMPTY
            b.length > TicketLimits.COMMENT_MAX -> CommentError.TOO_LONG
            c.photoUuids.size > maxPhotos.coerceIn(0, TicketLimits.PHOTOS_MAX) -> CommentError.TOO_MANY_PHOTOS
            else -> null
        }
    }

    fun commentPayload(ticketUuid: String, c: CommentDraft) = SupportTicketEventPayload(ticketUuid, EventKind.COMMENT, c.body.trim())

    fun closePayload(ticketUuid: String) = SupportTicketEventPayload(ticketUuid, EventKind.CLOSE_REVIEW, null)

    /**
     * The close_review event's uuid: one per ticket, derived from the ticket uuid, so a double tap, a second detail screen
     * or a retry after a kill can never queue two closes (D-TSO-MAN-20: one tap, no rating).
     */
    fun closeUuid(ticketUuid: String): String = TicketIds.nameV4("aron.ticket.close-review:" + ticketUuid.lowercase())
}

/** Name-based ids that are valid UUID version 4 (the contract's Uuid accepts only v4; UUID.nameUUIDFromBytes makes a v3). */
object TicketIds {
    fun nameV4(name: String): String {
        val b = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)).copyOf(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = b.joinToString("") { "%02x".format(it) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }
}

/** The thread a detail screen shows and the ticket's state as this phone knows it (server copy plus own outbox). */
object TicketThread {
    /** Server events, then this phone's events the server has not returned yet (by uuid), oldest first. */
    fun events(server: List<TicketEvent>, local: List<LocalEvent>): List<TicketEvent> {
        val seen = server.mapTo(HashSet()) { it.uuid.lowercase() }
        val mine = local.filter { it.uuid.lowercase() !in seen }.map { it.asEvent() }
        return (server + mine).sortedWith(compareBy<TicketEvent> { it.atMs }.thenBy { it.uuid })
    }

    /** The phone's close_review for this ticket, if any (pending, acked or refused). */
    fun close(ticketUuid: String, local: List<LocalEvent>): LocalEvent? =
        local.firstOrNull { it.kind == EventKind.CLOSE_REVIEW && it.ticketUuid.equals(ticketUuid, ignoreCase = true) }

    /**
     * The status shown: the server's, or Closed while this phone's close of a Resolved ticket is on its way (pending or
     * acked). A refused close leaves the server status.
     */
    fun status(ticket: Ticket, close: LocalEvent?): TicketStatus? {
        val s = ticket.statusOf
        return if (s == TicketStatus.RESOLVED && close != null && close.delivery !is Delivery.Refused) TicketStatus.CLOSED else s
    }

    /** Submit Review & Close: only a Resolved ticket the server holds, with no close of this phone on its way (D-TSO-MAN-20). */
    fun canClose(ticket: Ticket, close: LocalEvent?): Boolean =
        ticket.delivery == Delivery.Sent && ticket.number != null && ticket.statusOf == TicketStatus.RESOLVED && close == null

    /** The composer shows only for New and In Progress (D-TSO-MAN-21), never for a refused ticket. */
    fun canComment(ticket: Ticket, close: LocalEvent?): Boolean =
        ticket.delivery !is Delivery.Refused && status(ticket, close)?.open == true
}
