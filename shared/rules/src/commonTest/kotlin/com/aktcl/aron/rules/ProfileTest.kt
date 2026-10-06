package com.aktcl.aron.rules

import kotlinx.datetime.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-SYS-045, F-SYS-051 and F-SYS-070. */
class ProfileTest {
    @Test
    fun priceSnapshotSumsUnroundedAndRoundsOnce() {
        // 13 sticks at 7.935 = 103.155 Tk -> net 103.16, round_adj +5 mtk
        val one = MemoMath.totals(listOf(MemoLine.priced(1, 13, 7_935, priceType = PriceType.DISTRIBUTOR)), emptyList(), emptyList())
        assertEquals(103_155L, one.rawMtk); assertEquals(103_160L, one.netMtk); assertEquals(5L, one.roundAdjMtk)
        // 12 sticks at 7.935 = 95.220 exactly
        assertEquals(95_220L, MemoMath.totals(listOf(MemoLine.priced(1, 12, 7_935)), emptyList(), emptyList()).netMtk)
        // two lines: summed first (95,220 + 103,155 = 198,375 -> 198,380), not rounded per line (95,220 + 103,160 = 198,380 also, so use halves)
        val two = MemoMath.totals(listOf(MemoLine.priced(1, 1, 7_935), MemoLine.priced(2, 1, 7_935)), emptyList(), emptyList())
        assertEquals(15_870L, two.netMtk)                                   // 7,935 each would round to 7,940 -> 15,880 if rounded per line
        val l = MemoLine.priced(1, 13, 7_935, priceType = PriceType.CC)
        assertEquals(7_935L, l.unitPriceMtk); assertEquals(PriceType.CC, l.priceType)
    }

    @Test
    fun laterPriceChangeLeavesOldMemosUnchanged() {
        val old = MemoLine.priced(1, 20, 7_935)
        val newPrice = 8_500L                       // price table changes later; the stored line is not re-priced
        assertEquals(158_700L, MemoMath.totals(listOf(old), emptyList(), emptyList()).netMtk)
        assertTrue(old.priceSnapshotConsistent())
        assertEquals(old.grossMtk, 20 * 7_935L)
        assertFalse(old.copy(unitPriceMtk = newPrice).priceSnapshotConsistent(), "a tampered snapshot is detectable")
        val st = MemoMath.totals(listOf(old), emptyList(), emptyList())
        val stated = StatedMemo(st.grossMtk, 0, 0, 0, st.netMtk, st.roundAdjMtk, 0, st.netMtk, 1, 0, 0)
        assertEquals(emptyList(), MemoMath.verify(stated, listOf(old), emptyList(), emptyList()))
        assertEquals(listOf("line_price_snapshot[0]"), MemoMath.verify(stated, listOf(old.copy(unitPriceMtk = newPrice)), emptyList(), emptyList()).map { it.field })
    }

    @Test
    fun pricePerQtyKeepsSnapshot() {
        val l = MemoLine.priced(5, 1, 28_000, pricePerQty = 12)    // 1 piece at 28.00 a dozen
        assertEquals(2_333L, l.grossMtk); assertEquals(12L, l.pricePerQty); assertTrue(l.priceSnapshotConsistent())
    }

    @Test
    fun formattingProfile() {
        assertEquals("1,234.50 ৳", Formats.money(1_234_500, UiLocale.EN))
        assertEquals("১,২৩৪.৫০ ৳", Formats.money(1_234_500, UiLocale.BN))
        assertEquals("7.935 ৳", Formats.money(7_935, UiLocale.EN, 3))
        assertEquals("1,234,567", Formats.integer(1_234_567, UiLocale.EN))
        assertEquals("১,২৩৪,৫৬৭", Formats.integer(1_234_567, UiLocale.BN))
        assertEquals("2026-10-05", Formats.date(LocalDate(2026, 10, 5), UiLocale.EN))
        assertEquals("05/10/2026", Formats.date(LocalDate(2026, 10, 5), UiLocale.EN, iso = false))
        assertEquals("\u09F3 84.00", Formats.money(84_000, UiLocale.EN, sign = SignPosition.LEADING))
        assertEquals("২০২৬-১০-০৫", Formats.date(LocalDate(2026, 10, 5), UiLocale.BN))
        assertEquals("23:55", Formats.time(BusinessDate.DHAKA_OFFSET_MS * 0 + 17 * 3_600_000L + 55 * 60_000L, UiLocale.EN))
        assertEquals("00:05", Formats.time(-6 * 3_600_000L + 5 * 60_000L, UiLocale.EN))
        assertEquals("০০:০৫", Formats.time(-6 * 3_600_000L + 5 * 60_000L, UiLocale.BN))
    }

    @Test
    fun digitsAndPhones() {
        assertEquals(Normalised("01712345678", true), TextRules.toWesternDigits("০১৭১২৩৪৫৬৭৮"))
        assertEquals(Normalised("abc12", false), TextRules.toWesternDigits("abc12"))
        assertEquals(Normalised("01712345678", false), TextRules.normalisePhone("01712345678"))
        assertEquals("01712345678", TextRules.normalisePhone("+880 1712-345678")!!.value)
        assertEquals("01712345678", TextRules.normalisePhone("8801712345678")!!.value)
        assertEquals("01712345678", TextRules.normalisePhone("1712345678")!!.value)
        assertEquals("01712345678", TextRules.normalisePhone("০১৭১২৩৪৫৬৭৮")!!.value)
        assertTrue(TextRules.normalisePhone("+8801712345678")!!.changed)
        assertNull(TextRules.normalisePhone("0171234567")); assertNull(TextRules.normalisePhone("02123456789")); assertNull(TextRules.normalisePhone(""))
    }

    @Test
    fun sortKeyOrdersTwoHundredMixedNamesDeterministically() {
        val bn = "কখগঘচছজটঠডতথদপফবমরলসহ"
        val rnd = Random(70)
        val names = List(200) { i ->
            when (i % 3) {
                0 -> "Store " + ('A' + rnd.nextInt(26)) + ('a' + rnd.nextInt(26)) + " " + rnd.nextInt(100)
                1 -> List(rnd.nextInt(2, 6)) { bn[rnd.nextInt(bn.length)] }.joinToString("") + " ২" + rnd.nextInt(10)
                else -> "  Mix ${bn[rnd.nextInt(bn.length)]}${bn[rnd.nextInt(bn.length)]}  ‌ Shop "
            }
        }
        val cpCmp = Comparator<String> { a, b ->
            val c = TextRules.compareKeys(TextRules.nameSortKey(a), TextRules.nameSortKey(b)); if (c != 0) c else a.compareTo(b)
        }
        val byteCmp = Comparator<String> { a, b ->
            val x = TextRules.nameSortKey(a).encodeToByteArray(); val y = TextRules.nameSortKey(b).encodeToByteArray()
            var r = 0; var i = 0
            while (r == 0 && i < minOf(x.size, y.size)) { r = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF); i++ }
            if (r != 0) r else if (x.size != y.size) x.size - y.size else a.compareTo(b)
        }
        val byCodePoint = names.sortedWith(cpCmp)
        val byUtf8 = names.sortedWith(byteCmp)
        assertEquals(byCodePoint, byUtf8, "code point order equals UTF-8 byte order (SQLite BINARY, PostgreSQL C)")
        assertEquals("store ab 7", TextRules.nameSortKey("  Store   AB  ৭ "))
        assertEquals("কখ ২".replace('২', '2'), TextRules.nameSortKey("ক‌খ  ২"))
        assertTrue(TextRules.nameSortKey("Zeta") < TextRules.nameSortKey("ক"), "Latin before Bangla")
        assertTrue(TextRules.nameSortKey("ক") < TextRules.nameSortKey("খ"))
    }
}
