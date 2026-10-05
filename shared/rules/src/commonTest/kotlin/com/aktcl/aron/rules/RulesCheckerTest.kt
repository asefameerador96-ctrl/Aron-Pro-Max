package com.aktcl.aron.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

/** Independent checker probes; each should pass if the rule matches docs/24. */
class RulesCheckerTest {
    @Test fun exactly60KmhIsNotAboveTheLimit() {
        // 1000 m in 60 s = exactly 60 km/h; s11.4 says "> 60"
        assertFalse(Geo.impliedSpeedKmh(1000.0, 60_000L) > 60.0, "speed=${Geo.impliedSpeedKmh(1000.0, 60_000L)}")
    }

    @Test fun divHalfUpHugeDivisorDoesNotOverflow() {
        val d = (1L shl 62) + 1
        assertEquals(1L, Money.divHalfUp(1L shl 62, d))
    }

    @Test fun roundToPaisaNearMaxThrowsInsteadOfWrapping() {
        assertFailsWith<ArithmeticException> { Money.roundToPaisaHalfUp(Long.MAX_VALUE) }
    }

    @Test fun formatTakaMinValueDoesNotWrap() {
        val s = Money.formatTaka(Long.MIN_VALUE, 3)
        assertEquals('-', s[0])
    }
}
