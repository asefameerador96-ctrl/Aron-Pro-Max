package com.aktcl.aron.sr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The summary print must finish when the SR leaves the screen: a screen scope is cancelled, the day's print scope is not. */
class PrintRunnerTest {
    @Test fun aPrintStartedFromAScreenCompletesAfterTheScreenScopeIsCancelled() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val dayScope = CoroutineScope(SupervisorJob() + d)
        val screenScope = CoroutineScope(Job() + d) // what rememberCoroutineScope is: dies with the composition
        val runner = PrintRunner(dayScope)
        val paper = CompletableDeferred<Unit>() // the printer still has bytes to send
        val steps = mutableListOf<String>()
        var shown: String? = null

        screenScope.launch {
            runner.run({ steps += "start"; paper.await(); steps += "paper done"; "awaiting confirm" }) { shown = it }
        }
        advanceUntilIdle()
        assertEquals(listOf("start"), steps)

        screenScope.cancel() // the SR navigates away mid-print
        advanceUntilIdle()
        assertEquals("the print is still in flight", listOf("start"), steps)

        paper.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("start", "paper done"), steps)
        assertEquals("awaiting confirm", shown)
    }

    @Test fun theSameWorkInTheScreenScopeIsCutShort() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val screenScope = CoroutineScope(Job() + d)
        val paper = CompletableDeferred<Unit>()
        var finished = false
        screenScope.launch { paper.await(); finished = true }
        advanceUntilIdle(); screenScope.cancel(); paper.complete(Unit); advanceUntilIdle()
        assertTrue("documents the defect the runner fixes", !finished)
    }
}
