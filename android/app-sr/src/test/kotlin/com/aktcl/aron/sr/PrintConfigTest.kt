package com.aktcl.aron.sr

import org.junit.Assert.assertEquals
import org.junit.Test

class PrintConfigTest {
    @Test fun missingValuesKeepTheDefaults() = assertEquals(PrintConfig(5, true), PrintConfig.from(null, null))

    @Test fun storedValuesAreRead() = assertEquals(PrintConfig(2, false), PrintConfig.from("2", "false"))

    @Test fun malformedOrOutOfRangeValuesKeepTheDefaults() {
        assertEquals(PrintConfig(5, true), PrintConfig.from("many", "maybe"))
        assertEquals(PrintConfig(5, true), PrintConfig.from("99", null))
        assertEquals(PrintConfig(0, true), PrintConfig.from("0", null))
    }
}
