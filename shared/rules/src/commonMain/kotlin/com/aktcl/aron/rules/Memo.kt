package com.aktcl.aron.rules

/** Unit a quantity was typed in (docs/24 s7.2); the stored quantity is always in the SKU base unit. */
enum class QtyUnit(val wire: String) { STICK("stick"), PIECE("piece"), DOZEN("dozen"), PACK("pack") }

/** Quantity helpers for docs/24 s7.2. */
object Quantity {
    /** `qty_base = qty_entered x (unit = pack ? packFactor : 1)`; throws on negatives, a pack with factor < 1, or overflow. */
    fun toBase(qtyEntered: Long, unit: QtyUnit, packFactor: Long = 1L): Long {
        require(qtyEntered >= 0) { "qtyEntered must be >= 0" }
        require(packFactor >= 1) { "packFactor must be >= 1" }
        return if (unit == QtyUnit.PACK) checkedMul(qtyEntered, packFactor) else qtyEntered
    }

    /** Read-only pack badge (`= 2 packs + 4`) from the base quantity: (whole packs, remaining base units); never stored. */
    fun packBadge(qtyBase: Long, packFactor: Long): Pair<Long, Long> {
        require(qtyBase >= 0) { "qtyBase must be >= 0" }
        require(packFactor >= 1) { "packFactor must be >= 1" }
        return (qtyBase / packFactor) to (qtyBase % packFactor)
    }
}

/** Discount kinds of `memo_discount` (docs/24 s7.4): offer and free_goods are offer discounts, drp is the slide/DRP deduction. */
enum class DiscountKind(val wire: String) { OFFER("offer"), DRP("drp"), FREE_GOODS("free_goods") }

/** One memo line: [qtyBase] in the SKU base unit and the unrounded [grossMtk] of docs/24 s7.3. */
data class MemoLine(val skuId: Long, val qtyBase: Long, val grossMtk: Long) {
    init {
        require(qtyBase >= 0) { "qtyBase must be >= 0" }
        require(grossMtk >= 0) { "grossMtk must be >= 0" }
    }

    companion object {
        /** Prices a line: `divHalfUp(qtyBase x basePriceMtk, pricePerQty)` (docs/24 s7.3). */
        fun priced(skuId: Long, qtyBase: Long, basePriceMtk: Long, pricePerQty: Long = 1L): MemoLine =
            MemoLine(skuId, qtyBase, Money.lineGrossMtk(qtyBase, basePriceMtk, pricePerQty))
    }
}

/** One discount line `(sku, qty, value, kind)`; [skuId] is null for a memo-level discount; value may be 0 (docs/24 s7.4). */
data class DiscountLine(val skuId: Long?, val qtyBase: Long, val valueMtk: Long, val kind: DiscountKind) {
    init {
        require(qtyBase >= 0) { "qtyBase must be >= 0" }
        require(valueMtk >= 0) { "valueMtk must be >= 0" }
    }
}

/** One QC line; only lines with [appliedToMemo] reduce the memo (docs/24 s7.4). */
data class QcLine(val settlementMtk: Long, val appliedToMemo: Boolean) {
    init { require(settlementMtk >= 0) { "settlementMtk must be >= 0" } }
}

/** Every derived memo number of docs/24 s7.4; all in mtk except the counts. */
data class MemoTotals(
    val grossMtk: Long,
    val offerDiscountMtk: Long,
    val drpDiscountMtk: Long,
    val qcDeductionMtk: Long,
    val rawMtk: Long,
    val netMtk: Long,
    val roundAdjMtk: Long,
    val lineCount: Int,
    val discountLineCount: Int,
    val qcLineCount: Int,
)

/** Payment split of a memo: `paid + due = net`, credit when due > 0. */
data class MemoSettlement(val paidMtk: Long, val dueMtk: Long) {
    val isCredit: Boolean get() = dueMtk > 0
}

/** The numbers a memo header states, as sent by the phone; [MemoMath.verify] checks them against the children. */
data class StatedMemo(
    val grossMtk: Long,
    val offerDiscountMtk: Long,
    val drpDiscountMtk: Long,
    val qcDeductionMtk: Long,
    val netMtk: Long,
    val roundAdjMtk: Long,
    val paidMtk: Long,
    val dueMtk: Long,
    val lineCount: Int,
    val discountLineCount: Int,
    val qcLineCount: Int,
)

/** One failed equation: the header [field], what it [stated] and what the children [computed]. */
data class MemoMismatch(val field: String, val stated: Long, val computed: Long)

/** Memo arithmetic of docs/24 s7.4: exact integer sums over unrounded line values, rounded once to the paisa. */
object MemoMath {
    /** Computes all totals; sums are exact, `net = roundToPaisaHalfUp(gross - offer - drp - qc)`, `roundAdj = net - raw`. */
    fun totals(lines: List<MemoLine>, discounts: List<DiscountLine>, qcLines: List<QcLine>): MemoTotals {
        val gross = lines.fold(0L) { acc, l -> checkedAdd(acc, l.grossMtk) }
        val offer = discounts.filter { it.kind != DiscountKind.DRP }.fold(0L) { acc, d -> checkedAdd(acc, d.valueMtk) }
        val drp = discounts.filter { it.kind == DiscountKind.DRP }.fold(0L) { acc, d -> checkedAdd(acc, d.valueMtk) }
        val qc = qcLines.filter { it.appliedToMemo }.fold(0L) { acc, q -> checkedAdd(acc, q.settlementMtk) }
        val raw = checkedAdd(checkedAdd(gross, -offer), checkedAdd(-drp, -qc))
        val net = Money.roundToPaisaHalfUp(raw)
        return MemoTotals(gross, offer, drp, qc, raw, net, net - raw, lines.size, discounts.size, qcLines.size)
    }

    /** Splits [net] into paid and due: `due = net - paid` (credit when positive); [paidMtk] must be >= 0. */
    fun settle(netMtk: Long, paidMtk: Long): MemoSettlement {
        require(paidMtk >= 0) { "paidMtk must be >= 0" }
        return MemoSettlement(paidMtk, netMtk - paidMtk)
    }

    /**
     * Recomputes every equation of s7.4 against [stated] and returns the failures (empty = consistent).
     * [allowNegativeNet] mirrors `cfg.memo.allow_negative_net`; a negative net is only valid for a zero-gross sale (QC credit).
     */
    fun verify(
        stated: StatedMemo,
        lines: List<MemoLine>,
        discounts: List<DiscountLine>,
        qcLines: List<QcLine>,
        allowNegativeNet: Boolean = true,
    ): List<MemoMismatch> {
        val c = totals(lines, discounts, qcLines)
        val out = ArrayList<MemoMismatch>()
        fun check(field: String, s: Long, computed: Long) { if (s != computed) out += MemoMismatch(field, s, computed) }
        check("gross_mtk", stated.grossMtk, c.grossMtk)
        check("offer_discount_mtk", stated.offerDiscountMtk, c.offerDiscountMtk)
        check("drp_discount_mtk", stated.drpDiscountMtk, c.drpDiscountMtk)
        check("qc_deduction_mtk", stated.qcDeductionMtk, c.qcDeductionMtk)
        check("net_mtk", stated.netMtk, c.netMtk)
        check("round_adj_mtk", stated.roundAdjMtk, c.roundAdjMtk)
        check("paid_plus_due_mtk", checkedAdd(stated.paidMtk, stated.dueMtk), stated.netMtk)
        check("line_count", stated.lineCount.toLong(), c.lineCount.toLong())
        check("discount_line_count", stated.discountLineCount.toLong(), c.discountLineCount.toLong())
        check("qc_line_count", stated.qcLineCount.toLong(), c.qcLineCount.toLong())
        if (c.netMtk < 0 && (!allowNegativeNet || c.grossMtk != 0L)) out += MemoMismatch("net_negative", c.netMtk, 0)
        return out
    }
}
