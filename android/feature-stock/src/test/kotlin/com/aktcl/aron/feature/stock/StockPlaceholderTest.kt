package com.aktcl.aron.feature.stock

import org.junit.Assert.assertEquals
import org.junit.Test

class StockPlaceholderTest {
    @Test
    fun moduleIsWired() {
        assertEquals("feature-stock", StockPlaceholder.MODULE)
    }
}
