package com.aktcl.aron.feature.stock

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.StockMovementEntity

/**
 * Stock events that are not a load: faulty sticks leaving the SR's stock at QC (F-SR-053) and the "correct total" path
 * (F-SR-081). Both append one signed movement; nothing is ever overwritten (docs/24 s12.5).
 */
object StockAdjustments {
    private val REASON = Regex("^[a-z][a-z0-9_]{1,40}$")

    /** Faulty sticks collected at QC: a positive `qc_return` movement that the stock balance subtracts. */
    fun qcReturn(skuId: Long, qtyBase: Long, meta: CaptureMeta, newUuid: () -> String = ClientIds::newUuid): StockMovementEntity {
        require(qtyBase in 1..10_000_000) { "QC return must be a positive quantity" }
        return StockMovementEntity(newUuid(), meta, "qc_return", skuId, qtyBase, "stick", 1, qtyBase, null, false)
    }

    /**
     * "Correct total": the SR states the right total for a SKU and a reason; the app posts the signed difference to the
     * current balance as an `adjustment`. Returns null when nothing differs (no empty movement).
     */
    fun correctTotal(
        skuId: Long,
        currentBalanceBase: Long,
        rightTotalBase: Long,
        reasonCode: String,
        baseUnit: String,
        meta: CaptureMeta,
        newUuid: () -> String = ClientIds::newUuid,
    ): StockMovementEntity? {
        require(rightTotalBase in 0..10_000_000) { "the right total must be 0 or more" }
        require(REASON.matches(reasonCode)) { "a reason is required" }
        val delta = rightTotalBase - currentBalanceBase
        if (delta == 0L) return null
        require(delta in -10_000_000..10_000_000) { "difference out of range" }
        return StockMovementEntity(newUuid(), meta, "adjustment", skuId, delta, baseUnit, 1, delta, reasonCode, false)
    }
}
