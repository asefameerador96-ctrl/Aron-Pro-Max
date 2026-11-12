package com.aktcl.aron.feature.sale.domain

import com.aktcl.aron.core.common.ClientIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SR manual s8-s10, rows SRM-A-28/29/40 (D-SR-MAN-S4-02/03/05): the pure parts of the zero-sale reason, the target figures and the quantity stats. */
class ManualCallFlowTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun zeroSaleKeepsItsReasonAndOnlyAKnownOne() {
        val d = SaleDraftOps.confirmZeroSale(Fx.draft(), ZeroSaleReason.OWNER_ABSENT.code)
        assertTrue(d.zeroSale); assertEquals("absence_of_owner", d.zeroReasonCode)
        assertThrows(IllegalArgumentException::class.java) { SaleDraftOps.confirmZeroSale(Fx.draft(), "bored") }
        assertNull(SaleDraftOps.confirmZeroSale(Fx.draft()).zeroReasonCode) // the QC-only path and the AMO have no reason
    }

    @Test fun theFourReasonsAreTheManualsAndEachHasADistinctCode() {
        assertEquals(listOf("stock_available", "outlet_closed_during_visit", "absence_of_owner", "insufficient_cash"), ZeroSaleReason.entries.map { it.code })
        assertEquals(4, ZeroSaleReason.entries.map { it.code }.toSet().size)
    }

    @Test fun aSaleLineOrACancelledQcOnlyClearsTheReason() {
        val z = SaleDraftOps.confirmZeroSale(Fx.draft(), ZeroSaleReason.STOCK_AVAILABLE.code)
        assertNull(SaleDraftOps.setQuantity(z, 100, 3, com.aktcl.aron.rules.QtyUnit.STICK).zeroReasonCode)
        assertNull(SaleDraftOps.cancelQcOnly(z).zeroReasonCode)
    }

    @Test fun theReasonSurvivesAKillAndRelaunchAndAnOldDraftStillLoads() {
        val dir0 = tmp.newFolder(); val store = FileDraftStore(dir0)
        val uuid = ClientIds.newUuid()
        val d = SaleDraftOps.confirmZeroSale(Fx.draft(uuid), ZeroSaleReason.INSUFFICIENT_CASH.code)
        store.save(d)
        assertEquals("insufficient_cash", FileDraftStore(dir0).load(uuid)?.zeroReasonCode) // a new store on the same folder: the relaunch
        // a draft written before this field existed has no key for it
        val old = """{"visitUuid":"$uuid","outletId":50001,"routeId":10231,"businessDate":"2026-10-05","memoUuid":"${ClientIds.newUuid()}","zeroSale":true}"""
        val dir = tmp.newFolder(); java.io.File(dir, "sale-draft-$uuid.json").writeText(old)
        val loaded = FileDraftStore(dir).load(uuid)
        assertTrue(loaded!!.zeroSale); assertNull(loaded.zeroReasonCode)
    }

    @Test fun targetRemainingNeverGoesNegativeAndPercentIsIntegerMath() {
        val t = OutletTarget("Super Star Low", "November", 2240, 390, 18, 20, listOf(OutletTargetRow("A", "cigarette", 40, 10), OutletTargetRow("B", "cigarette", 0, 5), OutletTargetRow("L", "lighter", 3, 3)))
        assertEquals(1850, t.remainingStd); assertEquals(0, t.remainingMemo)
        assertEquals(25, t.rows[0].percent); assertEquals(0, t.rows[1].percent) // no target is 0 %, never a division by zero
        val totals = t.categoryTotals()
        assertEquals(listOf("cigarette", "lighter"), totals.map { it.categoryCode })
        assertEquals(40L to 15L, totals[0].target to totals[0].sales)
    }

    @Test fun quickStepsAreInTheEntryUnitPerCategory() {
        assertEquals(listOf(1L, 5, 10, 20, 50, 100), QuickSteps.forCategory("cigarette"))
        assertEquals(listOf(1L, 5, 10, 20, 50, 100), QuickSteps.forCategory("bidi"))
        assertEquals(listOf(1L, 5, 10), QuickSteps.forCategory("lighter"))
        assertEquals(listOf(1L, 5, 10, 20), QuickSteps.forCategory("match"))
    }

    @Test fun previousDaySdlwAndUnknownAds() {
        val rows = listOf(
            StatRow("2026-11-12", 7, 99, "pack"), // today is not "the previous day"
            StatRow("2026-11-10", 7, 20, "pack"), StatRow("2026-11-10", 7, 5, "pack"),
            StatRow("2026-11-05", 7, 8, "pack"),   // exactly seven days before 2026-11-12
            StatRow("2026-11-09", 8, 3, "dozen"),
        )
        val m = SkuStatsBuilder.build(rows, "2026-11-12")
        assertEquals(25L, m[7]!!.previousDay); assertEquals(8L, m[7]!!.sdlw); assertNull(m[7]!!.adsHundredths)
        assertEquals(3L, m[8]!!.previousDay); assertNull(m[8]!!.sdlw)
        assertEquals(333L, SkuStatsBuilder.build(rows, "2026-11-12") { 333 }[7]!!.adsHundredths)
        assertTrue(SkuStatsBuilder.build(rows, "not-a-date").isEmpty())
    }

    @Test fun linesInAnotherUnitAreNotMixedIntoTheFigures() {
        val rows = listOf(StatRow("2026-11-10", 7, 600, "stick"), StatRow("2026-11-11", 7, 4, "pack"))
        val s = SkuStatsBuilder.build(rows, "2026-11-12")[7]!!
        assertEquals("pack", s.unit); assertEquals(4L, s.previousDay)
        assertFalse(s.sdlw != null)
    }
}
