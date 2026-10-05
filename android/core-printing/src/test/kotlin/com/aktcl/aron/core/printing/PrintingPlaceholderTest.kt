package com.aktcl.aron.core.printing

import org.junit.Assert.assertEquals
import org.junit.Test

class PrintingPlaceholderTest {
    @Test
    fun moduleIsWired() {
        assertEquals("core-printing", PrintingPlaceholder.MODULE)
    }
}
