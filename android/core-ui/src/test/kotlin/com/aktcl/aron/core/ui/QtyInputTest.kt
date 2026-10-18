package com.aktcl.aron.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class QtyInputTest {
    @Test fun latinAndBengaliDigitsParseTheSame() {
        assertEquals(TypedQty(42, false), QtyInput.parse("42", 100))
        assertEquals(TypedQty(42, false), QtyInput.parse("৪২", 100))
        assertEquals(TypedQty(7, false), QtyInput.parse(" 0৭", 100))
    }

    @Test fun nothingTypedIsZeroAndNeverAFlag() {
        assertEquals(TypedQty(0, false), QtyInput.parse("", 100))
        assertEquals(TypedQty(0, false), QtyInput.parse("abc", 100))
    }

    @Test fun aNumberAboveTheLimitIsAcceptedDownToTheLimitAndFlagged() {
        assertEquals(TypedQty(40, true), QtyInput.parse("41", 40))
        assertEquals(TypedQty(40, false), QtyInput.parse("40", 40))
        assertEquals(TypedQty(0, true), QtyInput.parse("5", 0))
    }

    @Test fun veryLongInputIsCutNotOverflowed() {
        assertEquals(TypedQty(100_000, true), QtyInput.parse("99999999999999999999", 100_000))
    }
}
