package com.aktcl.aron.core.sync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import com.aktcl.aron.contract.SyncTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Trigger T3 of docs/24 s4.7 (F-SYS-046): when the network comes back while the process lives, wait 5 s of quiet
 * (`cfg.sync.debounce_s`; a flapping link restarts the wait), check `HEAD /v1/health` (an API answer, not a captive portal),
 * then ask for an upload for every user on this phone who has rows waiting. Nothing polls: it only reacts to the system's
 * network callback, and when the process is dead WorkManager's `CONNECTED` jobs (T4) do the same work.
 *
 * D-NIGHT-SYNC-01: a network that only becomes validated later (a mobile link still attaching when `onAvailable` fired,
 * so the health check failed) asks again on that transition; app start and every return to the front [nudge] the
 * pending job into existence for users with rows waiting (no health check, no network use when offline: the job then
 * waits on WorkManager's network constraint); with no rows waiting, a reconnect runs [pullIfStale] (the throttled bundle
 * delta) instead of an upload.
 */
class ConnectivityFlush(
    private val scope: CoroutineScope,
    private val healthy: suspend () -> Boolean,
    /** User ids on this phone with unsent rows. */
    private val usersWithPendingRows: suspend () -> List<Long>,
    private val scheduler: SyncScheduler,
    private val debounceMs: Long = 5_000,
    /** F-SYS-081: the default network came (true) or went (false); must not block. */
    private val onNetworkChange: (Boolean) -> Unit = {},
    /** D-NIGHT-SYNC-01: the signed-in user's bundle delta when its own gate says it is due; null pulls nothing. Never throws out. */
    private val pullIfStale: (suspend () -> Unit)? = null,
) {
    private var pending: Job? = null

    /** An activity of the app is started ([ForegroundNudge]); a background process start leaves it false. */
    @Volatile internal var inFront: Boolean = false

    /** The last failure of a flush, for diagnostics; never thrown. */
    @Volatile var lastError: Throwable? = null
        private set

    @Synchronized
    fun onNetworkAvailable() {
        try { onNetworkChange(true) } catch (_: Exception) { }
        flushAfterQuiet()
    }

    /** The default network became validated after `onAvailable` (telemetry already saw the network arrive). */
    @Synchronized
    fun onNetworkValidated() = flushAfterQuiet()

    private fun flushAfterQuiet() {
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMs)
            // A lookup that fails (an unreadable user database, a lost Keystore key) must never crash the app on a network
            // change: WorkManager's CONNECTED jobs still cover those rows.
            try {
                val users = usersWithPendingRows()
                // The pull only with the app in front (a WorkManager or push wake starts the process too) and only on an
                // API answer: a captive portal must not spend the delta gate or the server version (checker).
                if (users.isEmpty()) { pullIfStale?.let { if (inFront && healthy()) it() }; return@launch }
                if (!healthy()) return@launch
                users.forEach { scheduler.requestSync(it, SyncTrigger.CONNECTIVITY) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
    }

    /**
     * App start or back in front: one pending upload job for every user with rows waiting (KEEP, so a job already waiting
     * is kept). Reads the outbox counts only; the network is never touched here.
     */
    fun nudge() {
        scope.launch {
            try {
                usersWithPendingRows().forEach { scheduler.requestSync(it, SyncTrigger.FOREGROUND) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
    }

    @Synchronized
    fun onNetworkLost() {
        try { onNetworkChange(false) } catch (_: Exception) { }
        pending?.cancel()
        pending = null
    }

    /** Registers [flush] for the default network while the process lives (call once from `Application.onCreate`). */
    companion object {
        fun register(context: Context, flush: ConnectivityFlush) {
            flush.nudge() // app start
            (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(ForegroundNudge(flush))
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(callback(flush))
        }

        /** The default-network callback: available, validated (only on the change to validated), lost. */
        internal fun callback(flush: ConnectivityFlush) = object : ConnectivityManager.NetworkCallback() {
            private var validated: Network? = null

            override fun onAvailable(network: Network) = flush.onNetworkAvailable()

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Called again for signal-strength and bandwidth changes: only the step to validated counts, once per network.
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    if (validated == network) validated = null
                    return
                }
                if (validated == network) return
                validated = network
                flush.onNetworkValidated()
            }

            override fun onLost(network: Network) {
                if (validated == network) validated = null
                flush.onNetworkLost()
            }
        }
    }

    /** The first activity started after none were: the app came to the front (no polling, no lifecycle-process dependency). */
    internal class ForegroundNudge(private val flush: ConnectivityFlush) : Application.ActivityLifecycleCallbacks {
        private var started = 0
        override fun onActivityStarted(activity: Activity) {
            flush.inFront = true
            if (started++ == 0) flush.nudge()
        }
        override fun onActivityStopped(activity: Activity) {
            if (started > 0) started--
            if (started == 0) flush.inFront = false
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
