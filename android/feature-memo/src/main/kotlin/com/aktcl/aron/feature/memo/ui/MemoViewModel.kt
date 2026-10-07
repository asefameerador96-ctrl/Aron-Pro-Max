package com.aktcl.aron.feature.memo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
fun interface DueCollectionWriter { suspend fun write(draft: DueCollectionDraft) }

/** Prints or reprints a stored memo: production is `MemoPrinting.printMemo(memoUuid, print)`. */
fun interface MemoReprinter { suspend fun print(memoUuid: String, memo: MemoPrint): PrintAttempt }

data class MemoUiState(
    val rows: List<MemoMenuRow> = emptyList(),
    val selected: MemoDetail? = null,
    val remainingDueMtk: Long = 0,
    val canMarkPaid: Boolean = false,
    val outletDueMtk: Long = 0,
    val lastPrint: PrintAttempt? = null,
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
) : ViewModel() {
    private val lock = Mutex()
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
                dues.write(draft)
                reloadLocked(m.memoUuid)
            }
        }
    }

    fun reprint() {
        viewModelScope.launch {
            val m = _state.value.selected?.memo ?: return@launch
            val attempt = reprinter.print(m.memoUuid, PrintMapping.memo(m, names()))
            _state.value = _state.value.copy(lastPrint = attempt)
        }
    }

    private suspend fun reload(selected: String?) = lock.withLock { reloadLocked(selected) }

    private suspend fun reloadLocked(selected: String?) {
        val d = businessDate()
        memos = store.memos(d); collections = store.collections(d)
        val m = memos.firstOrNull { it.memoUuid == selected }
        val remaining = m?.let { DueLedger.remaining(it, collections) } ?: 0L
        _state.value = _state.value.copy(
            rows = MemoMenu.rows(memos), selected = m?.let(MemoMenu::detail), remainingDueMtk = remaining,
            canMarkPaid = m != null && DueLedger.markPaid(m, collections, 0) != null,
            outletDueMtk = m?.let { DueLedger.outletOutstanding(memos, collections, it.outletId) } ?: 0L,
        )
    }
}
