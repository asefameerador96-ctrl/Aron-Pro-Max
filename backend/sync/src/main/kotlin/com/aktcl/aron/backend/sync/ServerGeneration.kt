package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.NIL_GENERATION
import java.util.concurrent.atomic.AtomicReference

/**
 * The current database lineage (docs/24 s4.8): `app.server_generation` where `is_current`, cached for [ttlMs] so the
 * `X-Server-Generation` header on every response costs no query. A failover or restore mints a new row; phones that
 * see a new value re-send what was acked after `lost_after_utc`.
 */
class ServerGeneration(private val db: Database, private val ttlMs: Long = 30_000) {
    private val cache = AtomicReference<Pair<Long, String>?>(null)
    private val loading = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var retryAt = 0L
    @Volatile private var failures = 0

    /**
     * Refreshed on the request path once older than [ttlMs]; warm, one request at a time refreshes and the others serve
     * the cached value; cold, callers wait for the one load in flight, so the nil value never goes out while the
     * database answers. A failure backs off (2 s doubling to 30 s) so an outage never blocks every request (AUD-REL-01).
     */
    fun current(): String {
        val now = System.currentTimeMillis()
        val c = cache.get()
        if (c != null && now - c.first < ttlMs) return c.second
        if (c != null) {
            if (System.nanoTime() < retryAt || !loading.compareAndSet(false, true)) return c.second
            try { return tryLoad(now) ?: c.second } finally { loading.set(false) }
        }
        synchronized(coldLock) {
            cache.get()?.let { return it.second }
            return (if (System.nanoTime() >= retryAt) tryLoad(now) else null) ?: NIL_GENERATION
        }
    }

    private val coldLock = Any()

    private fun tryLoad(now: Long): String? = try {
        db.jdbi.withHandle<String?, Exception> { h ->
            h.createQuery("SELECT generation::text FROM app.server_generation WHERE is_current").mapTo(String::class.java).findOne().orElse(null)
        }.also { g -> failures = 0; if (g != null) cache.set(now to g) }
    } catch (e: Exception) {
        failures = (failures + 1).coerceAtMost(5)
        retryAt = System.nanoTime() + minOf(30_000L, 1_000L shl failures) * 1_000_000L
        null
    }

    /** The cached value without any I/O (health probes). */
    fun cached(): String = cache.get()?.second ?: NIL_GENERATION
}
