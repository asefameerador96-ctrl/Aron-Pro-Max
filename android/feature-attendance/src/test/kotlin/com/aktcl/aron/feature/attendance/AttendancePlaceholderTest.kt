package com.aktcl.aron.feature.attendance

import org.junit.Assert.assertEquals
import org.junit.Test

class AttendancePlaceholderTest {
    @Test
    fun moduleIsWired() {
        assertEquals("feature-attendance", AttendancePlaceholder.MODULE)
    }
}
