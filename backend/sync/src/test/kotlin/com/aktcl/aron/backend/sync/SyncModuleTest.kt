package com.aktcl.aron.backend.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncModuleTest {
    @Test
    fun moduleIsWired() {
        assertEquals("sync", SyncModule.NAME)
    }
}
