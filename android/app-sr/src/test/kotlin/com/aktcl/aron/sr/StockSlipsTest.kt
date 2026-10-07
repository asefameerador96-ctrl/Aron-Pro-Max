package com.aktcl.aron.sr

import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.StockMovementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StockSlipsTest {
    private fun meta(at: String) = CaptureMeta("2026-10-05", at, 1, 1, 0, true, 10231, null, "b", false, 1)
    private fun mv(uuid: String, at: String, sku: Long, printed: Boolean = false, kind: String = "issue") =
        StockMovementEntity(uuid, meta(at), kind, sku, 20, "dozen", 1, 20, null, printed)

    @Test fun twoUnprintedSavesPrintOldestFirstAsOneSlipEach() {
        val a = listOf(mv("a105", "2026-10-05T04:31:00Z", 105), mv("a103", "2026-10-05T04:31:00Z", 103))
        val b = listOf(mv("b100", "2026-10-05T05:10:00Z", 100))
        val first = StockSlips.oldestUnprintedSave(b + a)
        assertEquals(setOf("a105", "a103"), first.map { it.clientUuid }.toSet())
        assertEquals("a103", StockSlips.slipUuid(first))
    }

    @Test fun aPrintedSaveIsSkippedAndTheNextOneIsChosen() {
        val a = listOf(mv("a103", "2026-10-05T04:31:00Z", 103, printed = true))
        val b = listOf(mv("b100", "2026-10-05T05:10:00Z", 100))
        assertEquals(listOf("b100"), StockSlips.oldestUnprintedSave(a + b).map { it.clientUuid })
    }

    @Test fun aReturnInTheSameSecondIsAnotherSlip() {
        val issue = mv("i1", "2026-10-05T04:31:00Z", 103)
        val ret = mv("r1", "2026-10-05T04:31:00Z", 104, kind = "return")
        assertEquals(1, StockSlips.oldestUnprintedSave(listOf(issue, ret)).size)
    }

    @Test fun nothingUnprintedGivesNoSlip() = assertTrue(StockSlips.oldestUnprintedSave(listOf(mv("a", "2026-10-05T04:31:00Z", 1, printed = true))).isEmpty())
}
