package com.aktcl.aron.feature.tickets

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import com.aktcl.aron.rules.TicketCounts
import com.aktcl.aron.rules.TicketMath
import kotlinx.coroutines.CancellationException

/** Runs a port call; a failure is null (the screen keeps its copy), a cancellation is passed on. */
internal suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

// ---- Ticket Dashboard (screenshot 11)

data class TicketDashboardUi(
    val loading: Boolean = true,
    val counts: TicketCounts = TicketMath.counts(emptyList<String>()),
    val recent: List<Ticket> = emptyList(),
    /** The server stamp of the copy shown; null when nothing was ever read. */
    val asOfMs: Long? = null,
    /** The online read failed: the phone's copy (or only its unsent tickets) is shown. */
    val offline: Boolean = false,
)

/** Counts, the two whole percentages (TicketMath, R-F-14) and the 10 most recent tickets, from the copy plus the outbox. */
class TicketDashboardState(private val deps: TicketDeps) {
    var ui by mutableStateOf(TicketDashboardUi())
        private set
    private var loading = false

    /** The trusted now, for the age texts. */
    fun now(): Long = deps.now()

    /** On open and on pull-to-refresh only: the kept copy at once, then one online read. */
    suspend fun load() {
        if (loading) return
        loading = true
        try {
            val cached = attempt { deps.source.cached() }
            show(cached, attempt { deps.source.outbox() } ?: TicketOutbox.EMPTY, loading = true, offline = false)
            val fresh = attempt { deps.source.list() }
            show(fresh ?: cached, attempt { deps.source.outbox() } ?: TicketOutbox.EMPTY, loading = false, offline = fresh == null)
        } finally {
            loading = false
        }
    }

    private fun show(snapshot: TicketSnapshot?, outbox: TicketOutbox, loading: Boolean, offline: Boolean) {
        val merged = TicketLists.merge(snapshot?.tickets.orEmpty(), outbox.tickets)
        ui = TicketDashboardUi(loading, TicketLists.counts(snapshot, outbox), TicketLists.recent(merged), snapshot?.asOfMs, offline)
    }
}

// ---- All Tickets (screenshots 12, 13)

data class TicketListUi(
    val loading: Boolean = true,
    val all: List<Ticket> = emptyList(),
    val tab: TicketTab = TicketTab.ALL,
    val query: String = "",
    val asOfMs: Long? = null,
    val offline: Boolean = false,
) {
    val shown: List<Ticket> get() = TicketLists.filter(all, tab, query)
}

/** The own tickets with the unsent ones merged by uuid, the five tabs and the local search. */
class TicketListState(private val deps: TicketDeps, tab: TicketTab = TicketTab.ALL, query: String = "") {
    var ui by mutableStateOf(TicketListUi(tab = tab, query = query))
        private set
    private var loading = false

    fun now(): Long = deps.now()

    fun setTab(tab: TicketTab) { ui = ui.copy(tab = tab) }

    fun setQuery(query: String) { ui = ui.copy(query = query.take(QUERY_MAX)) }

    suspend fun load() {
        if (loading) return
        loading = true
        try {
            val cached = attempt { deps.source.cached() }
            show(cached, attempt { deps.source.outbox() } ?: TicketOutbox.EMPTY, loading = true, offline = false)
            val fresh = attempt { deps.source.list() }
            show(fresh ?: cached, attempt { deps.source.outbox() } ?: TicketOutbox.EMPTY, loading = false, offline = fresh == null)
        } finally {
            loading = false
        }
    }

    private fun show(snapshot: TicketSnapshot?, outbox: TicketOutbox, loading: Boolean, offline: Boolean) {
        ui = ui.copy(loading = loading, all = TicketLists.merge(snapshot?.tickets.orEmpty(), outbox.tickets), asOfMs = snapshot?.asOfMs, offline = offline)
    }

    private companion object {
        const val QUERY_MAX = 40
    }
}

// ---- forms (Quick Ticket sheet, Create Ticket)

sealed interface FormPhase {
    data object Editing : FormPhase
    data object Saving : FormPhase

    /** In the outbox: the screen toasts once and closes. */
    data object Saved : FormPhase
    data class Invalid(val errors: List<TicketFormError>) : FormPhase

    /** The phone could not store it (the uuid is kept, so the retry is the same ticket). */
    data object Failed : FormPhase
    data class PhotoFailed(val messageRes: Int?) : FormPhase
}

/**
 * One ticket form (Quick sheet or Create). The draft lives in the caller's saveable state ([read]/[write]), so its uuid
 * survives rotation, a language switch and process death: a retry after a kill mid-save queues the same uuid and the
 * outbox keeps one row. A second tap while saving, or after the save, does nothing. Nothing waits for the network.
 */
class TicketFormState(
    private val deps: TicketDeps,
    val issueTypes: List<IssueType>,
    private val read: () -> TicketDraft,
    private val write: (TicketDraft) -> Unit,
) {
    constructor(deps: TicketDeps, issueTypes: List<IssueType>, draft: MutableState<TicketDraft>) :
        this(deps, issueTypes, { draft.value }, { draft.value = it })

    val draft: TicketDraft get() = read()
    var phase by mutableStateOf(if (read().queued) FormPhase.Saved else FormPhase.Editing)
        private set
    private var busy = false

    val type: IssueType? get() = issueTypes.firstOrNull { it.code == draft.issueTypeCode }
    val maxPhotos: Int get() = deps.maxPhotos
    val photos: TicketPhotos? get() = deps.photos
    val canAddPhoto: Boolean get() = !draft.queued && draft.photoUuids.size < maxPhotos

    private fun edit(f: (TicketDraft) -> TicketDraft) {
        val d = read()
        if (d.queued || busy) return
        // the uuid, the origin and the photos never change through an edit
        write(f(d).copy(uuid = d.uuid, origin = d.origin, photoUuids = d.photoUuids, queued = false))
        if (phase !is FormPhase.Saving) phase = FormPhase.Editing
    }

    fun setIssueType(code: String) = edit { it.copy(issueTypeCode = code) }
    fun setTitle(v: String) = edit { it.copy(title = v.take(TicketLimits.TITLE_MAX)) }
    fun setDescription(v: String) = edit { it.copy(description = v.take(TicketLimits.DESCRIPTION_MAX)) }
    fun setAffected(v: String) = edit { it.copy(affectedUsername = v.take(AFFECTED_INPUT_MAX)) }

    /** One camera photo (staged; claimed with the ticket). */
    suspend fun capture() {
        val p = deps.photos ?: return
        if (!canAddPhoto) return
        val id = deps.newUuid()
        if (attempt { p.capture(id) } == true) {
            val d = read()
            if (d.queued || d.photoUuids.size >= maxPhotos) attempt { p.discard(id) } else write(d.copy(photoUuids = d.photoUuids + id))
        }
    }

    /** Gallery pictures, staged one by one up to the limit; the first failure is shown and the rest still tried. */
    suspend fun addPicked(picks: List<String>) {
        val p = deps.photos ?: return
        for (pick in picks) {
            if (!canAddPhoto) break
            val id = deps.newUuid()
            when (val r = attempt { p.import(id, pick, PhotoOwner.ticket(draft.uuid)) } ?: PhotoResult.Failed(null)) {
                PhotoResult.Added -> read().let { write(it.copy(photoUuids = it.photoUuids + id)) }
                is PhotoResult.Failed -> phase = FormPhase.PhotoFailed(r.messageRes)
            }
        }
    }

    suspend fun removePhoto(id: String) {
        val d = read()
        if (d.queued || id !in d.photoUuids) return
        write(d.copy(photoUuids = d.photoUuids - id))
        attempt { deps.photos?.discard(id) }
    }

    /** The form is dismissed without a save: its staged photos are dropped. */
    suspend fun abandon() {
        val d = read()
        if (d.queued) return
        d.photoUuids.forEach { attempt { deps.photos?.discard(it) } }
        write(d.copy(photoUuids = emptyList()))
    }

    /** Validates, then queues the ticket under the form's one uuid. True when this call saved it. */
    suspend fun submit(): Boolean {
        val d = read()
        if (busy || d.queued) return false
        val errors = TicketRules.validate(d, issueTypes, maxPhotos)
        if (errors.isNotEmpty()) { phase = FormPhase.Invalid(errors); return false }
        val type = issueTypes.first { it.code == d.issueTypeCode }
        busy = true
        phase = FormPhase.Saving
        try {
            // false = the outbox already holds this uuid (the retry after a kill mid-save): saved either way, one record
            deps.queue.ticket(d.uuid, TicketRules.payload(d, type), d.photoUuids, TicketRules.local(d, type, deps.now()))
            write(read().copy(queued = true))
            phase = FormPhase.Saved
            return true
        } catch (e: CancellationException) {
            phase = FormPhase.Editing
            throw e
        } catch (_: Exception) {
            phase = FormPhase.Failed
            return false
        } finally {
            busy = false
        }
    }

    private var toasted = false

    /** True once after the save, for the one toast: a recreated screen does not toast again. */
    fun consumeSavedToast(): Boolean {
        if (phase != FormPhase.Saved || toasted) return false
        toasted = true
        return true
    }

    private companion object {
        const val AFFECTED_INPUT_MAX = 40
    }
}

/** The bundle's issue types, read once per screen (offline). Null while loading. */
class IssueTypesState(private val source: TicketSource) {
    var types by mutableStateOf<List<IssueType>?>(null)
        private set

    suspend fun load() {
        types = attempt { source.issueTypes() }.orEmpty()
    }
}

// ---- Ticket Details (screenshot 14)

data class TicketDetailUi(
    val loading: Boolean = true,
    val ticket: Ticket? = null,
    /** The server thread. */
    val serverEvents: List<TicketEvent> = emptyList(),
    /** This phone's events for the ticket (outbox), shown after the server's until read back. */
    val localEvents: List<LocalEvent> = emptyList(),
    val notFound: Boolean = false,
    val offline: Boolean = false,
) {
    val close: LocalEvent? get() = ticket?.let { TicketThread.close(it.uuid, localEvents) }
    val events: List<TicketEvent> get() = TicketThread.events(serverEvents, localEvents)
    val status: TicketStatus? get() = ticket?.let { TicketThread.status(it, close) }
    val canClose: Boolean get() = ticket?.let { TicketThread.canClose(it, close) } == true
    val canComment: Boolean get() = ticket?.let { TicketThread.canComment(it, close) } == true
}

sealed interface DetailPhase {
    data object Idle : DetailPhase
    data object ClosedSaved : DetailPhase
    data object CommentSaved : DetailPhase
    data class CommentInvalid(val error: CommentError) : DetailPhase
    data class PhotoFailed(val messageRes: Int?) : DetailPhase
    data object Failed : DetailPhase
}

/**
 * One ticket: the server thread plus this phone's own events, Submit Review & Close (one tap, one uuid per ticket), and the
 * comment composer for New and In Progress only (D-TSO-MAN-20, 21). The comment draft lives in the caller's saveable
 * state, one uuid per comment. Offline: the stored copy (or the unsent ticket itself) is shown and actions still queue.
 */
class TicketDetailState(
    private val deps: TicketDeps,
    val ticketUuid: String,
    private val readComment: () -> CommentDraft,
    private val writeComment: (CommentDraft) -> Unit,
) {
    constructor(deps: TicketDeps, ticketUuid: String, comment: MutableState<CommentDraft>) :
        this(deps, ticketUuid, { comment.value }, { comment.value = it })

    var ui by mutableStateOf(TicketDetailUi())
        private set
    var phase by mutableStateOf<DetailPhase>(DetailPhase.Idle)
        private set
    private var busy = false
    private var loading = false

    val comment: CommentDraft get() = readComment()
    val maxPhotos: Int get() = deps.maxPhotos
    val photos: TicketPhotos? get() = deps.photos

    fun now(): Long = deps.now()
    val canAddPhoto: Boolean get() = readComment().photoUuids.size < maxPhotos

    private fun same(a: String) = a.equals(ticketUuid, ignoreCase = true)

    suspend fun load() {
        if (loading) return
        loading = true
        try {
            val outbox = attempt { deps.source.outbox() } ?: TicketOutbox.EMPTY
            val local = outbox.tickets.firstOrNull { same(it.uuid) }
            val mine = outbox.events.filter { same(it.ticketUuid) }
            val stored = attempt { deps.source.cachedDetail(ticketUuid) }
            val listed = if (stored == null) attempt { deps.source.cached() }?.tickets?.firstOrNull { same(it.uuid) } else null
            val shown = stored?.ticket ?: listed ?: local
            ui = TicketDetailUi(loading = true, ticket = shown, serverEvents = stored?.events.orEmpty(), localEvents = mine)
            when (val r = attempt { deps.source.detail(ticketUuid) } ?: DetailRead.Unavailable) {
                is DetailRead.Found -> {
                    val after = attempt { deps.source.outbox() } ?: outbox
                    ui = TicketDetailUi(loading = false, ticket = r.detail.ticket, serverEvents = r.detail.events, localEvents = after.events.filter { same(it.ticketUuid) })
                }
                // not on the server yet: the unsent ticket is what there is
                DetailRead.NotFound -> ui = if (local != null) ui.copy(loading = false, ticket = local) else ui.copy(loading = false, notFound = true, ticket = null)
                DetailRead.Unavailable -> ui = ui.copy(loading = false, offline = true)
            }
        } finally {
            loading = false
        }
    }

    /** Submit Review & Close (D-TSO-MAN-20): one tap queues one close_review; a second tap, or another screen, does nothing. */
    suspend fun close(): Boolean {
        val t = ui.ticket ?: return false
        if (busy || !ui.canClose) return false
        busy = true
        try {
            val id = TicketRules.closeUuid(t.uuid)
            val local = LocalEvent(id, t.uuid, EventKind.CLOSE_REVIEW, null, deps.now())
            deps.queue.event(id, TicketRules.closePayload(t.uuid), emptyList(), local)
            ui = ui.copy(localEvents = ui.localEvents.filterNot { it.uuid == id } + local)
            phase = DetailPhase.ClosedSaved
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            phase = DetailPhase.Failed
            return false
        } finally {
            busy = false
        }
    }

    fun editComment(body: String) {
        val c = readComment()
        if (busy) return
        writeComment(c.copy(body = body.take(TicketLimits.COMMENT_MAX)))
        if (phase !is DetailPhase.Idle) phase = DetailPhase.Idle
    }

    suspend fun captureForComment() {
        val p = deps.photos ?: return
        if (!canAddPhoto) return
        val id = deps.newUuid()
        if (attempt { p.capture(id) } == true) readComment().let { writeComment(it.copy(photoUuids = it.photoUuids + id)) }
    }

    suspend fun addPickedForComment(picks: List<String>) {
        val p = deps.photos ?: return
        for (pick in picks) {
            if (!canAddPhoto) break
            val id = deps.newUuid()
            when (val r = attempt { p.import(id, pick, PhotoOwner.event(readComment().uuid)) } ?: PhotoResult.Failed(null)) {
                PhotoResult.Added -> readComment().let { writeComment(it.copy(photoUuids = it.photoUuids + id)) }
                is PhotoResult.Failed -> phase = DetailPhase.PhotoFailed(r.messageRes)
            }
        }
    }

    suspend fun removeCommentPhoto(id: String) {
        val c = readComment()
        if (id !in c.photoUuids) return
        writeComment(c.copy(photoUuids = c.photoUuids - id))
        attempt { deps.photos?.discard(id) }
    }

    /** Queues the comment under its one uuid, then starts a new empty comment with a new uuid. */
    suspend fun sendComment(): Boolean {
        val t = ui.ticket ?: return false
        val c = readComment()
        if (busy || !ui.canComment) return false
        TicketRules.validateComment(c, maxPhotos)?.let {
            // a second tap right after the save sees the new empty comment: nothing to say
            if (!(it == CommentError.EMPTY && phase == DetailPhase.CommentSaved)) phase = DetailPhase.CommentInvalid(it)
            return false
        }
        busy = true
        try {
            val local = LocalEvent(c.uuid, t.uuid, EventKind.COMMENT, c.body.trim(), deps.now(), c.photoUuids.size)
            deps.queue.event(c.uuid, TicketRules.commentPayload(t.uuid, c), c.photoUuids, local)
            ui = ui.copy(localEvents = ui.localEvents.filterNot { it.uuid == c.uuid } + local)
            writeComment(CommentDraft(deps.newUuid()))
            phase = DetailPhase.CommentSaved
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            phase = DetailPhase.Failed
            return false
        } finally {
            busy = false
        }
    }

    private var toasted: DetailPhase? = null

    /** The saved phase once, for its toast. */
    fun consumeToast(): DetailPhase? {
        val p = phase
        if ((p == DetailPhase.ClosedSaved || p == DetailPhase.CommentSaved || p == DetailPhase.Failed) && toasted !== p) {
            toasted = p
            return p
        }
        return null
    }
}

/** Saveable forms of the drafts (Bundle-friendly lists of strings), for rememberSaveable. */
object TicketSavers {
    private const val SEP = ","

    private fun toList(d: TicketDraft): List<String> = listOf(
        d.uuid, d.origin.wire, d.issueTypeCode.orEmpty(), d.title, d.description, d.affectedUsername, d.photoUuids.joinToString(SEP), if (d.queued) "1" else "0",
    )

    private fun fromList(l: List<String>): TicketDraft? = runCatching {
        TicketDraft(
            uuid = l[0], origin = TicketOrigin.entries.first { it.wire == l[1] }, issueTypeCode = l[2].ifEmpty { null }, title = l[3], description = l[4],
            affectedUsername = l[5], photoUuids = l[6].split(SEP).filter { it.isNotEmpty() }, queued = l[7] == "1",
        )
    }.getOrNull()

    val Draft: Saver<TicketDraft, Any> = Saver(save = { ArrayList(toList(it)) }, restore = { @Suppress("UNCHECKED_CAST") fromList(it as List<String>) })

    /** The Quick sheet's draft: null while no tile is open. */
    val OptionalDraft: Saver<TicketDraft?, Any> = Saver(
        save = { d -> if (d == null) arrayListOf() else ArrayList(toList(d)) },
        restore = { @Suppress("UNCHECKED_CAST") (it as List<String>).takeIf { l -> l.size == 8 }?.let(::fromList) },
    )

    val Comment: Saver<CommentDraft, Any> = Saver(
        save = { c -> arrayListOf(c.uuid, c.body, c.photoUuids.joinToString(SEP)) },
        restore = { @Suppress("UNCHECKED_CAST") (it as List<String>).let { l -> CommentDraft(l[0], l[1], l[2].split(SEP).filter { s -> s.isNotEmpty() }) } },
    )
}
