package com.aktcl.aron.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class CommonPlaceholderTest {
    @Test
    fun moduleIsWired() {
        assertEquals("core-common", CommonPlaceholder.MODULE)
    }
}
