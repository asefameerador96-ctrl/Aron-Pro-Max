package com.aktcl.aron.amo

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The work a test starts on a real day, so none of it outlives the test's database (CI run 41750277921). There, Mark Paid's
 * reload, a view model coroutine the test never cleared, read the memos after the fixture had closed the database; its
 * "connection pool has been closed" failed the next policy's "nothing escaped" check and, through kotlinx-coroutines-test's
 * collector, the first runTest of a later class.
 *
 * [stop] runs before the database closes. A screen the test showed ([own], its ActivityScenario) is destroyed first, which
 * disposes its composition and clears its view models. The work still in flight then finishes on its own (bounded) while
 * the database is open, so an exception it throws reaches the test's catcher: every view model made through [viewModel] and
 * every coroutine of the day's own scopes ([dayJob]). Then all of it is cancelled (the view models cleared, as their
 * screen's store would) and joined, with Room's executor ([roomExecutor]) drained and the main looper idled. The printer
 * manager's scope ([printerScope]) is cancelled and joined without the wait: its idle timer runs for minutes.
 */
internal class TestWork {
    /** The parent of the day's own scopes (AmoDay's `parentJob`): their work in flight finishes, then it is cancelled and joined. */
    val dayJob: CompletableJob = SupervisorJob()

    /** The printer manager's scope (idle timer, reconnects): cancelled and joined, never waited for. */
    val printerScope = CoroutineScope(SupervisorJob())

    /** Room's query and transaction executor for the test's database: [stop] waits until nothing is queued or running on it. */
    val roomExecutor = DrainableExecutor()

    private val store = ViewModelStore()
    private val viewModelJobs = ArrayList<Job>()
    private val owned = ArrayList<AutoCloseable>()
    private var stopped = false

    /** [make]s a view model held as its screen's store would hold it: [stop] lets its work finish, clears it and joins it. */
    fun <T : ViewModel> viewModel(make: () -> T): T {
        check(!stopped) { "the test's work has stopped" }
        val vm = make()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = vm as V
        }
        check(ViewModelProvider(store, factory)["test-vm-" + viewModelJobs.size, vm.javaClass] === vm)
        viewModelJobs += vm.viewModelScope.coroutineContext.job
        return vm
    }

    /** A screen the test showed (its ActivityScenario): closed first by [stop], which ends its composition and view models. */
    fun <T : AutoCloseable> own(closeable: T): T {
        check(!stopped) { "the test's work has stopped" }
        owned += closeable
        return closeable
    }

    /**
     * Stops all of the test's work; afterwards nothing of it runs and the database may close. Throws [AssertionError] when the
     * work did not stop (the caller then must not close the database under it). Runs once; later calls do nothing.
     */
    fun stop() {
        if (stopped) return
        stopped = true
        var failure: Throwable? = null
        fun step(block: () -> Unit) {
            try { block() } catch (t: Throwable) { failure?.addSuppressed(t) ?: run { failure = t } }
        }
        owned.asReversed().forEach { c -> step { c.close() } }
        step {
            if (!waitUntil(SETTLE_MS) { inFlight().isEmpty() && roomExecutor.isIdle() }) {
                System.err.println("TestWork: still running after $SETTLE_MS ms, cancelled now: ${inFlight()}")
            }
        }
        step { store.clear() }
        dayJob.cancel()
        printerScope.cancel()
        val all = viewModelJobs + dayJob + printerScope.coroutineContext.job
        step {
            if (!waitUntil(JOIN_MS) { all.all { it.isCompleted } && roomExecutor.isIdle() }) {
                throw AssertionError("the test's work did not stop in $JOIN_MS ms: ${all.filter { !it.isCompleted }}, room idle ${roomExecutor.isIdle()}")
            }
        }
        failure?.let { throw it }
    }

    /**
     * True once nothing the test started is still in flight: the view models' and the day's coroutines are done and Room's executor is idle
     * (the main looper is idled on each poll). A check that "nothing happened" awaits this state instead of sleeping; [ms] is only a hang guard (D-QA-CI-12).
     */
    fun awaitSettled(ms: Long = HANG_GUARD_MS): Boolean = waitUntil(ms) { inFlight().isEmpty() && roomExecutor.isIdle() }

    /** Coroutines still running in the view models' and the day's scopes. */
    private fun inFlight(): List<Job> = (viewModelJobs + dayJob.children.toList()).flatMap { it.children.toList() }.filter { !it.isCompleted }

    /** Polls [done] (main looper idled each time, so work posted there runs) until true or [ms] pass. */
    private fun waitUntil(ms: Long, done: () -> Boolean): Boolean {
        val end = System.nanoTime() + ms * 1_000_000
        while (true) {
            if (Looper.myLooper() == Looper.getMainLooper()) shadowOf(Looper.getMainLooper()).idle()
            if (done()) return true
            if (System.nanoTime() > end) return false
            Thread.sleep(5)
        }
    }

    private companion object {
        const val SETTLE_MS = 10_000L
        const val JOIN_MS = 10_000L
        const val HANG_GUARD_MS = 60_000L
    }
}

/** Room's executor of a test database (four threads, as Room's default): counts what is queued or running, so it can drain. */
internal class DrainableExecutor : Executor {
    private val threads = AtomicInteger()
    private val pool: ExecutorService = Executors.newFixedThreadPool(4) { r -> Thread(r, "test-room-io-" + threads.incrementAndGet()).apply { isDaemon = true } }
    private val pending = AtomicInteger()

    override fun execute(command: Runnable) {
        pending.incrementAndGet()
        try {
            pool.execute { try { command.run() } finally { pending.decrementAndGet() } }
        } catch (e: RejectedExecutionException) {
            pending.decrementAndGet()
            throw e
        }
    }

    /** Nothing queued or running. */
    fun isIdle(): Boolean = pending.get() == 0

    /** After the database closed: the threads end once idle. */
    fun shutdown() = pool.shutdown()
}
