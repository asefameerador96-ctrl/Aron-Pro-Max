package com.aktcl.aron.feature.tso

import org.junit.Assert.assertEquals
import org.junit.Test

class TsoPlaceholderTest {
    @Test
    fun moduleIsWired() {
        assertEquals("feature-tso", TsoPlaceholder.MODULE)
    }
}
