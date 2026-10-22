package com.aktcl.aron.feature.memo.domain

/**
 * A stored memo with everything the Memo menu, Sale History, due collection and the print model read. Built from the
 * immutable rows (`memo`, `memo_line`, `memo_discount`, `qc_line`); never edited. REQUEST: docs/requests/android-sr-b-core-records.md
 * (the DAO reads for discounts, QC lines and date ranges).
 */
data class StoredMemo(
    val memoUuid: String,
    val memoNo: String,
    val outletId: Long,
    val businessDate: String,
    val committedAtIso: String,
    val supersedesUuid: String?,
    val lines: List<MemoItem>,
    val discounts: List<MemoDiscountItem>,
    val qcLines: List<MemoQcItem>,
    val grossMtk: Long,
    val offerDiscountMtk: Long,
    val drpDiscountMtk: Long,
    val qcDeductionMtk: Long,
    val netMtk: Long,
    val paidMtk: Long,
    val dueMtk: Long,
    val printedAtIso: String? = null,
    /** Stored `round_adj_mtk` (never recomputed for printing). */
    val roundAdjMtk: Long = 0,
    /** Number of the memo this one replaced, for an edited memo. */
    val supersedesMemoNo: String? = null,
)

/**
 * One stored memo line: [qtyBase] is what the server aggregates; [qtyEntered] in [unitEntered] (`pack`, `dozen`, `stick`, `piece`)
 * is what the rep counted and what the screens and the paper show (docs/34 s4.2). A blank [unitEntered] means "base units".
 */
data class MemoItem(val skuId: Long, val qtyBase: Long, val unitPriceMtk: Long, val grossMtk: Long, val qtyEntered: Long = qtyBase, val unitEntered: String = "")
data class MemoDiscountItem(val skuId: Long?, val qtyBase: Long, val valueMtk: Long, val kind: String)
data class MemoQcItem(val skuId: Long, val qtyBase: Long, val settlementMtk: Long)

/** A recorded due collection against a memo (F-SR-032). */
data class StoredCollection(val againstMemoUuid: String, val amountMtk: Long, val businessDate: String)
