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

    private fun snapshot(): Snapshot? {
        val now = clock.now().toEpochMilli()
        cache.get()?.let { if (now - it.at < ttlMs) return it }
        return runCatching { load(now) }.getOrNull()?.also(cache::set) ?: cache.get()
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
}
