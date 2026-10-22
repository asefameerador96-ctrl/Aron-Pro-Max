package com.aktcl.aron.feature.sale.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.random.Random

/**
 * Money gate on the phone (qa; docs/31 money row, docs/24 s7, D-18, D-19, D-34): the SR review maths
 * (SaleReviewCalculator, what the memo prints and the outbox stores) against a BigInteger/BigDecimal oracle written
 * here from the draft alone. Seeded loops; a failure names the seed and the case.
 */
class MoneyGateReviewTest {
    private val skus = listOf(
        Fx.maxr, Fx.aster, Fx.match, Fx.odd,
        SaleSku(107, "PACK20", "cigarette", "Pack priced", null, "stick", 20, 158_705, 20, "2026-09-01", allowLoose = true), // 158.705 per 20 sticks
        SaleSku(108, "DOZ", "match", "Dozen priced", null, "dozen", 1, 92_005, 12, "2026-09-01", allowLoose = true),
    )
    private val catalog = skus.associateBy { it.skuId }

    private fun big(n: Long) = BigInteger.valueOf(n)
    private fun halfAway(n: BigInteger, d: Long): BigInteger = BigDecimal(n).divide(BigDecimal(d), 0, RoundingMode.HALF_UP).toBigIntegerExact()
    private fun gross(qtyBase: Long, s: SaleSku) = halfAway(big(qtyBase) * big(s.unitPriceMtk), s.pricePerQty.toLong())

    @Test
    fun reviewTotalsEqualTheOracleRoundedOnce() {
        val rnd = Random(2019)
        repeat(2_000) { n ->
            val lines = List(rnd.nextInt(1, 8)) {
                val s = skus.random(rnd)
                if (rnd.nextInt(4) == 0 && s.packFactor > 1) DraftLine(s.skuId, rnd.nextLong(1, 30), "pack") else DraftLine(s.skuId, rnd.nextLong(1, 500), s.baseUnit)
            }
            val qc = if (rnd.nextBoolean()) listOf(QcEntry(skus.random(rnd).skuId, "torn_pack", "MFC", rnd.nextLong(1, 4))) else emptyList()
            val slide = if (rnd.nextInt(3) == 0) listOf(SlideEntry(Fx.maxr.skuId, rnd.nextLong(0, 60))) else emptyList()
            val draft = Fx.draft().copy(lines = lines, qc = qc, slide = slide)
            val r = SaleReviewCalculator.review(draft, catalog)
            val ctx = "seed 2019 case $n: $draft"

            val g = lines.fold(BigInteger.ZERO) { a, l -> val s = catalog.getValue(l.skuId); a + gross(if (l.unit == "pack") l.qtyEntered * s.packFactor else l.qtyEntered, s) }
            val q = qc.fold(BigInteger.ZERO) { a, e -> a + gross(e.defectQty, catalog.getValue(e.skuId)) } // D-34: defect x price at capture
            val d = slide.fold(BigInteger.ZERO) { a, e -> val packs = e.emptyPackets / Fx.maxr.drp!!.emptyPerReward; a + gross(packs * Fx.maxr.packFactor, Fx.maxr) }
            val raw = g - d - q
            assertEquals(ctx, g.toLong(), r.totals.grossMtk)
            assertEquals(ctx, q.toLong(), r.totals.qcDeductionMtk)
            assertEquals(ctx, d.toLong(), r.totals.drpDiscountMtk)
            assertEquals(ctx, 0L, r.totals.offerDiscountMtk) // docs/27: offers deferred, the offer line stays zero
            assertEquals("D-19 once-rounded $ctx", (halfAway(raw, 10) * BigInteger.TEN).toLong(), r.totals.netMtk)
            assertEquals(ctx, r.totals.grossMtk, r.categories.sumOf { it.grossMtk })
            assertEquals(ctx, r.totals.netMtk, r.settlement.paidMtk + r.settlement.dueMtk)
            r.qc.forEach { assertEquals(ctx, gross(it.entry.defectQty, catalog.getValue(it.entry.skuId)).toLong(), it.settlementMtk) }
        }
    }

    @Test
    fun aQcOnlyZeroSaleMayLeaveACredit() {
        // D-18: zero sale plus QC gives a negative net, stored as a credit (cfg.memo.allow_negative_net).
        val r = SaleReviewCalculator.review(Fx.draft().copy(zeroSale = true, qc = listOf(QcEntry(Fx.odd.skuId, "torn_pack", "MFC", 3))), catalog)
        assertEquals(-23_810L, r.totals.netMtk) // 3 x 7.935 = 23.805 -> -23.81
        assertEquals(0L, r.totals.grossMtk)
        assertTrue(r.problems.toString(), r.canCommit)
    }

    /**
     * FINDING QA-MNY-05 (android-sr, SaleReviewCalculator): a sale whose QC settlement exceeds its gross gives a negative
     * net with a non-zero gross and no review problem (no QC cap configured), so the phone prints and commits a memo the
     * server then quarantines (D-18, MemoMath.verify `net_negative`; proven in MoneyIngestOracleTest). Enable when the
     * review blocks it (a problem such as NetNegativeWithSale) or caps QC at the gross.
     */
    @Ignore("QA-MNY-05: negative net with a gross is committable on the phone (D-18)")
    @Test
    fun aSaleWithQcAboveItsGrossIsNotCommittable() {
        val draft = Fx.draft().copy(lines = listOf(DraftLine(Fx.aster.skuId, 1, "piece")), qc = listOf(QcEntry(Fx.maxr.skuId, "torn_pack", "MFC", 5)))
        val r = SaleReviewCalculator.review(draft, catalog)
        assertTrue(r.totals.grossMtk > 0 && r.totals.netMtk < 0)
        assertFalse("net ${r.totals.netMtk} with gross ${r.totals.grossMtk} is committable", r.canCommit)
    }
}
