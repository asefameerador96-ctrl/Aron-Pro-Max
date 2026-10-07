package com.aktcl.aron.feature.memo.domain

data class HistoryDay(val businessDate: String, val memos: List<MemoMenuRow>, val subtotalMtk: Long)
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
            HistoryDay(d, rows, rows.sumOf { it.totalMtk })
        }
        return SaleHistory(days, days.sumOf { it.subtotalMtk }, fromServer)
    }
}
