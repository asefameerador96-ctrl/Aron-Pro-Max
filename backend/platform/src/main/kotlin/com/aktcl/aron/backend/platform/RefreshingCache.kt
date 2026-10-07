package com.aktcl.aron.backend.platform

import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * A value loaded from PostgreSQL that the request path reads (AUD-REL-01, docs/18 FM-15/FM-16): kept for [ttlMs] on the
 * injected clock, refreshed by one caller at a time (single flight; the others keep the last good value), and after a
 * failed load not retried before a back-off on the monotonic clock (from [backoffMinMs], doubling to [backoffMaxMs]),
 * so a database outage costs at most one blocked request per back-off window instead of every request.
 * [peek] never blocks: health and the response headers use it, and it refreshes a stale value in the background.
 */
class RefreshingCache<T : Any>(
    private val name: String,
    private val ttlMs: Long,
    private val nowMs: () -> Long,
    private val backoffMinMs: Long = 2_000,
    private val backoffMaxMs: Long = 30_000,
    private val load: () -> T,
) {
    private data class Entry<T>(val at: Long, val value: T)

    private val entry = AtomicReference<Entry<T>?>(null)
    private val loading = AtomicBoolean(false)
    private val nextTryNanos = AtomicLong(Long.MIN_VALUE)
    private val backoff = AtomicLong(backoffMinMs)

    private fun fresh(e: Entry<T>?): Boolean = e != null && nowMs() - e.at < ttlMs

    private fun inBackoff(): Boolean = nextTryNanos.get() != Long.MIN_VALUE && System.nanoTime() - nextTryNanos.get() < 0

    /** The value, loading it on this thread when stale (unless another load runs or a failure back-off holds). */
    fun get(): T? {
        val e = entry.get()
        if (fresh(e) || inBackoff()) return e?.value
        return refresh() ?: entry.get()?.value
    }

    /** The last good value, never blocking; a stale value is refreshed on a background thread. */
    fun peek(): T? {
        val e = entry.get()
        if (!fresh(e) && !inBackoff() && !loading.get()) {
            runCatching { BACKGROUND.execute { refresh() } }
        }
        return e?.value
    }

    /** Loads once if no other load is running; null when skipped or failed. */
    private fun refresh(): T? {
        if (!loading.compareAndSet(false, true)) return null
        try {
            val v = load()
            entry.set(Entry(nowMs(), v))
            nextTryNanos.set(Long.MIN_VALUE)
            backoff.set(backoffMinMs)
            return v
        } catch (e: Exception) {
            val wait = backoff.get()
            nextTryNanos.set(System.nanoTime() + wait * 1_000_000)
            backoff.set((wait * 2).coerceAtMost(backoffMaxMs))
            // No stack trace: an outage would otherwise flood the log (one line per back-off window).
            log.warn("{} refresh failed, serving the last good value; next try in {} ms: {}", name, wait, e.javaClass.simpleName)
            return null
        } finally {
            loading.set(false)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger("aron.cache")
        private val BACKGROUND = Executors.newSingleThreadExecutor { r -> Thread(r, "aron-cache-refresh").apply { isDaemon = true } }
    }
}
