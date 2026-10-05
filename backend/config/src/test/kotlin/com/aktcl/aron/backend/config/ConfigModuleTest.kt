package com.aktcl.aron.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigModuleTest {
    @Test
    fun moduleIsWired() {
        assertEquals("config", ConfigModule.NAME)
    }
}
