package com.aktcl.aron.core.sync

import com.aktcl.aron.contract.SyncTrigger
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-SYS-046: regaining connectivity flushes waiting rows within 60 s without pressing Sync, after 5 s of quiet. */
class ConnectivityFlushTest {
    private val requests = ArrayList<Pair<Long, SyncTrigger>>()
    private val scheduler = object : SyncScheduler {
        override fun requestSync(userId: Long, trigger: SyncTrigger) { requests += userId to trigger }
    }

    @Test fun aReconnectFlushesEveryUserWithRowsAfterFiveQuietSeconds() = runTest {
        var health = 0
        val flush = ConnectivityFlush(backgroundScope, { health++; true }, { listOf(7L, 9L) }, scheduler)
        flush.onNetworkAvailable()
        advanceTimeBy(4_999); runCurrent()
        assertTrue(requests.isEmpty())
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf(7L to SyncTrigger.CONNECTIVITY, 9L to SyncTrigger.CONNECTIVITY), requests)
        assertEquals(1, health)
    }

    @Test fun aFlappingLinkAsksOnceAndALostLinkNotAtAll() = runTest {
        val flush = ConnectivityFlush(backgroundScope, { true }, { listOf(7L) }, scheduler)
        repeat(5) { flush.onNetworkAvailable(); advanceTimeBy(2_000) }
        advanceTimeBy(6_000); runCurrent()
        assertEquals(1, requests.size)
        flush.onNetworkAvailable(); advanceTimeBy(1_000); flush.onNetworkLost()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, requests.size)
    }

    @Test fun aCaptivePortalOrAnEmptyOutboxSendsNothing() = runTest {
        var asked = 0
        ConnectivityFlush(backgroundScope, { asked++; false }, { listOf(7L) }, scheduler).onNetworkAvailable()
        ConnectivityFlush(backgroundScope, { asked++; true }, { emptyList() }, scheduler).onNetworkAvailable()
        advanceTimeBy(10_000); runCurrent()
        assertTrue(requests.isEmpty())
        assertEquals(1, asked) // no health check at all when nothing waits
    }
}
