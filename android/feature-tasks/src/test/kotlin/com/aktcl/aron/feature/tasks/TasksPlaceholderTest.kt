package com.aktcl.aron.feature.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

class TasksPlaceholderTest {
    @Test
    fun moduleIsWired() {
        assertEquals("feature-tasks", TasksPlaceholder.MODULE)
    }
}
