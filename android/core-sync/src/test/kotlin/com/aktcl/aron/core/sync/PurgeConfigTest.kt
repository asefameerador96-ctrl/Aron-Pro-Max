package com.aktcl.aron.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F-SYS-028 checker: day counts arrive as JSON numbers or strings; anything else leaves the default. */
class PurgeConfigTest {
    @Test fun configIntReadsNumbersAndStrings() {
        assertEquals(7, SessionSyncRunner.configInt("7"))
        assertEquals(10, SessionSyncRunner.configInt("\"10\""))
        assertEquals(5, SessionSyncRunner.configInt(" 5 "))
        assertNull(SessionSyncRunner.configInt("\"seven\""))
        assertNull(SessionSyncRunner.configInt("{}"))
        assertNull(SessionSyncRunner.configInt(null))
    }
}
