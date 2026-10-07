package com.aktcl.aron.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.ContentItemEntity
import kotlinx.coroutines.launch
import java.io.File

/**
 * AV and KV files of the bundle's `content` (F-SR-020, F-SYS-029): their own bounded LRU cache ([CAP_MB], apart from the
 * SKU thumbnails, so a video never evicts the sale screen's images), downloaded ahead by [ContentPrefetchWorker] and never
 * on the call's path. A screen asks [file]: null means the asset is missing (logged `skipped_missing`, the sale goes on).
 * Each download is checked against the item's sha256. `cfg.content.download_network_policy` `any` allows mobile data;
 * `wifi_only` and `wifi_preferred` wait for an unmetered network (pilot: preferred is treated as Wi-Fi only).
 */
class ContentAssets(private val cache: ImageCache) {

    /** The cached file of [item], or null. Disk only: never the network. */
    suspend fun file(item: ContentItemEntity): File? = cache.get(item.assetUrl, key(item))

    /**
     * Downloads every item valid on [today] or later that is not cached yet, in play-date order. Returns the files now on
     * disk. Never throws; an item that fails is tried again by the next job.
     */
    suspend fun prefetch(db: AronDatabase, today: String, allowMetered: Boolean): Int =
        db.referenceDao().contentFrom(today).count {
            cache.fetch(it.assetUrl, ImageCache.Kind.AV, sha256 = it.sha256, allowMetered = allowMetered, cacheKey = key(it)) != null
        }

    /** Files are keyed by their sha256 (checker): a new version published at the same path is a new file. */
    private fun key(item: ContentItemEntity) = "content:" + item.sha256

    companion object {
        /** Pilot cap of the AV/KV cache (no value in the specs: 50 items of up to cfg.content.max_item_mb 8 MB fit twice over). */
        const val CAP_MB = 120L
        const val CFG_POLICY = "cfg.content.download_network_policy"

        /** True when the policy lets assets come over mobile data. */
        fun allowsMetered(policy: String?): Boolean = policy?.trim()?.trim('"') == "any"
    }
}

/**
 * Queues the asset download of [userId]'s content as one job that WorkManager starts on an unmetered network (or any
 * network when the policy is `any`): no polling, no foreground service. KEEP: a job already waiting is enough.
 */
object ContentPrefetch {
    const val KEY_USER = "user_id"

    /** The constraint is part of the name: a policy change queues its own job instead of being kept out by KEEP. */
    fun name(userId: Long, allowMetered: Boolean) = "aron-content-$userId-" + if (allowMetered) "any" else "wifi"

    fun schedule(workManager: WorkManager, userId: Long, allowMetered: Boolean) {
        if (!allowMetered) workManager.cancelUniqueWork(name(userId, true))
        val request = OneTimeWorkRequestBuilder<ContentPrefetchWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED).setRequiresStorageNotLow(true).build())
            .setInputData(Data.Builder().putLong(KEY_USER, userId).build())
            .build()
        workManager.enqueueUniqueWork(name(userId, allowMetered), ExistingWorkPolicy.KEEP, request)
    }
}

/** The asset download job; [prefetch] is the shell's (user database, trusted business date, policy). */
class ContentPrefetchWorker(
    context: Context,
    params: WorkerParameters,
    private val prefetch: suspend (userId: Long) -> Unit,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val userId = inputData.getLong(ContentPrefetch.KEY_USER, 0)
        if (userId <= 0) return Result.failure()
        try { prefetch(userId) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        return Result.success()
    }
}

/**
 * The SR shell's content wiring (F-SR-020): the AV/KV cache under `cacheDir/content`, the job after each sync run (a new
 * bundle may have brought items) and the job's work. AMO and TSO never construct it.
 */
class ContentShell(
    context: Context,
    private val components: com.aktcl.aron.core.session.SessionComponents,
    private val databases: com.aktcl.aron.core.database.UserDatabases,
) {
    private val app = context.applicationContext

    val assets = ContentAssets(
        ImageCache(
            File(app.cacheDir, "content"),
            okhttp3.OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
            unmetered = {
                val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
                cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
            },
            capBytes = ContentAssets.CAP_MB * 1024L * 1024L,
        ),
    )

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** After a sync run: one waiting job per user (KEEP), constrained by that user's policy; it starts when the network allows. */
    fun afterSync(userId: Long) {
        scope.launch {
            try {
                if (!databases.exists(userId)) return@launch
                ContentPrefetch.schedule(WorkManager.getInstance(app), userId, allowsMetered(databases.of(userId)))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    private suspend fun allowsMetered(db: AronDatabase) = ContentAssets.allowsMetered(
        com.aktcl.aron.core.database.repo.ReferenceRepository(db).config(ContentAssets.CFG_POLICY, SyncEngine.iso(components.trustedClock.nowMs())),
    )

    /** The job's work: the user's items from today's business date (trusted time), by the configured policy. */
    suspend fun prefetch(userId: Long) {
        if (!databases.exists(userId)) return
        val db = databases.of(userId)
        assets.prefetch(db, components.trustedClock.businessDate().toString(), allowsMetered(db))
    }
}
