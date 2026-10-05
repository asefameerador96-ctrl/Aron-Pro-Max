package com.aktcl.aron.backend.media

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaModuleTest {
    @Test
    fun moduleIsWired() {
        assertEquals("media", MediaModule.NAME)
    }
}
