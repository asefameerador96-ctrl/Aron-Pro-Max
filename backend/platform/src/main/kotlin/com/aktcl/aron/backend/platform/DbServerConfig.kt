package com.aktcl.aron.backend.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicReference

/**
 * Server-side `cfg.*` reads from PostgreSQL (docs/24 s9.3): the global `cfg_value` row valid now, else the registry
 * default of `cfg_key`, cached per replica for at most [ttlMs] (30 s). Scoped (role, zone, ...) resolution is
 * backend:config's; the keys the platform and auth read on Day 1 are global. [fallback] answers keys the registry
 * does not have yet and anything while the database is unreachable.
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
     * The cached snapshot, refreshed on the request path once it is older than [ttlMs] (AUD-REL-01): one request at a
     * time refreshes (the others serve the stale snapshot, or [fallback] on a cold replica), and a failed refresh backs
     * off (2 s doubling to 30 s) so an unreachable database blocks at most one request per back-off for the pool's
     * connection timeout, never every request.
     */
    private fun snapshot(): Snapshot? {
        val now = clock.now().toEpochMilli()
        val c = cache.get()
        if (c != null && now - c.at < ttlMs) return c
        if (System.nanoTime() < retryAt || !loading.compareAndSet(false, true)) return c
        try {
            return load(now).also { cache.set(it); failures = 0 }
        } catch (e: Exception) {
            failures = (failures + 1).coerceAtMost(5)
            retryAt = System.nanoTime() + minOf(30_000L, 1_000L shl failures) * 1_000_000L
            return c
        } finally {
            loading.set(false)
        }
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

    override fun value(key: String): JsonElement = snapshot()?.values?.get(key) ?: fallback.value(key)

    override fun configVersion(): Long = snapshot()?.version ?: fallback.configVersion()

    /** The cached version without touching the database (health probes, AUD-REL-01). */
    override fun cachedConfigVersion(): Long = cache.get()?.version ?: fallback.configVersion()
}
