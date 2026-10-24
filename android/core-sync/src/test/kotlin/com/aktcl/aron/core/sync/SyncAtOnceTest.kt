package com.aktcl.aron.core.sync

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import kotlin.random.Random

/**
 * D-NIGHT-SYNC-01: while rows wait there is exactly one network-constrained pending job; WorkManager runs it the moment a
 * network returns; a failure backoff never swallows a save's debounce; reconnect, validation, app start and the app in
 * front ask for that same job; with nothing to upload a reconnect pulls instead.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SyncAtOnceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val reports = ArrayDeque<SyncReport>()
    private val runs = ArrayList<Pair<Long, SyncTrigger>>()
    private lateinit var scheduler: WorkManagerSyncScheduler

    private val runner = object : SyncRunner {
        override suspend fun run(userId: Long, trigger: SyncTrigger): SyncReport {
            runs += userId to trigger
            return reports.removeFirstOrNull() ?: SyncReport(SyncStop.DRAINED, 1, 0, 0, 0, 0, 0)
        }
    }

    private fun report(stop: SyncStop, unsent: Int, retryAfterMs: Long? = null) = SyncReport(stop, 1, 0, 0, 0, 0, unsent, retryAfterMs)
    private val wm get() = WorkManager.getInstance(context)
    private fun live(name: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }
    private fun spec(info: WorkInfo) = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(info.id.toString())!!
    private val driver get() = WorkManagerTestInitHelper.getTestDriver(context)!!

    /** Every live one-time upload job of [user] (the periodic safety net is counted apart). */
    private fun oneTime(user: Long) = listOf(
        WorkManagerSyncScheduler.pendingName(user), WorkManagerSyncScheduler.mainName(user), WorkManagerSyncScheduler.retryName(user),
        WorkManagerSyncScheduler.nowName(user), WorkManagerSyncScheduler.gateName(user),
    ).flatMap { live(it) }

    /** The network returns: constraints met; a delay still running is not skipped (that is the backoff case). */
    private fun networkBack(info: WorkInfo) = driver.setAllConstraintsMet(info.id)
    private fun runNow(info: WorkInfo) {
        driver.setAllConstraintsMet(info.id)
        if (spec(info).initialDelay > 0) driver.setInitialDelayMet(info.id)
    }

    @Before fun setUp() {
        scheduler = WorkManagerSyncScheduler({ wm }, Random(1), sdkInt = 36)
        val config = Configuration.Builder().setExecutor(SynchronousExecutor())
            .setWorkerFactory(AronWorkerFactory({ runner }, { scheduler })).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test fun anOfflineSaveLeavesOneConstrainedJobThatRunsOnceWhenTheNetworkReturns() {
        repeat(3) { scheduler.requestSync(7, SyncTrigger.WRITE_DEBOUNCE) } // three sales offline
        scheduler.requestSync(7, SyncTrigger.FOREGROUND) // the app came to the front, still offline
        val job = oneTime(7).single()
        assertEquals(job.id, live(WorkManagerSyncScheduler.pendingName(7)).single().id)
        assertEquals(NetworkType.CONNECTED, job.constraints.requiredNetworkType)
        assertFalse("never expedited: no quota, no foreground service", spec(job).expedited)
        assertEquals(1, live(WorkManagerSyncScheduler.periodicName(7)).size)
        assertTrue("nothing runs without a network", runs.isEmpty())

        driver.setInitialDelayMet(job.id) // the 5 s pass offline: still waiting on the network
        assertTrue(runs.isEmpty())
        networkBack(job)
        assertEquals(listOf(7L to SyncTrigger.WRITE_DEBOUNCE), runs)
        assertTrue("no second job", oneTime(7).isEmpty())
        assertTrue("the outbox is empty: no safety net left", live(WorkManagerSyncScheduler.periodicName(7)).isEmpty())
    }

    @Test fun aReconnectOrForegroundAsksForTheSameJobAndRunsAtOnce() {
        scheduler.requestSync(7, SyncTrigger.CONNECTIVITY)
        scheduler.requestSync(7, SyncTrigger.FOREGROUND)
        val job = oneTime(7).single()
        assertEquals(0L, spec(job).initialDelay)
        networkBack(job)
        assertEquals(1, runs.size)
        assertTrue(oneTime(7).isEmpty())
    }

    /** The owner's slow reflection: a save while a 300 s backoff waited on the main name inherited that backoff (KEEP). */
    @Test fun aSaveAfterFailedSendsIsNotHeldBehindTheBackoff() {
        scheduler.requestSync(7, SyncTrigger.MANUAL)
        reports += report(SyncStop.OFFLINE, 3)
        reports += report(SyncStop.FAILED, 3, retryAfterMs = 300_000)
        runNow(live(WorkManagerSyncScheduler.mainName(7)).single())
        runNow(live(WorkManagerSyncScheduler.retryName(7)).single())
        assertEquals(300_000L, spec(live(WorkManagerSyncScheduler.mainName(7)).single()).initialDelay)

        scheduler.requestSync(7, SyncTrigger.WRITE_DEBOUNCE) // a new sale, online again
        val debounce = live(WorkManagerSyncScheduler.pendingName(7)).single()
        assertEquals(5_000L, spec(debounce).initialDelay)
        reports += report(SyncStop.DRAINED, 0)
        runNow(debounce)
        assertEquals(3, runs.size)
        assertEquals(SyncTrigger.WRITE_DEBOUNCE, runs.last().second)
    }

    /** A server hold (hold_s, 429/503) still holds every automatic trigger, the pending job included (docs/24 s4.7). */
    @Test fun aServerHoldStillHoldsThePendingJob() {
        scheduler.afterRun(7, report(SyncStop.RETRY_LATER, 3, retryAfterMs = 120_000), WorkManagerSyncScheduler.mainName(7))
        scheduler.requestSync(7, SyncTrigger.FOREGROUND)
        assertTrue(spec(live(WorkManagerSyncScheduler.pendingName(7)).single()).initialDelay >= 100_000)
    }

    /** Checker: a save swallowed while the pending job ran gets its follow-up in 5 s, not behind an older backoff. */
    @Test fun aRunThatDrainedReplacesAStaleBackoffWithTheDebounce() {
        scheduler.afterRun(7, report(SyncStop.FAILED, 3, retryAfterMs = 300_000), WorkManagerSyncScheduler.mainName(7))
        assertEquals(300_000L, spec(live(WorkManagerSyncScheduler.retryName(7)).single()).initialDelay)
        scheduler.afterRun(7, report(SyncStop.DRAINED, 1), WorkManagerSyncScheduler.pendingName(7))
        assertEquals(5_000L, spec(live(WorkManagerSyncScheduler.retryName(7)).single()).initialDelay)
    }

    @Test fun checkOutAndSubmitStillGoAtOnceBesideAWaitingDebounce() {
        scheduler = WorkManagerSyncScheduler({ wm }, Random(1), sdkInt = 36, checkoutGate = { false })
        scheduler.requestSync(7, SyncTrigger.WRITE_DEBOUNCE)
        scheduler.requestSync(7, SyncTrigger.CHECKOUT)
        scheduler.requestSync(7, SyncTrigger.DAY_SUBMIT)
        assertEquals(0L, spec(live(WorkManagerSyncScheduler.nowName(7)).single()).initialDelay)
        live(WorkManagerSyncScheduler.mainName(7)).single().let { assertEquals(0L, spec(it).initialDelay); assertTrue(spec(it).expedited) }
        assertEquals(5_000L, spec(live(WorkManagerSyncScheduler.pendingName(7)).single()).initialDelay)
    }

    // ---- ConnectivityFlush: validation, start and foreground nudges, pull when idle ----

    private class Recorder : SyncScheduler {
        val asked = ArrayList<Pair<Long, SyncTrigger>>()
        override fun requestSync(userId: Long, trigger: SyncTrigger) { asked += userId to trigger }
    }

    @Test fun aNetworkThatValidatesLaterFlushesOnceEvenWhenTheFirstHealthCheckFailed() {
        val test = TestScope()
        val rec = Recorder()
        var healthy = false
        val flush = ConnectivityFlush(test.backgroundScope, { healthy }, { listOf(7L) }, rec)
        val cb = ConnectivityFlush.callback(flush)
        val net = ShadowNetwork.newInstance(11)
        cb.onAvailable(net) // the link is still attaching
        test.advanceTimeBy(6_000); test.runCurrent()
        assertTrue(rec.asked.isEmpty())
        healthy = true
        val validated = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        cb.onCapabilitiesChanged(net, validated)
        repeat(4) { cb.onCapabilitiesChanged(net, validated) } // signal-strength updates: not new
        test.advanceTimeBy(6_000); test.runCurrent()
        assertEquals(listOf(7L to SyncTrigger.CONNECTIVITY), rec.asked)
        cb.onLost(net)
        cb.onCapabilitiesChanged(net, validated) // validated again after a loss: a new flush
        test.advanceTimeBy(6_000); test.runCurrent()
        assertEquals(2, rec.asked.size)
    }

    @Test fun appStartAndEveryReturnToTheFrontNudgeUsersWithRowsOnly() {
        val test = TestScope()
        val rec = Recorder()
        var waiting = listOf(7L, 9L)
        var health = 0
        val flush = ConnectivityFlush(test.backgroundScope, { health++; true }, { waiting }, rec)
        val nudge = ConnectivityFlush.ForegroundNudge(flush)
        val a = Robolectric.buildActivity(Activity::class.java).get()
        val b = Robolectric.buildActivity(Activity::class.java).get()
        flush.nudge() // app start (register does this first)
        test.runCurrent()
        assertEquals(listOf(7L to SyncTrigger.FOREGROUND, 9L to SyncTrigger.FOREGROUND), rec.asked)
        nudge.onActivityStarted(a); nudge.onActivityStarted(b); nudge.onActivityStopped(a) // one front
        test.runCurrent()
        assertEquals(4, rec.asked.size)
        nudge.onActivityStopped(b) // background
        waiting = emptyList()
        nudge.onActivityStarted(a) // front again, nothing waits: nothing asked
        test.runCurrent()
        assertEquals(4, rec.asked.size)
        assertEquals("a nudge never touches the network", 0, health)
    }

    @Test fun aReconnectWithNothingToUploadPullsInsteadAndWithRowsDoesNot() {
        val test = TestScope()
        val rec = Recorder()
        var waiting = emptyList<Long>()
        var pulls = 0
        var healthy = false
        val flush = ConnectivityFlush(test.backgroundScope, { healthy }, { waiting }, rec, pullIfStale = { pulls++ })
        flush.onNetworkAvailable() // a process started in the background (a push or WorkManager wake): no pull
        test.advanceTimeBy(6_000); test.runCurrent()
        assertEquals(0, pulls)
        flush.inFront = true
        flush.onNetworkAvailable() // in front behind a captive portal: no pull
        test.advanceTimeBy(6_000); test.runCurrent()
        assertEquals(0, pulls)
        healthy = true
        flush.onNetworkAvailable()
        test.advanceTimeBy(6_000); test.runCurrent()
        assertEquals(1, pulls)
        assertTrue(rec.asked.isEmpty())
        waiting = listOf(7L) // rows wait: the run itself pulls when the server is newer
        flush.onNetworkAvailable()
        test.advanceTimeBy(6_000); test.runCurrent()
        assertEquals(1, pulls)
        assertEquals(listOf(7L to SyncTrigger.CONNECTIVITY), rec.asked)
    }

    @Test fun registerNudgesAtStartAndListensForTheDefaultNetwork() {
        val test = TestScope()
        val rec = Recorder()
        ConnectivityFlush.register(context, ConnectivityFlush(test.backgroundScope, { true }, { listOf(7L) }, rec))
        test.runCurrent()
        assertEquals(listOf(7L to SyncTrigger.FOREGROUND), rec.asked)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        assertTrue(shadowOf(cm).networkCallbacks.isNotEmpty())
    }
}
