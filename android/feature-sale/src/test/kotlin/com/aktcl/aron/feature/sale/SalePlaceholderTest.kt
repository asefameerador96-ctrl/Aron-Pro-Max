package com.aktcl.aron.feature.sale

import org.junit.Assert.assertEquals
import org.junit.Test

class SalePlaceholderTest {
    @Test
    fun moduleIsWired() {
        assertEquals("feature-sale", SalePlaceholder.MODULE)
    }
}
