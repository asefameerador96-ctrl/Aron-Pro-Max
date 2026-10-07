package com.aktcl.aron.feature.sale.domain

import com.aktcl.aron.rules.DiscountKind
import com.aktcl.aron.rules.DiscountLine
import com.aktcl.aron.rules.MemoLine
import com.aktcl.aron.rules.MemoMath
import com.aktcl.aron.rules.MemoSettlement
import com.aktcl.aron.rules.MemoTotals
import com.aktcl.aron.rules.Money
import com.aktcl.aron.rules.PriceType
import com.aktcl.aron.rules.QcLine
import com.aktcl.aron.rules.Quantity
import com.aktcl.aron.rules.QtyUnit

/** A priced draft line with its pack badge (whole packs, remainder) and an optional stock warning. */
data class ReviewLine(
    val sku: SaleSku,
    val entry: DraftLine,
    val qtyBase: Long,
    val memoLine: MemoLine,
    val packBadge: Pair<Long, Long>,
    /** True when [qtyBase] exceeds the known current stock; a warning, never a block (F-SR-023). */
    val exceedsStock: Boolean,
)

data class CategorySubtotal(val categoryCode: String, val qtyBase: Long, val grossMtk: Long)

/** One Product QC deduction: defect sticks x price at capture (F-SR-027). */
data class QcDeduction(val entry: QcEntry, val unitPriceMtk: Long, val pricePerQty: Int, val settlementMtk: Long)

/** One DRP reward: empty packets give whole reward packs valued as a deduction (F-SR-022). */
data class DrpReward(val skuId: Long, val emptyPackets: Long, val rewardPacks: Long, val rewardQtyBase: Long, val valueMtk: Long)

sealed interface ReviewProblem {
    data class UnknownSku(val skuId: Long) : ReviewProblem
    data object NothingToSell : ReviewProblem
    data object PaidNegative : ReviewProblem
    /** Credit needs 0 <= paid < net, in whole paisa (two decimals). */
    data class PaidOutOfRange(val paidMtk: Long, val netMtk: Long) : ReviewProblem
    data class PaidNotPaisa(val paidMtk: Long) : ReviewProblem
    data class NoDrpOffer(val skuId: Long) : ReviewProblem
    data class QcAboveCap(val skuId: Long, val settlementMtk: Long, val capMtk: Long) : ReviewProblem
    /** `cfg.sale.max_lines_per_memo`: the server rejects a longer memo with `lines_exceed_max`, not retryable (docs/24 s4.5). */
    data class TooManyLines(val count: Int, val max: Int) : ReviewProblem
    /** A unit that is neither the SKU's base unit nor `pack` (docs/24 s7.2), or not a known unit at all. */
    data class UnitNotAllowed(val skuId: Long, val unit: String) : ReviewProblem
}

data class SaleReview(
    val lines: List<ReviewLine>,
    val categories: List<CategorySubtotal>,
    val drp: List<DrpReward>,
    val qc: List<QcDeduction>,
    val totals: MemoTotals,
    val settlement: MemoSettlement,
    val problems: List<ReviewProblem>,
) {
    /** Saving is allowed only with no problems. */
    val canCommit: Boolean get() = problems.isEmpty()
}

/**
 * Review (নিরীক্ষণ) maths: lines, category subtotals, DRP reward deductions and QC deductions, and
 * `net = gross - offer discount - DRP - QC` through `shared:rules` only (docs/24 s7.4). The offer line stays zero (docs/27).
 */
object SaleReviewCalculator {
    /** Maximum QC deduction per SKU in milli-taka; assumed cap (MQ-03/MQ-04 unknown): no cap when absent. */
    const val DEFAULT_MAX_LINES: Int = 60

    fun review(draft: SaleDraft, catalog: Map<Long, SaleSku>, qcCapMtkBySku: Map<Long, Long> = emptyMap(), maxLines: Int = DEFAULT_MAX_LINES): SaleReview {
        val problems = ArrayList<ReviewProblem>()
        val priceType = PriceType.entries.firstOrNull { it.wire == draft.priceType } ?: PriceType.OUTLET

        val lines = draft.lines.mapNotNull { e ->
            val sku = catalog[e.skuId] ?: run { problems += ReviewProblem.UnknownSku(e.skuId); return@mapNotNull null }
            val unit = QtyUnit.entries.firstOrNull { it.wire == e.unit }
            if (unit == null || (unit != QtyUnit.PACK && unit.wire != sku.baseUnit)) {
                problems += ReviewProblem.UnitNotAllowed(sku.skuId, e.unit); return@mapNotNull null
            }
            val base = Quantity.toBase(e.qtyEntered, unit, sku.packFactor.toLong())
            val line = MemoLine.priced(sku.skuId, base, sku.unitPriceMtk, sku.pricePerQty.toLong(), priceType)
            ReviewLine(sku, e, base, line, Quantity.packBadge(base, sku.packFactor.toLong()), sku.stockBase?.let { base > it } ?: false)
        }

        val categories = lines.groupBy { it.sku.categoryCode }.map { (cat, ls) ->
            CategorySubtotal(cat, ls.sumOf { it.qtyBase }, ls.sumOf { it.memoLine.grossMtk })
        }.sortedBy { it.categoryCode }

        val drp = draft.slide.mapNotNull { s ->
            val sku = catalog[s.skuId]
            val rule = sku?.drp
            if (sku == null || rule == null) { problems += ReviewProblem.NoDrpOffer(s.skuId); return@mapNotNull null }
            val packs = s.emptyPackets / rule.emptyPerReward
            if (packs == 0L) return@mapNotNull null
            val qty = packs * sku.packFactor
            DrpReward(s.skuId, s.emptyPackets, packs, qty, Money.lineGrossMtk(qty, sku.unitPriceMtk, sku.pricePerQty.toLong()))
        }

        val qc = draft.qc.mapNotNull { q ->
            val sku = catalog[q.skuId] ?: run { problems += ReviewProblem.UnknownSku(q.skuId); return@mapNotNull null }
            QcDeduction(q, sku.unitPriceMtk, sku.pricePerQty, Money.lineGrossMtk(q.defectQty, sku.unitPriceMtk, sku.pricePerQty.toLong()))
        }
        qc.groupBy { it.entry.skuId }.forEach { (sku, ds) ->
            val sum = ds.sumOf { it.settlementMtk }
            qcCapMtkBySku[sku]?.let { cap -> if (sum > cap) problems += ReviewProblem.QcAboveCap(sku, sum, cap) }
        }

        val discounts = drp.map { DiscountLine(it.skuId, it.rewardQtyBase, it.valueMtk, DiscountKind.DRP) }
        val totals = MemoMath.totals(lines.map { it.memoLine }, discounts, qc.map { QcLine(it.settlementMtk, true) })

        if (draft.lines.size > maxLines) problems += ReviewProblem.TooManyLines(draft.lines.size, maxLines)
        if (lines.isEmpty() && !draft.zeroSale && problems.none { it is ReviewProblem.UnitNotAllowed || it is ReviewProblem.UnknownSku }) problems += ReviewProblem.NothingToSell
        val paid = draft.paidMtk ?: totals.netMtk
        if (draft.paidMtk != null) {
            if (paid % Money.MTK_PER_PAISA != 0L) problems += ReviewProblem.PaidNotPaisa(paid)
            // Credit means due > 0: partial payment strictly below the net (and at least 0).
            if (paid < 0 || paid >= totals.netMtk) problems += ReviewProblem.PaidOutOfRange(paid, totals.netMtk)
        }
        val settlement = MemoMath.settle(totals.netMtk, paid.coerceAtLeast(0))
        return SaleReview(lines, categories, drp, qc, totals, settlement, problems)
    }
}
