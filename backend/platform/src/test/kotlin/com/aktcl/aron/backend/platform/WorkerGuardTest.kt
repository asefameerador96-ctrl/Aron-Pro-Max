package com.aktcl.aron.backend.platform

import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** The worker jobs' failure rule (push A3): an Exception never exits the process, an Error does, through the injectable hook. */
class WorkerGuardTest {
    private val log = LoggerFactory.getLogger("aron.test.guard")

    private fun exits(t: Throwable): List<Int> {
        val seen = mutableListOf<Int>()
        val saved = WorkerExit.hook
        WorkerExit.hook = { seen += it }
        try { t.exitIfFatal(log, "job") } finally { WorkerExit.hook = saved }
        return seen
    }

    @Test fun anExceptionNeverExits() { assertEquals(emptyList(), exits(IllegalStateException("x"))); assertEquals(emptyList(), exits(java.sql.SQLException("x", "40001"))) }
    @Test fun anErrorExitsNonZero() { assertEquals(listOf(70), exits(OutOfMemoryError("x"))); assertEquals(listOf(70), exits(StackOverflowError())) }
    @Test fun aCaughtErrorInsideRunCatchingStillExits() {
        val seen = mutableListOf<Int>()
        val saved = WorkerExit.hook
        WorkerExit.hook = { seen += it }
        try { runCatching { throw NoClassDefFoundError("x") }.onFailure { it.exitIfFatal(log, "job") } } finally { WorkerExit.hook = saved }
        assertEquals(listOf(70), seen)
    }
}
