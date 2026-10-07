package com.aktcl.aron.feature.dayclose

import org.junit.Assert.assertTrue
import org.junit.Test

class CheckerTest {
    /** docs/17 D-383 and docs/24 s4.12: rejected rows are answered by the server and reconcile (accepted+rejected+quarantined); submit records rejected_count, so it must not block. */
    @Test fun aServerRejectedRowDoesNotLockTheDayForever() {
        val g = SalesSubmitRules.gate(OutboxStates(0, 0, 39, 1, 0), listOf(CountRow("memo", 6, 6)), DuesAtSubmit(0, 0), false)
        assertTrue(g.blocks.toString(), g.enabled)
    }
}
