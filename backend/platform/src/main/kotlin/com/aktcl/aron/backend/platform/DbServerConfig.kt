package com.aktcl.aron.backend.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicReference

/**
 * Server-side `cfg.*` reads from PostgreSQL (docs/24 s9.3): the global `cfg_value` row valid now, else the registry
 * default of `cfg_key`, cached per replica for at most [ttlMs] (30 s). Scoped (role, zone, ...) resolution is
 * backend:config's; the keys the platform and auth read on Day 1 are global. [fallback] answers keys the registry
 * does not have yet and, on a cold replica whose database is unreachable, the keys it knows (others: 503).
 */
class DbServerConfig(
    private val db: Database,
    private val fallback: ServerConfig,
    private val clock: AronClock = AronClock.SYSTEM,
    private val ttlMs: Long = 30_000,
) : ServerConfig {
    private data class Snapshot(val at: Long, val values: Map<String, JsonElement>, val version: Long)

    private val cache = AtomicReference<Snapshot?>(null)
    private val loading = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var retryAt = 0L
    @Volatile private var failures = 0

    /**
     * The cached snapshot, refreshed on the request path once it is older than [ttlMs] (AUD-REL-01). Warm: one request
     * at a time refreshes and the others serve the stale snapshot. Cold (nothing loaded yet): callers wait for the one
     * load in flight, never fall back to the registry defaults while the database answers. A failed load backs off
     * (2 s doubling to 30 s), so an unreachable database blocks at most one request per back-off; a cold read during
     * the back-off gets the registry default for a key it knows, else a transient failure (503), never a 500.
     */
    private fun snapshot(): Snapshot? {
        val now = clock.now().toEpochMilli()
        val c = cache.get()
        if (c != null && now - c.at < ttlMs) return c
        if (c != null) {
            if (System.nanoTime() < retryAt || !loading.compareAndSet(false, true)) return c
            try { return tryLoad(now) ?: c } finally { loading.set(false) }
        }
        synchronized(coldLock) {
            cache.get()?.let { return it }
            return if (System.nanoTime() >= retryAt) tryLoad(now) else null
        }
    }

    private val coldLock = Any()

    private fun tryLoad(now: Long): Snapshot? = try {
        load(now).also { cache.set(it); failures = 0 }
    } catch (e: Exception) {
        failures = (failures + 1).coerceAtMost(5)
        retryAt = System.nanoTime() + minOf(30_000L, 1_000L shl failures) * 1_000_000L
        null
    }

    private fun load(now: Long): Snapshot = db.jdbi.withHandle<Snapshot, Exception> { h ->
        val values = h.createQuery(
            """
            SELECT k.key, COALESCE(v.value, k.default_value)::text AS value
            FROM app.cfg_key k
            LEFT JOIN app.cfg_value v ON v.key = k.key AND v.scope_type = 'global'
                 AND v.effective_from <= now() AND (v.effective_to IS NULL OR v.effective_to > now())
            WHERE k.retired_at IS NULL
            """.trimIndent(),
        ).map { rs, _ -> rs.getString("key") to (rs.getString("value")?.let { Json.parseToJsonElement(it) }) }
            .list().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
        val version = h.createQuery("SELECT COALESCE(max(config_version), 0) FROM app.cfg_version").mapTo(Long::class.java).one()
        Snapshot(now, values, version)
    }

    override fun value(key: String): JsonElement {
        val snap = snapshot()
        snap?.values?.get(key)?.let { return it }
        if (snap != null) return fallback.value(key)
        // Cold and the database unreachable: the registry defaults answer the keys they know (a replica starts while
        // the database is down); any other key is a transient failure (503), never a 500.
        return runCatching { fallback.value(key) }.getOrElse { throw java.sql.SQLTransientConnectionException("server config not loaded: database unavailable") }
    }

    override fun configVersion(): Long = snapshot()?.version ?: fallback.configVersion()

    /** The cached version without touching the database (health probes, AUD-REL-01). */
    override fun cachedConfigVersion(): Long = cache.get()?.version ?: fallback.configVersion()
}
