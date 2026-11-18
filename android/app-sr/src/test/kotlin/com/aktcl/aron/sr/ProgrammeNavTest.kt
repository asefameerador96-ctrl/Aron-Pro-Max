package com.aktcl.aron.sr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgrammeNavTest {
    @Test fun asthaTargetBacksToTheMenuAndTheMenuToHome() {
        assertEquals(SrScreen.ASTHA_MENU, SrNav.parent(SrScreen.ASTHA_TARGET))
        assertEquals(SrScreen.HOME, SrNav.parent(SrScreen.ASTHA_MENU))
        assertTrue(SrNav.forward(SrScreen.ASTHA_MENU, SrScreen.ASTHA_TARGET))
    }

    @Test fun yearComesFromTheBusinessDateElseTheDhakaClock() {
        assertEquals(2026, programmeYear("2026-11-18", 0L))
        // 2026-12-31T20:00:00Z is already 2027-01-01 in Dhaka (UTC+6)
        assertEquals(2027, programmeYear(null, java.time.Instant.parse("2026-12-31T20:00:00Z").toEpochMilli()))
    }

    @Test fun loyaltyPointsBacksToTheLoyaltyMenuAndTheMenuToHome() {
        assertEquals(SrScreen.LOYALTY_MENU, SrNav.parent(SrScreen.LOYALTY_POINTS))
        assertEquals(SrScreen.HOME, SrNav.parent(SrScreen.LOYALTY_MENU))
    }
}
