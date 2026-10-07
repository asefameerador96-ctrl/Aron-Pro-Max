package com.aktcl.aron.feature.memo.domain

/** One row of the Memo menu selector: the memo total beside the outlet, the memo number or time to tell memos apart (UI-SR-28). */
data class MemoMenuRow(val memoUuid: String, val memoNo: String, val outletId: Long, val committedAtIso: String, val totalMtk: Long, val dueMtk: Long)

/** The detail of a selected memo: items, discount table, and the three totals of the screen (UI-SR-27). */
data class MemoDetail(
    val memo: StoredMemo,
    val items: List<MemoItem>,
    val discountTable: List<MemoDiscountItem>,
    /** "Total discount": offer plus slide (DRP). */
    val totalDiscountMtk: Long,
    val totalQcMtk: Long,
    val grandTotalMtk: Long,
    val canMarkPaid: Boolean,
    /** Edit is offered only before QC and while the geofence holds (decided by the sale edit row, F-SR-033). */
    val reprintIsDuplicate: Boolean,
)

object MemoMenu {
    /** Live memos of the day (a memo another one supersedes is hidden), newest first. */
    fun rows(memos: List<StoredMemo>): List<MemoMenuRow> {
        val superseded = memos.mapNotNull { it.supersedesUuid }.toSet()
        return memos.filter { it.memoUuid !in superseded }.sortedByDescending { it.committedAtIso }
            .map { MemoMenuRow(it.memoUuid, it.memoNo, it.outletId, it.committedAtIso, it.netMtk, it.dueMtk) }
    }

    fun detail(m: StoredMemo): MemoDetail = MemoDetail(
        memo = m, items = m.lines, discountTable = m.discounts,
        totalDiscountMtk = m.offerDiscountMtk + m.drpDiscountMtk, totalQcMtk = m.qcDeductionMtk, grandTotalMtk = m.netMtk,
        canMarkPaid = m.dueMtk > 0,
        // A reprint of a memo already printed must say "duplicate" so a retailer cannot be billed twice (UI-SR-29).
        reprintIsDuplicate = m.printedAtIso != null,
    )
}
