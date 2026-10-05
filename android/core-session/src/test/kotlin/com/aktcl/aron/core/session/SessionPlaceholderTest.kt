package com.aktcl.aron.core.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPlaceholderTest {
    @Test
    fun moduleIsWired() {
        assertEquals("core-session", SessionPlaceholder.MODULE)
    }
}
