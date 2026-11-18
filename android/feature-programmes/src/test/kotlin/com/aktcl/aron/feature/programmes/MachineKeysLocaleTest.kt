package com.aktcl.aron.feature.programmes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** Machine keys (quarter, month, payload values) must be ASCII digits whatever the phone's language: a Bangla default locale once turned `%d` into Bengali digits. */
class MachineKeysLocaleTest {
    private var saved: Locale = Locale.getDefault()
    @Before fun bangla() { saved = Locale.getDefault(); Locale.setDefault(Locale("bn", "BD")) }
    @After fun restore() { Locale.setDefault(saved) }

    @Test fun theDefaultLocaleReallyWritesBengaliDigitsHere() = assertEquals("২০২৬", "%d".format(2026))

    @Test fun giftQuarterKey() {
        assertEquals("2026-Q4", GiftChoiceModel.quarterOf("2026-11-18"))
        assertEquals("2025-Q1", GiftChoiceModel.quarterOf("2026-01-05", startMonth = 11))
    }

    @Test fun monthKeysOfAQuarter() {
        assertEquals(listOf("2026-10", "2026-11", "2026-12"), AsthaModel.monthsOfQuarter(2026, 4))
        assertEquals(listOf("2026-01", "2026-02", "2026-03"), AsthaModel.monthsOfQuarter(2026, 1))
    }

    @Test fun clientIdsAreAsciiHexAndAValidUuid() {
        val u = com.aktcl.aron.core.common.ClientIds.derivedUuid("astha", "71", "2026-Q4")
        assertEquals(u, Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matchEntire(u)?.value)
    }

    @Test fun quarterOfAMonthAndTheDhakaDateKeys() {
        assertEquals(4, AsthaModel.quarterOf("2026-11"))
        assertEquals("2026-11", DhakaTime.dateOf("2026-11-17T10:00:00Z")?.take(7))
    }
}
