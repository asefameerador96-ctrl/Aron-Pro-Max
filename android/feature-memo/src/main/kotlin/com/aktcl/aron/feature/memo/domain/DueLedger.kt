package com.aktcl.aron.feature.memo.domain

/** What a Mark paid tap records: the whole remaining due of one memo as one collection (F-SR-032). */
data class DueCollectionDraft(
    val outletId: Long,
    val againstMemoUuid: String,
    val againstMemoNo: String,
    val againstMemoBusinessDate: String,
    val amountMtk: Long,
    val isFullSettlement: Boolean,
    val outstandingBeforeMtk: Long,
)

object DueLedger {
    /** Remaining due of a memo: stored due minus every collection against it. Never negative. */
    fun remaining(memo: StoredMemo, collections: List<StoredCollection>): Long =
        (memo.dueMtk - collections.filter { it.againstMemoUuid == memo.memoUuid }.sumOf { it.amountMtk }).coerceAtLeast(0)

    /** The outlet's outstanding due: the sum over its live memos (a superseded memo's due moves to the edit, F-SR-033). */
    fun outletOutstanding(memos: List<StoredMemo>, collections: List<StoredCollection>, outletId: Long): Long {
        val superseded = memos.mapNotNull { it.supersedesUuid }.toSet()
        return memos.filter { it.outletId == outletId && it.memoUuid !in superseded }.sumOf { remaining(it, collections) }
    }

    /**
     * Mark paid settles the whole memo. Returns null when nothing is owed (the button is hidden), so a double tap after the
     * first collection was recorded cannot record a second one. The amount is at least one paisa (contract minimum 10 mtk).
     */
    fun markPaid(memo: StoredMemo, collections: List<StoredCollection>, outstandingBeforeMtk: Long): DueCollectionDraft? {
        val due = remaining(memo, collections)
        if (due < 10) return null
        return DueCollectionDraft(memo.outletId, memo.memoUuid, memo.memoNo, memo.businessDate, due, true, outstandingBeforeMtk)
    }
}
