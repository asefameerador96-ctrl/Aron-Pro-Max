package com.aktcl.aron.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutletLiteMapTest {
    @Test fun theMapDrawsOnlyOnlineWithAKeyAndAGoogleProvider() {
        val g = MapSettings()
        assertTrue(outletMapLive(true, g, true))
        assertFalse(outletMapLive(false, g, true)) // offline: no Maps class, no tile request
        assertFalse(outletMapLive(true, g, false))
        assertFalse(outletMapLive(true, MapSettings(provider = MapProvider.MAPLIBRE), true))
    }

    @Test fun theFrameIsCentredOnTheOutletWhileThePhoneIsInsideTheCircle() {
        val (c, z) = outletMapFrame(23.79, 90.40, 100, 23.7905, 90.4003)
        assertEquals(23.79, c.latitude, 1e-9); assertEquals(90.40, c.longitude, 1e-9)
        assertTrue("zoom $z", z in 16f..17f) // a 100 m circle fills the lite view at street level
    }

    @Test fun theFrameWidensToHoldAFarPhone() {
        val near = outletMapFrame(23.79, 90.40, 100, null, null).second
        val far = outletMapFrame(23.79, 90.40, 100, 23.80, 90.41, ).second
        assertTrue(far < near)
        val (c, _) = outletMapFrame(23.79, 90.40, 100, 23.80, 90.41)
        assertTrue(c.latitude > 23.79 && c.longitude > 90.40) // the centre moves toward the phone so both show
    }
}
