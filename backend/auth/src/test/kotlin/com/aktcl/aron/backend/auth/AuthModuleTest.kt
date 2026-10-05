package com.aktcl.aron.backend.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class AuthModuleTest {
    @Test
    fun moduleIsWired() {
        assertEquals("auth", AuthModule.NAME)
    }
}
