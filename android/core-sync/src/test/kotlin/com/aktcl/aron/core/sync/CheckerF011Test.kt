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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

/** Checker (refuter) for F-SYS-011: each test is a defect found in review; it fails until the defect is fixed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CheckerF011Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val reports = ArrayDeque<SyncReport>()
    private lateinit var scheduler: WorkManagerSyncScheduler

    private val runner = object : SyncRunner {
        override suspend fun run(userId: Long, trigger: SyncTrigger): SyncReport {
            return reports.removeFirstOrNull() ?: report(SyncStop.DRAINED, 0)
        }
    }

    private fun report(stop: SyncStop, unsent: Int, retryAfterMs: Long? = null) = SyncReport(stop, 1, 0, 0, 0, 0, unsent, retryAfterMs)
    private val wm get() = WorkManager.getInstance(context)

    @Before fun setUp() {
        scheduler = WorkManagerSyncScheduler({ wm }, Random(1), sdkInt = 36)
        val config = Configuration.Builder().setExecutor(SynchronousExecutor())
            .setWorkerFactory(AronWorkerFactory({ runner }, { scheduler })).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    private fun live(name: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }
    private fun spec(info: WorkInfo) = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(info.id.toString())!!
    private val driver get() = WorkManagerTestInitHelper.getTestDriver(context)!!
    private fun runNow(info: WorkInfo) {
        val delayed = spec(info).initialDelay > 0
        val periodic = spec(info).isPeriodic
        driver.setAllConstraintsMet(info.id)
        if (delayed) driver.setInitialDelayMet(info.id)
        if (periodic) driver.setPeriodDelayMet(info.id)
    }
    private fun oneTime(user: Long) = live(WorkManagerSyncScheduler.mainName(user)) + live(WorkManagerSyncScheduler.retryName(user))

    /**
     * A save lands after the engine's last nextSendable but before its report: DRAINED with unsent > 0. afterRun leaves only
     * the 15-minute battery-not-low periodic job (never runs on low battery), not the 5 s one-time send T1 promises.
     */
    @Test fun drainedWithRowsLeftGetsAOneTimeJob() {
        scheduler.requestSync(7, SyncTrigger.MANUAL)
        reports += report(SyncStop.DRAINED, 2) // the save's own requestSync was swallowed by KEEP while this job ran
        runNow(live(WorkManagerSyncScheduler.mainName(7)).single())
        assertTrue("only the periodic job is left for 2 pending rows", oneTime(7).isNotEmpty())
    }

    /**
     * OFFLINE also covers timeouts and edge pages while CONNECTED holds (server down, captive portal). Each run enqueues the
     * other name at delay 0, ignoring report.retryAfterMs: main -> retry -> main ... a hot loop with no backoff.
     */
    @Test fun repeatedTransportFailuresBackOff() {
        scheduler.requestSync(7, SyncTrigger.MANUAL)
        reports += report(SyncStop.OFFLINE, 5, retryAfterMs = 4_000)
        runNow(live(WorkManagerSyncScheduler.mainName(7)).single())
        reports += report(SyncStop.OFFLINE, 5, retryAfterMs = 8_000)
        runNow(live(WorkManagerSyncScheduler.retryName(7)).single())
        val next = live(WorkManagerSyncScheduler.mainName(7)).single()
        assertTrue("second consecutive failure re-ran with delay ${spec(next).initialDelay} ms", spec(next).initialDelay >= 8_000)
    }

    /** s4.7: hold_s pauses automatic triggers. The hold lives only on the retry job; the next save sends 5 s later. */
    @Test fun aServerHoldPausesTheSaveDebounce() {
        scheduler.requestSync(7, SyncTrigger.MANUAL)
        reports += report(SyncStop.RETRY_LATER, 3, retryAfterMs = 120_000)
        runNow(live(WorkManagerSyncScheduler.mainName(7)).single())
        scheduler.requestSync(7, SyncTrigger.WRITE_DEBOUNCE)
        val soonest = oneTime(7).minOf { spec(it).initialDelay }
        assertTrue("a save during a 120 s hold is sent after $soonest ms", soonest >= 100_000)
    }

    /** The lint's false negatives: each sample is polling, a timer, an alarm, a sub-15-min period or a foreground service. */
    @Test fun theLintCatchesTheseToo() {
        val lint = NoPollingLintTest()
        val samples = listOf(
            "feature-x/src/main/A.kt" to "val r = object : Runnable { override fun run() { sync(); handler.postDelayed(this, 30_000) } }",
            "feature-x/src/main/B.kt" to "scope.launch { repeat(Int.MAX_VALUE) { sync(); delay(30_000) } }",
            "feature-x/src/main/C.kt" to "val t = ticker(30_000)",
            "feature-x/src/main/D.kt" to "val m = 10L; PeriodicWorkRequestBuilder<W>(m, TimeUnit.MINUTES)",
            "feature-x/src/main/E.kt" to "PeriodicWorkRequest.Builder(W::class.java, 10, TimeUnit.MINUTES)",
            "feature-x/src/main/F.kt" to "PeriodicWorkRequestBuilder<W>(Duration.ofMinutes(10))",
            "feature-x/src/main/G.kt" to "ContextCompat.startForegroundService(ctx, intent)",
            "feature-x/src/main/H.kt" to "override suspend fun doWork(): Result { setForeground(info); return Result.success() }",
            "feature-x/src/main/I.kt" to "scope.launch { while (running) { sync(); delay(30_000) } }",
            "feature-x/src/main/J.kt" to "val am = ctx.getSystemService(Context.ALARM_SERVICE)",
            "feature-x/src/main/K.kt" to "scope.launch { do { sync(); delay(30_000) } while (true) }",
        )
        val missed = samples.filter { lint.violations(listOf(it)).isEmpty() }.map { it.first }
        assertEquals(emptyList<String>(), missed)
    }
}
