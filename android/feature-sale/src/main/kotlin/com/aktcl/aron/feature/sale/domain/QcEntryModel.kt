package com.aktcl.aron.feature.sale.domain

import com.aktcl.aron.rules.Money

/** One of the six fault types the SR app offers (`qc_fault_type` with `applies_to` app; the closed list of db V0027, D-34). */
data class QcFaultType(val code: String, val group: String)

/** One row of the QC summary table (manual p37): the defect quantity of a SKU in group MFC and in group MKT, and their total. */
data class QcSummaryRow(val skuId: Long, val mfcQty: Long, val mktQty: Long) { val totalQty: Long get() = mfcQty + mktQty }

/**
 * The QC entry rules (F-SR-027, screens-sr s8, manual p36-38): six fault types in two groups, the settlement of a row is
 * defect quantity x the price at capture through `shared:rules` (the same function the Review uses), and with a per-SKU cap
 * (MQ-03: the basis is unknown, so a cap exists only when the bundle supplies one) no row can be raised past what is left.
 */
object QcEntryModel {
    /** Group MFC: production faults; group MKT: transport (market) faults. The contract enum is exactly these two. */
    const val PRODUCTION = "MFC"
    const val TRANSPORT = "MKT"

    /** `qty_base` of a QC line is 1..100000 in the contract (QcLinePayload). */
    const val MAX_QTY: Long = 100_000L

    val APP_TYPES: List<QcFaultType> = listOf(
        QcFaultType("damaged_crushed", PRODUCTION), QcFaultType("short_outer_pack_stick", PRODUCTION), QcFaultType("other_mfc", PRODUCTION),
        QcFaultType("expired_stock", TRANSPORT), QcFaultType("damaged_in_transit", TRANSPORT), QcFaultType("taste", TRANSPORT),
    )

    /** One row per SKU in first-entered order, MFC and MKT columns, nothing double counted: the sum of the rows equals the sum of the entries. */
    fun summary(entries: List<QcEntry>): List<QcSummaryRow> =
        entries.groupBy { it.skuId }.map { (sku, es) ->
            QcSummaryRow(sku, es.filter { it.faultGroup == PRODUCTION }.sumOf { it.defectQty }, es.filter { it.faultGroup == TRANSPORT }.sumOf { it.defectQty })
        }

    fun typesOf(group: String): List<QcFaultType> = APP_TYPES.filter { it.group == group }
    fun groupOf(code: String): String? = APP_TYPES.firstOrNull { it.code == code }?.group

    /**
     * How much QC the memo can still take: gross less discounts less the QC of every other row (the memo may not go below zero,
     * `net_negative`). Null when the gross is zero: a QC-only sale is a credit note and has no ceiling from the memo (D-18).
     */
    fun memoRoomMtk(grossMtk: Long, discountsMtk: Long, otherQcMtk: Long): Long? =
        if (grossMtk == 0L) null else (grossMtk - discountsMtk - otherQcMtk).coerceAtLeast(0)

    fun settlementMtk(sku: SaleSku, qty: Long): Long = Money.lineGrossMtk(qty, sku.unitPriceMtk, sku.pricePerQty.toLong())

    /** QC already entered for [sku] in taka (all six rows). */
    fun doneMtk(sku: SaleSku, entries: List<QcEntry>): Long = entries.filter { it.skuId == sku.skuId }.sumOf { settlementMtk(sku, it.defectQty) }

    /** What is left under [capMtk]; null when there is no cap. */
    fun remainingMtk(capMtk: Long?, doneMtk: Long): Long? = capMtk?.let { (it - doneMtk).coerceAtLeast(0) }

    /**
     * The largest quantity the row ([sku], [faultCode]) may hold so that the SKU's total stays within [capMtk] (no cap: no limit
     * from it) and the whole memo stays at or above zero: [memoRoomMtk] is gross less discounts less the QC of every other row
     * (the server refuses a memo with a negative net, `net_negative`). Both null: the contract quantity limit only.
     */
    fun maxQty(sku: SaleSku, entries: List<QcEntry>, faultCode: String, capMtk: Long?, memoRoomMtk: Long? = null): Long {
        val capRoom = capMtk?.let { cap -> cap - entries.filter { it.skuId == sku.skuId && it.faultTypeCode != faultCode }.sumOf { settlementMtk(sku, it.defectQty) } }
        val room = listOfNotNull(capRoom, memoRoomMtk).minOrNull() ?: return MAX_QTY
        if (room <= 0) return 0
        var lo = 0L
        var hi = MAX_QTY
        while (lo < hi) { // settlement is monotone in the quantity
            val mid = (lo + hi + 1) / 2
            if (settlementMtk(sku, mid) <= room) lo = mid else hi = mid - 1
        }
        return lo
    }
}
