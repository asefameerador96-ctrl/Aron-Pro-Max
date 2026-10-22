package com.aktcl.aron.feature.memo.domain

import com.aktcl.aron.rules.EntrySpec
import com.aktcl.aron.rules.EntryUnits


/** One memo of the day as the summary needs it; built from stored memo rows (never from the screen). */
data class SummaryMemo(
    val memoUuid: String,
    val supersedesUuid: String?,
    val lines: List<SummaryLine>,
    val discounts: List<SummaryDiscount>,
    val qcDeductionMtk: Long,
    val netMtk: Long,
)

data class SummaryLine(val skuId: Long, val qtyBase: Long, val grossMtk: Long)

/** A discount component of a memo: [skuId] null for memo-level; [kind] is `offer`, `free_goods` or `drp`. */
data class SummaryDiscount(val skuId: Long?, val valueMtk: Long, val kind: String)

data class SummarySku(val skuId: Long, val categoryCode: String)

/** Per-SKU row of the Sales Summary (F-SR-036, UI-SR-30..34): memo count is the number of distinct memos holding the SKU. */
data class SkuSummaryRow(
    val skuId: Long,
    val categoryCode: String,
    val memoCount: Int,
    val qtyBase: Long,
    val valueMtk: Long,
    val discountMtk: Long,
    val discountedValueMtk: Long,
    /** Closing stock that goes back: issued - sold - qc returns (never negative). */
    val returnQtyBase: Long,
    /** [qtyBase] and [returnQtyBase] in the SKU's entry unit (packets, boxes, dozens; docs/34 s4.2), which the screens and the paper show; equal to the base figures when no entry unit is known. */
    val qtyEntered: Long = qtyBase,
    val returnEntered: Long = returnQtyBase,
    /** Unit label key of the entered figures (`packet`, `box`, `dozen`, `stick`, `piece`); blank when unknown. */
    val unitKey: String = "",
    /** Base units left over after whole entry units (sticks or pieces); shown small beside the figure, never floored away. */
    val qtyRemBase: Long = 0L,
    val returnRemBase: Long = 0L,
)

/** The category rows add entry units of their SKUs; [unitKey] is blank when its SKUs do not share one unit word (never added across units). */
data class CategorySummaryRow(
    val categoryCode: String, val qtyBase: Long, val valueMtk: Long, val discountMtk: Long, val discountedValueMtk: Long, val returnQtyBase: Long,
    val qtyEntered: Long = qtyBase, val returnEntered: Long = returnQtyBase, val unitKey: String = "",
    val qtyRemBase: Long = 0L, val returnRemBase: Long = 0L,
)

data class DaySummary(
    val skus: List<SkuSummaryRow>,
    val categories: List<CategorySummaryRow>,
    val grossMtk: Long,
    /** The "discount and others" line: offer + DRP + QC, shown as a deduction. */
    val discountAndOthersMtk: Long,
    val grandTotalMtk: Long,
    val memoCount: Int,
)

object DaySummaryCalculator {
    /**
     * Aggregates the day's live memos. A memo that another memo supersedes (an edit) is dropped, so an edited sale is counted once.
     * [issuedBase] and [qcReturnBase] come from the stock tracker; the return column is `issued - sold - qc_return`.
     * Every total is an exact integer sum of stored values; the grand total is the sum of memo nets (docs/24 s7.6).
     */
    fun compute(
        memos: List<SummaryMemo>,
        skus: Map<Long, SummarySku>,
        issuedBase: Map<Long, Long>,
        qcReturnBase: Map<Long, Long> = emptyMap(),
        /** How each SKU is counted (docs/34 s4.2); when absent the entered figures equal the base figures. */
        entryOf: (Long) -> EntrySpec? = { null },
    ): DaySummary {
        val superseded = memos.mapNotNull { it.supersedesUuid }.toSet()
        val live = memos.filter { it.memoUuid !in superseded }
        val skuIds = (live.flatMap { m -> m.lines.map { it.skuId } } + issuedBase.keys).toSortedSet()
        val rows = skuIds.map { id ->
            val sku = skus[id]
            val withSku = live.filter { m -> m.lines.any { it.skuId == id } }
            val qty = live.sumOf { m -> m.lines.filter { it.skuId == id }.sumOf { it.qtyBase } }
            val value = live.sumOf { m -> m.lines.filter { it.skuId == id }.sumOf { it.grossMtk } }
            val disc = live.sumOf { m -> m.discounts.filter { it.skuId == id && it.kind != "drp" }.sumOf { it.valueMtk } }
            val ret = ((issuedBase[id] ?: 0L) - qty - (qcReturnBase[id] ?: 0L)).coerceAtLeast(0L)
            val spec = entryOf(id)
            val cat = sku?.categoryCode ?: ""
            SkuSummaryRow(
                id, cat, withSku.size, qty, value, disc, value - disc, ret,
                qtyEntered = spec?.fromBase(qty) ?: qty, returnEntered = spec?.fromBase(ret) ?: ret,
                unitKey = spec?.let { EntryUnits.labelKey(cat, it.unit) }.orEmpty(),
                qtyRemBase = spec?.remainderBase(qty) ?: 0L, returnRemBase = spec?.remainderBase(ret) ?: 0L,
            )
        }
        val skuCats = rows.groupBy { it.categoryCode }.toSortedMap().map { (c, rs) ->
            CategorySummaryRow(
                c, rs.sumOf { it.qtyBase }, rs.sumOf { it.valueMtk }, rs.sumOf { it.discountMtk }, rs.sumOf { it.discountedValueMtk }, rs.sumOf { it.returnQtyBase },
                rs.sumOf { it.qtyEntered }, rs.sumOf { it.returnEntered }, rs.map { it.unitKey }.distinct().singleOrNull().orEmpty(),
                rs.sumOf { it.qtyRemBase }, rs.sumOf { it.returnRemBase },
            )
        }
        // Memo-level offers (no SKU) form their own "" category row so the Discount column reconciles with the
        // "discount and others" line.
        val memoLevel = live.sumOf { m -> m.discounts.filter { it.skuId == null && it.kind != "drp" }.sumOf { it.valueMtk } }
        val cats = if (memoLevel == 0L) skuCats else (skuCats.filter { it.categoryCode != "" } + skuCats.filter { it.categoryCode == "" } +
            CategorySummaryRow("", 0, 0, memoLevel, -memoLevel, 0)).let { l ->
            l.filter { it.categoryCode != "" } + l.filter { it.categoryCode == "" }.reduce { a, b -> CategorySummaryRow("", a.qtyBase + b.qtyBase, a.valueMtk + b.valueMtk, a.discountMtk + b.discountMtk, a.discountedValueMtk + b.discountedValueMtk, a.returnQtyBase + b.returnQtyBase, a.qtyEntered + b.qtyEntered, a.returnEntered + b.returnEntered, if (a.unitKey == b.unitKey) a.unitKey else "", a.qtyRemBase + b.qtyRemBase, a.returnRemBase + b.returnRemBase) }
        }
        val gross = live.sumOf { m -> m.lines.sumOf { it.grossMtk } }
        val net = live.sumOf { it.netMtk }
        return DaySummary(rows, cats, gross, gross - net, net, live.size)
    }
}
