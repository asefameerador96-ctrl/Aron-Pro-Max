package com.aktcl.aron.rules

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Property test against an arbitrary-precision oracle (BigInteger / BigDecimal HALF_UP = away from zero). */
class MemoOracleTest {
    @Test
    fun netMatchesBigDecimalOracle() {
        val rnd = Random(7)
        repeat(2000) {
            val lines = List(rnd.nextInt(0, 30)) { MemoLine(1, 1, rnd.nextLong(0, 5_000_000)) }
            val discounts = List(rnd.nextInt(0, 6)) { DiscountLine(null, 0, rnd.nextLong(0, 2_000_000), DiscountKind.entries.random(rnd)) }
            val qcs = List(rnd.nextInt(0, 3)) { QcLine(rnd.nextLong(0, 500_000), true) }
            val raw = lines.fold(BigInteger.ZERO) { a, l -> a + BigInteger.valueOf(l.grossMtk) } -
                discounts.fold(BigInteger.ZERO) { a, d -> a + BigInteger.valueOf(d.valueMtk) } -
                qcs.fold(BigInteger.ZERO) { a, q -> a + BigInteger.valueOf(q.settlementMtk) }
            val oracleNet = BigDecimal(raw).movePointLeft(1).setScale(0, RoundingMode.HALF_UP).movePointRight(1).toBigIntegerExact()
            assertEquals(oracleNet.toLong(), MemoMath.totals(lines, discounts, qcs).netMtk)
        }
    }

    @Test
    fun divHalfUpMatchesBigDecimalOracle() {
        val rnd = Random(11)
        repeat(5000) {
            val n = rnd.nextLong(-1_000_000_000, 1_000_000_000); val d = rnd.nextLong(1, 1000)
            val expected = BigDecimal(n).divide(BigDecimal(d), 0, RoundingMode.HALF_UP).toLong()
            assertEquals(expected, Money.divHalfUp(n, d), "$n / $d")
        }
    }
}
