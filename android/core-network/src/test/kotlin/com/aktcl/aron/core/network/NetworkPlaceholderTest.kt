package com.aktcl.aron.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkPlaceholderTest {
    @Test
    fun moduleIsWired() {
        assertEquals("core-network", NetworkPlaceholder.MODULE)
    }
}
