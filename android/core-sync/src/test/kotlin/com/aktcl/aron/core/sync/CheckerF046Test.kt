package com.aktcl.aron.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.aktcl.aron.contract.SyncTrigger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

/** Checker (refuter) for F-SYS-046 and F-SYS-011 round 2: each test is a defect found in review; it fails until fixed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CheckerF046Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val reports = ArrayDeque<SyncReport>()
    private var now = 1_000L
    private lateinit var scheduler: WorkManagerSyncScheduler

    private val runner = object : SyncRunner {
        override suspend fun run(userId: Long, trigger: SyncTrigger): SyncReport = reports.removeFirstOrNull() ?: report(SyncStop.DRAINED, 0)
    }

    private fun report(stop: SyncStop, unsent: Int, retryAfterMs: Long? = null) = SyncReport(stop, 1, 0, 0, 0, 0, unsent, retryAfterMs)
    private val wm get() = WorkManager.getInstance(context)

    private fun init(hold: SyncHold) {
        scheduler = WorkManagerSyncScheduler({ wm }, Random(1), sdkInt = 36, hold = hold, elapsedMs = { now })
        val config = Configuration.Builder().setExecutor(SynchronousExecutor())
            .setWorkerFactory(AronWorkerFactory({ runner }, { scheduler })).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Before fun setUp() = init(SyncHold.Memory())

    private fun live(name: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }
    private fun spec(info: WorkInfo) = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(info.id.toString())!!
    private val driver get() = WorkManagerTestInitHelper.getTestDriver(context)!!
    private fun runNow(info: WorkInfo) {
        val delayed = spec(info).initialDelay > 0
        driver.setAllConstraintsMet(info.id)
        if (delayed) driver.setInitialDelayMet(info.id)
    }
    private fun oneTime(user: Long) = live(WorkManagerSyncScheduler.mainName(user)) + live(WorkManagerSyncScheduler.retryName(user)) +
        live(WorkManagerSyncScheduler.nowName(user))

    /**
     * Two transport failures while the link flapped leave a 300 s backoff job under the main name. The link comes back:
     * T3's requestSync(CONNECTIVITY) uses KEEP on that same name, so the reconnect waits 300 s, not the 60 s AC.
     */
    @Test fun aReconnectIsNotSwallowedByATransportBackoff() {
        scheduler.requestSync(7, SyncTrigger.MANUAL)
        reports += report(SyncStop.OFFLINE, 5, retryAfterMs = 2_000)
        runNow(live(WorkManagerSyncScheduler.mainName(7)).single())
        reports += report(SyncStop.OFFLINE, 5, retryAfterMs = 300_000)
        runNow(live(WorkManagerSyncScheduler.retryName(7)).single())
        scheduler.requestSync(7, SyncTrigger.CONNECTIVITY) // ConnectivityFlush after 5 s of quiet
        val delays = oneTime(7).map { spec(it).initialDelay }
        assertTrue("after reconnect the only jobs wait $delays ms", delays.any { it <= 60_000 })
    }

    /**
     * SyncHold.Prefs (wired in all three apps) persists an elapsedRealtime deadline. After a reboot elapsedRealtime restarts
     * near 0, so a 60 s hold taken after 3 days of uptime pushes every automatic trigger about 3 days out.
     */
    @Test fun aHoldDoesNotSurviveAReboot() {
        init(SyncHold.Prefs(context))
        now = 3L * 24 * 3600 * 1000
        scheduler.afterRun(7, report(SyncStop.RETRY_LATER, 3, retryAfterMs = 60_000), WorkManagerSyncScheduler.mainName(7))
        now = 30_000 // the phone rebooted
        scheduler.requestSync(7, SyncTrigger.WRITE_DEBOUNCE)
        val delay = spec(live(WorkManagerSyncScheduler.mainName(7)).single()).initialDelay
        assertTrue("save debounce after reboot waits $delay ms", delay <= 60_000)
    }

    /**
     * Production gives ConnectivityFlush a SupervisorJob + Dispatchers.Default scope with no handler. If opening a user's
     * database throws (Keystore key invalidated, stale file of a wiped user) or healthy() throws, the exception is uncaught:
     * on Android that kills the process, on every network change.
     */
    @Test fun aFailingLookupDoesNotEscapeTheFlush() = runTest {
        val escaped = ArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> escaped += e })
        val none = object : SyncScheduler { override fun requestSync(userId: Long, trigger: SyncTrigger) = Unit }
        ConnectivityFlush(scope, { true }, { throw IllegalStateException("keystore key invalidated") }, none).onNetworkAvailable()
        ConnectivityFlush(scope, { throw java.io.IOException("socket") }, { listOf(7L) }, none).onNetworkAvailable()
        advanceTimeBy(10_000); runCurrent()
        assertEquals("uncaught in the app scope", emptyList<Throwable>(), escaped)
    }

    /** NoPollingLintTest holes: each line is real polling/timer code that the lint passes. */
    @Test fun theLintCatchesTheseCases() {
        val lint = NoPollingLintTest()
        val bad = listOf(
            // nested parentheses in the loop condition defeat `\([^)]*\)`
            "feature-x/src/main/A.kt" to "scope.launch { while (running.get()) { sync(); delay(30_000) } }",
            // kotlin.concurrent timers
            "feature-x/src/main/B.kt" to "fixedRateTimer(period = 60_000) { sync() }",
            "feature-x/src/main/C.kt" to "object : CountDownTimer(60_000, 1_000) { }",
            // "/*" inside a string starts a block comment for the stripper and hides the code up to "*/"
            "feature-x/src/main/D.kt" to "val glob = \"src/*\"\nval t = java.util.Timer()\nval mime = \"*/*\"",
            // a char literal '"' opens a "string" that swallows the rest of the line
            "feature-x/src/main/E.kt" to "val q = '\"'; startForeground(1, n); val s = \"x\"",
            // code inside a string template is code
            "feature-x/src/main/F.kt" to "val s = \"\${java.util.Timer().hashCode()}\"",
        )
        val missed = bad.filter { lint.violations(listOf(it)).isEmpty() }.map { it.first }
        assertEquals("lint passed real violations", emptyList<String>(), missed)
    }
}
