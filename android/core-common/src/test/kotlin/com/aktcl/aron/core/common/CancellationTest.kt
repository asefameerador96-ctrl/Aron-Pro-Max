package com.aktcl.aron.core.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-AMO-CALLS-35, the deterministic probe of the flake in AmoDayLockHostsTest.editOnTheMemoDetailFollowsTheLockThatLandsWhileItIsOnScreen. The memo host's
 * `LaunchedEffect(memo, dayLocked, pulls, dayLock) { editRefused = kit.editRefusal(memo) }` was cancelled by the key change while its query was in flight; the query was
 * wrapped in `runCatching`, which returned null for the cancellation, and the cancelled coroutine then wrote `editRefused = null` AFTER the newer run had written FINAL_SUBMIT.
 * No clock, no threads, no scheduling luck: the run is cancelled while suspended, so the outcome is the same every time (with `runCatching(..).getOrNull()` in place of the helper,
 * [cancelledRunNeverReturnsAValue] and [aSupersededRunCannotOverwriteTheNewerAnswer], 2 of the 3 tests, fail every time; D-AMO-CALLS-37 adds the same pair for [orDefaultKeepingCancellation]).
 */
class CancellationTest {
    @Test fun cancelledRunNeverReturnsAValue() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var wrote = false
        val run = launch(start = CoroutineStart.UNDISPATCHED) {
            val answer = orNullKeepingCancellation { gate.await(); "FINAL_SUBMIT" }
            wrote = true // what the effect does with the answer: editRefused = answer
            assertNull(answer)
        }
        run.cancel()
        run.join()
        assertTrue("the run is cancelled", run.isCancelled)
        assertFalse("a cancelled run must not go on to write its (null) answer", wrote)
    }

    @Test fun aSupersededRunCannotOverwriteTheNewerAnswer() = runBlocking {
        var state: String? = "unset"
        val oldGate = CompletableDeferred<Unit>()
        val old = launch(start = CoroutineStart.UNDISPATCHED) { state = orNullKeepingCancellation { oldGate.await(); null } }
        old.cancel() // the key changed: the old run is cancelled, the new one starts
        val fresh = launch(start = CoroutineStart.UNDISPATCHED) { state = orNullKeepingCancellation { "FINAL_SUBMIT" } }
        fresh.join(); old.join() // the old run's resumption comes after the new run's write, as in the failing runs
        assertEquals("FINAL_SUBMIT", state)
    }

    @Test fun otherFailuresStillGiveNullAndValuesPassThrough() = runBlocking {
        assertNull(orNullKeepingCancellation<String> { error("query failed") })
        assertNull(orNullKeepingCancellation<String> { null })
        assertEquals("x", orNullKeepingCancellation { "x" })
    }

    @Test fun cancelledRunNeverFallsBackToTheDefault() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var wrote = false
        val run = launch(start = CoroutineStart.UNDISPATCHED) {
            orDefaultKeepingCancellation(emptyList<Int>()) { gate.await(); listOf(1) }
            wrote = true // refreshLock: _dayLock.value = ... computed from the (default) answer
        }
        run.cancel(); run.join()
        assertTrue(run.isCancelled)
        assertFalse("a cancelled refreshLock must not write the default over the shared lock", wrote)
    }

    @Test fun defaultAppliesToOtherFailuresOnly() = runBlocking {
        assertEquals(emptyList<Int>(), orDefaultKeepingCancellation(emptyList<Int>()) { error("read failed") })
        assertEquals(listOf(1), orDefaultKeepingCancellation(emptyList<Int>()) { listOf(1) })
    }
}
