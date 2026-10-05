package com.aktcl.aron.backend.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformModuleTest {
    @Test
    fun moduleIsWired() {
        assertEquals("platform", PlatformModule.NAME)
    }
}
