package com.aktcl.aron.core.printing.render

import com.aktcl.aron.core.printing.Fixtures
import com.aktcl.aron.core.printing.doc.DocumentBuilder
import com.aktcl.aron.core.printing.doc.MemoPrint
import com.aktcl.aron.core.printing.doc.MemoPrintDiscount
import com.aktcl.aron.core.printing.doc.MemoPrintLine
import com.aktcl.aron.core.printing.template.DigitStyle
import com.aktcl.aron.rules.DiscountKind
import com.aktcl.aron.rules.DiscountLine
import com.aktcl.aron.rules.MemoLine
import com.aktcl.aron.rules.MemoMath
import com.aktcl.aron.rules.QcLine
import com.aktcl.aron.rules.StatedMemo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The printed total equals the stored total, and the stored total is the shared memo oracle's (`MemoMath`).
 * Printed amounts are parsed back to milli-taka and compared exactly, in both digit styles, over random memos.
 */
class MemoTotalsPrintTest {
    /** Parses a printed amount ("1,234.50", "৭.৯৩৫", "- 2.40", "+0.005") back to milli-taka. */
    private fun mtk(printed: String): Long {
        val ascii = printed.map { if (it in '০'..'৯') '0' + (it - '০') else it }.joinToString("").replace(",", "").replace(" ", "")
        val neg = ascii.startsWith("-")
        val body = ascii.removePrefix("-").removePrefix("+")
        val whole = body.substringBefore('.').toLong()
        val frac = body.substringAfter('.', "").padEnd(3, '0')
        val v = whole * 1000 + frac.toLong()
        return if (neg) -v else v
    }

    private fun check(m: MemoPrint, digits: DigitStyle) {
        val doc = DocumentBuilder(digits).memo(m)
        assertEquals(m.netMtk, mtk(doc.fields.getValue("grand_total")))
        assertEquals(m.qcDeductionMtk, mtk(doc.fields.getValue("total_qc")))
        assertEquals(m.offerDiscountMtk + m.drpDiscountMtk, mtk(doc.fields.getValue("total_discount")))
        assertEquals(m.paidMtk, mtk(doc.fields.getValue("paid")))
        assertEquals(m.dueMtk, mtk(doc.fields.getValue("due")))
        assertEquals(m.roundAdjMtk, mtk(doc.fields.getValue("rounding")))
        assertEquals(m.grossMtk, mtk(doc.tables.getValue("line_total")[0].getValue("value")))
        doc.tables.getValue("lines").forEachIndexed { i, row -> assertEquals(m.lines[i].grossMtk, mtk(row.getValue("value"))) }
        if (digits == DigitStyle.BENGALI) {
            assertTrue(doc.fields.getValue("grand_total").none { it in '0'..'9' })
            assertEquals(m.memoNo, doc.fields.getValue("memo_no")) // identifiers keep their digits
        }
    }

    @Test fun seededSaleIsConsistentWithTheOracleAndPrintsItsStoredTotals() {
        val m = Fixtures.seededSale
        val stated = StatedMemo(m.grossMtk, m.offerDiscountMtk, m.drpDiscountMtk, m.qcDeductionMtk, m.netMtk, m.roundAdjMtk, m.paidMtk, m.dueMtk, m.lines.size, m.discounts.size, 1)
        val lines = m.lines.map { MemoLine(1, it.qtyBase, it.grossMtk) }
        val discounts = m.discounts.map { DiscountLine(1, it.qtyBase, it.valueMtk, DiscountKind.DRP) }
        assertEquals(emptyList<Any>(), MemoMath.verify(stated, lines, discounts, listOf(QcLine(m.qcDeductionMtk, true))))
        check(m, DigitStyle.LATIN)
        check(m, DigitStyle.BENGALI)
    }

    @Test fun randomMemosPrintExactlyWhatTheOracleComputes() {
        val rnd = Random(18)
        repeat(500) {
            val n = 1 + rnd.nextInt(60)
            val lines = (1..n).map {
                val qty = 1L + rnd.nextInt(5000)
                MemoLine.priced(it.toLong(), qty, 1L + rnd.nextInt(2_000_000), pricePerQty = if (rnd.nextBoolean()) 1 else 12)
            }
            val gross = lines.sumOf { it.grossMtk }
            val discounts = (0 until rnd.nextInt(4)).map { DiscountLine(1, 1L + rnd.nextInt(50), rnd.nextInt(1 + minOf(gross / 10, 1_000_000L).toInt()).toLong(), if (rnd.nextBoolean()) DiscountKind.DRP else DiscountKind.OFFER) }
            val qc = listOf(QcLine(rnd.nextInt(5000).toLong() * 10, true))
            val t = MemoMath.totals(lines, discounts, qc)
            val paid = if (rnd.nextBoolean()) t.netMtk else maxOf(0L, t.netMtk / 2)
            val s = MemoMath.settle(t.netMtk, paid)
            val m = MemoPrint(
                kind = if (s.isCredit) "credit_memo" else "cash_memo", memoNo = "sr001-261007-0001", committedAtEpochMs = Fixtures.T,
                outlet = "o", sr = "s", route = "r",
                lines = lines.map { MemoPrintLine("SKU", it.qtyBase, it.grossMtk) },
                discounts = discounts.map { MemoPrintDiscount("SKU", it.qtyBase, it.valueMtk) },
                grossMtk = t.grossMtk, offerDiscountMtk = t.offerDiscountMtk, drpDiscountMtk = t.drpDiscountMtk,
                qcDeductionMtk = t.qcDeductionMtk, roundAdjMtk = t.roundAdjMtk, netMtk = t.netMtk,
                paidMtk = s.paidMtk, dueMtk = s.dueMtk, isCredit = s.isCredit,
            )
            check(m, if (it % 2 == 0) DigitStyle.LATIN else DigitStyle.BENGALI)
        }
    }

    @Test fun datesAreDhakaWallTime() {
        val b = DocumentBuilder(DigitStyle.LATIN)
        assertEquals("07/10/2026", b.date(Fixtures.T))
        assertEquals("10:32", b.time(Fixtures.T))
        // 18:30 UTC on the 6th is 00:30 on the 7th in Dhaka; 17:59 UTC is still the 6th.
        assertEquals("07/10/2026", b.date(Fixtures.T - (10 * 60 + 2) * 60_000L))
        assertEquals("00:30", b.time(Fixtures.T - (10 * 60 + 2) * 60_000L))
        assertEquals("06/10/2026", b.date(Fixtures.T - (10 * 60 + 33) * 60_000L))
        assertEquals("০৭/১০/২০২৬", DocumentBuilder(DigitStyle.BENGALI).date(Fixtures.T))
    }
}
