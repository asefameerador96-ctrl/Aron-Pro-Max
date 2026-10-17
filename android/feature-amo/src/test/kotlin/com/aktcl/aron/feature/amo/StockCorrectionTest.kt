package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StockCorrectionTest {
    @Test fun totalsAreWholeBaseUnitsInRange() {
        assertEquals(0L, StockCorrection.parseTotal("0")); assertEquals(120L, StockCorrection.parseTotal(" 120 ")); assertEquals(120L, StockCorrection.parseTotal("১২০"))
        listOf("", "-1", "1.5", "abc", "10000001", "123456789").forEach { assertNull(it, StockCorrection.parseTotal(it)) }
        assertEquals(10_000_000L, StockCorrection.parseTotal("10000000"))
    }

    @Test fun deltaIsSignedAndNullWhenNothingDiffersOrTextIsBad() {
        assertEquals(-20L, StockCorrection.delta(100, "80")); assertEquals(30L, StockCorrection.delta(100, "130"))
        assertNull(StockCorrection.delta(100, "100")); assertNull(StockCorrection.delta(100, "x"))
        assertEquals(-100L, StockCorrection.delta(100, "0"))
    }
}
