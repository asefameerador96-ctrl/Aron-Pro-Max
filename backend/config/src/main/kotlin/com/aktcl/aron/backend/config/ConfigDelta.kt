package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.contract.ProblemCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Contract `ConfigDelta` (docs/24 s9.3 item 3). `calendar_changes` and `outlet_radius_changes` are carried by the bundle service. */
@Serializable
data class ConfigDeltaDto(
    val from_version: Long, val to_version: Long, val values: List<ResolvedConfigValue>, val scheduled: List<ResolvedConfigValue>,
    val removed_keys: List<String>, val calendar_changes: List<kotlinx.serialization.json.JsonObject> = emptyList(), val policy_changed: Boolean = false,
)

/** What a delta call decided: 304 (nothing relevant changed or inside the minimum gap) or the body. */
sealed interface DeltaOutcome {
    data class NotModified(val version: Long) : DeltaOutcome
    data class Changes(val body: ConfigDeltaDto) : DeltaOutcome
}

/**
 * `GET /v1/config/delta?since=<version>` (F-API-040) and the minimum-gap gate (F-API-083). The delta holds only keys
 * whose resolved value changed for the caller's chain since [since] (docs/24 s9.3 item 3); the state "as of a
 * version" is rebuilt from `config_version` and `superseded_in_version`, so history is never edited. A phone further
 * behind than `cfg.sys.config_delta_max_age_versions` gets 410 and refetches the bundle.
 */
class ConfigDelta(private val db: Database, private val resolver: ConfigResolver, private val clock: AronClock = AronClock.SYSTEM) {
    fun etag(version: Long) = "\"cfg-$version\""

    fun delta(userId: Long, deviceId: Long?, since: Long, ifNoneMatch: String?, applyDue: () -> Unit = {}): DeltaOutcome {
        applyDue()
        val now = clock.now()
        val current = db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT COALESCE(max(config_version), 0) FROM app.cfg_version").mapTo(Long::class.java).one() }
        if (since > current) throw ApiProblem(ProblemCode.ERR_VALIDATION, "since is newer than the server's config version", errors = listOf(com.aktcl.aron.backend.platform.FieldError("query.since", "in_future")))
        val maxAge = resolver.resolve("cfg.sys.config_delta_max_age_versions", listOf(ScopeNode("global", 0)), now).value.toString().toDouble().toInt()
        if (current - since > maxAge) throw ApiProblem(ProblemCode.ERR_BUNDLE_CURSOR_EXPIRED, "config version too old; fetch the bundle")
        if (since == current || ifNoneMatch == etag(current)) return DeltaOutcome.NotModified(current)
        val chain = resolver.subjectChain(userId, deviceId, now)
        val deviceKeys = resolver.registry().values.filter { !it.retired && it.delivery != "server" }.map { it.key }
        val newer = resolver.resolveAll(chain, now, deviceKeys)
        val older = resolveAsOf(chain, now, since, deviceKeys)
        val changed = newer.values.filter { n -> older[n.key.key]?.let { sameValue(it, n) } != true }.map { it.toDto() }
        val scheduled = scheduled(chain, now, deviceKeys)
        if (changed.isEmpty() && scheduled.isEmpty()) return DeltaOutcome.NotModified(current)
        return DeltaOutcome.Changes(ConfigDeltaDto(since, current, changed, scheduled, emptyList()))
    }

    private fun sameValue(a: Resolved, b: Resolved) = a.value == b.value && a.scopeType == b.scopeType && a.scopeId == b.scopeId && a.effectiveFrom == b.effectiveFrom

    private fun rank() = "CASE v.scope_type " + Precedence.rank.entries.joinToString(" ") { "WHEN '${it.key}' THEN ${it.value}" } + " END"

    private fun predicate(chain: List<ScopeNode>) = chain.indices.joinToString(" OR ") { "(v.scope_type = :t$it AND v.scope_id = :i$it)" }

    /** The resolved values as they stood when [asOf] was the latest committed version, evaluated at [at]. */
    private fun resolveAsOf(chain: List<ScopeNode>, at: Instant, asOf: Long, keys: Collection<String>): Map<String, Resolved> {
        val reg = resolver.registry()
        val rows = db.jdbi.withHandle<Map<String, Resolved>, Exception> { h ->
            val q = h.createQuery(
                "SELECT DISTINCT ON (v.key) v.* FROM app.cfg_value v WHERE v.config_version <= :asof AND (v.superseded_in_version IS NULL OR v.superseded_in_version > :asof) " +
                    "AND v.effective_from <= :at AND (v.effective_to IS NULL OR v.effective_to > :at OR (v.superseded_in_version IS NOT NULL AND v.superseded_in_version > :asof)) " +
                    "AND (${predicate(chain)}) ORDER BY v.key, ${rank()} DESC, v.scope_id ASC",
            ).bind("asof", asOf).bind("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            chain.forEachIndexed { i, n -> q.bind("t$i", n.type).bind("i$i", n.id) }
            q.map { rs, _ -> reg[rs.getString("key")]?.let { d -> d.key to row(rs, d) } }.list().filterNotNull().toMap()
        }
        return keys.mapNotNull { reg[it] }.associate { d -> d.key to (rows[d.key] ?: Resolved(d, d.default, "default", null, null, null, null)) }
    }

    /** Values that start within `cfg.sys.schedule_horizon_days` (travel in `scheduled`; the phone switches them on trusted time). */
    private fun scheduled(chain: List<ScopeNode>, now: Instant, keys: Collection<String>): List<ResolvedConfigValue> {
        val reg = resolver.registry()
        val horizon = resolver.resolve("cfg.sys.schedule_horizon_days", listOf(ScopeNode("global", 0)), now).value.toString().toDouble().toLong()
        return db.jdbi.withHandle<List<ResolvedConfigValue>, Exception> { h ->
            val q = h.createQuery(
                "SELECT v.* FROM app.cfg_value v WHERE v.effective_from > :now AND v.effective_from <= :hz AND v.key = ANY (CAST(:keys AS text[])) AND (${predicate(chain)}) ORDER BY v.key, v.effective_from",
            ).bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).bind("hz", OffsetDateTime.ofInstant(now.plusSeconds(horizon * 86400), ZoneOffset.UTC))
                .bind("keys", "{" + keys.joinToString(",") + "}")
            chain.forEachIndexed { i, n -> q.bind("t$i", n.type).bind("i$i", n.id) }
            q.map { rs, _ -> reg[rs.getString("key")]?.let { d -> row(rs, d).toDto() } }.list().filterNotNull()
        }
    }

    private fun row(rs: java.sql.ResultSet, def: KeyDef) = Resolved(
        def, Json.parseToJsonElement(rs.getString("value")), rs.getString("scope_type"), rs.getLong("scope_id"),
        rs.getObject("effective_from", OffsetDateTime::class.java).toInstant(), rs.getObject("effective_to", OffsetDateTime::class.java)?.toInstant(), rs.getLong("config_version"),
    )
}
