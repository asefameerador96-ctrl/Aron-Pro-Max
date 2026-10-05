package com.aktcl.aron.backend.notify

import kotlin.test.Test
import kotlin.test.assertEquals

class NotifyModuleTest {
    @Test
    fun moduleIsWired() {
        assertEquals("notify", NotifyModule.NAME)
    }
}
