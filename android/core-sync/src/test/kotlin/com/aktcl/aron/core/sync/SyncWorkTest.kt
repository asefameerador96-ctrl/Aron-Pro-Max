package com.aktcl.aron.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.aktcl.aron.contract.SyncTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

/** F-SYS-011: every job needs a network, one expedited job follows a failed send, the periodic job exists only while rows wait. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SyncWorkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val reports = ArrayDeque<SyncReport>()
    private val runs = ArrayList<Pair<Long, SyncTrigger>>()
    private lateinit var scheduler: WorkManagerSyncScheduler

    private val runner = object : SyncRunner {
        override suspend fun run(userId: Long, trigger: SyncTrigger): SyncReport {
            runs += userId to trigger
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

    private fun infos(name: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(name).get()
    private fun live(name: String) = infos(name).filter { !it.state.isFinished }
    private fun spec(info: WorkInfo) = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(info.id.toString())!!
    private val driver get() = WorkManagerTestInitHelper.getTestDriver(context)!!

    private fun runNow(info: WorkInfo) {
        driver.setAllConstraintsMet(info.id)
        if (spec(info).initialDelay > 0) driver.setInitialDelayMet(info.id)
        if (spec(info).isPeriodic) driver.setPeriodDelayMet(info.id)
    }

    @Test fun aSaveAsksForOneNetworkJobFiveSecondsLaterAndAPeriodicSafetyNet() {
        scheduler.requestSync(7, SyncTrigger.WRITE_DEBOUNCE)
        scheduler.requestSync(7, SyncTrigger.WRITE_DEBOUNCE) // a burst shares the job
        val job = live(WorkManagerSyncScheduler.mainName(7)).single()
        assertEquals(5_000L, spec(job).initialDelay)
        assertEquals(NetworkType.CONNECTED, job.constraints.requiredNetworkType)
        assertFalse(spec(job).expedited)
        val periodic = live(WorkManagerSyncScheduler.periodicName(7)).single()
        assertEquals(15 * 60_000L, spec(periodic).intervalDuration)
        assertTrue(periodic.constraints.requiresBatteryNotLow())
        assertEquals(NetworkType.CONNECTED, periodic.constraints.requiredNetworkType)
        assertTrue(runs.isEmpty()) // nothing runs without a network
    }

    @Test fun salesSubmitRunsAtOnceExpedited() {
        scheduler.requestSync(7, SyncTrigger.DAY_SUBMIT)
        val job = live(WorkManagerSyncScheduler.mainName(7)).single()
        assertTrue(spec(job).expedited)
        assertEquals(0L, spec(job).initialDelay)
        runNow(job)
        assertEquals(listOf(7L to SyncTrigger.DAY_SUBMIT), runs)
    }

    @Test fun anEmptiedOutboxCancelsThePeriodicJob() {
        scheduler.requestSync(7, SyncTrigger.MANUAL)
        reports += report(SyncStop.DRAINED, 0)
        runNow(live(WorkManagerSyncScheduler.mainName(7)).single())
        assertTrue(live(WorkManagerSyncScheduler.periodicName(7)).isEmpty())
    }

    @Test fun aFailedSendLeavesOneExpeditedJobWaitingForTheNetwork() {
        scheduler.requestSync(7, SyncTrigger.MANUAL)
        reports += report(SyncStop.OFFLINE, 14)
        runNow(live(WorkManagerSyncScheduler.mainName(7)).single())
        val retry = live(WorkManagerSyncScheduler.retryName(7)).single()
        assertTrue(spec(retry).expedited)
        assertEquals(NetworkType.CONNECTED, retry.constraints.requiredNetworkType)
        assertEquals(1, live(WorkManagerSyncScheduler.periodicName(7)).size)

        reports += report(SyncStop.OFFLINE, 14) // the retry job fails too: the follow-up uses the other name, never replaces itself
        runNow(retry)
        assertEquals(1, live(WorkManagerSyncScheduler.mainName(7)).size)
        reports += report(SyncStop.DRAINED, 0)
        runNow(live(WorkManagerSyncScheduler.mainName(7)).single())
        assertTrue(live(WorkManagerSyncScheduler.periodicName(7)).isEmpty())
        assertEquals(3, runs.size)
    }

    @Test fun belowAndroid12TheFollowUpIsAPlainJobNeverAForegroundService() {
        scheduler = WorkManagerSyncScheduler({ wm }, Random(1), sdkInt = 30)
        WorkManagerTestInitHelper.closeWorkDatabase()
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(AronWorkerFactory({ runner }, { scheduler })).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        scheduler.requestSync(7, SyncTrigger.DAY_SUBMIT)
        assertFalse(spec(live(WorkManagerSyncScheduler.mainName(7)).single()).expedited)
    }

    @Test fun aServerHoldDelaysTheNextJobAndCheckOutIsJittered() {
        scheduler.requestSync(7, SyncTrigger.MANUAL)
        reports += report(SyncStop.RETRY_LATER, 3, retryAfterMs = 120_000)
        runNow(live(WorkManagerSyncScheduler.mainName(7)).single())
        val later = live(WorkManagerSyncScheduler.retryName(7)).single()
        assertEquals(120_000L, spec(later).initialDelay)
        assertFalse(spec(later).expedited)

        scheduler.requestSync(8, SyncTrigger.CHECKOUT)
        assertTrue(spec(live(WorkManagerSyncScheduler.mainName(8)).single()).initialDelay in 0L..90_000L)
    }

    @Test fun usersAreScheduledApart() {
        scheduler.requestSync(7, SyncTrigger.MANUAL)
        scheduler.requestSync(8, SyncTrigger.MANUAL)
        runNow(live(WorkManagerSyncScheduler.mainName(8)).single())
        assertEquals(listOf(8L to SyncTrigger.MANUAL), runs)
    }
}
