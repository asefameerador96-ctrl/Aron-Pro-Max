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

    /**
     * BY SOURCE SCAN (Main's `main` is not callable in a test): between the pool's construction and the "aron worker started" line, every `.start()` of a job sits inside the
     * `startWorker(...) { ... }` block, i.e. after its opening; and the block really does contain the starts (so a refactor that moves one out turns this red).
     */
    @Test
    fun mainStartsEveryJobInsideStartWorker() {
        val root = java.io.File(System.getProperty("aron.repoRoot") ?: "../..")
        val src = java.io.File(root, "backend/app/src/main/kotlin/com/aktcl/aron/backend/app/Main.kt").readText()
        val pool = src.indexOf("val workerDb = Database.fromSettings(settings)")
        val open = src.indexOf("startWorker({", pool)
        val done = src.indexOf("aron worker started", pool)
        assertTrue(pool in 0 until open && open < done, "the worker branch of Main has the expected shape")
        val starts = Regex("""\.start\(\)""").findAll(src.substring(pool, done)).map { pool + it.range.first }.toList()
        assertTrue(starts.size >= 10, "the block starts the jobs (found ${starts.size})")
        assertEquals(emptyList(), starts.filter { it < open }, "no job starts before startWorker's block opens")
    }
}
