package com.aktcl.aron.backend.analytics

import kotlin.test.Test
import kotlin.test.assertEquals

class AnalyticsModuleTest {
    @Test
    fun moduleIsWired() {
        assertEquals("analytics", AnalyticsModule.NAME)
    }
}
