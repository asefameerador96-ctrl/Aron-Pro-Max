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
}

/** Server totals of the last successful sync (null before the first sync); production reads `server_totals` of the sync response. */
fun interface ServerCounts { suspend fun counts(businessDate: String): Map<String, Int>? }

/** Queues the `day_submit` record as the LAST event of the day, with the device counts and dues (REQUEST: core `recordDaySubmit`). */
fun interface DaySubmitWriter { suspend fun queue(businessDate: String, gate: SubmitGate, counts: List<CountRow>) }

data class SubmitUiState(
    val online: Boolean = false,
    val counts: List<CountRow> = emptyList(),
    val gate: SubmitGate = SubmitGate(false, emptyList(), null),
    val progress: SubmitProgress = SubmitProgress.Idle,
    val syncing: Boolean = false,
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

    fun submit() {
        viewModelScope.launch {
            lock.withLock {
                recompute()
                val s = _state.value
                if (!s.gate.enabled || s.progress != SubmitProgress.Idle) return@withLock
                writer.queue(businessDate(), s.gate, s.counts)
                _state.value = s.copy(progress = SubmitProgress.Queued)
                scheduler.requestSync(userId, SyncTrigger.DAY_SUBMIT)
            }
        }
    }

    private suspend fun recompute() {
        val d = businessDate()
        val device = source.deviceCounts(d)
        val srv = server.counts(d)
        val counts = device.toSortedMap().map { (t, n) -> CountRow(t, n, srv?.get(t)) }
        val submitted = source.alreadySubmitted(d)
        val settled = submitted && source.submitSettled(d)
        val prev = _state.value.progress
        val gate = SalesSubmitRules.gate(source.outboxStates(d), counts, source.dues(d), alreadySubmitted = submitted && prev == SubmitProgress.Idle)
        _state.value = _state.value.copy(
            online = online(), counts = counts, gate = gate,
            progress = when { settled -> SubmitProgress.Settled; submitted || prev == SubmitProgress.Queued -> SubmitProgress.Queued; else -> SubmitProgress.Idle },
        )
    }
}
