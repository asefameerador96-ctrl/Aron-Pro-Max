package com.aktcl.aron.core.sync.push

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
import com.aktcl.aron.core.sync.AronWorkerFactory
import com.aktcl.aron.core.sync.SyncReport
import com.aktcl.aron.core.sync.SyncRunner
import com.aktcl.aron.core.sync.WorkManagerSyncScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

/** N-038: a push makes one jittered pull on a network job and never an upload (docs/24 s4.7). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PushPullWorkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pulls = mutableListOf<PushPullKind>()
    private var applied = false
    private val uploads = mutableListOf<SyncTrigger>()
    private val wm get() = WorkManager.getInstance(context)
    private val scheduler = PushPullScheduler { wm }
    private val driver get() = WorkManagerTestInitHelper.getTestDriver(context)!!

    @Before fun setUp() {
        val runner = object : SyncRunner {
            override suspend fun run(userId: Long, trigger: SyncTrigger): SyncReport { uploads += trigger; error("a push must never upload") }
        }
        val syncScheduler = WorkManagerSyncScheduler({ wm }, Random(1), sdkInt = 36)
        val config = Configuration.Builder().setExecutor(SynchronousExecutor())
            .setWorkerFactory(AronWorkerFactory({ runner }, { syncScheduler }, { PushPull { kind -> pulls += kind; applied } })).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    private fun live(kind: PushPullKind) = wm.getWorkInfosForUniqueWork(PushPullScheduler.name(kind)).get().filter { !it.state.isFinished }
    private fun all(kind: PushPullKind) = wm.getWorkInfosForUniqueWork(PushPullScheduler.name(kind)).get()
    private fun spec(info: WorkInfo) = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(info.id.toString())!!
    private fun run(info: WorkInfo) {
        driver.setAllConstraintsMet(info.id)
        if (spec(info).initialDelay > 0) driver.setInitialDelayMet(info.id)
    }

    @Test fun aTaskPushSchedulesOneNetworkPullAfterItsDelayAndNoUpload() {
        val dispatcher = PushDispatcher({ }, { k, ms -> scheduler.schedule(k, ms) }, Random(1))
        dispatcher.onMessage(mapOf("kind" to "sync_nudge", "reason" to "task_assigned", "pull_after_s" to "12"))
        dispatcher.onMessage(mapOf("kind" to "sync_nudge", "reason" to "task_assigned", "pull_after_s" to "3")) // a burst: one pull
        val job = live(PushPullKind.BUNDLE).single()
        assertEquals(12_000L, spec(job).initialDelay)
        assertEquals(NetworkType.CONNECTED, job.constraints.requiredNetworkType)
        run(job)
        assertEquals(listOf(PushPullKind.BUNDLE), pulls)
        assertTrue(uploads.isEmpty())
        // No upload job was queued either.
        assertTrue(wm.getWorkInfosByTag(WorkManagerSyncScheduler.TAG).get().isEmpty())
        assertTrue(live(PushPullKind.BUNDLE).isEmpty())
    }

    /** Checker: a push landing while a pull runs (it may have fetched before the change) queues exactly one more. */
    @Test fun aPushDuringARunningPullQueuesOneMoreAndAWaitingPullCoversIt() {
        assertEquals(androidx.work.ExistingWorkPolicy.KEEP, PushPullScheduler.policyFor(emptyList()))
        assertEquals(androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE, PushPullScheduler.policyFor(listOf(WorkInfo.State.RUNNING)))
        assertEquals(androidx.work.ExistingWorkPolicy.KEEP, PushPullScheduler.policyFor(listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)))
        assertEquals(androidx.work.ExistingWorkPolicy.KEEP, PushPullScheduler.policyFor(listOf(WorkInfo.State.ENQUEUED)))
        assertEquals(androidx.work.ExistingWorkPolicy.KEEP, PushPullScheduler.policyFor(listOf(WorkInfo.State.SUCCEEDED)))
    }

    @Test fun configAndBundlePullsAreSeparateJobs() {
        scheduler.schedule(PushPullKind.CONFIG, 5_000)
        scheduler.schedule(PushPullKind.BUNDLE, 5_000)
        run(live(PushPullKind.CONFIG).single())
        run(live(PushPullKind.BUNDLE).single())
        assertEquals(setOf(PushPullKind.CONFIG, PushPullKind.BUNDLE), pulls.toSet())
        assertEquals(2, pulls.size)
    }

    @Test fun openingTheTaskNotificationPullsAtOnceInsteadOfWaitingOutTheSpread() {
        scheduler.schedule(PushPullKind.BUNDLE, 20_000)
        scheduler.schedule(PushPullKind.BUNDLE, 0, now = true)
        val job = live(PushPullKind.BUNDLE).single()
        assertEquals(0L, spec(job).initialDelay)
        run(job)
        assertEquals(listOf(PushPullKind.BUNDLE), pulls)
    }

    @Test fun anAppliedPullTellsTheScreensToReload() = runTest {
        applied = true
        val seen = async(start = CoroutineStart.UNDISPATCHED) { PushRuntime.pulled.first() }
        scheduler.schedule(PushPullKind.BUNDLE, 0)
        run(live(PushPullKind.BUNDLE).single())
        assertEquals(PushPullKind.BUNDLE, seen.await())
    }

    @Test fun aFailedPullIsNotRetriedByThePushJob() {
        val failing = AronWorkerFactory({ error("no upload") }, { WorkManagerSyncScheduler({ wm }) }, { PushPull { throw IllegalStateException("db") } })
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(failing).build())
        scheduler.schedule(PushPullKind.CONFIG, 0)
        run(live(PushPullKind.CONFIG).single())
        assertEquals(WorkInfo.State.SUCCEEDED, all(PushPullKind.CONFIG).single().state)
    }

    /** F-SYS-073: an ordinary config pull waiting out its 120 s spread never holds back an urgent one. */
    @Test fun anUrgentConfigPullDoesNotWaitBehindAnOrdinaryOne() {
        val dispatcher = PushDispatcher({ }, { k, ms -> scheduler.schedule(k, ms) }, Random(1))
        dispatcher.onMessage(mapOf("kind" to "config_pull", "pull_after_s" to "110"))
        dispatcher.onMessage(mapOf("kind" to "config_pull", "urgent" to "true", "pull_after_s" to "5"))
        val urgent = live(PushPullKind.CONFIG_URGENT).single()
        assertEquals(5_000L, spec(urgent).initialDelay)
        assertEquals(110_000L, spec(live(PushPullKind.CONFIG).single()).initialDelay)
        run(urgent)
        assertEquals(listOf(PushPullKind.CONFIG_URGENT), pulls)
    }

    /**
     * F-SYS-073 acceptance: no FCM message ever triggers an upload. Random data messages (every known kind and key, hostile
     * values, kinds that sound like an upload) go through the dispatcher and every job they make is run: the sync runner is
     * never called and no upload job is queued.
     */
    @Test fun noPushMessageEverTriggersAnUpload() {
        val dispatcher = PushDispatcher({ }, { k, ms -> scheduler.schedule(k, ms) }, Random(5))
        val r = Random(11)
        val kinds = listOf("sync_nudge", "announcement", "config_pull", "bundle_pull", "upload", "sync_now", "batch", "", "kill_switch")
        val values = listOf("true", "false", "0", "-1", "20", "99999", "task_assigned", "upload", "", "x")
        repeat(300) {
            val data = buildMap {
                if (r.nextBoolean()) put("kind", kinds.random(r)) else put("type", listOf("cfg", "upload", "sync").random(r))
                listOf("reason", "urgent", "pull_after_s", "title_en", "body_en", "version").forEach { k -> if (r.nextBoolean()) put(k, values.random(r)) }
            }
            dispatcher.onMessage(data)
            PushPullKind.entries.forEach { kind -> live(kind).forEach { run(it) } }
        }
        assertTrue(pulls.isNotEmpty())
        assertTrue(uploads.isEmpty())
        assertTrue(wm.getWorkInfosByTag(WorkManagerSyncScheduler.TAG).get().isEmpty())
    }
}
