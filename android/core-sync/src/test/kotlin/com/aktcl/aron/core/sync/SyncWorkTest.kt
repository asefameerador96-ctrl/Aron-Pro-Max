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
        assertTrue(spec(live(WorkManagerSyncScheduler.gateName(8)).single()).initialDelay in 0L..90_000L)
    }

    /** F-SYS-079: 2026-10-05 in Dhaka at [hh]:[mm] (UTC+6), as trusted epoch ms. */
    private fun dhaka(hh: Int, mm: Int, ss: Int = 0) = 1_791_158_400_000L + ((hh - 6) * 3600L + mm * 60L + ss) * 1000L

    @Test fun theGateIsJustOpenedOnlyInTheFirstMinutesAfter1700Dhaka() {
        var now = 0L
        val gate = CheckoutGate.dhaka({ now })
        mapOf(dhaka(16, 59, 59) to false, dhaka(17, 0) to true, dhaka(17, 9, 59) to true, dhaka(17, 10) to false, dhaka(5, 0) to false)
            .forEach { (t, want) -> now = t; assertEquals("at $t", want, gate.justOpened()) }
        now = dhaka(18, 2)
        assertTrue(CheckoutGate.dhaka({ now }, gateMinutes = { 18 * 60 }).justOpened()) // cfg.day.checkout_earliest_time moved
    }

    /** Follow-up of F-SYS-079: the jitter is read from config at each request, and held to T7's 120 s cap. */
    @Test fun theGateJitterComesFromConfigAndIsCapped() {
        var jitterS = 5
        scheduler = WorkManagerSyncScheduler({ wm }, Random(1), sdkInt = 36, checkoutJitterS = { jitterS }, checkoutGate = { true })
        val small = (1..20).map { u ->
            scheduler.requestSync(400L + u, SyncTrigger.DAY_SUBMIT)
            spec(live(WorkManagerSyncScheduler.gateName(400L + u)).single()).initialDelay
        }
        assertTrue(small.all { it in 0L..5_000L })
        jitterS = 600 // registry allows 600; T7 caps the wave delay at 120
        val capped = (1..40).map { u ->
            scheduler.requestSync(500L + u, SyncTrigger.DAY_SUBMIT)
            spec(live(WorkManagerSyncScheduler.gateName(500L + u)).single()).initialDelay
        }
        assertTrue(capped.all { it in 0L..120_000L })
        assertTrue(capped.any { it > 90_000L })
        // A config read that throws never breaks a tap: the default 90 s holds, and a throwing gate time means 17:00.
        scheduler = WorkManagerSyncScheduler({ wm }, Random(1), sdkInt = 36, checkoutJitterS = { error("db") }, checkoutGate = { true })
        scheduler.requestSync(600, SyncTrigger.CHECKOUT)
        assertTrue(spec(live(WorkManagerSyncScheduler.gateName(600)).single()).initialDelay in 0L..90_000L)
        val at1703 = 1_791_198_180_000L // 2026-10-05T11:03Z = 17:03 Dhaka
        assertTrue(CheckoutGate.dhaka({ at1703 }, gateMinutes = { error("db") }).justOpened())
    }

    @Test fun atTheGateCheckOutAndSubmitAreJitteredAndManualIsNot() {
        var open = true
        scheduler = WorkManagerSyncScheduler({ wm }, Random(1), sdkInt = 36, checkoutGate = { open })
        val delays = (1..20).map { u ->
            scheduler.requestSync(100L + u, SyncTrigger.DAY_SUBMIT)
            spec(live(WorkManagerSyncScheduler.gateName(100L + u)).single()).also { assertFalse(it.expedited) }.initialDelay
        }
        assertTrue(delays.all { it in 0L..90_000L })
        assertTrue("spread, not one second", delays.toSet().size > 10)
        scheduler.requestSync(200, SyncTrigger.CHECKOUT)
        assertTrue(spec(live(WorkManagerSyncScheduler.gateName(200)).single()).initialDelay in 0L..90_000L)
        scheduler.requestSync(201, SyncTrigger.MANUAL)
        spec(live(WorkManagerSyncScheduler.mainName(201)).single()).let { assertEquals(0L, it.initialDelay); assertTrue(it.expedited) }
        // Later in the evening the same taps upload at once: submit expedited, check-out without the debounce.
        open = false
        scheduler.requestSync(300, SyncTrigger.DAY_SUBMIT)
        spec(live(WorkManagerSyncScheduler.mainName(300)).single()).let { assertEquals(0L, it.initialDelay); assertTrue(it.expedited) }
        scheduler.requestSync(301, SyncTrigger.CHECKOUT)
        assertEquals(0L, spec(live(WorkManagerSyncScheduler.nowName(301)).single()).initialDelay)
    }

    @Test fun aJitteredSubmitIgnoresAServerHoldButCheckOutWaitsItOut() {
        val hold = SyncHold.Memory()
        scheduler = WorkManagerSyncScheduler({ wm }, Random(1), sdkInt = 36, hold = hold, elapsedMs = { 1_000L }, checkoutGate = { true })
        hold.set(400, 1_000L + 600_000L)
        hold.set(401, 1_000L + 600_000L)
        scheduler.requestSync(400, SyncTrigger.DAY_SUBMIT)
        assertTrue(spec(live(WorkManagerSyncScheduler.gateName(400)).single()).initialDelay <= 90_000L)
        scheduler.requestSync(401, SyncTrigger.CHECKOUT)
        assertEquals(600_000L, spec(live(WorkManagerSyncScheduler.gateName(401)).single()).initialDelay)
    }

    /** Checker: a sale saved at 17:00:30 keeps its 5 s debounce when Submit is tapped at 17:00:32 inside the wave. */
    @Test fun aJitteredSubmitNeverDelaysTheDebounceOfEarlierRows() {
        scheduler = WorkManagerSyncScheduler({ wm }, Random(1), sdkInt = 36, checkoutGate = { true })
        scheduler.requestSync(600, SyncTrigger.WRITE_DEBOUNCE)
        scheduler.requestSync(600, SyncTrigger.DAY_SUBMIT)
        assertEquals(5_000L, spec(live(WorkManagerSyncScheduler.mainName(600)).single()).initialDelay)
        assertEquals(1, live(WorkManagerSyncScheduler.gateName(600)).size)
    }

    /** Off the wave a check-out is not swallowed by a backoff waiting on the main name. */
    @Test fun aCheckOutAfterTheWaveIsNotHeldBehindABackoff() {
        scheduler = WorkManagerSyncScheduler({ wm }, Random(1), sdkInt = 36, checkoutGate = { false })
        scheduler.requestSync(700, SyncTrigger.MANUAL)
        reports += report(SyncStop.FAILED, 3)
        reports += report(SyncStop.FAILED, 3)
        runNow(live(WorkManagerSyncScheduler.mainName(700)).single())
        runNow(live(WorkManagerSyncScheduler.retryName(700)).single()) // the second failure backs off on the main name
        assertTrue(spec(live(WorkManagerSyncScheduler.mainName(700)).single()).initialDelay > 0)
        scheduler.requestSync(700, SyncTrigger.CHECKOUT)
        assertEquals(0L, spec(live(WorkManagerSyncScheduler.nowName(700)).single()).initialDelay)
    }

    /** Re-check: a failed gate run follows up on the retry name, never on itself; a later Submit replaces a held gate check-out. */
    @Test fun aGateRunFollowsUpOnTheRetryNameAndSubmitReplacesAHeldCheckOut() {
        val hold = SyncHold.Memory()
        scheduler = WorkManagerSyncScheduler({ wm }, Random(1), sdkInt = 36, hold = hold, elapsedMs = { 1_000L }, checkoutGate = { true })
        scheduler.afterRun(800, report(SyncStop.OFFLINE, 2), WorkManagerSyncScheduler.gateName(800))
        assertEquals(1, live(WorkManagerSyncScheduler.retryName(800)).size)
        assertTrue(live(WorkManagerSyncScheduler.gateName(800)).isEmpty())
        hold.set(801, 1_000L + 600_000L)
        scheduler.requestSync(801, SyncTrigger.CHECKOUT)
        assertEquals(600_000L, spec(live(WorkManagerSyncScheduler.gateName(801)).single()).initialDelay)
        scheduler.requestSync(801, SyncTrigger.DAY_SUBMIT)
        assertTrue(spec(live(WorkManagerSyncScheduler.gateName(801)).single()).initialDelay <= 90_000L)
    }

    @Test fun aGateThatThrowsMeansNoJitter() {
        scheduler = WorkManagerSyncScheduler({ wm }, Random(1), sdkInt = 36, checkoutGate = { error("clock") })
        scheduler.requestSync(500, SyncTrigger.DAY_SUBMIT)
        assertTrue(spec(live(WorkManagerSyncScheduler.mainName(500)).single()).expedited)
    }

    @Test fun usersAreScheduledApart() {
        scheduler.requestSync(7, SyncTrigger.MANUAL)
        scheduler.requestSync(8, SyncTrigger.MANUAL)
        runNow(live(WorkManagerSyncScheduler.mainName(8)).single())
        assertEquals(listOf(8L to SyncTrigger.MANUAL), runs)
    }
}
