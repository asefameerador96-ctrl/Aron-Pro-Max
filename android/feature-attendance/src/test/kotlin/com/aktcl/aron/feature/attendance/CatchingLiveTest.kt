package com.aktcl.aron.feature.attendance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-AGATE-25: Room throws a CancellationException when its database closes under a running query; that is a failure of the step, not the caller's own cancellation. */
class CatchingLiveTest {
    @Test fun aCancellationThatIsNotTheCallersOwnIsAPlainFailure() = runBlocking {
        val r = catchingLive<Unit> { throw CancellationException("database closed") }
        assertTrue("a failure result, and this line is reached: the caller goes on (a timer re-arms)", r.isFailure)
    }

    @Test fun theCallersOwnCancellationIsStillRethrown() = runBlocking {
        var rethrown = false
        val job = launch {
            try { delay(60_000) } catch (c: CancellationException) {
                try { catchingLive<Unit> { throw c } } catch (e: CancellationException) { rethrown = true; throw e }
            }
        }
        yield(); job.cancelAndJoin()
        assertTrue("a cancelled caller stops", rethrown)
    }

    @Test fun anyOtherFailureIsAResult() = runBlocking {
        assertEquals("IllegalStateException", catchingLive<Unit> { throw IllegalStateException("x") }.exceptionOrNull()!!.javaClass.simpleName)
    }
}
