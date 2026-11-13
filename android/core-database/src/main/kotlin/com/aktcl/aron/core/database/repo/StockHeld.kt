package com.aktcl.aron.core.database.repo

import com.aktcl.aron.core.database.dao.CaptureDao
import com.aktcl.aron.core.database.entity.MemoDiscountEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity

/** One SKU a sale asks more of than the rep holds (all in base units: sticks, pieces, dozens). */
data class StockShortage(val skuId: Long, val requestedBase: Long, val heldBase: Long)

/** Thrown inside the saving transaction when a sale would take more of a SKU than the phone holds; nothing is written. */
class OverStockException(val shortages: List<StockShortage>) : IllegalStateException("sale exceeds the stock held: $shortages")

/**
 * The stock the rep holds, from stored rows only (D-OWNER-PREREQ-01 rule 2): `issue + adjustment - qc_return - sold - reward packs`
 * per SKU in base units, over live memos (a memo replaced by an edit counts once, through its replacement). A SKU with no row is
 * ZERO, never unknown. This is the one formula; the Stock tracker, the sale catalog and the in-transaction re-check all read it.
 */
object StockHeld {
    /** Memos not replaced by a later edit; [ignoring] is one more memo to leave out (the memo an open edit is about to supersede). */
    fun liveMemos(memos: List<MemoEntity>, ignoring: String? = null): Set<String> {
        val superseded = memos.mapNotNull { it.supersedesClientUuid }.toSet()
        return memos.map { it.clientUuid }.filter { it !in superseded && it != ignoring }.toSet()
    }

    /** Free reward packs (`memo_discount` kind drp with a quantity) of live memos per SKU, base units. */
    fun rewardsBySku(discounts: List<MemoDiscountEntity>, live: Set<String>): Map<Long, Long> =
        discounts.filter { it.memoClientUuid in live && it.kind == "drp" && it.skuId != null }
            .groupBy { it.skuId!! }.mapValues { (_, v) -> v.sumOf { it.qtyBase ?: 0L } }

    fun held(
        movements: List<StockMovementEntity>, memos: List<MemoEntity>, lines: List<MemoLineEntity>,
        discounts: List<MemoDiscountEntity> = emptyList(), ignoring: String? = null,
    ): Map<Long, Long> {
        val live = liveMemos(memos, ignoring)
        val sold = lines.filter { it.memoClientUuid in live }.groupBy { it.skuId }.mapValues { (_, v) -> v.sumOf { it.qtyBase } }
        val free = rewardsBySku(discounts, live)
        val moved = movements.groupBy { it.skuId }.mapValues { (_, m) ->
            m.sumOf {
                when (it.kind) { "issue", "adjustment" -> it.qtyBase; "qc_return" -> -it.qtyBase; else -> 0L }
            }
        }
        return (moved.keys + sold.keys + free.keys).associateWith { (moved[it] ?: 0L) - (sold[it] ?: 0L) - (free[it] ?: 0L) }
    }

    /** What one memo takes from each SKU: its sale lines plus its reward packs. */
    fun requested(lines: List<MemoLineEntity>, discounts: List<MemoDiscountEntity>): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        lines.forEach { out.merge(it.skuId, it.qtyBase, Long::plus) }
        discounts.filter { it.kind == "drp" && it.skuId != null }.forEach { out.merge(it.skuId!!, it.qtyBase ?: 0L, Long::plus) }
        return out
    }

    /** The SKUs of [requested] that exceed what is held (a missing SKU holds zero). */
    fun shortages(requested: Map<Long, Long>, held: Map<Long, Long>): List<StockShortage> =
        requested.filter { (_, q) -> q > 0 }.mapNotNull { (id, q) -> (held[id] ?: 0L).let { h -> if (q > h) StockShortage(id, q, h) else null } }.sortedBy { it.skuId }

    /** Reads the day's rows through [dao] (inside a transaction when the caller opened one) and returns the held figure per SKU. */
    suspend fun read(dao: CaptureDao, businessDate: String, ignoring: String? = null): Map<Long, Long> =
        held(dao.stockOn(businessDate), dao.memosOn(businessDate), dao.linesOn(businessDate), dao.discountsOn(businessDate), ignoring)
}
