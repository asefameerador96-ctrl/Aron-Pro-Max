package com.aktcl.aron.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePlaceholderTest {
    @Test
    fun moduleIsWired() {
        assertEquals("feature-home", HomePlaceholder.MODULE)
    }
}
