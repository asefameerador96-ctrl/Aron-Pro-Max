package com.aktcl.aron.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthPlaceholderTest {
    @Test
    fun moduleIsWired() {
        assertEquals("feature-auth", AuthPlaceholder.MODULE)
    }
}
