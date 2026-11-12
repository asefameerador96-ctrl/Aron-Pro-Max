package com.aktcl.aron.feature.memo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aktcl.aron.core.database.repo.RouteDayFinalSubmittedException
import com.aktcl.aron.core.database.repo.RouteDaySubmittedException
import com.aktcl.aron.core.printing.doc.MemoPrint
import com.aktcl.aron.core.printing.flow.PrintAttempt
import com.aktcl.aron.feature.memo.domain.DueCollectionDraft
import com.aktcl.aron.feature.memo.domain.DueLedger
import com.aktcl.aron.feature.memo.domain.MemoDetail
import com.aktcl.aron.feature.memo.domain.MemoMenu
import com.aktcl.aron.feature.memo.domain.MemoMenuRow
import com.aktcl.aron.feature.memo.domain.PrintMapping
import com.aktcl.aron.feature.memo.domain.PrintNames
import com.aktcl.aron.feature.memo.domain.StoredCollection
import com.aktcl.aron.feature.memo.domain.StoredMemo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reads of the day's memos and collections (production: Room DAOs; REQUEST: docs/requests/android-sr-b-core-records.md). */
interface MemoStore {
    suspend fun memos(businessDate: String): List<StoredMemo>
    suspend fun collections(businessDate: String): List<StoredCollection>
}

/** Writes a `due_collection` with its outbox row in one transaction (REQUEST: core `recordDueCollection`). */
fun interface DueCollectionWriter {
    suspend fun write(draft: DueCollectionDraft)

    /**
     * tso-manual X4a (fix round): true when a collection for [outletId] would be accepted now (its route-day is closed by
     * neither a Final Submit nor a Sales Submit); the memo screen draws Mark Paid only then. A writer without the check
     * accepts everything, as before.
     */
    suspend fun accepts(outletId: Long): Boolean = true
}

/** Prints or reprints a stored memo and records the "readable?" answer: production is [MemoPrintingReprinter]. */
interface MemoReprinter {
    suspend fun print(memoUuid: String, memo: MemoPrint): PrintAttempt
    suspend fun confirm(attempt: PrintAttempt.AwaitingConfirmation, readable: Boolean) {}
}

/** Production [MemoReprinter]: `MemoPrinting.printMemo` and `confirm`; the reprint number and the duplicate marker come from it. */
class MemoPrintingReprinter(private val printing: com.aktcl.aron.core.printing.flow.MemoPrinting) : MemoReprinter {
    override suspend fun print(memoUuid: String, memo: MemoPrint) = printing.printMemo(memoUuid, memo)
    override suspend fun confirm(attempt: PrintAttempt.AwaitingConfirmation, readable: Boolean) = printing.confirm(attempt, readable)
}

data class MemoUiState(
    val rows: List<MemoMenuRow> = emptyList(),
    val selected: MemoDetail? = null,
    val remainingDueMtk: Long = 0,
    val canMarkPaid: Boolean = false,
    val outletDueMtk: Long = 0,
    val lastPrint: PrintAttempt? = null,
    val printing: Boolean = false,
    /** The first read of the day's memos has finished: before it the list is not empty, it is not known yet. */
    val loaded: Boolean = false,
    /**
     * tso-manual X4a (R-W-11; fix round): a Mark Paid of the selected memo would be refused now, because the route-day of its
     * collection is closed by its zone's Final Submit (`due_collection` is a locked type) or by a standing Sales Submit:
     * Mark Paid is hidden. Reprints stay allowed.
     */
    val dayLocked: Boolean = false,
    /** tso-manual X4a: a Mark Paid was refused by the Final Submit lock; the host shows the lock text until [MemoViewModel.dismissLockRefusal]. */
    val lockRefused: Boolean = false,
    /**
     * tso-manual X4a (fix round): a Mark Paid was refused because the route-day is closed by a Sales Submit (the lock arrived
     * after the button was drawn); nothing was stored. The host says the day's sales are submitted until [MemoViewModel.dismissLockRefusal].
     */
    val submitRefused: Boolean = false,
    /** SRM-B-20: a Mark Paid was stored; the host shows "marked as paid" with OK until [MemoViewModel.dismissPaidDone]. */
    val paidDone: Boolean = false,
)

/**
 * Memo menu model (F-SR-030/032/031): live memos of the day, the selected memo's detail, Mark paid (one collection, then the
 * button disappears) and reprint. [canMarkPaid] comes from [DueLedger.markPaid], never from the stored due.
 */
class MemoViewModel(
    private val store: MemoStore,
    private val dues: DueCollectionWriter,
    private val reprinter: MemoReprinter,
    private val names: () -> PrintNames,
    private val businessDate: () -> String,
    /**
     * tso-manual X4a (R-W-11; fix round): true when a Mark Paid of this memo would be refused now. By default the writer's own
     * check ([DueCollectionWriter.accepts], production: the collection's route-day is closed by neither Final Submit nor Sales
     * Submit), so the button and the write can never disagree. Read with every reload; a failing read counts as open.
     */
    private val markPaidLocked: suspend (StoredMemo) -> Boolean = { m -> !dues.accepts(m.outletId) },
) : ViewModel() {
    private val lock = Mutex()
    private val printLock = Mutex()
    private val _state = MutableStateFlow(MemoUiState())
    val state: StateFlow<MemoUiState> = _state.asStateFlow()
    private var memos: List<StoredMemo> = emptyList()
    private var collections: List<StoredCollection> = emptyList()

    fun refresh() { viewModelScope.launch { reload(_state.value.selected?.memo?.memoUuid) } }

    fun open(memoUuid: String) { viewModelScope.launch { reload(memoUuid) } }

    fun markPaid() {
        viewModelScope.launch {
            lock.withLock {
                val m = _state.value.selected?.memo ?: return@withLock
                val draft = DueLedger.markPaid(m, collections, DueLedger.outletOutstanding(memos, collections, m.outletId)) ?: return@withLock
                // tso-manual X4a (R-W-11; fix round): a lock can arrive after the button was drawn. Its refusal stores nothing (one
                // transaction) and is shown: the Final Submit lock text, or "already submitted" for a Sales Submit. Any other
                // failure stores nothing either and leaves the due and the button as they were; nothing leaves this scope.
                var lock = false
                var submitted = false
                var stored = false
                try {
                    dues.write(draft)
                    stored = true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: RouteDayFinalSubmittedException) {
                    lock = true
                } catch (_: RouteDaySubmittedException) {
                    submitted = true
                } catch (_: Exception) {
                }
                // one state write: the screen never sees the refusal before Mark Paid has gone with the reload
                reloadLocked(m.memoUuid) { it.copy(lockRefused = it.lockRefused || lock, submitRefused = it.submitRefused || submitted, paidDone = it.paidDone || stored) }
            }
        }
    }

    fun reprint() {
        viewModelScope.launch {
            printLock.withLock {
                val m = _state.value.selected?.memo ?: return@withLock
                if (_state.value.printing) return@withLock // a second tap while printing does nothing
                _state.value = _state.value.copy(printing = true, lastPrint = null)
                val attempt = try { reprinter.print(m.memoUuid, PrintMapping.memo(m, names())) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { PrintAttempt.Failed(com.aktcl.aron.core.printing.bt.PrintFailure.DISCONNECTED) }
                _state.value = _state.value.copy(printing = false, lastPrint = attempt)
                if (attempt == PrintAttempt.Done) reloadLocked(m.memoUuid) // printed_at may have changed: the duplicate marker follows it
            }
        }
    }

    /** "ছাপা ঠিক আছে?" answer; recorded once. */
    fun confirmPrint(readable: Boolean) {
        viewModelScope.launch {
            val a = _state.value.lastPrint as? PrintAttempt.AwaitingConfirmation ?: return@launch
            // A failed ledger write keeps the question open so the SR can answer again; it never escapes the scope.
            try { reprinter.confirm(a, readable) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { return@launch }
            _state.value = _state.value.copy(lastPrint = null)
            _state.value.selected?.memo?.memoUuid?.let { lock.withLock { reloadLocked(it) } }
        }
    }

    /** Closes the text of a refused Mark Paid, the Final Submit lock or the Sales Submit (tso-manual X4a). */
    fun dismissLockRefusal() { _state.value = _state.value.copy(lockRefused = false, submitRefused = false) }

    /** SRM-B-20: closes the "marked as paid" modal. */
    fun dismissPaidDone() { _state.value = _state.value.copy(paidDone = false) }

    /** Retry from a failed print, or dismiss it ("print later"): the memo stays reprintable. */
    fun retryPrint() = reprint()
    fun dismissPrint() { _state.value = _state.value.copy(lastPrint = null) }

    private suspend fun reload(selected: String?) = lock.withLock { reloadLocked(selected) }

    /** Re-reads the day and writes the state once; [also] adds to that same write (a Mark Paid's refusal). */
    private suspend fun reloadLocked(selected: String?, also: (MemoUiState) -> MemoUiState = { it }) {
        val d = businessDate()
        memos = store.memos(d); collections = store.collections(d)
        val m = memos.firstOrNull { it.memoUuid == selected }
        val remaining = m?.let { DueLedger.remaining(it, collections) } ?: 0L
        val locked = m != null && try { markPaidLocked(m) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
        _state.value = also(_state.value.copy(
            rows = MemoMenu.rows(memos), selected = m?.let(MemoMenu::detail), remainingDueMtk = remaining,
            canMarkPaid = m != null && !locked && DueLedger.markPaid(m, collections, 0) != null, dayLocked = locked,
            outletDueMtk = m?.let { DueLedger.outletOutstanding(memos, collections, it.outletId) } ?: 0L,
            loaded = true,
        ))
    }
}
