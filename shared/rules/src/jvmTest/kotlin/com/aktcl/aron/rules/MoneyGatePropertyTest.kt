package com.aktcl.aron.rules

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Money correctness gate (docs/31 "Money correctness" row; docs/24 s7; D-18, D-19). Seeded property loops (no property
 * library is on the classpath) against an arbitrary-precision oracle written here with BigInteger/BigDecimal only, so a
 * bug shared by the rules and the oracle is unlikely. Owner: qa lane. Each property names the rule it proves.
 *
 * Seeds are fixed: a failure prints the seed and the case, and reruns identically.
 */
class MoneyGatePropertyTest {
    private val cases = 3_000

    // ---------------------------------------------------------------------------------------------------------------
    // Oracle (independent of shared:rules)

    private fun big(n: Long) = BigInteger.valueOf(n)

    /** n / d rounded half away from zero (BigDecimal HALF_UP is away from zero for both signs). */
    private fun oracleDiv(n: BigInteger, d: BigInteger): BigInteger = BigDecimal(n).divide(BigDecimal(d), 0, RoundingMode.HALF_UP).toBigIntegerExact()

    private fun oracleLineGross(qty: Long, price: Long, per: Long) = oracleDiv(big(qty) * big(price), big(per))

    /** D-19: net = the exact sum of unrounded (mtk) line values, rounded once, half up, to the paisa. */
    private fun oracleNet(raw: BigInteger): BigInteger = oracleDiv(raw, BigInteger.TEN) * BigInteger.TEN

    private data class Case(val lines: List<MemoLine>, val discounts: List<DiscountLine>, val qcs: List<QcLine>)

    /** A random memo: seed-like prices with 3 decimals (7.935 Tk a stick), pack prices (price per 10 or 20 sticks), discounts, QC. */
    private fun memoCase(rnd: Random): Case {
        val lines = List(rnd.nextInt(0, 25)) { i ->
            val per = listOf(1L, 1L, 1L, 10L, 12L, 20L).random(rnd)
            MemoLine.priced(i.toLong() + 1, rnd.nextLong(0, 5_000), rnd.nextLong(0, 400_000), per)
        }
        val discounts = List(rnd.nextInt(0, 5)) { DiscountLine(null, 0, rnd.nextLong(0, 300_000), DiscountKind.entries.random(rnd)) }
        val qcs = List(rnd.nextInt(0, 4)) { QcLine(Money.lineGrossMtk(rnd.nextLong(1, 40), rnd.nextLong(1, 20_000)), rnd.nextBoolean()) }
        return Case(lines, discounts, qcs)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // D-19: rounded once, half up to the paisa, never per line

    @Test
    fun memoNetIsTheExactSumRoundedOnceHalfUpToThePaisa() {
        val rnd = Random(19)
        var perLineWouldDiffer = 0
        repeat(cases) { n ->
            val c = memoCase(rnd)
            val t = MemoMath.totals(c.lines, c.discounts, c.qcs)
            val gross = c.lines.fold(BigInteger.ZERO) { a, l -> a + oracleLineGross(l.qtyBase, l.unitPriceMtk!!, l.pricePerQty) }
            val offer = c.discounts.filter { it.kind != DiscountKind.DRP }.fold(BigInteger.ZERO) { a, d -> a + big(d.valueMtk) }
            val drp = c.discounts.filter { it.kind == DiscountKind.DRP }.fold(BigInteger.ZERO) { a, d -> a + big(d.valueMtk) }
            val qc = c.qcs.filter { it.appliedToMemo }.fold(BigInteger.ZERO) { a, q -> a + big(q.settlementMtk) }
            val raw = gross - offer - drp - qc
            val ctx = "seed 19 case $n: $c"
            assertEquals(gross.toLong(), t.grossMtk, "gross = exact sum of line gross (no rounding) $ctx")
            assertEquals(raw.toLong(), t.rawMtk, "raw = gross - offer - drp - qc (D-18) $ctx")
            assertEquals(oracleNet(raw).toLong(), t.netMtk, "net rounded once (D-19) $ctx")
            assertEquals(t.netMtk - t.rawMtk, t.roundAdjMtk, "round_adj = net - raw $ctx")
            assertTrue(Math.abs(t.roundAdjMtk) <= 5, "|round_adj| <= 5 $ctx")
            assertEquals(0L, t.netMtk % Money.MTK_PER_PAISA, "net is a whole paisa $ctx")
            // The wrong rule (each line rounded to the paisa first) gives a different net on some memos: the test has teeth.
            val perLine = c.lines.fold(BigInteger.ZERO) { a, l -> a + oracleNet(big(l.grossMtk)) } - offer - drp - qc
            if (oracleNet(perLine) != oracleNet(raw)) perLineWouldDiffer++
        }
        assertTrue(perLineWouldDiffer > cases / 20, "the generator must produce memos where per-line rounding differs ($perLineWouldDiffer)")
    }

    @Test
    fun dRule19FixturesAreReproducedToThePaisa() {
        // docs/24 s7.4: 116,416 mtk -> 116,420; 5 mtk rounds away from zero; 20 sticks x 7,935 = 158,700.
        assertEquals(116_420L, MemoMath.totals(listOf(MemoLine(1, 1, 116_416)), emptyList(), emptyList()).netMtk)
        assertEquals(10L, Money.roundToPaisaHalfUp(5)); assertEquals(-10L, Money.roundToPaisaHalfUp(-5)); assertEquals(0L, Money.roundToPaisaHalfUp(4))
        assertEquals(158_700L, Money.lineGrossMtk(20, 7_935))
        // Three lines of 1.005 Tk: once-rounded 3.015 -> 3.02 (per line it would be 1.01 x 3 = 3.03).
        val t = MemoMath.totals(List(3) { MemoLine(it.toLong(), 1, 1_005) }, emptyList(), emptyList())
        assertEquals(3_020L, t.netMtk); assertEquals(5L, t.roundAdjMtk)
    }

    @Test
    fun lineGrossIsDivHalfUpToTheMilliTakaForPackPrices() {
        val rnd = Random(73)
        repeat(cases) {
            val q = rnd.nextLong(0, 1_000_000); val p = rnd.nextLong(0, 10_000_000); val per = rnd.nextLong(1, 200)
            assertEquals(oracleLineGross(q, p, per).toLong(), Money.lineGrossMtk(q, p, per), "$q x $p / $per")
            assertTrue(MemoLine.priced(1, q, p, per).priceSnapshotConsistent())
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // D-18: net = gross - discounts - QC; a negative net only on a zero gross

    @Test
    fun verifyAcceptsEveryConsistentMemoAndRejectsANegativeNetWithGross() {
        val rnd = Random(18)
        var negWithGross = 0; var negZeroGross = 0
        repeat(cases) { n ->
            val c = memoCase(rnd)
            val t = MemoMath.totals(c.lines, c.discounts, c.qcs)
            val paid = if (t.netMtk > 0) rnd.nextLong(0, t.netMtk + 1) / 10 * 10 else 0L
            val stated = StatedMemo(t.grossMtk, t.offerDiscountMtk, t.drpDiscountMtk, t.qcDeductionMtk, t.netMtk, t.roundAdjMtk, paid, t.netMtk - paid,
                t.lineCount, t.discountLineCount, t.qcLineCount)
            val problems = MemoMath.verify(stated, c.lines, c.discounts, c.qcs)
            if (t.netMtk < 0 && t.grossMtk != 0L) {
                negWithGross++
                assertEquals(listOf("net_negative"), problems.map { it.field }, "D-18: negative net with gross ${t.grossMtk} case $n")
            } else {
                if (t.netMtk < 0) negZeroGross++
                assertEquals(emptyList(), problems, "consistent memo case $n: $c")
                assertTrue(MemoMath.verify(stated, c.lines, c.discounts, c.qcs, allowNegativeNet = false).let { p -> t.netMtk >= 0 == p.isEmpty() })
            }
        }
        assertTrue(negWithGross > 0, "the generator must reach a negative net with a gross")
        // Zero-gross QC credits (D-18): the zero-sale QC-only memo.
        repeat(200) {
            val qcs = List(rnd.nextInt(1, 4)) { QcLine(rnd.nextLong(1, 100_000), true) }
            val t = MemoMath.totals(emptyList(), emptyList(), qcs)
            assertTrue(t.netMtk <= 0)
            val s = StatedMemo(0, 0, 0, t.qcDeductionMtk, t.netMtk, t.roundAdjMtk, 0, t.netMtk, 0, 0, qcs.size)
            assertEquals(emptyList(), MemoMath.verify(s, emptyList(), emptyList(), qcs), "QC-only credit is allowed (cfg.memo.allow_negative_net)")
            assertEquals(listOf("net_negative"), MemoMath.verify(s, emptyList(), emptyList(), qcs, allowNegativeNet = false).map { it.field })
            negZeroGross++
        }
        assertTrue(negZeroGross > 0)
    }

    @Test
    fun aTamperedHeaderIsAlwaysCaught() {
        val rnd = Random(181)
        repeat(cases) { n ->
            val c = memoCase(rnd).let { if (it.lines.isEmpty()) it.copy(lines = listOf(MemoLine.priced(1, 10, 7_935))) else it }
            val t = MemoMath.totals(c.lines, c.discounts, c.qcs)
            val ok = StatedMemo(t.grossMtk, t.offerDiscountMtk, t.drpDiscountMtk, t.qcDeductionMtk, t.netMtk, t.roundAdjMtk, 0, t.netMtk, t.lineCount, t.discountLineCount, t.qcLineCount)
            val d = rnd.nextLong(1, 1_000) * (if (rnd.nextBoolean()) 1 else -1)
            val bad = when (rnd.nextInt(6)) {
                0 -> ok.copy(grossMtk = ok.grossMtk + d)
                1 -> ok.copy(offerDiscountMtk = ok.offerDiscountMtk + d)
                2 -> ok.copy(drpDiscountMtk = ok.drpDiscountMtk + d)
                3 -> ok.copy(qcDeductionMtk = ok.qcDeductionMtk + d)
                4 -> ok.copy(netMtk = ok.netMtk + d * 10, dueMtk = ok.dueMtk + d * 10)
                else -> ok.copy(roundAdjMtk = ok.roundAdjMtk + d)
            }
            assertTrue(MemoMath.verify(bad, c.lines, c.discounts, c.qcs).isNotEmpty(), "tampered header not caught, case $n")
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // QC settlement = defect sticks x the price at capture (D-34, F-SR-027; the phone uses Money.lineGrossMtk)

    @Test
    fun qcSettlementIsDefectQuantityTimesThePriceAtCapture() {
        val rnd = Random(34)
        repeat(cases) {
            val defect = rnd.nextLong(1, 100_000); val price = rnd.nextLong(0, 50_000); val per = listOf(1L, 10L, 20L).random(rnd)
            val settlement = Money.lineGrossMtk(defect, price, per)
            assertEquals(oracleLineGross(defect, price, per).toLong(), settlement)
            // Within half a milli-taka of the exact defect x price / per; exact when the price is per base unit.
            val err = big(settlement) * big(per) - big(defect) * big(price)
            assertTrue(err.abs() * BigInteger.TWO <= big(per), "|settlement - exact| <= 0.5 mtk: $defect x $price / $per")
            if (per == 1L) assertEquals(defect * price, settlement)
            // Only applied QC lines reduce the memo, by exactly their settlement.
            val gross = MemoLine(1, 1, settlement + rnd.nextLong(0, 1_000_000))
            val other = rnd.nextLong(0, 100_000)
            val t = MemoMath.totals(listOf(gross), emptyList(), listOf(QcLine(settlement, true), QcLine(other, false)))
            assertEquals(settlement, t.qcDeductionMtk)
            assertEquals(gross.grossMtk - settlement, t.rawMtk)
        }
        // docs/16: the only evidence, 10 sticks x 8.00 = 80.00.
        assertEquals(80_000L, Money.lineGrossMtk(10, 8_000))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Units: sticks, pieces, dozens; pack factors round-trip and never lose a unit

    @Test
    fun unitConversionsRoundTripAndNeverLoseAUnit() {
        val rnd = Random(10)
        repeat(cases) {
            val factor = listOf(1L, 10L, 12L, 20L, 50L, 200L, rnd.nextLong(1, 1_000)).random(rnd)
            val packs = rnd.nextLong(0, 100_000)
            val base = Quantity.toBase(packs, QtyUnit.PACK, factor)
            assertEquals(big(packs) * big(factor), big(base))
            assertEquals(packs to 0L, Quantity.packBadge(base, factor), "whole packs come back whole")
            val q = rnd.nextLong(0, 10_000_000)
            val (p, r) = Quantity.packBadge(q, factor)
            assertEquals(q, p * factor + r, "badge never loses a unit"); assertTrue(r in 0 until factor)
            for (u in listOf(QtyUnit.STICK, QtyUnit.PIECE, QtyUnit.DOZEN)) assertEquals(q, Quantity.toBase(q, u, factor), "$u is already the base unit")
        }
        assertFailsWith<ArithmeticException> { Quantity.toBase(Long.MAX_VALUE / 2, QtyUnit.PACK, 3) }
        assertFailsWith<IllegalArgumentException> { Quantity.toBase(-1, QtyUnit.STICK) }
        assertFailsWith<IllegalArgumentException> { Quantity.toBase(1, QtyUnit.PACK, 0) }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Integer milli-taka everywhere: overflow throws, never wraps; rounding is symmetric

    @Test
    fun moneyNeverWrapsAndRoundsSymmetrically() {
        assertFailsWith<ArithmeticException> { Money.lineGrossMtk(Long.MAX_VALUE, 2) }
        assertFailsWith<ArithmeticException> { MemoMath.totals(listOf(MemoLine(1, 1, Long.MAX_VALUE), MemoLine(2, 1, 1)), emptyList(), emptyList()) }
        val rnd = Random(5)
        repeat(cases) {
            val m = rnd.nextLong(-1_000_000_000_000, 1_000_000_000_000)
            assertEquals(-Money.roundToPaisaHalfUp(m), Money.roundToPaisaHalfUp(-m), "half away from zero is symmetric: $m")
            assertEquals(oracleNet(big(m)).toLong(), Money.roundToPaisaHalfUp(m))
            val paid = rnd.nextLong(0, 1_000_000_000)
            val s = MemoMath.settle(m, paid)
            assertEquals(m, s.paidMtk + s.dueMtk); assertEquals(s.dueMtk > 0, s.isCredit)
        }
    }
}
