package com.aktcl.aron.sr

import com.aktcl.aron.core.database.entity.StockMovementEntity

/** Stock slip grouping: one slip is exactly one Save (same business date, capture time and kind), the unit the print ledger flips. */
object StockSlips {
    /** The rows of the oldest Save that still has an unprinted row; empty when everything is printed. */
    fun oldestUnprintedSave(rows: List<StockMovementEntity>): List<StockMovementEntity> {
        val unprinted = rows.filter { !it.slipPrinted }
        val first = unprinted.minWithOrNull(compareBy({ it.meta.capturedAt }, { it.skuId })) ?: return emptyList()
        return rows.filter { it.meta.businessDate == first.meta.businessDate && it.meta.capturedAt == first.meta.capturedAt && it.kind == first.kind }
    }

    /** The slip's uuid: the lowest sku_id row of the Save. */
    fun slipUuid(save: List<StockMovementEntity>): String = save.minByOrNull { it.skuId }!!.clientUuid
}
