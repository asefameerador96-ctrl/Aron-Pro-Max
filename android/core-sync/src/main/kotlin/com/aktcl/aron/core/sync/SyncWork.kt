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

    /** F-SYS-047: the other users with a database on this phone (a restore concerns their acked rows too). */
    fun otherUsers(userId: Long): List<Long> = emptyList()
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
    /** After every run (the media shell asks for a photo upload once records were acked); must never throw. */
    private val afterRun: (userId: Long, report: SyncReport) -> Unit = { _, _ -> },
    /** F-SYS-053: a config delta when the batch answer's X-Config-Version is newer than the held one; null in tests. */
    private val config: ResumeConfigCheck? = null,
    /** F-SYS-024: buffered events become one outbox row before the batch is built (they ride this upload). */
    private val activityLog: ActivityLog? = null,
    /** F-SYS-081: the device's daily telemetry (one per process); null in tests that do not cover it. */
    private val telemetry: DeviceTelemetry? = null,
) : SyncRunner {
    override suspend fun run(userId: Long, trigger: SyncTrigger): SyncReport {
        // A queued run of a user wiped since (TSO logout) must not create an empty database and bring the user back.
        if (!databases.exists(userId)) return SyncReport(SyncStop.DRAINED, 0, 0, 0, 0, 0, 0, code = "no_database")
        val db = databases.of(userId)
        try { beforeBatch(userId, db, trigger) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        activityLog?.flush(userId)
        val started = components.clock.elapsedRealtimeMs()
        try {
            telemetry?.sample()
            telemetry?.noteGps(userId) { date -> DeviceTelemetry.gpsFixes(db, date) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        val report = engine(userId, db).run(trigger)
        // Only after a run the server answered in full: never straight after a hold, 429, 503 or a refusal (s4.10, s4.7).
        // The delta goes out under the FULL grant of the signed-in user: a run for another user on a shared phone (A's rows
        // uploading while B is signed in) must never pull B's day into A's database (F-SYS-052 checker).
        val active = (components.session.settled() as? com.aktcl.aron.core.session.SessionState.Active)?.user?.userId
        if (refreshesBundle(userId, active, report.stop)) {
            try { bundles?.of(userId)?.refreshIfServerNewer() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
        if (config != null && refreshesBundle(userId, active, report.stop)) {
            try {
                val meta = db.referenceDao()
                val held = meta.meta(SyncEngine.KEY_CONFIG_VERSION)?.toLongOrNull()
                val server = meta.meta(SyncEngine.KEY_CONFIG_VERSION_SERVER)?.toLongOrNull()
                if (pullsConfig(held, server)) config.pullAfterPush(userId)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
        dailyPurge(db)
        components.session.noteTimePassing() // F-SYS-052: proven uptime for the 7-day offline window
        try { afterRun(userId, report) } catch (_: Exception) { }
        // After the pull and the media hand-off, so their bytes are billed to the network they used.
        try {
            telemetry?.noteWake(components.clock.elapsedRealtimeMs() - started) // the job's wake lock (WorkManager's)
            telemetry?.sample()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        return report
    }

    /**
     * F-SYS-028: once per business date (trusted time), after a run, whatever its outcome (the purge only touches whole
     * acked families). `cfg.app.local_history_days` (default 7, 1..30) and `cfg.app.outbox_keep_days` (default 3, 1..14), the registry values of db V0055. Never throws.
     */
    private suspend fun dailyPurge(db: com.aktcl.aron.core.database.AronDatabase) {
        try {
            val clock = components.trustedClock
            if (clock.clockOffsetMs() == null) return // no server-time anchor: a clock set forward must never wipe history
            val today = clock.businessDate().toString()
            val meta = db.referenceDao()
            if (meta.meta(KEY_PURGE_DATE) == today) return
            // F-SYS-080 (D-517): after a server restore nothing is purged until a clean digest (at most 8 days).
            if (purgeHeldByDigest(meta.meta(SyncEngine.KEY_DIGEST_HOLD), clock.nowMs())) return
            val nowIso = SyncEngine.iso(clock.nowMs())
            val ref = com.aktcl.aron.core.database.repo.ReferenceRepository(db)
            suspend fun days(key: String, default: Int) = configInt(ref.config(key, nowIso)) ?: default
            val historyDays = days(com.aktcl.aron.core.database.repo.LocalPurge.CFG_HISTORY_DAYS, com.aktcl.aron.core.database.repo.LocalPurge.DEFAULT_HISTORY_DAYS).coerceIn(1, 30)
            // The digest never counts a date this purge may touch: the low-water mark is raised before the purge runs.
            val cutoff = java.time.LocalDate.parse(today).minusDays(historyDays.toLong()).toString()
            if (cutoff > (meta.meta(SyncEngine.KEY_PURGE_CUTOFF) ?: "")) meta.putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity(SyncEngine.KEY_PURGE_CUTOFF, cutoff))
            com.aktcl.aron.core.database.repo.LocalPurge(db).purge(
                today, nowIso,
                historyDays = historyDays,
                keepDays = days(com.aktcl.aron.core.database.repo.LocalPurge.CFG_KEEP_DAYS, com.aktcl.aron.core.database.repo.LocalPurge.DEFAULT_KEEP_DAYS).coerceIn(1, 14),
            )
            meta.putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity(KEY_PURGE_DATE, today))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
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
        recordSigner = components.proofSigner, // F-SYS-072: the same enrolled key as X-Device-Proof
        telemetry = telemetry?.forBatch { key -> com.aktcl.aron.core.database.repo.ReferenceRepository(db).config(key, SyncEngine.iso(components.clock.nowMs())) },
        generationApi = SyncGenerationApi(components.apiClient), // F-SYS-047
        generationHint = { com.aktcl.aron.core.network.ServerGenerationHint.latest },
        digestApi = SyncDigestApi(components.apiClient), // F-SYS-080
    )

    companion object {
        /** F-SYS-028: the business date of the last purge. */
        const val KEY_PURGE_DATE = "purge.last_business_date"

        /** A config value (JSON text) as an int: `7` or `"7"`; anything else is null (the default applies). */
        fun configInt(json: String?): Int? = json?.let {
            runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.toIntOrNull() }.getOrNull()
        }
        /** The server said its config is newer than what the phone holds (a portal change since the last delta). */
        fun pullsConfig(held: Long?, server: Long?): Boolean = held != null && server != null && server > held

        /** A bundle delta after a run: only for the signed-in user, and only after a run the server answered in full. */
        fun refreshesBundle(userId: Long, activeUserId: Long?, stop: SyncStop): Boolean =
            userId == activeUserId && (stop == SyncStop.DRAINED || stop == SyncStop.RUN_LIMIT)
    }

    override suspend fun unsent(userId: Long): Int = if (!databases.exists(userId)) 0 else databases.of(userId).outboxDao().unsentCount()

    override fun otherUsers(userId: Long): List<Long> = runCatching { databases.knownUserIds() }.getOrDefault(emptyList()).filter { it != userId }
}

/**
 * WorkManager scheduling of uploads (docs/24 s4.7, s5.4; F-SYS-011). Every job needs a network (`CONNECTED`); nothing polls:
 * - a save asks for one job 5 s later (`cfg.sync.debounce_s`; a burst of saves shares it, AC-14);
 * - Sales Submit and the Sync button run at once (expedited);
 * - check-out and Sales Submit go at once (no debounce), except in the minutes just after the 17:00
 *   gate opens ([CheckoutGate], F-SYS-079, doc 17 T7, D-505): then they wait a random 0 to 90 s
 *   (`cfg.sync.checkout_jitter_s` from the user's bundle, at most 120) so 8,500 phones do not fire in the same second. Without a gate (tests,
 *   older wiring) check-out is always jittered;
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
    /** `cfg.sync.checkout_jitter_s`, read at each request (the shells pass [com.aktcl.aron.core.sync.device.DayConfig]). */
    private val checkoutJitterS: () -> Int = { 90 },
    private val checkoutGate: CheckoutGate? = null,
    /** `cfg.sync.resync_jitter_s` (default 900), read at each `resync` request. */
    private val resyncJitterS: () -> Int = { 900 },
    /** Called on every request (every save, check-out, submit): F-SYS-081 samples there, offline too. Must not block. */
    private val onRequest: () -> Unit = {},
) : SyncScheduler {

    override fun requestSync(userId: Long, trigger: SyncTrigger) {
        try { onRequest() } catch (_: Exception) { }
        // The local state (checked out, submitted_local) is already set at the tap; only the upload waits (doc 17 T7).
        val gateWave = (trigger == SyncTrigger.CHECKOUT || trigger == SyncTrigger.DAY_SUBMIT) &&
            (checkoutGate?.let { runCatching { it.justOpened() }.getOrDefault(false) } ?: (trigger == SyncTrigger.CHECKOUT))
        val jitterMs = if (gateWave) random.nextLong(0, (runCatching { checkoutJitterS() }.getOrDefault(90)).coerceIn(0, MAX_CHECKOUT_JITTER_S) * 1000L + 1) else 0L
        when {
            // The jittered upload runs under its own name: a pending debounce or a running upload of earlier rows on the
            // main name is neither replaced nor delayed (doc 17 T7: rows captured earlier are never delayed). Sales Submit
            // ignores a server hold (s4.7), also while it waits out the jitter.
            gateWave && trigger == SyncTrigger.DAY_SUBMIT ->
                enqueue(userId, trigger, gateName(userId), ExistingWorkPolicy.REPLACE, jitterMs, expedited = false, failures = 0)
            gateWave ->
                enqueue(userId, trigger, gateName(userId), ExistingWorkPolicy.KEEP, held(userId, jitterMs), expedited = false, failures = 0)
            trigger == SyncTrigger.DAY_SUBMIT || trigger == SyncTrigger.MANUAL ->
                enqueue(userId, trigger, mainName(userId), ExistingWorkPolicy.REPLACE, delayMs = 0, expedited = true, failures = 0)
            // Off the wave, check-out goes at once under the "now" name, so a backoff queued on the main name cannot hold it.
            trigger == SyncTrigger.CHECKOUT ->
                enqueue(userId, trigger, nowName(userId), ExistingWorkPolicy.KEEP, held(userId, 0), expedited = false, failures = 0)
            trigger == SyncTrigger.WRITE_DEBOUNCE ->
                enqueue(userId, trigger, mainName(userId), ExistingWorkPolicy.KEEP, held(userId, debounceS * 1000), expedited = false, failures = 0)
        }
        when (trigger) {
            SyncTrigger.DAY_SUBMIT, SyncTrigger.MANUAL, SyncTrigger.WRITE_DEBOUNCE, SyncTrigger.CHECKOUT -> Unit
            // A new signal (network back, app in front) runs under its own name, so a long backoff queued after earlier
            // failures cannot swallow it; the engine's per-user lock keeps runs from overlapping.
            SyncTrigger.CONNECTIVITY, SyncTrigger.FOREGROUND ->
                enqueue(userId, trigger, nowName(userId), ExistingWorkPolicy.KEEP, held(userId, 0), expedited = false, failures = 0)
            // F-SYS-047: after a server restore, spread over 0 to cfg.sync.resync_jitter_s so the fleet does not arrive at once.
            SyncTrigger.RESYNC -> {
                val jitterMs = random.nextLong(0, runCatching { resyncJitterS() }.getOrDefault(900).coerceIn(0, 3_600) * 1000L + 1)
                enqueue(userId, trigger, resyncName(userId), ExistingWorkPolicy.KEEP, held(userId, jitterMs), expedited = false, failures = 0)
            }
            else -> enqueue(userId, trigger, mainName(userId), ExistingWorkPolicy.KEEP, held(userId, 0), expedited = false, failures = 0)
        }
        ensurePeriodic(userId)
    }

    /**
     * What a finished run leaves scheduled. [ranAs] is the unique name of the job that ran (it must not replace itself);
     * [failures] is how many runs in a row failed before this one.
     */
    fun afterRun(userId: Long, report: SyncReport, ranAs: String, failures: Int = 0) {
        // F-SYS-047: a new server generation: one re-send run, jittered 0 to cfg.sync.resync_jitter_s (KEEP: the first wins).
        if (report.resyncRequested) requestSync(userId, SyncTrigger.RESYNC)
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
        /** F-SYS-079: a check-out or Sales Submit upload jittered at the 17:00 gate. */
        fun gateName(userId: Long) = "aron-sync-gate-u$userId"
        /** `cfg.sync.checkout_jitter_s` bound of doc 17 T7 (the registry allows up to 600; T7 caps the wave delay at 120). */
        const val MAX_CHECKOUT_JITTER_S = 120
        fun retryName(userId: Long) = "aron-sync-retry-u$userId"
        fun periodicName(userId: Long) = "aron-sync-periodic-u$userId"
        /** F-SYS-047: the jittered re-send run after a server restore. */
        fun resyncName(userId: Long) = "aron-sync-resync-u$userId"

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
        // F-SYS-047: a restore concerns every user's acked rows on a shared phone, also one who signed out with nothing
        // left to upload (the upload grant survives logout): each gets its own jittered resync run.
        if (report.resyncRequested) runCatching { runner.otherUsers(userId).forEach { scheduler.requestSync(it, SyncTrigger.RESYNC) } }
        return Result.success()
    }

    private companion object {
        const val MAX_RETRIES = 5
    }
}


/**
 * Builds [SyncWorker] and the push pull job ([com.aktcl.aron.core.sync.push.PushPullWorker], N-038) with their
 * dependencies; the app registers it through `Configuration.Provider`. The pull job never gets the [SyncRunner]: a push
 * cannot cause an upload (docs/24 s4.7).
 */
class AronWorkerFactory(
    private val runner: () -> SyncRunner,
    private val scheduler: () -> WorkManagerSyncScheduler,
    private val pushPull: (() -> com.aktcl.aron.core.sync.push.PushPull)? = null,
    /** F-SR-020: the AV/KV asset download ([ContentShell.prefetch]); null in the AMO and TSO shells. */
    private val contentPrefetch: (suspend (userId: Long) -> Unit)? = null,
) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = when (workerClassName) {
        SyncWorker::class.java.name -> SyncWorker(appContext, workerParameters, runner(), scheduler())
        com.aktcl.aron.core.sync.push.PushPullWorker::class.java.name ->
            com.aktcl.aron.core.sync.push.PushPullWorker(appContext, workerParameters, pushPull?.invoke() ?: com.aktcl.aron.core.sync.push.PushPull { false })
        ContentPrefetchWorker::class.java.name -> ContentPrefetchWorker(appContext, workerParameters, contentPrefetch ?: { _ -> })
        else -> null
    }
}

/**
 * F-SYS-079 (doc 17 T7, D-505): true in the first [windowMinutes] after the check-out gate `cfg.day.checkout_earliest_time`
 * (17:00 Dhaka) opens, when a check-out or Sales Submit is most likely there only because the gate opened. [nowMs] is the
 * trusted clock; spreading load needs no stronger time than that.
 */
fun interface CheckoutGate {
    fun justOpened(): Boolean

    companion object {
        const val WINDOW_MINUTES = 10

        fun dhaka(nowMs: () -> Long, gateMinutes: () -> Int = { 17 * 60 }, windowMinutes: Int = WINDOW_MINUTES) = CheckoutGate {
            val minutes = Math.floorMod(Math.floorDiv(nowMs() + com.aktcl.aron.rules.BusinessDate.DHAKA_OFFSET_MS, 60_000L), 24 * 60L).toInt()
            minutes - runCatching { gateMinutes() }.getOrDefault(17 * 60) in 0 until windowMinutes
        }
    }
}

/**
 * F-SYS-080 (D-517): true while a server restore handled at [hold] (phone ms, `sync.digest_purge_hold`) waits for a clean
 * digest, at most [SyncEngine.DIGEST_HOLD_MAX_MS]; a hold stamped in the future (a clock set back) also holds, for as long.
 */
internal fun purgeHeldByDigest(hold: String?, nowMs: Long): Boolean {
    val at = hold?.toLongOrNull() ?: return false
    return kotlin.math.abs(nowMs - at) < SyncEngine.DIGEST_HOLD_MAX_MS
}
