package com.aktcl.aron.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
) {
    private var pending: Job? = null

    /** The last failure of a flush, for diagnostics; never thrown. */
    @Volatile var lastError: Throwable? = null
        private set

    @Synchronized
    fun onNetworkAvailable() {
        try { onNetworkChange(true) } catch (_: Exception) { }
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMs)
            // A lookup that fails (an unreadable user database, a lost Keystore key) must never crash the app on a network
            // change: WorkManager's CONNECTED jobs still cover those rows.
            try {
                val users = usersWithPendingRows()
                if (users.isEmpty() || !healthy()) return@launch
                users.forEach { scheduler.requestSync(it, SyncTrigger.CONNECTIVITY) }
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
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = flush.onNetworkAvailable()
                override fun onLost(network: Network) = flush.onNetworkLost()
            })
        }
    }
}
