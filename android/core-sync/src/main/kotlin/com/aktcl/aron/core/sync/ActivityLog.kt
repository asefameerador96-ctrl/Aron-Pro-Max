package com.aktcl.aron.core.sync

import androidx.room.withTransaction
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.record.ActivityEvent
import com.aktcl.aron.core.database.record.RecordMapping
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.session.TrustedClockSource
import com.aktcl.aron.rules.BusinessDate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * Activity log (F-SYS-024, docs/24 s4 item 6): screen and action events, sampled at `cfg.app.activity_log_sample_pct`
 * (default 10) per user and business date, kept in memory and written as `activity_log` outbox rows (at most 200 events
 * each, one per business date of the events) when the app goes to the background ([flushSoon]), when 200 events wait,
 * and before every batch is built. The rows ride the next upload: no request of their own, and no sync is asked for
 * (telemetry never costs data). Their client uuid makes a resend a duplicate on the server, so an event appears once in
 * `activity_log`; a written row is never written again (the insert is not cancellable). Events still in memory when the
 * process dies are lost: telemetry, never a business record. [log] never blocks and never throws.
 */
class ActivityLog(
    private val db: suspend (userId: Long) -> AronDatabase,
    private val clock: TrustedClockSource,
    private val offline: () -> Boolean,
    /** Flushes that must outlive the screen that asked (an activity's scope dies at onDestroy). */
    private val scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
) {
    private val lock = Any()
    private val buffers = HashMap<Long, ArrayList<Pair<Long, ActivityEvent>>>() // (trusted ms, event)
    private val sampled = HashMap<Long, Pair<String, Boolean>>() // user -> (business date, sampled in)
    private val flushLock = Mutex()

    /**
     * Records one event for [userId] when the user is sampled in today. [screen] and [action] are lower-case names
     * (`^[a-z][a-z0-9_.]{1,60}$`, contract); anything else is dropped. Returns true when kept.
     */
    fun log(userId: Long, screen: String, action: String, durationMs: Long? = null): Boolean = try {
        if (!NAME.matches(screen) || !NAME.matches(action)) false
        else {
            val today = clock.businessDate().toString()
            var refresh = false
            var full = false
            val kept = synchronized(lock) {
                val s = sampled[userId]
                when {
                    s == null || s.first != today -> { refresh = true; false } // first event of a login or a new day
                    !s.second -> false
                    else -> {
                        val buffer = buffers.getOrPut(userId) { ArrayList() }
                        if (buffer.size >= MAX_BUFFER) false
                        else {
                            val now = clock.nowMs()
                            buffer += now to ActivityEvent(SyncEngine.iso(now), screen, action, durationMs?.coerceIn(0L, 86_400_000L))
                            full = buffer.size >= 200
                            true
                        }
                    }
                }
            }
            if (refresh) scope.launch { refreshSampling(userId) }
            if (full) flushSoon(userId)
            kept
        }
    } catch (_: Exception) {
        false
    }

    /** [flush] on the log's own scope: for onStop and a full buffer, so leaving the screen never cancels a write. */
    fun flushSoon(userId: Long) {
        scope.launch { flush(userId) }
    }

    /**
     * Decides today's sampling for [userId] from the setting (call at start, resume and after a bundle; cheap). The
     * choice is stable for a user, phone and business date, so a day is logged whole or not at all.
     */
    suspend fun refreshSampling(userId: Long) {
        val date = clock.businessDate().toString()
        val pct = try {
            SessionSyncRunner.configInt(ReferenceRepository(db(userId)).config(CFG_SAMPLE_PCT, SyncEngine.iso(clock.nowMs())))
        } catch (_: Exception) {
            null
        } ?: DEFAULT_SAMPLE_PCT
        val inSample = bucket(userId, date) < pct.coerceIn(0, 100) // stable for the user and the day, on any phone
        synchronized(lock) {
            sampled[userId] = date to inSample
            if (!inSample) buffers.remove(userId)
        }
    }

    /** Events of [userId] waiting in memory. */
    fun pending(userId: Long): Int = synchronized(lock) { buffers[userId]?.size ?: 0 }

    /**
     * Writes the buffered events of [userId]: one outbox row per business date of the events and per 200 events. Never
     * throws; returns the rows written. The insert is not cancellable, so rows are never written twice; events go back
     * to the buffer only when nothing was written.
     */
    suspend fun flush(userId: Long): Int = flushLock.withLock {
        val events = synchronized(lock) { buffers.remove(userId) } ?: return 0
        if (events.isEmpty()) return 0
        var started = false
        try {
            val database = db(userId)
            val ref = ReferenceRepository(database)
            val now = clock.nowMs()
            val bundleVersion = ref.bundleVersion()
            val configVersion = ref.configVersionHeld()
            val offlineNow = runCatching { offline() }.getOrDefault(true)
            val rows = events.groupBy { (ms, _) -> BusinessDate.of(ms).toString() }.flatMap { (date, ofDate) ->
                val meta = CaptureMeta(
                    businessDate = date,
                    capturedAt = SyncEngine.iso(now),
                    capturedElapsedMs = clock.elapsedRealtimeMs(),
                    bootCount = clock.bootCountNow(),
                    clockOffsetMs = clock.clockOffsetMs(),
                    capturedOffline = offlineNow,
                    routeId = null,
                    bundleVersion = bundleVersion,
                    configVersion = configVersion,
                )
                ofDate.map { it.second }.chunked(200).map { RecordMapping.activityLog(ClientIds.newUuid(), meta, it) }
            }
            started = true
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { database.withTransaction { database.outboxDao().insert(rows) } }
            rows.size
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (!started) synchronized(lock) { buffers.getOrPut(userId) { ArrayList() }.addAll(0, events) }
            throw e
        } catch (_: Exception) {
            0 // telemetry: dropped rather than retried forever
        }
    }

    private fun bucket(userId: Long, date: String): Int {
        val d = MessageDigest.getInstance("SHA-256").digest("$userId|$date".toByteArray())
        return ((d[0].toInt() and 0xff) shl 8 or (d[1].toInt() and 0xff)) % 100
    }

    companion object {
        const val CFG_SAMPLE_PCT = "cfg.app.activity_log_sample_pct"
        const val DEFAULT_SAMPLE_PCT = 10
        const val MAX_BUFFER = 400
        private val NAME = Regex("^[a-z][a-z0-9_.]{1,60}$")
    }
}
