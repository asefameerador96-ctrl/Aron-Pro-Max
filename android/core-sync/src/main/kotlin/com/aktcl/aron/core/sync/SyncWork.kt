package com.aktcl.aron.core.sync

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.contract.TimeAnchor
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.session.SessionComponents
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Runs one upload of a user's outbox; [SessionSyncRunner] in production, a fake in tests. */
interface SyncRunner {
    suspend fun run(userId: Long, trigger: SyncTrigger): SyncReport

    /** Rows still unsent now (re-read after the run, so a save during the run is never missed); null to trust the report. */
    suspend fun unsent(userId: Long): Int? = null
}

/**
 * When automatic uploads may run again after a server `hold_s` (docs/24 s4.7), on elapsed realtime so a clock change
 * cannot shorten it. [Memory] for tests; [Prefs] in the app.
 */
interface SyncHold {
    fun until(userId: Long): Long
    fun set(userId: Long, untilElapsedMs: Long)

    class Memory : SyncHold {
        private val map = java.util.concurrent.ConcurrentHashMap<Long, Long>()
        override fun until(userId: Long) = map[userId] ?: 0L
        override fun set(userId: Long, untilElapsedMs: Long) { map[userId] = untilElapsedMs }
    }

    class Prefs(context: Context) : SyncHold {
        private val prefs = context.getSharedPreferences("aron-sync-hold", Context.MODE_PRIVATE)
        override fun until(userId: Long) = prefs.getLong("u$userId", 0L)
        override fun set(userId: Long, untilElapsedMs: Long) = prefs.edit().putLong("u$userId", untilElapsedMs).apply()
    }
}

/** Production runner: the user's own database, upload grant, device id and trusted-time anchors. */
class SessionSyncRunner(
    private val databases: UserDatabases,
    private val components: SessionComponents,
    /** Runs before the batch is built (device status and integrity, `DeviceRuntime.beforeBatch`); must never throw. */
    private val beforeBatch: suspend (userId: Long, db: com.aktcl.aron.core.database.AronDatabase, trigger: SyncTrigger) -> Unit = { _, _, _ -> },
    /** Bundle deltas after a run when the server's current bundle is newer (F-SYS-007); null in tests. */
    private val bundles: BundleDownloaders? = null,
) : SyncRunner {
    override suspend fun run(userId: Long, trigger: SyncTrigger): SyncReport {
        val db = databases.of(userId)
        try { beforeBatch(userId, db, trigger) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        val report = engine(userId, db).run(trigger)
        // Only after a run the server answered in full: never straight after a hold, 429, 503 or a refusal (s4.10, s4.7).
        if (report.stop == SyncStop.DRAINED || report.stop == SyncStop.RUN_LIMIT) {
            try { bundles?.of(userId)?.refreshIfServerNewer() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
        return report
    }

    private fun engine(userId: Long, db: com.aktcl.aron.core.database.AronDatabase) = SyncEngine(
        userId = userId,
        db = db,
        api = SyncBatchApi(components.apiClient, components.proofSigner),
        auth = SessionUploadAuth(components.session),
        deviceUuid = { components.deviceIdentity.deviceUuid },
        appVersion = components.appVersion,
        clock = components.clock,
        timeAnchors = { components.trustedClock.recentAnchors().map { TimeAnchor(it.bootCount, SyncEngine.iso(it.serverTimeMs), it.elapsedMs) } },
    )

    override suspend fun unsent(userId: Long): Int = databases.of(userId).outboxDao().unsentCount()
}

/**
 * WorkManager scheduling of uploads (docs/24 s4.7, s5.4; F-SYS-011). Every job needs a network (`CONNECTED`); nothing polls:
 * - a save asks for one job 5 s later (`cfg.sync.debounce_s`; a burst of saves shares it, AC-14);
 * - Sales Submit and the Sync button run at once (expedited);
 * - check-out waits a random 0 to 90 s (`cfg.sync.checkout_jitter_s`) so the 17:00 wave is spread;
 * - after a failed send ONE expedited job waits for the network (API 31+, AC-13); further failures back off (2 s doubling to
 *   300 s, jittered, or the server's Retry-After) and are never expedited;
 * - a server `hold_s` pushes every automatic trigger past the hold; the Sync button and Sales Submit ignore it;
 * - `aron-sync-periodic` (exactly 15 min, battery not low) exists only while rows are pending and is cancelled when the
 *   outbox is empty.
 */
class WorkManagerSyncScheduler(
    private val workManager: () -> WorkManager,
    private val random: Random = Random.Default,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val hold: SyncHold = SyncHold.Memory(),
    private val elapsedMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val policy: SyncPolicy = SyncPolicy(),
    private val debounceS: Long = 5,
    private val checkoutJitterS: Int = 90,
) : SyncScheduler {

    override fun requestSync(userId: Long, trigger: SyncTrigger) {
        when (trigger) {
            SyncTrigger.DAY_SUBMIT, SyncTrigger.MANUAL ->
                enqueue(userId, trigger, mainName(userId), ExistingWorkPolicy.REPLACE, delayMs = 0, expedited = true, failures = 0)
            SyncTrigger.WRITE_DEBOUNCE ->
                enqueue(userId, trigger, mainName(userId), ExistingWorkPolicy.KEEP, held(userId, debounceS * 1000), expedited = false, failures = 0)
            SyncTrigger.CHECKOUT ->
                enqueue(userId, trigger, mainName(userId), ExistingWorkPolicy.KEEP, held(userId, random.nextLong(0, checkoutJitterS * 1000L + 1)), expedited = false, failures = 0)
            // A new signal (network back, app in front) runs under its own name, so a long backoff queued after earlier
            // failures cannot swallow it; the engine's per-user lock keeps runs from overlapping.
            SyncTrigger.CONNECTIVITY, SyncTrigger.FOREGROUND ->
                enqueue(userId, trigger, nowName(userId), ExistingWorkPolicy.KEEP, held(userId, 0), expedited = false, failures = 0)
            else -> enqueue(userId, trigger, mainName(userId), ExistingWorkPolicy.KEEP, held(userId, 0), expedited = false, failures = 0)
        }
        ensurePeriodic(userId)
    }

    /**
     * What a finished run leaves scheduled. [ranAs] is the unique name of the job that ran (it must not replace itself);
     * [failures] is how many runs in a row failed before this one.
     */
    fun afterRun(userId: Long, report: SyncReport, ranAs: String, failures: Int = 0) {
        if (report.unsent == 0) {
            workManager().cancelUniqueWork(periodicName(userId))
            return
        }
        ensurePeriodic(userId)
        val next = if (ranAs == retryName(userId)) mainName(userId) else retryName(userId)
        fun follow(delayMs: Long, expedited: Boolean, failed: Int) =
            enqueue(userId, SyncTrigger.WORKMANAGER_CONNECTIVITY, next, ExistingWorkPolicy.KEEP, delayMs, expedited, failed)
        when (report.stop) {
            SyncStop.OFFLINE, SyncStop.FAILED -> {
                val failed = failures + 1
                // The first job after a failure waits for the network at once; repeated failures back off.
                if (failed == 1 && report.stop == SyncStop.OFFLINE) follow(0, expedited = true, failed)
                else follow(report.retryAfterMs ?: Backoff.delayMs(failed, policy, random), expedited = false, failed)
            }
            SyncStop.RETRY_LATER -> {
                val delay = report.retryAfterMs ?: 60_000L
                // A server hold, a 429/503 Retry-After or a backoff: automatic triggers wait it out too (s4.7).
                hold.set(userId, elapsedMs() + delay)
                follow(delay, expedited = false, failures)
            }
            SyncStop.RUN_LIMIT -> follow(held(userId, 0), expedited = false, 0)
            // Rows were saved while the job ran (its KEEP swallowed their request): one more debounced job.
            SyncStop.DRAINED -> follow(held(userId, debounceS * 1000), expedited = false, 0)
            // Nothing a job can fix now (sign-in, device state, update): the periodic job keeps the rows in view.
            SyncStop.AUTH_REQUIRED, SyncStop.BLOCKED, SyncStop.UPDATE_REQUIRED, SyncStop.NO_DEVICE -> Unit
        }
    }

    /**
     * [delayMs], or longer while a server hold lasts. A remaining hold longer than any hold can be (900 s × 1.2) comes from
     * an earlier boot (elapsed realtime restarted) and is ignored.
     */
    private fun held(userId: Long, delayMs: Long): Long {
        val remaining = hold.until(userId) - elapsedMs()
        return if (remaining in 1..MAX_HOLD_MS) maxOf(delayMs, remaining) else delayMs
    }

    private fun enqueue(userId: Long, trigger: SyncTrigger, name: String, policy: ExistingWorkPolicy, delayMs: Long, expedited: Boolean, failures: Int) {
        val builder = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(input(userId, trigger, name, failures))
            .addTag(TAG)
        // Expedited work cannot be delayed, and below API 31 it would run as a foreground service (docs/24 s5.4 forbids one).
        if (expedited && delayMs <= 0L && sdkInt >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        } else if (delayMs > 0) {
            builder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        }
        workManager().enqueueUniqueWork(name, policy, builder.build())
    }

    private fun ensurePeriodic(userId: Long) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES) // cfg.sync.periodic_min, the Android floor
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setInputData(input(userId, SyncTrigger.PERIODIC, periodicName(userId), 0))
            .addTag(TAG)
            .build()
        workManager().enqueueUniquePeriodicWork(periodicName(userId), ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val TAG = "aron-sync"
        const val KEY_USER = "user_id"
        const val KEY_TRIGGER = "trigger"
        const val KEY_NAME = "unique_name"
        const val KEY_FAILURES = "failures"

        /** The longest hold a server can ask for: `hold_s` ≤ 900 × U(1.0, 1.2), or Retry-After capped at 900 s × 1.2. */
        const val MAX_HOLD_MS = 1_080_000L

        fun mainName(userId: Long) = "aron-sync-u$userId"
        fun nowName(userId: Long) = "aron-sync-now-u$userId"
        fun retryName(userId: Long) = "aron-sync-retry-u$userId"
        fun periodicName(userId: Long) = "aron-sync-periodic-u$userId"

        private fun input(userId: Long, trigger: SyncTrigger, name: String, failures: Int): Data =
            Data.Builder().putLong(KEY_USER, userId).putString(KEY_TRIGGER, trigger.wire).putString(KEY_NAME, name)
                .putInt(KEY_FAILURES, failures).build()
    }
}

/** The upload job: one engine run for one user, then [WorkManagerSyncScheduler.afterRun]. */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val runner: SyncRunner,
    private val scheduler: WorkManagerSyncScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val userId = inputData.getLong(WorkManagerSyncScheduler.KEY_USER, 0)
        if (userId <= 0) return Result.failure()
        val trigger = SyncTrigger.entries.firstOrNull { it.wire == inputData.getString(WorkManagerSyncScheduler.KEY_TRIGGER) } ?: SyncTrigger.PERIODIC
        val name = inputData.getString(WorkManagerSyncScheduler.KEY_NAME) ?: WorkManagerSyncScheduler.mainName(userId)
        val failures = inputData.getInt(WorkManagerSyncScheduler.KEY_FAILURES, 0)
        val report = try {
            runner.run(userId, trigger).let { r -> runner.unsent(userId)?.let { r.copy(unsent = it) } ?: r }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Rows are safe in Room (a batch in flight is resent as it was); WorkManager retries with its backoff, a few
            // times only: after that the periodic job (registered while rows wait) keeps them in view.
            return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
        scheduler.afterRun(userId, report, name, failures)
        return Result.success()
    }

    private companion object {
        const val MAX_RETRIES = 5
    }
}


/** Builds [SyncWorker] with its dependencies; the app registers it through `Configuration.Provider`. */
class AronWorkerFactory(private val runner: () -> SyncRunner, private val scheduler: () -> WorkManagerSyncScheduler) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == SyncWorker::class.java.name) SyncWorker(appContext, workerParameters, runner(), scheduler()) else null
}
