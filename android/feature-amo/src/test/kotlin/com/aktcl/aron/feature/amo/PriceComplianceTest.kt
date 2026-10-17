package com.aktcl.aron.feature.amo

import com.aktcl.aron.core.database.entity.PriceEntity
import com.aktcl.aron.core.database.entity.SkuEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceComplianceTest {
    private fun sku(id: Long, pack: Int) = SkuEntity(id, "S$id", 1, "cigarette", "Maxim $id", "M$id", "ম্যাক্সিম", "stick", pack, "pack", null, "1.000", 1, "active", 1)
    private fun price(sku: Long, mtk: Long, per: Int = 1) = PriceEntity(sku, sku, "outlet", mtk, per, "2026-09-01", null)

    @Test fun takaTextParsesToExactMilliTakaAndRejectsTheRest() {
        assertEquals(125_000L, PriceCompliance.parseTakaToMtk("125"))
        assertEquals(125_500L, PriceCompliance.parseTakaToMtk("125.5"))
        assertEquals(125_125L, PriceCompliance.parseTakaToMtk(" 125,125 "))
        assertEquals(0L, PriceCompliance.parseTakaToMtk("0"))
        listOf("", "-5", "1.2345", "abc", "1e3", "1..2", "9999999999").forEach { assertNull(it, PriceCompliance.parseTakaToMtk(it)) }
        assertEquals("125", PriceCompliance.formatTaka(125_000)); assertEquals("125.5", PriceCompliance.formatTaka(125_500)); assertEquals("0.05", PriceCompliance.formatTaka(50))
    }

    @Test fun banglaDigitsParseLikeAscii() {
        assertEquals(125_500L, PriceCompliance.parseTakaToMtk("১২৫.৫"))
        assertEquals(PriceCompliance.parseTakaToMtk("9999999"), PriceCompliance.parseTakaToMtk("৯৯৯৯৯৯৯"))
    }

    @Test fun aLargePriceDoesNotOverflowTheToleranceMaths() {
        val big = 999_999_999_000L
        assertTrue(PriceCompliance.compliant(big, big, 5000)); assertFalse(PriceCompliance.compliant(big, 0, 5000))
    }

    @Test fun referenceIsThePriceOfOnePackRoundedHalfUp() {
        assertEquals(90_000L, PriceCompliance.packReferenceMtk(price(1, 9_000), sku(1, 10)))
        assertEquals(10_000L, PriceCompliance.packReferenceMtk(price(1, 5_000, per = 3), sku(1, 6))) // 5000 x 6 / 3
        assertEquals(2L, PriceCompliance.packReferenceMtk(price(1, 1, per = 3), sku(1, 5))) // 5/3 = 1.67 rounds to 2
    }

    @Test fun toleranceIsIntegerBasisPoints() {
        assertTrue(PriceCompliance.compliant(90_000, 90_000, 0))
        assertTrue(PriceCompliance.compliant(90_000, 90_900, 100)) // exactly 1%
        assertFalse(PriceCompliance.compliant(90_000, 90_901, 100))
        assertTrue(PriceCompliance.compliant(90_000, 89_100, 100))
        assertFalse(PriceCompliance.compliant(90_000, 89_099, 100))
        assertTrue(PriceCompliance.compliant(0, 0)); assertFalse(PriceCompliance.compliant(0, 1))
    }

    @Test fun onlySkusWithAListPriceAreRowsAndBanglaNamesAreUsed() {
        val skus = listOf(sku(1, 10), sku(2, 10))
        val rows = PriceCompliance.rows(skus, { if (it.skuId == 1L) price(1, 9_000) else null }, "outlet", bn = true)
        assertEquals(listOf(1L), rows.map { it.skuId }); assertEquals("ম্যাক্সিম", rows[0].name); assertEquals("10 stick", rows[0].packLabel)
    }

    @Test fun recordsAreOneRank2CheckPerTypedSkuAndATypoBlocksSave() {
        val rows = listOf(PriceRow(1, "A", "10 stick", "outlet", 90_000), PriceRow(2, "B", "10 stick", "outlet", 50_000))
        var n = 0
        val recs = PriceCompliance.records("v1", rows, mapOf(1L to "95", 2L to ""), 100) { "u${n++}" }
        assertEquals(1, recs.size); assertEquals(2, recs[0].rank); assertEquals("v1", recs[0].familyUuid)
        val p = recs[0].payload
        assertEquals("95000", p["observed_price_mtk"].toString()); assertEquals("90000", p["reference_price_mtk"].toString()); assertEquals("false", p["compliant"].toString())
        assertTrue(PriceCompliance.canSave(rows, mapOf(1L to "95")))
        assertFalse(PriceCompliance.canSave(rows, mapOf(1L to "95", 2L to "5x")))
        assertFalse(PriceCompliance.canSave(rows, emptyMap()))
    }
}
