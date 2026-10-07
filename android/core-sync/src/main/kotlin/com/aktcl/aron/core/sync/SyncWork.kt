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
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.session.SessionComponents
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Runs one upload of a user's outbox; [SessionSyncRunner] in production, a fake in tests. */
interface SyncRunner {
    suspend fun run(userId: Long, trigger: SyncTrigger): SyncReport
}

/** Production runner: the user's own database, upload grant, device id and trusted-time anchors. */
class SessionSyncRunner(private val databases: UserDatabases, private val components: SessionComponents) : SyncRunner {
    override suspend fun run(userId: Long, trigger: SyncTrigger): SyncReport = SyncEngine(
        userId = userId,
        db = databases.of(userId),
        api = SyncBatchApi(components.apiClient, components.proofSigner),
        auth = SessionUploadAuth(components.session),
        deviceUuid = { components.deviceIdentity.deviceUuid },
        appVersion = components.appVersion,
        clock = components.clock,
        timeAnchors = { components.trustedClock.recentAnchors().map { TimeAnchorDto(it.bootCount, SyncEngine.iso(it.serverTimeMs), it.elapsedMs) } },
    ).run(trigger)
}

/**
 * WorkManager scheduling of uploads (docs/24 s4.7, s5.4; F-SYS-011). Every job needs a network (`CONNECTED`); nothing polls:
 * - a save asks for one job 5 s later (`cfg.sync.debounce_s`; a burst of saves shares it);
 * - Sales Submit and the Sync button run at once (expedited);
 * - check-out waits a random 0 to 90 s (`cfg.sync.checkout_jitter_s`) so the 17:00 wave is spread;
 * - after a failed send one expedited job waits for the network (API 31+; below that a plain job, never a foreground service);
 * - `aron-sync-periodic` (15 min, battery not low) exists only while rows are pending and is cancelled when the outbox is empty.
 */
class WorkManagerSyncScheduler(
    private val workManager: () -> WorkManager,
    private val random: Random = Random.Default,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val debounceS: Long = 5,
    private val checkoutJitterS: Int = 90,
    private val periodicMin: Long = 15,
) : SyncScheduler {

    override fun requestSync(userId: Long, trigger: SyncTrigger) {
        when (trigger) {
            SyncTrigger.DAY_SUBMIT, SyncTrigger.MANUAL ->
                enqueue(userId, trigger, mainName(userId), ExistingWorkPolicy.REPLACE, delayMs = 0, expedited = true)
            SyncTrigger.WRITE_DEBOUNCE ->
                enqueue(userId, trigger, mainName(userId), ExistingWorkPolicy.KEEP, delayMs = debounceS * 1000, expedited = false)
            SyncTrigger.CHECKOUT ->
                enqueue(userId, trigger, mainName(userId), ExistingWorkPolicy.KEEP, delayMs = random.nextLong(0, checkoutJitterS * 1000L + 1), expedited = false)
            else -> enqueue(userId, trigger, mainName(userId), ExistingWorkPolicy.KEEP, delayMs = 0, expedited = false)
        }
        ensurePeriodic(userId)
    }

    /** What a finished run leaves scheduled. [ranAs] is the unique name of the job that ran (it must not replace itself). */
    fun afterRun(userId: Long, report: SyncReport, ranAs: String) {
        if (report.unsent == 0) {
            workManager().cancelUniqueWork(periodicName(userId))
            return
        }
        ensurePeriodic(userId)
        val next = if (ranAs == retryName(userId)) mainName(userId) else retryName(userId)
        when (report.stop) {
            SyncStop.OFFLINE -> enqueue(userId, SyncTrigger.WORKMANAGER_CONNECTIVITY, next, ExistingWorkPolicy.KEEP, 0, expedited = true)
            SyncStop.RETRY_LATER -> enqueue(userId, SyncTrigger.WORKMANAGER_CONNECTIVITY, next, ExistingWorkPolicy.KEEP, report.retryAfterMs ?: 60_000, expedited = false)
            SyncStop.RUN_LIMIT -> enqueue(userId, SyncTrigger.WORKMANAGER_CONNECTIVITY, next, ExistingWorkPolicy.KEEP, 0, expedited = false)
            // Nothing a job can fix now (sign-in, device state, update, bad answer): the periodic job keeps the rows in view.
            SyncStop.DRAINED, SyncStop.AUTH_REQUIRED, SyncStop.BLOCKED, SyncStop.UPDATE_REQUIRED, SyncStop.NO_DEVICE, SyncStop.FAILED -> Unit
        }
    }

    private fun enqueue(userId: Long, trigger: SyncTrigger, name: String, policy: ExistingWorkPolicy, delayMs: Long, expedited: Boolean) {
        val builder = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(input(userId, trigger, name))
            .addTag(TAG)
        // Expedited work cannot be delayed, and below API 31 it would run as a foreground service (docs/24 s5.4 forbids one).
        if (expedited && delayMs == 0L && sdkInt >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        } else if (delayMs > 0) {
            builder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        }
        workManager().enqueueUniqueWork(name, policy, builder.build())
    }

    private fun ensurePeriodic(userId: Long) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(periodicMin, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setInputData(input(userId, SyncTrigger.PERIODIC, periodicName(userId)))
            .addTag(TAG)
            .build()
        workManager().enqueueUniquePeriodicWork(periodicName(userId), ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val TAG = "aron-sync"
        const val KEY_USER = "user_id"
        const val KEY_TRIGGER = "trigger"
        const val KEY_NAME = "unique_name"

        fun mainName(userId: Long) = "aron-sync-u$userId"
        fun retryName(userId: Long) = "aron-sync-retry-u$userId"
        fun periodicName(userId: Long) = "aron-sync-periodic-u$userId"

        private fun input(userId: Long, trigger: SyncTrigger, name: String): Data =
            Data.Builder().putLong(KEY_USER, userId).putString(KEY_TRIGGER, trigger.wire).putString(KEY_NAME, name).build()
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
        val report = try {
            runner.run(userId, trigger)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Rows are safe in Room (a batch in flight is resent as it was); WorkManager retries with its backoff.
            return Result.retry()
        }
        scheduler.afterRun(userId, report, name)
        return Result.success()
    }
}

/** Builds [SyncWorker] with its dependencies; the app registers it through `Configuration.Provider`. */
class AronWorkerFactory(private val runner: () -> SyncRunner, private val scheduler: () -> WorkManagerSyncScheduler) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == SyncWorker::class.java.name) SyncWorker(appContext, workerParameters, runner(), scheduler()) else null
}
