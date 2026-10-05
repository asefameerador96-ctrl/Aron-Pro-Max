package com.aktcl.aron.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiPlaceholderTest {
    @Test
    fun moduleIsWired() {
        assertEquals("core-ui", UiPlaceholder.MODULE)
    }
}
