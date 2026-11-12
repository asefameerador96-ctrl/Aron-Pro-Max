package com.aktcl.aron.core.sync.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SR manual 07: the installer opens by itself once per release per process, and never after "Later". */
class AutoInstallGateTest {
    @Test fun aReleaseIsClaimedOnceAndNeverTwice() {
        val g = AutoInstallGate()
        assertTrue(g.claim(25))
        assertFalse(g.claim(25))
        assertTrue(g.claim(26)) // another release is its own
    }

    @Test fun laterBlocksTheAutomaticStartUntilTheRepTapsThemselves() {
        val g = AutoInstallGate()
        g.later(25)
        assertFalse(g.claim(25))
        g.chosenByRep(25)
        assertTrue(g.claim(25)) // the rep's own tap lifts the put-off; the start stays once-only
        assertFalse(g.claim(25))
    }

    @Test fun laterOnOneReleaseDoesNotBlockAnother() {
        val g = AutoInstallGate()
        g.later(25)
        assertTrue(g.claim(26))
    }
}
