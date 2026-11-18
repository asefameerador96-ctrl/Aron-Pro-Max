package com.aktcl.aron.core.geo.spoof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpoofCheckerTest {
    @Test fun satellitesAreReadFromTheGnssSummary() {
        assertEquals(GnssFacts(7), AndroidSpoofChecker.gnssFacts("""{"window_ms":4000,"satellites_visible":12,"satellites_used":7,"x":1}"""))
        assertEquals(GnssFacts(0), AndroidSpoofChecker.gnssFacts("""{"satellites_visible":9,"satellites_used":0}"""))
    }

    @Test fun noStatusAtAllIsNullNotZeroUsed() {
        assertNull(AndroidSpoofChecker.gnssFacts(null))
        assertNull(AndroidSpoofChecker.gnssFacts("""{"satellites_visible":0,"satellites_used":0}""")) // GNSS reported nothing
        assertNull(AndroidSpoofChecker.gnssFacts("not json"))
    }

    @Test fun thePreviousFixStoreRoundTripsExactly() {
        val s = PreviousFixStore.Memory()
        assertNull(s.load())
        s.save(PreviousFix(23.7925, 90.4078, 123_456)); assertEquals(PreviousFix(23.7925, 90.4078, 123_456), s.load())
    }

    @Test fun clearingForgetsThePreviousFix() {
        val s = PreviousFixStore.Memory()
        s.save(PreviousFix(23.7925, 90.4078, 123_456)); s.clear(); assertNull(s.load())
    }
}
