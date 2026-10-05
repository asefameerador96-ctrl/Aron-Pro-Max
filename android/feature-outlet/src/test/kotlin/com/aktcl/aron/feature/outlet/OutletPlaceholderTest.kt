package com.aktcl.aron.feature.outlet

import org.junit.Assert.assertEquals
import org.junit.Test

class OutletPlaceholderTest {
    @Test
    fun moduleIsWired() {
        assertEquals("feature-outlet", OutletPlaceholder.MODULE)
    }
}
