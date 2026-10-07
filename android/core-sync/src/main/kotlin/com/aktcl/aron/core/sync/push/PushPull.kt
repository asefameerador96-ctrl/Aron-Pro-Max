package com.aktcl.aron.core.sync.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aktcl.aron.core.sync.BundleDownloaders
import com.aktcl.aron.core.sync.BundleOutcome
import com.aktcl.aron.core.sync.ConfigCheckResult
import com.aktcl.aron.core.sync.ResumeConfigCheck
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import java.util.concurrent.TimeUnit

/** One pull after a push, for the user signed in now. It reads only; it never builds or sends a batch. */
fun interface PushPull {
    /** Returns true when something new was applied. */
    suspend fun pull(kind: PushPullKind): Boolean
}

/**
 * Production pull: the signed-in user's bundle delta (F-SYS-007) or config delta (F-SYS-092 without the resume gap).
 * Nobody signed in: nothing (the token is the server's only address, and it follows the last user who registered it).
 */
class SessionPushPull(
    private val activeUserId: () -> Long?,
    private val bundles: BundleDownloaders,
    private val config: ResumeConfigCheck,
) : PushPull {
    override suspend fun pull(kind: PushPullKind): Boolean {
        val userId = activeUserId() ?: return false
        return when (kind) {
            PushPullKind.BUNDLE -> bundles.of(userId).refreshDelta().outcome == BundleOutcome.APPLIED
            PushPullKind.CONFIG -> config.pullAfterPush(userId) == ConfigCheckResult.APPLIED
        }
    }
}

/**
 * Schedules the pull a push asked for (N-038): one WorkManager job per kind, after the push's delay, only with a network.
 * A pull already waiting covers a later push of the same kind ([policyFor]): a burst of pushes costs one request. A push
 * that is lost loses nothing: the next sync run sees the newer bundle version and pulls anyway (s4.10).
 */
class PushPullScheduler(private val workManager: () -> WorkManager) {
    fun schedule(kind: PushPullKind, delayMs: Long, now: Boolean = false) {
        val builder = OneTimeWorkRequestBuilder<PushPullWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString(KEY_KIND, kind.wire).build())
            .addTag(TAG)
        if (delayMs > 0) builder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        // The rep opened the task notification: pull at once instead of waiting out the spread.
        val policy = if (now) ExistingWorkPolicy.REPLACE else policyFor(states(kind))
        workManager().enqueueUniqueWork(name(kind), policy, builder.build())
    }

    private fun states(kind: PushPullKind): List<WorkInfo.State> =
        try { workManager().getWorkInfosForUniqueWork(name(kind)).get().map { it.state } } catch (_: Exception) { emptyList() }

    companion object {
        const val TAG = "aron-push"
        const val KEY_KIND = "kind"
        fun name(kind: PushPullKind) = "aron-push-${kind.wire}"

        /**
         * A pull still waiting covers a new push (KEEP: a burst costs one request). A pull already running may have fetched
         * before the new change was made, so the new push queues one more after it (APPEND_OR_REPLACE), at most one.
         */
        fun policyFor(states: List<WorkInfo.State>): ExistingWorkPolicy = when {
            states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> ExistingWorkPolicy.KEEP
            states.any { it == WorkInfo.State.RUNNING } -> ExistingWorkPolicy.APPEND_OR_REPLACE
            else -> ExistingWorkPolicy.KEEP
        }
    }
}

/** The pull job. A failure is not retried: the next sync run or app resume covers it, so a push never adds a loop. */
class PushPullWorker(context: Context, params: WorkerParameters, private val pull: PushPull) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind = PushPullKind.entries.firstOrNull { it.wire == inputData.getString(PushPullScheduler.KEY_KIND) } ?: return Result.success()
        val applied = try {
            pull.pull(kind)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (applied) PushRuntime.pulledNow(kind)
        return Result.success()
    }
}

/** Process-wide link between the FCM service, the app shell and the screens (set in Application.onCreate). */
object PushRuntime {
    /** Handles messages and tokens; null until the shell is installed (then a message is dropped: the next sync covers it). */
    @Volatile var handler: PushHandler? = null

    private val _pulled = MutableSharedFlow<PushPullKind>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A pull applied something new: screens that show tasks or prices reload from the database. */
    val pulled: SharedFlow<PushPullKind> = _pulled.asSharedFlow()

    internal fun pulledNow(kind: PushPullKind) { _pulled.tryEmit(kind) }
}

/** What the FCM service hands over; [com.aktcl.aron.core.sync.shell.PushShell] in the apps. */
interface PushHandler {
    fun onMessage(data: Map<String, String>)
    fun onNewToken(token: String)
}
