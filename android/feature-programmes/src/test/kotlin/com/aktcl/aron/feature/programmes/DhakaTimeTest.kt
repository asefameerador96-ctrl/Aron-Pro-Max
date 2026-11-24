package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DhakaTimeTest {
    @Test fun anEveningUtcInstantIsTheNextDayInDhaka() {
        assertEquals("2026-11-01", DhakaTime.dateOf("2026-10-31T18:00:00Z")) // 00:00 Dhaka
        assertEquals("2026-11-01", DhakaTime.dateOf("2026-10-31T23:59:59.999Z"))
        assertEquals("2026-10-31", DhakaTime.dateOf("2026-10-31T17:59:59Z")) // 23:59 Dhaka
        assertEquals("2027-01-01", DhakaTime.dateOf("2026-12-31T20:00:00Z"))
    }

    @Test fun aPlainBusinessDatePassesThroughAndJunkIsNull() {
        assertEquals("2026-12-07", DhakaTime.dateOf("2026-12-07"))
        assertNull(DhakaTime.dateOf("not a date")); assertNull(DhakaTime.dateOf(null)); assertNull(DhakaTime.dateOf(" "))
    }

    @Test fun theLoyaltyDateTextUsesTheSameRule() {
        assertEquals("২০২৬-১১-০১", LoyaltyModel.dateText("2026-10-31T18:00:00Z", UiLocale.BN))
        assertEquals("—", LoyaltyModel.dateText("garbage", UiLocale.EN))
    }
}
