package com.aktcl.aron.feature.tickets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aktcl.aron.contract.SupportTicketEventPayload
import com.aktcl.aron.contract.SupportTicketPayload
import com.aktcl.aron.rules.TicketCounts
import com.aktcl.aron.rules.TicketMath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** 2026-10-25 11:34:00 UTC = 17:34 in Dhaka (screenshot 14's "5:34 PM"). */
const val T0 = 1_792_928_040_000L
const val MIN = 60_000L
const val HOUR = 3_600_000L
const val DAY = 86_400_000L

val TYPES = listOf(
    IssueType("login_otp", "Login OTP issue", null, 15, icon = 6),
    IssueType("outlet_related", "Outlet Related Issue", null, 19, icon = 2),
    IssueType("final_submission", "Final Submission", null, 13, icon = 4),
    IssueType("reporting", "Reporting", "রিপোর্টিং", 24, icon = 7),
    IssueType("user_login", "User Login issue", null, 30, icon = 5),
)

fun ticket(
    uuid: String, number: String?, status: String = "new", title: String = "t-$uuid", type: IssueType = TYPES[0], at: Long = T0,
    delivery: Delivery = Delivery.Sent,
) = Ticket(uuid, number, type.code, type.labelEn, type.labelBn, title, "", "p1", status, at, delivery = delivery)

/**
 * The support server as the phone sees it (D-TSO-MAN-18..21): upsert by the client uuid (a replay changes nothing), INC
 * numbers from 100001 at first insert, comments only while new or in_progress, close_review only from resolved.
 */
class FakeServer(private val types: List<IssueType> = TYPES) {
    class Row(val ticket: Ticket, val events: MutableList<TicketEvent> = mutableListOf())

    val rows = LinkedHashMap<String, Row>()
    private var seq = 100001
    var ingests = 0
        private set

    /** null = accepted (or a replay of an accepted uuid); else the rejection code. */
    fun ingestTicket(uuid: String, p: SupportTicketPayload, atMs: Long): String? {
        ingests++
        if (rows.containsKey(uuid)) return null
        val type = types.firstOrNull { it.code == p.issueTypeCode } ?: return "issue_type_unknown"
        rows[uuid] = Row(Ticket(uuid, "INC" + seq++, type.code, type.labelEn, type.labelBn, p.title, p.description, type.priority.wire, "new", atMs))
        return null
    }

    fun ingestEvent(uuid: String, p: SupportTicketEventPayload, atMs: Long): String? {
        ingests++
        val row = rows[p.ticketUuid] ?: return "ticket_state_invalid"
        if (row.events.any { it.uuid == uuid }) return null
        val status = row.ticket.status
        when (p.kind) {
            EventKind.COMMENT -> if (status != "new" && status != "in_progress") return "ticket_state_invalid"
            EventKind.CLOSE_REVIEW -> if (status != "resolved") return "ticket_state_invalid" else set(p.ticketUuid, "closed")
            else -> return "ticket_state_invalid"
        }
        row.events += TicketEvent(uuid, p.kind, p.body, false, "SR - Testing Banani", atMs, if (p.kind == EventKind.CLOSE_REVIEW) "closed" else null)
        return null
    }

    private fun set(uuid: String, status: String) {
        val r = rows.getValue(uuid)
        rows[uuid] = Row(r.ticket.copy(status = status, version = r.ticket.version + 1), r.events)
    }

    fun startWork(uuid: String) = set(uuid, "in_progress")

    fun resolve(uuid: String, notes: String, atMs: Long) {
        val no = rows.getValue(uuid).ticket.number
        set(uuid, "resolved")
        rows.getValue(uuid).events += TicketEvent(
            "res-$uuid", EventKind.RESOLUTION, "[TicketID: $no] - Resolved:\nResolution Code: Resolved\nResolution Notes:\n\n$notes", true, "Support", atMs, "resolved",
        )
    }

    fun list(): List<Ticket> = rows.values.map { it.ticket }
    fun summary(): TicketCounts = TicketMath.counts(rows.values.map { it.ticket.status })
    fun detail(uuid: String): TicketDetail? = rows[uuid]?.let { TicketDetail(it.ticket, it.events.toList()) }
}

/**
 * The phone: the host outbox (one row per uuid, the mirror row with it), the stored copies, and a sync that sends every
 * pending row in order. [online] false makes every read fail as a host's offline read does.
 */
class FakeDevice(val server: FakeServer, private val types: List<IssueType> = TYPES, var nowMs: Long = T0) : TicketSource, TicketQueue {
    var online = false

    sealed interface State {
        data object Pending : State
        data object Acked : State
        data class Refused(val code: String) : State
    }

    class Rec(val uuid: String, val ticket: SupportTicketPayload?, val event: SupportTicketEventPayload?, val atMs: Long, val photos: List<String>, var state: State = State.Pending)

    val outboxRows = LinkedHashMap<String, Rec>()
    private val ticketMirror = LinkedHashMap<String, Ticket>()
    private val eventMirror = LinkedHashMap<String, LocalEvent>()
    private var copy: TicketSnapshot? = null
    private val details = HashMap<String, TicketDetail>()

    /** When set, a queue call suspends here BEFORE it commits (a kill mid-save cancels it there). */
    var gate: CompletableDeferred<Unit>? = null

    /** When set, a queue call commits and then throws it (a kill right after the commit). */
    var afterCommit: Throwable? = null

    /** When set, a queue call throws it without committing (a full disk). */
    var failure: Exception? = null
    var queueCalls = 0
        private set

    override suspend fun ticket(uuid: String, payload: SupportTicketPayload, photoUuids: List<String>, local: Ticket): Boolean {
        queueCalls++
        gate?.await()
        failure?.let { throw it }
        if (outboxRows.containsKey(uuid)) return false
        outboxRows[uuid] = Rec(uuid, payload, null, local.capturedAtMs, photoUuids)
        ticketMirror[uuid] = local
        afterCommit?.let { afterCommit = null; throw it }
        return true
    }

    override suspend fun event(uuid: String, payload: SupportTicketEventPayload, photoUuids: List<String>, local: LocalEvent): Boolean {
        queueCalls++
        gate?.await()
        failure?.let { throw it }
        if (outboxRows.containsKey(uuid)) return false
        outboxRows[uuid] = Rec(uuid, null, payload, local.atMs, photoUuids)
        eventMirror[uuid] = local
        afterCommit?.let { afterCommit = null; throw it }
        return true
    }

    /** Sends every pending row in outbox order. [ackLost]: the server stored them but the answers never arrived. */
    fun sync(ackLost: Boolean = false) {
        if (!online) return
        for (r in outboxRows.values.filter { it.state == State.Pending }) {
            val code = if (r.ticket != null) server.ingestTicket(r.uuid, r.ticket, r.atMs) else server.ingestEvent(r.uuid, r.event!!, r.atMs)
            if (!ackLost) r.state = if (code == null) State.Acked else State.Refused(code)
        }
    }

    fun pending(): Int = outboxRows.values.count { it.state == State.Pending }

    private fun delivery(uuid: String): Delivery = when (val s = outboxRows[uuid]?.state) {
        State.Acked -> Delivery.Sent
        is State.Refused -> Delivery.Refused(s.code)
        else -> Delivery.Pending
    }

    override suspend fun issueTypes() = types
    override suspend fun cached() = copy

    /** Online list reads asked for (each costs data on a phone). */
    var listReads = 0
        private set

    override suspend fun list(): TicketSnapshot? {
        listReads++
        if (!online) return null
        val items = server.list()
        val snap = TicketSnapshot(items, complete = true, summary = server.summary(), asOfMs = nowMs, readAtMs = nowMs)
        copy = snap
        items.forEach { ticketMirror.remove(it.uuid) }
        return snap
    }

    override suspend fun detail(ticketUuid: String): DetailRead {
        if (!online) return DetailRead.Unavailable
        val d = server.detail(ticketUuid) ?: return DetailRead.NotFound
        details[ticketUuid] = d
        d.events.forEach { eventMirror.remove(it.uuid) }
        return DetailRead.Found(d)
    }

    override suspend fun cachedDetail(ticketUuid: String) = details[ticketUuid]

    override suspend fun outbox() = TicketOutbox(
        ticketMirror.values.map { it.copy(delivery = delivery(it.uuid)) },
        eventMirror.values.map { it.copy(delivery = delivery(it.uuid)) },
    )
}

/** Photos that always stage; [picked] records the gallery handles imported. */
class FakePhotos(override val max: Int = 3) : TicketPhotos {
    val staged = mutableListOf<String>()
    val discarded = mutableListOf<String>()
    var failImport = false

    /** When set, a capture waits here (the camera is open) before it stages its photo. */
    var cameraGate: CompletableDeferred<Unit>? = null

    override suspend fun capture(photoUuid: String): Boolean { cameraGate?.await(); staged += photoUuid; return true }

    @Composable
    override fun rememberGallery(onPicked: (List<String>) -> Unit): (maxItems: Int) -> Unit = { n -> onPicked(List(n) { "content://pick/$it" }) }

    override suspend fun import(photoUuid: String, picked: String, owner: PhotoOwner): PhotoResult =
        if (failImport) PhotoResult.Failed(null) else { staged += photoUuid; PhotoResult.Added }

    override suspend fun discard(photoUuid: String) { discarded += photoUuid; staged -= photoUuid }

    @Composable
    override fun Thumbnail(photoUuid: String, modifier: Modifier) = Unit
}

/** Sequential v4-shaped uuids, so tests can name them. */
class Uuids(private val prefix: String = "00000000-0000-4000-8000-") {
    private var n = 0
    fun next(): String = prefix + (++n).toString().padStart(12, '0')
}

/** [TicketDeps.background] runs at once here, so a left screen's discards are visible right after the dispose. */
fun deps(device: FakeDevice, photos: TicketPhotos? = null, uuids: Uuids = Uuids()) =
    TicketDeps(device, device, photos, now = { device.nowMs }, newUuid = uuids::next, background = CoroutineScope(Dispatchers.Unconfined))
