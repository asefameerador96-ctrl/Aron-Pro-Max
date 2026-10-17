package com.aktcl.aron.feature.sale.domain

import com.aktcl.aron.rules.Money

/** One of the six fault types the SR app offers (`qc_fault_type` with `applies_to` app; the closed list of db V0027, D-34). */
data class QcFaultType(val code: String, val group: String)

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

    fun typesOf(group: String): List<QcFaultType> = APP_TYPES.filter { it.group == group }
    fun groupOf(code: String): String? = APP_TYPES.firstOrNull { it.code == code }?.group

    fun settlementMtk(sku: SaleSku, qty: Long): Long = Money.lineGrossMtk(qty, sku.unitPriceMtk, sku.pricePerQty.toLong())

    /** QC already entered for [sku] in taka (all six rows). */
    fun doneMtk(sku: SaleSku, entries: List<QcEntry>): Long = entries.filter { it.skuId == sku.skuId }.sumOf { settlementMtk(sku, it.defectQty) }

    /** What is left under [capMtk]; null when there is no cap. */
    fun remainingMtk(capMtk: Long?, doneMtk: Long): Long? = capMtk?.let { (it - doneMtk).coerceAtLeast(0) }

    /** The largest quantity the row ([sku], [faultCode]) may hold so the SKU's total stays within [capMtk] (any size when there is no cap). */
    fun maxQty(sku: SaleSku, entries: List<QcEntry>, faultCode: String, capMtk: Long?): Long {
        if (capMtk == null) return MAX_QTY
        val others = entries.filter { it.skuId == sku.skuId && it.faultTypeCode != faultCode }.sumOf { settlementMtk(sku, it.defectQty) }
        val room = capMtk - others
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
