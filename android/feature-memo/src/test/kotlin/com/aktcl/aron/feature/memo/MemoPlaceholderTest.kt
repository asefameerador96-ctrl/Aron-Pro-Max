package com.aktcl.aron.feature.memo

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoPlaceholderTest {
    @Test
    fun moduleIsWired() {
        assertEquals("feature-memo", MemoPlaceholder.MODULE)
    }
}
