package com.aktcl.aron.feature.stock

import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity

/** One SKU's day of stock in base units (sticks, pieces, dozens). */
data class StockBalance(
    val skuId: Long,
    /** Loaded from the depot today (`issue` movements). */
    val issued: Long,
    /** Signed "correct total" adjustments (F-SR-081). */
    val adjusted: Long,
    /** Faulty sticks collected at QC (`qc_return`, F-SR-053). */
    val qcReturned: Long,
    /** Sold on live memos (a memo replaced by an edit counts once, through its replacement). */
    val sold: Long,
    /** False when the SKU has no stock movement today (never loaded): the sale warning then has nothing to compare with. */
    val hasStockRow: Boolean = true,
) {
    /** In the bag right now: issued + adjusted - qc returned - sold. May be negative; the sale review warns, never blocks. */
    val current: Long get() = issued + adjusted - qcReturned - sold

    /** Stock that goes back to the depot at day end (the Summary "return" column). Never below zero. */
    val toReturn: Long get() = maxOf(0L, current)
}

/**
 * The current stock tracker (F-SR-050). Pure over stored rows, so it is the same after every sale, after an edit and
 * after a kill and relaunch: the balance is never a stored counter, it is recomputed from the append-only movements and
 * the live memo lines (docs/24 s12.5). One implementation feeds the sale stock warning, the KPI strip and the Summary.
 */
object StockTracker {
    /** Memos not replaced by a later edit. */
    fun liveMemos(memos: List<MemoEntity>): Set<String> {
        val superseded = memos.mapNotNull { it.supersedesClientUuid }.toSet()
        return memos.map { it.clientUuid }.filter { it !in superseded }.toSet()
    }

    fun balances(movements: List<StockMovementEntity>, memos: List<MemoEntity>, lines: List<MemoLineEntity>): Map<Long, StockBalance> {
        val live = liveMemos(memos)
        val sold = lines.filter { it.memoClientUuid in live }.groupBy { it.skuId }.mapValues { (_, v) -> v.sumOf { it.qtyBase } }
        val byKind = movements.groupBy { it.skuId }
        val ids = byKind.keys + sold.keys
        return ids.associateWith { id ->
            val m = byKind[id].orEmpty()
            StockBalance(
                skuId = id,
                issued = m.filter { it.kind == "issue" }.sumOf { it.qtyBase },
                adjusted = m.filter { it.kind == "adjustment" }.sumOf { it.qtyBase },
                qcReturned = m.filter { it.kind == "qc_return" }.sumOf { it.qtyBase },
                sold = sold[id] ?: 0L,
                hasStockRow = id in byKind,
            )
        }
    }

    /** The day's balances read from Room (no network, no clock). SKUs with neither a movement nor a sale are absent. */
    suspend fun of(db: AronDatabase, businessDate: String): Map<Long, StockBalance> {
        val dao = db.captureDao()
        return balances(dao.stockOn(businessDate), dao.memosOn(businessDate), dao.linesOn(businessDate))
    }
}
