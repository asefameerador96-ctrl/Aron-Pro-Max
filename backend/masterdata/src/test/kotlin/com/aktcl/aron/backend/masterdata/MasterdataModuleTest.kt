package com.aktcl.aron.backend.masterdata

import kotlin.test.Test
import kotlin.test.assertEquals

class MasterdataModuleTest {
    @Test
    fun moduleIsWired() {
        assertEquals("masterdata", MasterdataModule.NAME)
    }
}
