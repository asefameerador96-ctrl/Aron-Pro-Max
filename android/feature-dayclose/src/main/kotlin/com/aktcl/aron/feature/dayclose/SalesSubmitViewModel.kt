package com.aktcl.aron.feature.dayclose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.contract.SyncTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Local facts of the route-day read from Room (REQUEST: docs/requests/android-sr-b-core-records.md). */
interface DaySource {
    suspend fun outboxStates(businessDate: String): OutboxStates
    suspend fun deviceCounts(businessDate: String): Map<String, Int>
    suspend fun dues(businessDate: String): DuesAtSubmit
    suspend fun alreadySubmitted(businessDate: String): Boolean
    /** True once the server acknowledged the day_submit record of this day. */
    suspend fun submitSettled(businessDate: String): Boolean
    /** D-NIGHT-DAYCLOSE-01 (b): the rows of the phone's ack registry the server refused, holds or asked to retry. */
    suspend fun rejectedRows(businessDate: String): List<RejectedRow> = emptyList()
    /** D-AGATE-04: marks one refused record (and its family) as seen: a SCREEN matter, it leaves the banner count; the row, the payload and the gates are untouched. */
    suspend fun markSeen(clientUuid: String) {}
    /** D-AGATE-04 round 2: undo of [markSeen] (a mis-tap is not permanent). */
    suspend fun markUnseen(clientUuid: String) {}
    /** D-AGATE-04 round 2: the refused rows the rep marked as seen, per state; subtracted from the banner only, never from the gates or the day_submit. */
    suspend fun seenRefused(): OutboxStates = OutboxStates(0, 0, 0, 0, 0)
    /** D-AGATE-04 round 2: the same states scoped to [businessDate] (the status line of the screen). */
    suspend fun outboxStatesOn(businessDate: String): OutboxStates = outboxStates(businessDate)
    /** D-AGATE-04 round 2: at most [limit] ROWS of the refused list, newest first (the list is paged, never an unbounded read). */
    suspend fun rejectedPage(businessDate: String, limit: Int): List<RejectedRow> = rejectedRows(businessDate).take(limit)
    /** D-AGATE-04 round 2: how many refused rows exist, a COUNT, so the screen can say "N more not shown". */
    suspend fun refusedTotal(): Int = 0
    /** D-AGATE-04: re-queues a record the phone gave up on, with the same client uuid. True when it was re-queued. */
    suspend fun sendAgain(clientUuid: String): Boolean = false
}

/** Server totals of the last successful sync (null before the first sync); production reads `server_totals` of the sync response. */
fun interface ServerCounts {
    suspend fun counts(businessDate: String): Map<String, Int>?

    /**
     * ADAPTER, contract 1.9 queue row 4 (backend-core, later): this phone's own counts per record type for the date, from
     * the per-device digest. Null until the server sends them; the compare rule then uses the login totals of [counts].
     */
    suspend fun thisDeviceCounts(businessDate: String): Map<String, Int>? = null

    /** The server's own list of rows of this login and date it did not store (contract 1.9 row 4); empty until it sends them. */
    suspend fun serverRejected(businessDate: String): List<RejectedRow> = emptyList()
}

/** Queues the `day_submit` record as the LAST event of the day, with the device counts and dues (REQUEST: core `recordDaySubmit`). */
fun interface DaySubmitWriter { suspend fun queue(businessDate: String, gate: SubmitGate, counts: List<CountRow>) }

data class SubmitUiState(
    val online: Boolean = false,
    val counts: List<CountRow> = emptyList(),
    val gate: SubmitGate = SubmitGate(false, emptyList(), null),
    /** D-SRFIX-03b: the gate on the TRUE outbox counts (dismissed rows included): what the day_submit record uploads; [gate] is the screen's. */
    val uploadGate: SubmitGate = SubmitGate(false, emptyList(), null),
    val progress: SubmitProgress = SubmitProgress.Idle,
    val syncing: Boolean = false,
    /** D-NIGHT-DAYCLOSE-01 (b): listed, shown whether or not a digest has run. */
    val rejected: List<RejectedRow> = emptyList(),
    /** D-SRFIX-03: refused non-money records older than [RejectedDismissal.KEEP_DAYS] days ("earlier"), opened from a link, never in the main list. They ARE counted and listed. */
    val dismissed: List<RejectedRow> = emptyList(),
    /** D-AGATE-04: records the rep marked as seen: out of the count, still listed (closed by default); nothing is deleted. */
    val seen: List<RejectedRow> = emptyList(),
    /** D-AGATE-04: reading the refused records failed. The screen says so and offers a retry; the count is then NOT shown as zero. */
    val rejectedReadFailed: Boolean = false,
    /** D-AGATE-04: the true send state of the phone's outbox, for the status line (sent, waiting, not accepted). */
    val outbox: OutboxStates = OutboxStates(0, 0, 0, 0, 0),
    /** D-AGATE-04 round 2: refused rows beyond the page the list reads ("show more"); the banner count already includes them. */
    val refusedNotShown: Int = 0,
    /** A digest (server totals) has answered at least once for the date: before it, "Nothing to compare yet". */
    val serverKnown: Boolean = false,
    /** SRM-B-25: the day's business figures on the device and, once the digest carries them, on the server. Null: the host shows the record counts. */
    val business: BusinessTotals? = null,
    val serverBusiness: BusinessTotals? = null,
)

/**
 * Sales Submit (F-SR-034/035). Submit queues offline as the last event of the day and asks for an immediate upload; the
 * success text shows only when [DaySource.submitSettled] says the server settled it. A second tap while queued does nothing.
 */
class SalesSubmitViewModel(
    private val userId: Long,
    private val businessDate: () -> String,
    private val source: DaySource,
    private val server: ServerCounts,
    private val writer: DaySubmitWriter,
    private val scheduler: SyncScheduler,
    private val online: () -> Boolean,
    /** SRM-B-25: null keeps the record-count table; the gate and the submit never read it. */
    private val businessSource: BusinessSource? = null,
    private val serverBusiness: ServerBusiness = ServerBusiness { null },
) : ViewModel() {
    private val lock = Mutex()
    private val _state = MutableStateFlow(SubmitUiState())
    val state: StateFlow<SubmitUiState> = _state.asStateFlow()

    fun refresh() { viewModelScope.launch { lock.withLock { recompute() } } }

    /** The Sync data button: asks for an upload now, then re-reads the counts. */
    fun sync() {
        viewModelScope.launch {
            if (!SalesSubmitRules.syncButtonEnabled(online(), _state.value.syncing)) return@launch
            _state.value = _state.value.copy(syncing = true)
            scheduler.requestSync(userId, SyncTrigger.MANUAL)
            lock.withLock { recompute() }
            _state.value = _state.value.copy(syncing = false)
        }
    }

    /** D-AGATE-04 round 2: undo of "seen": the entry returns to the banner count and the main list. */
    fun markUnseen(clientUuid: String) {
        viewModelScope.launch { lock.withLock { runCatching { source.markUnseen(clientUuid) }.onFailure { logRead("mark_unseen", it) }; recompute() } }
    }

    /** D-AGATE-04: the rep has read a refused record: it leaves the count. Nothing is deleted. */
    fun markSeen(clientUuid: String) {
        viewModelScope.launch { lock.withLock { runCatching { source.markSeen(clientUuid) }.onFailure { logRead("mark_seen", it) }; recompute() } }
    }

    /** D-AGATE-04: "send again" for a record the phone gave up on: the same client uuid goes back to the queue and a sync is asked for. */
    fun sendAgain(clientUuid: String) {
        viewModelScope.launch {
            lock.withLock {
                val queued = runCatching { source.sendAgain(clientUuid) }.onFailure { logRead("send_again", it) }.getOrDefault(false)
                if (queued) scheduler.requestSync(userId, SyncTrigger.MANUAL)
                recompute()
            }
        }
    }

    /** One log line, no payload and no personal data: what failed and its exception type. */
    private fun logRead(what: String, t: Throwable) {
        runCatching { android.util.Log.w("AronSubmit", "$what failed type=${t.javaClass.simpleName}") }
    }

    fun submit() {
        viewModelScope.launch {
            lock.withLock {
                recompute()
                val s = _state.value
                if (!s.gate.enabled || s.progress != SubmitProgress.Idle) return@withLock
                writer.queue(businessDate(), s.uploadGate, s.counts)
                _state.value = s.copy(progress = SubmitProgress.Queued)
                scheduler.requestSync(userId, SyncTrigger.DAY_SUBMIT)
            }
        }
    }

    /** How many refused ROWS the list reads (newest first); "show more" raises it. The banner count is a COUNT query and never depends on it. */
    @Volatile private var listLimit = PAGE_ROWS

    /** D-AGATE-04 round 2: the rep asks for the older refused records (the list is paged, never an unbounded read). */
    fun showMoreRefused() { listLimit += PAGE_ROWS; refresh() }

    /**
     * One guarded read for the whole screen (D-AGATE-04 round 2, D1-4): a damaged read anywhere in it (the counts, the list) is flagged,
     * logged once and answered with the error state, and the gates FAIL CLOSED: Submit is not offered on data the phone could not read.
     */
    private suspend fun recompute() {
        try {
            recomputeUnsafe()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logRead("recompute", e)
            val closed = SubmitGate(false, listOf(SubmitBlock.Unreadable), null)
            _state.value = _state.value.copy(online = online(), gate = closed, uploadGate = closed, rejectedReadFailed = true)
        }
    }

    private suspend fun recomputeUnsafe() {
        val d = businessDate()
        val device = source.deviceCounts(d)
        val srv = server.counts(d)
        val mine = runCatching { server.thisDeviceCounts(d) }.getOrNull()
        // Every type either side counts (a type only the server has shows device 0: information, never a mismatch).
        val types = (device.keys + srv.orEmpty().keys).toSortedSet()
        // A type absent from the digest is all zero for this phone (contract 1.9), never a fallback to the login totals.
        val counts = types.map { t -> CountRow(t, device[t] ?: 0, srv?.let { it[t] ?: 0 }, mine?.let { it[t] ?: 0 }) }
        val local = source.rejectedPage(d, listLimit)
        val (seenRows, openLocal) = local.partition { it.seen }
        // The phone's own rows first; the server adds what this install does not hold (earlier installs, other phones).
        val held = local.map { it.clientUuid }.toSet()
        val (rejected, dismissed) = RejectedDismissal.split(openLocal + runCatching { server.serverRejected(d) }.getOrDefault(emptyList()).filter { it.clientUuid !in held }, d)
        val submitted = source.alreadySubmitted(d)
        val settled = submitted && source.submitSettled(d)
        val prev = _state.value.progress
        // the TRUE states feed the gate that the day_submit upload reads; "seen" is a screen matter and only the screen's gate subtracts it (D1-1)
        val trueStates = source.outboxStates(d)
        val seenStates = source.seenRefused()
        val states = trueStates.copy(
            rejected = (trueStates.rejected - seenStates.rejected - dismissed.sumOf { it.familySize }).coerceAtLeast(0),
            quarantined = (trueStates.quarantined - seenStates.quarantined).coerceAtLeast(0),
        )
        val dues = source.dues(d)
        val uploadGate = SalesSubmitRules.gate(trueStates, counts, dues, alreadySubmitted = submitted && prev == SubmitProgress.Idle)
        val gate = SalesSubmitRules.gate(states, counts, dues, alreadySubmitted = submitted && prev == SubmitProgress.Idle)
        val total = source.refusedTotal()
        // the business figures are display only: a failing read leaves the record-count table, it never blocks or changes the gate
        val biz = try { businessSource?.totals(d) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        val bizServer = if (biz == null) null else try { serverBusiness.totals(d) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        _state.value = _state.value.copy(
            business = biz, serverBusiness = bizServer,
            online = online(), counts = counts, gate = gate, uploadGate = uploadGate, rejected = rejected, dismissed = dismissed, seen = seenRows, rejectedReadFailed = false,
            outbox = source.outboxStatesOn(d), refusedNotShown = (total - local.sumOf { if (it.outcome == RejectedOutcome.RETRY) 0 else it.familySize }).coerceAtLeast(0),
            serverKnown = srv != null,
            progress = when { settled -> SubmitProgress.Settled; submitted || prev == SubmitProgress.Queued -> SubmitProgress.Queued; else -> SubmitProgress.Idle },
        )
    }

    private companion object { const val PAGE_ROWS = 100 }
}
