package com.aktcl.aron.sr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen tree behind Back, the top bar's back button and the transition direction (screens-sr s0 motion defaults). */
class SrNavigationTest {
    @Test fun everyScreenReachesHomeThroughItsParents() {
        SrScreen.entries.forEach { s ->
            var cur = s; var hops = 0
            while (!SrNav.isRoot(cur)) { cur = SrNav.parent(cur); hops++; assertTrue("$s loops", hops < 10) }
            assertEquals("$s depth", hops, SrNav.depth(s))
        }
        assertEquals(0, SrNav.depth(SrScreen.HOME))
    }

    @Test fun subScreensReturnToTheirOwnParentNotHome() {
        assertEquals(SrScreen.VISIT, SrNav.parent(SrScreen.FORCE))
        assertEquals(SrScreen.VISIT, SrNav.parent(SrScreen.NO_SALE))
        assertEquals(SrScreen.PICKER, SrNav.parent(SrScreen.SKIP))
        assertEquals(SrScreen.MEMO, SrNav.parent(SrScreen.EDIT))
        assertEquals(SrScreen.SETTINGS, SrNav.parent(SrScreen.SUPPORT))
        assertEquals(SrScreen.SETTINGS, SrNav.parent(SrScreen.PRINTER_DIAG))
        assertEquals(SrScreen.OUTLET_MENU, SrNav.parent(SrScreen.REQUEST_FORM))
        assertEquals(SrScreen.OUTLET_MENU, SrNav.parent(SrScreen.OWN_REQUESTS))
        assertEquals(SrScreen.HOME, SrNav.parent(SrScreen.MEMO))
        assertEquals(2, SrNav.depth(SrScreen.EDIT)); assertEquals(2, SrNav.depth(SrScreen.FORCE))
    }

    @Test fun deeperIsForwardAndBackIsNot() {
        assertTrue(SrNav.forward(SrScreen.HOME, SrScreen.MEMO))
        assertTrue(SrNav.forward(SrScreen.MEMO, SrScreen.EDIT))
        assertFalse(SrNav.forward(SrScreen.EDIT, SrScreen.MEMO))
        assertFalse(SrNav.forward(SrScreen.SUBMIT, SrScreen.HOME))
        assertFalse(SrNav.forward(SrScreen.MEMO, SrScreen.KPI)) // a sideways move is a back (from the start)
    }
}
