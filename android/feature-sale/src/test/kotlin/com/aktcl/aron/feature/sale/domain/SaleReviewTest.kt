package com.aktcl.aron.feature.sale.domain

import com.aktcl.aron.rules.QtyUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SaleReviewTest {
    private fun review(d: SaleDraft, caps: Map<Long, Long> = emptyMap()) = SaleReviewCalculator.review(d, Fx.catalog, caps)

    @Test fun twentySticksAtSevenPointNineThreeFiveAreSummedUnroundedAndRoundedOnce() {
        val d = SaleDraftOps.setQuantity(Fx.draft(), 106, 20, QtyUnit.STICK)
        val r = review(d)
        assertEquals(158_700, r.totals.grossMtk)
        assertEquals(158_700, r.totals.netMtk)
        assertEquals(0, r.totals.roundAdjMtk)
    }

    @Test fun categorySubtotalsAndTheNetFormula() {
        var d = Fx.draft()
        d = SaleDraftOps.setQuantity(d, 100, 20, QtyUnit.STICK)   // 160,000
        d = SaleDraftOps.setQuantity(d, 103, 3, QtyUnit.PIECE)    // 37,500
        d = SaleDraftOps.setQuantity(d, 105, 3, QtyUnit.DOZEN)    // 84,000
        d = SaleDraftOps.setQuantity(d, 106, 1, QtyUnit.STICK)    // 7,935 -> 7,935
        d = SaleDraftOps.setSlide(d, 100, 10)                      // 1 reward pack = 10 sticks x 8,000 = 80,000
        d = SaleDraftOps.setQc(d, QcEntry(103, "torn_pack", "MFC", 2)) // 2 x 12,500 = 25,000
        val r = review(d)
        assertEquals(listOf("cigarette", "lighter", "match"), r.categories.map { it.categoryCode })
        assertEquals(167_935, r.categories[0].grossMtk)
        assertEquals(289_435, r.totals.grossMtk)
        assertEquals(80_000, r.totals.drpDiscountMtk)
        assertEquals(0, r.totals.offerDiscountMtk)
        assertEquals(25_000, r.totals.qcDeductionMtk)
        assertEquals(289_435 - 80_000 - 25_000, r.totals.rawMtk)
        assertEquals(184_440, r.totals.netMtk) // 184,435 rounds half up to the paisa
        assertEquals(5, r.totals.roundAdjMtk)
        assertEquals(4, r.totals.lineCount)
    }

    @Test fun tenEmptyPacketsGiveOneRewardPackAsEightyTakaAndQuantityIsUnchanged() {
        var d = SaleDraftOps.setQuantity(Fx.draft(), 100, 20, QtyUnit.STICK)
        d = SaleDraftOps.setSlide(d, 100, 10)
        val r = review(d)
        assertEquals(1, r.drp.single().rewardPacks)
        assertEquals(80_000, r.drp.single().valueMtk)
        assertEquals(20, r.lines.single().qtyBase)
        // nine packets are not yet a reward; no discount line is made
        assertTrue(review(SaleDraftOps.setSlide(d, 100, 9)).drp.isEmpty())
    }

    @Test fun slideOnASkuWithoutAnOfferIsAProblem() {
        val d = SaleDraftOps.setSlide(SaleDraftOps.setQuantity(Fx.draft(), 103, 1, QtyUnit.PIECE), 103, 5)
        assertTrue(review(d).problems.single() is ReviewProblem.NoDrpOffer)
    }

    @Test fun packBadgeAndStockWarningAreReadOnlyAndNeverBlock() {
        val d = SaleDraftOps.setQuantity(Fx.draft(), 100, 4, QtyUnit.PACK) // 40 sticks, stock 400
        val over = SaleDraftOps.setQuantity(Fx.draft(), 103, 26, QtyUnit.PIECE) // stock 25
        assertEquals(40, review(d).lines.single().qtyBase)
        assertEquals(4L to 0L, review(d).lines.single().packBadge)
        assertFalse(review(d).lines.single().exceedsStock)
        val r = review(over)
        assertTrue(r.lines.single().exceedsStock)
        assertTrue(r.canCommit)
    }

    @Test fun creditNeedsPaidFromZeroUpToBelowTheNetInWholePaisa() {
        val base = SaleDraftOps.setQuantity(Fx.draft(), 100, 10, QtyUnit.STICK) // net 80,000
        assertEquals(80_000, review(SaleDraftOps.setPaid(base, 79_990)).settlement.paidMtk + review(SaleDraftOps.setPaid(base, 79_990)).settlement.dueMtk)
        val ok = review(SaleDraftOps.setPaid(base, 30_000))
        assertTrue(ok.canCommit); assertTrue(ok.settlement.isCredit); assertEquals(50_000, ok.settlement.dueMtk)
        assertTrue(review(SaleDraftOps.setPaid(base, 0)).canCommit)
        assertTrue(review(SaleDraftOps.setPaid(base, 80_000)).problems.single() is ReviewProblem.PaidOutOfRange)
        assertTrue(review(SaleDraftOps.setPaid(base, 100_000)).problems.single() is ReviewProblem.PaidOutOfRange)
        assertTrue(review(SaleDraftOps.setPaid(base, 30_005)).problems.any { it is ReviewProblem.PaidNotPaisa })
        assertThrows(IllegalArgumentException::class.java) { SaleDraftOps.setPaid(base, -10) }
        assertFalse(review(base).settlement.isCredit)
    }

    @Test fun anEmptySaleCannotCommitUntilTheSrConfirmsZeroSale() {
        val d = Fx.draft()
        assertEquals(listOf(ReviewProblem.NothingToSell), review(d).problems)
        val z = review(SaleDraftOps.confirmZeroSale(d))
        assertTrue(z.canCommit); assertEquals(0, z.totals.lineCount); assertEquals(0, z.totals.netMtk)
        assertThrows(IllegalStateException::class.java) { SaleDraftOps.confirmZeroSale(SaleDraftOps.setQuantity(d, 100, 1, QtyUnit.STICK)) }
    }

    @Test fun settingAQuantityToZeroRemovesTheLineAndTypingASaleClearsZeroSale() {
        var d = SaleDraftOps.confirmZeroSale(Fx.draft())
        d = SaleDraftOps.setQuantity(d, 100, 5, QtyUnit.STICK)
        assertFalse(d.zeroSale)
        d = SaleDraftOps.setQuantity(d, 100, 0, QtyUnit.STICK)
        assertTrue(d.lines.isEmpty())
    }

    @Test fun qcDeductionIsDefectSticksTimesPriceAndCapsAreEnforced() {
        var d = SaleDraftOps.setQuantity(Fx.draft(), 100, 50, QtyUnit.STICK)
        d = SaleDraftOps.setQc(d, QcEntry(100, "torn_pack", "MFC", 3))
        d = SaleDraftOps.setQc(d, QcEntry(100, "wet", "TFC", 2))
        assertEquals(40_000, review(d).totals.qcDeductionMtk)
        assertTrue(review(d, mapOf(100L to 39_999L)).problems.single() is ReviewProblem.QcAboveCap)
        assertTrue(review(d, mapOf(100L to 40_000L)).canCommit)
    }

    @Test fun afterQcCompletesTheSaleIsLocked() {
        val d = SaleDraftOps.completeQc(SaleDraftOps.setQuantity(Fx.draft(), 100, 5, QtyUnit.STICK))
        assertThrows(IllegalStateException::class.java) { SaleDraftOps.setQuantity(d, 100, 6, QtyUnit.STICK) }
        assertThrows(IllegalStateException::class.java) { SaleDraftOps.setQc(d, QcEntry(100, "x", "MFC", 1)) }
    }

    @Test fun unknownSkuIsAProblemNotACrash() {
        val d = SaleDraftOps.setQuantity(Fx.draft(), 999, 1, QtyUnit.STICK)
        assertTrue(ReviewProblem.UnknownSku(999) in review(d).problems); assertFalse(review(d).canCommit)
    }
}
