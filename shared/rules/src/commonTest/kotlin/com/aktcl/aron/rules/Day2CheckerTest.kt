package com.aktcl.aron.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class Day2CheckerTest {
    @Test fun nfcEquivalentBanglaNamesGiveSameKey() {
        // y-dot: U+09DF vs U+09AF U+09BC ; o-kar: U+09CB vs U+09C7 U+09BE (canonically equivalent)
        assertEquals(TextRules.nameSortKey("য়"), TextRules.nameSortKey("য়"))
        assertEquals(TextRules.nameSortKey("কো"), TextRules.nameSortKey("কো"))
    }
    @Test fun otherFormatCharsStripped() {
        assertEquals(TextRules.nameSortKey("ab"), TextRules.nameSortKey("a​b"))
        assertEquals(TextRules.nameSortKey("ab"), TextRules.nameSortKey("﻿ab"))
    }
    @Test fun keyIdempotent() {
        for (s in listOf("İstanbul", "ΟΔΥΣΣΕΥΣ Σ", " ক  ১২ ", " x y", "A😀B")) {
            val k = TextRules.nameSortKey(s); assertEquals(k, TextRules.nameSortKey(k), s)
        }
    }
    @Test fun formatExtremes() {
        Formats.money(Long.MIN_VALUE, UiLocale.EN); Formats.money(Long.MAX_VALUE, UiLocale.BN)
        assertEquals("-1,234.50 ৳", Formats.money(-1_234_500, UiLocale.EN))
        assertEquals("9,223,372,036,854,775,807", Formats.integer(Long.MAX_VALUE, UiLocale.EN))
    }
    @Test fun phoneEdges() {
        assertNotNull(TextRules.normalisePhone("+880 01712345678"), "+880 followed by trunk 0")
        assertNotNull(TextRules.normalisePhone("০০৮৮০১৭১২৩৪৫৬৭৮"))
        assertEquals(null, TextRules.normalisePhone("01012345678"))
    }
}
