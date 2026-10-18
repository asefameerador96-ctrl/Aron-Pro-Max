package com.aktcl.aron.feature.memo.domain

/** One SKU of one business date: the quantity in base units and the line values (gross) summed over the date's live memos. */
data class HistorySkuRow(val skuId: Long, val qtyBase: Long, val valueMtk: Long)

/**
 * [subtotalMtk] is the sum of the memos' totals (net). [skuRows] is the per-SKU table of the manual (SKU, quantity, value) and
 * [skuTotalMtk] its footer, the exact sum of the rows shown (the manual's own footer is wrong by 400; F-SR-054, F-AMO-013).
 */
data class HistoryDay(val businessDate: String, val memos: List<MemoMenuRow>, val subtotalMtk: Long, val skuRows: List<HistorySkuRow> = emptyList(), val skuTotalMtk: Long = skuRows.sumOf { it.valueMtk })
data class SaleHistory(val days: List<HistoryDay>, val footerMtk: Long, val fromServer: Boolean)

object SaleHistoryBuilder {
    /**
     * An outlet's sales by date (newest first) from the local window. The footer is the exact sum of the shown rows
     * (F-SR-054). [fromServer] marks the offline-banner fallback (rows came from GET /memos); the arithmetic is identical.
     */
    fun build(memos: List<StoredMemo>, outletId: Long, fromServer: Boolean = false): SaleHistory {
        val superseded = memos.mapNotNull { it.supersedesUuid }.toSet()
        val mine = memos.filter { it.outletId == outletId && it.memoUuid !in superseded }
        val days = mine.groupBy { it.businessDate }.toSortedMap(reverseOrder()).map { (d, ms) ->
            val rows = MemoMenu.rows(ms)
            val skuRows = ms.flatMap { it.lines }.groupBy { it.skuId }
                .map { (sku, ls) -> HistorySkuRow(sku, ls.sumOf { it.qtyBase }, ls.sumOf { it.grossMtk }) }.sortedBy { it.skuId }
            HistoryDay(d, rows, rows.sumOf { it.totalMtk }, skuRows)
        }
        return SaleHistory(days, days.sumOf { it.subtotalMtk }, fromServer)
    }
}
