package com.aktcl.aron.core.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The photo upload job (docs/24 s5.4 `aron-media`). It is its own unique work, never part of `aron-sync`, so photos never
 * delay records. Wi-Fi first: the main job waits for an unmetered network; a CONNECTED job exists only when the rep turned
 * Wi-Fi-only off, or from the moment an evidence photo may fall back to mobile data. No polling: while photos wait for
 * their record's ack, one re-check is scheduled [RECHECK_MIN] minutes out (and android-core's sync can trigger at once).
 */
class MediaWorkScheduler(
    private val workManager: WorkManager,
    private val wifiOnly: () -> Boolean,
    private val nowMs: () -> Long,
    private val config: () -> MediaConfig = { MediaConfig() },
) {

    /** Call after a sync run acked records, at app start and when the Wi-Fi-only switch changes. */
    fun requestUpload() {
        workManager.enqueueUniqueWork(WORK_WIFI, ExistingWorkPolicy.KEEP, request(NetworkType.UNMETERED, 0))
        if (!wifiOnly()) workManager.enqueueUniqueWork(WORK_ANY, ExistingWorkPolicy.KEEP, request(NetworkType.CONNECTED, 0))
    }

    /**
     * Call when a photo is attached to its record (and for every attached photo at app start). An evidence photo arms its
     * mobile-data fallback job here, because without Wi-Fi no run would ever happen to arm it.
     */
    fun photoQueued(item: MediaItem) {
        requestUpload()
        val h = config().evidenceMobileFallbackH
        if (item.evidence && wifiOnly() && h > 0) armFallback(item.createdAtMs + h * MediaUploader.HOUR_MS)
    }

    /**
     * Follow-ups after a run: the evidence fallback moment, and ONE re-check while photos wait for their records.
     * [runningAs] is the unique-work name of the job calling this, if it is a re-check: the next re-check then goes to the
     * other slot of the pair, so exactly one re-check is ever queued behind a running one (never a growing chain).
     */
    fun afterRun(report: MediaRunReport, runningAs: String? = null) {
        report.evidenceFallbackAtMs?.let(::armFallback)
        if (report.waitingForRecord > 0) {
            val network = if (!wifiOnly() || report.evidenceDueOnMobile) NetworkType.CONNECTED else NetworkType.UNMETERED
            val base = "$WORK_RECHECK-" + if (network == NetworkType.CONNECTED) "any" else "wifi"
            val name = if (runningAs == "$base-1") "$base-2" else "$base-1"
            workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request(network, TimeUnit.MINUTES.toMillis(RECHECK_MIN), name))
        }
    }

    /**
     * One CONNECTED job per hour of fallback time, kept if already there: a run never cancels itself or another
     * photo's fallback, and a day of evidence photos needs at most a few jobs.
     */
    private fun armFallback(atMs: Long) {
        val bucket = Math.floorDiv(atMs + HOUR_MS - 1, HOUR_MS) // round up: never before the photo's moment
        workManager.enqueueUniqueWork("$WORK_FALLBACK-$bucket", ExistingWorkPolicy.KEEP, request(NetworkType.CONNECTED, maxOf(0L, bucket * HOUR_MS - nowMs())))
    }

    private fun request(network: NetworkType, delayMs: Long, name: String? = null) = OneTimeWorkRequestBuilder<MediaWorker>()
        .apply { if (name != null) addTag(NAME_TAG + name) }
        .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
        .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
        .addTag(TAG)
        .build()

    companion object {
        const val WORK_WIFI = "aron-media"
        const val WORK_ANY = "aron-media-any"
        const val WORK_FALLBACK = "aron-media-fallback"
        const val WORK_RECHECK = "aron-media-recheck"
        const val TAG = "aron-media"
        const val RECHECK_MIN = 15L
        /** Tag prefix carrying a re-check's own unique-work name, so the running job knows its slot. */
        const val NAME_TAG = "aron-media-name:"
        private const val HOUR_MS = 3_600_000L
    }
}

/**
 * What the app shell installs once (Application.onCreate): every user with a media queue on this phone, and how to build
 * that user's uploader. Every user's photos upload, whoever is signed in (docs/24 s5.3); logout never stops them.
 */
object MediaRuntime {
    class Wiring(
        val users: suspend () -> List<Long>,
        val uploader: suspend (userId: Long) -> MediaUploader?,
        val scheduler: () -> MediaWorkScheduler,
    )

    @Volatile var wiring: Wiring? = null
}

class MediaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val w = MediaRuntime.wiring ?: return Result.success() // the app has not wired media yet: nothing to upload
        val network = networkKind(applicationContext)
        var retry = false
        var merged = MediaRunReport()
        for (userId in w.users()) {
            // One user's problem (a locked database, a full disk) never stops the other users' photos.
            val report = try {
                w.uploader(userId)?.run(network) ?: continue
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (_: Exception) {
                retry = true
                continue
            }
            merged = merged.copy(
                waitingForRecord = merged.waitingForRecord + report.waitingForRecord,
                evidenceDueOnMobile = merged.evidenceDueOnMobile || report.evidenceDueOnMobile,
                evidenceFallbackAtMs = listOfNotNull(merged.evidenceFallbackAtMs, report.evidenceFallbackAtMs).minOrNull(),
            )
            retry = retry || report.needsRetry
        }
        // One follow-up for all users of the phone, not one per user.
        w.scheduler().afterRun(merged, runningAs = tags.firstOrNull { it.startsWith(MediaWorkScheduler.NAME_TAG) }?.removePrefix(MediaWorkScheduler.NAME_TAG))
        return if (retry && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    companion object {
        const val MAX_ATTEMPTS = 8

        fun networkKind(context: Context): NetworkKind {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkKind.NONE
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkKind.NONE
            // A captive portal (hotel or shop Wi-Fi with a login page) is not a network for uploads.
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return NetworkKind.NONE
            return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) NetworkKind.UNMETERED else NetworkKind.METERED
        }
    }
}
