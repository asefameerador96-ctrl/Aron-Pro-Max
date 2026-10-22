package com.aktcl.aron.sr

import com.aktcl.aron.feature.memo.domain.HomeMoney
import com.aktcl.aron.feature.memo.domain.MoneyCategory
import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Home panels of sr/home-2-kpis.webp: every row is bound to its OWN field. A money figure must never land under the wrong
 * label (a discount under QC): the reference reads discount 437.50 and QC 0.00 for the same day.
 */
class SrHomeMoneyBindingTest {
    // the reference day: match 141.00 + lighter 4,687.50 = gross 4,828.50; discount 437.50; QC 0.00; net 4,391.00
    private val day = HomeMoney(
        categories = listOf(MoneyCategory("match", 141_000, 0, 0, 0, 141_000), MoneyCategory("lighter", 4_687_500, 437_500, 0, 0, 4_250_000)),
        grossMtk = 4_828_500, totalDiscountMtk = 437_500, drpDiscountMtk = 0, totalQcMtk = 0, grandTotalMtk = 4_391_000,
    )

    private val label = { c: String -> c }

    @Test fun theDiscountNeverRendersUnderTheQcLabel() {
        val m = SrHomeReads.money(day, label, UiLocale.EN, packetsSold = 0)
        assertEquals("437.50", m.offerDiscountBare)
        assertEquals("0.00", m.qcBare)
        assertEquals("437.50", m.totalDiscountBare)
        assertEquals("0.00", m.totalQcBare)
        assertEquals("0.00", m.drpDiscountBare)
        assertEquals("4,828.50", m.grossBare)
        assertEquals("4,391.00", m.netBare)
    }

    @Test fun aQcDeductionNeverRendersUnderTheDiscountLabel() {
        val m = SrHomeReads.money(day.copy(totalDiscountMtk = 0, totalQcMtk = 80_000, grandTotalMtk = 4_748_500), label, UiLocale.EN)
        assertEquals("0.00", m.offerDiscountBare)
        assertEquals("80.00", m.qcBare)
        assertEquals("80.00", m.totalQcBare)
    }

    @Test fun theOfferDiscountIsTheTotalLessTheDrpPart() {
        val m = SrHomeReads.money(day.copy(totalDiscountMtk = 500_000, drpDiscountMtk = 62_500), label, UiLocale.EN)
        assertEquals("437.50", m.offerDiscountBare)
        assertEquals("62.50", m.drpDiscountBare)
        assertEquals("500.00", m.totalDiscountBare)
    }

    @Test fun banglaDigitsOnTheBareFigures() {
        assertEquals("৪,৩৯১.০০", SrHomeReads.bare(4_391_000, UiLocale.BN))
    }
}
