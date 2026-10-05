package com.aktcl.aron.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlaceholderTest {
    @Test
    fun moduleIsWired() {
        assertEquals("core-sync", SyncPlaceholder.MODULE)
    }
}
