package com.aktcl.aron.rules

import kotlin.test.Test
import kotlin.test.assertEquals

/** Golden fixtures from docs/16 s1.3 and docs/24 s7.6. */
class MoneyTest {
    @Test
    fun amoMemoFixtureRoundsOnceToThePaisa() {
        // 80,000 + 20,000 + 12,500 + 2,333 (FB 1 piece at 28.00/dozen) + 1,583 (SL 1 piece at 19.00/dozen) = 116,416
        val lines = listOf(80_000L, 20_000L, 12_500L, Money.lineGrossMtk(1, 28_000, 12), Money.lineGrossMtk(1, 19_000, 12))
        assertEquals(116_416L, lines.sum())
        assertEquals(116_420L, Money.roundToPaisaHalfUp(lines.sum()))
    }

    @Test
    fun distributorPriceWithThreeDecimalsIsExact() {
        assertEquals(158_700L, Money.lineGrossMtk(qtyBase = 20, basePriceMtk = 7_935))
    }

    @Test
    fun halfUpIsAwayFromZero() {
        assertEquals(2L, Money.divHalfUp(15, 10))
        assertEquals(-2L, Money.divHalfUp(-15, 10))
        assertEquals(1L, Money.divHalfUp(14, 10))
    }
}
