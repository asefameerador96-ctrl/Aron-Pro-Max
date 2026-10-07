package com.aktcl.aron.feature.stock

import com.aktcl.aron.core.database.entity.CaptureMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class StockAdjustmentsTest {
    private val meta = CaptureMeta("2026-10-05", "2026-10-05T04:31:07.120Z", 1, 1, 0, true, 10231, null, "b", false, 1)

    @Test fun qcReturnIsAPositiveQcReturnMovement() {
        val m = StockAdjustments.qcReturn(103, 3, meta)
        assertEquals("qc_return", m.kind); assertEquals(3, m.qtyBase); assertEquals(103, m.skuId)
        assertThrows(IllegalArgumentException::class.java) { StockAdjustments.qcReturn(103, 0, meta) }
        assertThrows(IllegalArgumentException::class.java) { StockAdjustments.qcReturn(103, -1, meta) }
    }

    @Test fun correctTotalPostsTheSignedDifferenceNeverAnOverwrite() {
        val down = StockAdjustments.correctTotal(103, 400, 390, "count_error", "piece", meta)!!
        assertEquals("adjustment", down.kind); assertEquals(-10, down.qtyBase); assertEquals(-10, down.qtyEntered); assertEquals("count_error", down.reasonCode)
        assertEquals(15, StockAdjustments.correctTotal(103, 400, 415, "found_stock", "piece", meta)!!.qtyBase)
        assertNull(StockAdjustments.correctTotal(103, 400, 400, "count_error", "piece", meta))
    }

    @Test fun aReasonIsRequiredAndTheTotalCannotBeNegative() {
        assertThrows(IllegalArgumentException::class.java) { StockAdjustments.correctTotal(103, 400, 390, "", "piece", meta) }
        assertThrows(IllegalArgumentException::class.java) { StockAdjustments.correctTotal(103, 400, -1, "x_ok", "piece", meta) }
    }
}
