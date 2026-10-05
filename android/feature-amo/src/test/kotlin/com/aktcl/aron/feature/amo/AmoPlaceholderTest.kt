package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Test

class AmoPlaceholderTest {
    @Test
    fun moduleIsWired() {
        assertEquals("feature-amo", AmoPlaceholder.MODULE)
    }
}
