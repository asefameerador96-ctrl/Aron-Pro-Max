package com.aktcl.aron.feature.dayclose

/**
 * SRM-B-25 (manual shot 40): the Sales Submit table shows the day's business figures, not record counts. One quantity of a category in the
 * field unit of rule 5 ([entered] in [unitKey]: packet, box, dozen, stick or piece, plus [remBase] base units left over after whole units),
 * and the same quantity in base units ([qtyBase]; what the server stores and compares). Quantities of different categories are never added.
 */
data class CategoryQty(val categoryCode: String, val qtyBase: Long, val entered: Long, val unitKey: String, val remBase: Long = 0L, val skuId: Long? = null)

/**
 * The five figures of the manual's table. [outletsVisited] counts distinct outlets (a revisit counts once); [sold], [stockIssued] and [qc]
 * are per category; [promotionMtk] is the offer + DRP discount in integer milli-taka (zero while the programmes are deferred, docs/27).
 * The device builds it from its own rows; the server's copy comes from the submit digest (contract 1.14.0, SV lane) through [ServerBusiness].
 */
data class BusinessTotals(
    val outletsVisited: Int,
    val sold: List<CategoryQty>,
    val stockIssued: List<CategoryQty>,
    val qc: List<CategoryQty>,
    val promotionMtk: Long,
)

/** Reads the device's business figures for the date from Room (production: the SR kit). */
fun interface BusinessSource { suspend fun totals(businessDate: String): BusinessTotals? }

/** The server's business figures for the date from the submit digest; null until the digest carries them (the column then shows "—"). */
fun interface ServerBusiness { suspend fun totals(businessDate: String): BusinessTotals? }

/** The category a SKU without a master row on the phone is counted under: shown as "other", never dropped. */
const val OTHER_CATEGORY = "other"

enum class BusinessKey(val recordKey: String) {
    OUTLET("visit"), SALE("memo"), STOCK("stock_movement"), QC("qc_line"), PROMOTION("memo_discount"),
}

object BusinessCompare {
    /** Base-unit total per category of [rows]. */
    private fun byCategory(rows: List<CategoryQty>): Map<String, Long> = rows.groupBy { it.categoryCode + (it.skuId?.let { id -> "#$id" } ?: "") }.mapValues { (_, v) -> v.sumOf { it.qtyBase } }

    /**
     * The compare rule of the count table (device ⊆ server, D-NIGHT-DAYCLOSE-01): the row is in sync when the server holds at least what
     * this phone has, per category; more on the server (another phone or an earlier install of this login) is information, never a mismatch.
     * Null when the server has no figures yet.
     */
    fun matches(key: BusinessKey, device: BusinessTotals, server: BusinessTotals?): Boolean? {
        server ?: return null
        return when (key) {
            BusinessKey.OUTLET -> server.outletsVisited >= device.outletsVisited
            BusinessKey.PROMOTION -> server.promotionMtk >= device.promotionMtk
            BusinessKey.SALE -> atLeast(byCategory(device.sold), byCategory(server.sold))
            BusinessKey.STOCK -> atLeast(byCategory(device.stockIssued), byCategory(server.stockIssued))
            BusinessKey.QC -> atLeast(byCategory(device.qc), byCategory(server.qc))
        }
    }

    private fun atLeast(device: Map<String, Long>, server: Map<String, Long>): Boolean = device.all { (c, q) -> (server[c] ?: 0L) >= q }
}
