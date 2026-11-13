package com.aktcl.aron.backend.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Main's wiring (D-LOY-API-21): `startWorker` constructs and starts no job before the readiness wait returns, and none at all when it fails. */
class WorkerStartTest {
    @Test
    fun noJobStartsWhileTheReadinessWaitIsStillBlocked() {
        val release = CountDownLatch(1); val entered = CountDownLatch(1); val started = AtomicBoolean(false)
        val t = Thread { startWorker({ entered.countDown(); release.await() }) { started.set(true) } }.also { it.start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)   // a wrong wiring would have started the jobs by now
        assertFalse(started.get(), "nothing starts while the wait is blocked")
        release.countDown(); t.join(5_000)
        assertTrue(started.get(), "the jobs start once the wait returned")
    }

    @Test
    fun aFailedWaitStartsNothing() {
        val order = mutableListOf<String>()
        assertFailsWith<IllegalStateException> { startWorker({ order += "wait"; error("boom") }) { order += "jobs" } }
        assertEquals(listOf("wait"), order)
    }
}
