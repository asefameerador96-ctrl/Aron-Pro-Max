package com.aktcl.aron.backend.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Server-side `cfg.*` reads from PostgreSQL (docs/24 s9.3): the global `cfg_value` row valid now, else the registry
 * default of `cfg_key`, cached per replica for at most [ttlMs] (30 s). Scoped (role, zone, ...) resolution is
 * backend:config's; the keys the platform and auth read on Day 1 are global. [fallback] answers keys the registry
 * does not have yet and anything while the database is unreachable and nothing was loaded yet.
 *
 * The database is off the request path while it is down (AUD-REL-01): a failed refresh is retried only after a
 * back-off, one caller at a time, and the last good snapshot is served meanwhile; [peekConfigVersion] never blocks.
 */
class DbServerConfig(
    private val db: Database,
    private val fallback: ServerConfig,
    private val clock: AronClock = AronClock.SYSTEM,
    ttlMs: Long = 30_000,
) : ServerConfig {
    private class Snapshot(val values: Map<String, JsonElement>, val version: Long)

    private val cache = RefreshingCache("server-config", ttlMs, { clock.now().toEpochMilli() }) { load() }

    private fun load(): Snapshot = db.jdbi.withHandle<Snapshot, Exception> { h ->
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
        Snapshot(values, version)
    }

    override fun value(key: String): JsonElement = cache.get()?.values?.get(key) ?: fallback.value(key)

    override fun configVersion(): Long = cache.get()?.version ?: fallback.configVersion()

    override fun peekConfigVersion(): Long = cache.peek()?.version ?: fallback.configVersion()
}
