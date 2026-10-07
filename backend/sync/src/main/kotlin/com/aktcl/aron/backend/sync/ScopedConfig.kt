package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ConfigScopeType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jdbi.v3.core.Handle
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Scoped config resolution for the bundle (docs/24 s9.2): among the `cfg_value` rows valid at an instant whose
 * (scope_type, scope_id) is in the caller's scope chain, the one with the highest [ConfigScopeType.precedence] wins;
 * with none, the registry default of `cfg_key` (scope_type `default`). Loaded once per bundle; resolution is in memory.
 */
class ScopedConfig private constructor(
    private val keys: Map<String, KeyDef>,
    private val rows: Map<String, List<ValueRow>>,
    private val now: Instant,
) {
    data class KeyDef(val key: String, val default: JsonElement?, val bounds: JsonObject, val delivery: String, val requiresAck: Boolean)

    data class ValueRow(
        val key: String,
        val scope: ConfigScopeType,
        val scopeId: Long,
        val value: JsonElement,
        val from: Instant,
        val to: Instant?,
        val version: Long,
    ) {
        fun validAt(t: Instant): Boolean = !from.isAfter(t) && (to == null || to.isAfter(t))
    }

    /** One scope chain: each scope type with the id the caller has at that level (global is always 0). */
    class Chain(private val ids: Map<ConfigScopeType, Long>) {
        fun matches(r: ValueRow): Boolean = ids[r.scope] == r.scopeId
        fun with(extra: Map<ConfigScopeType, Long?>): Chain = Chain(ids + extra.filterValues { it != null }.mapValues { it.value!! })

        companion object {
            fun of(vararg pairs: Pair<ConfigScopeType, Long?>): Chain =
                Chain(mapOf(ConfigScopeType.GLOBAL to 0L) + pairs.filter { it.second != null }.associate { it.first to it.second!! })
        }
    }

    /** The winning row valid now for [key] in [chain], or null when only the registry default applies. */
    fun winner(key: String, chain: Chain, at: Instant = now): ValueRow? =
        rows[key].orEmpty().filter { it.validAt(at) && chain.matches(it) }.maxByOrNull { it.scope.precedence }

    /** The resolved value now (row or registry default), or null when the key has no value. */
    fun value(key: String, chain: Chain): JsonElement? = (winner(key, chain)?.value ?: keys[key]?.default)?.takeUnless { it is JsonNull }

    fun int(key: String, chain: Chain, fallback: Int): Int = value(key, chain)?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: fallback

    /** Every key delivered to phones (`delivery` device or both) resolved for [chain]; keys without a value are left out. */
    fun deviceValues(chain: Chain): List<ResolvedConfigValue> = keys.values.filter { it.delivery != "server" }.sortedBy { it.key }.mapNotNull { k ->
        val w = winner(k.key, chain)
        val v = (w?.value ?: k.default)?.takeUnless { it is JsonNull }?.takeIf(::fitsContract) ?: return@mapNotNull null
        ResolvedConfigValue(
            key = k.key, value = v, scope_type = w?.scope?.wire ?: "default", scope_id = w?.scopeId,
            effective_from = w?.from?.wire(), effective_to = w?.to?.wire(), config_version = w?.version,
            requires_ack = k.requiresAck, bounds = k.bounds.takeIf { it.isNotEmpty() },
        )
    }

    /** Values of device keys in [chain] that start after now and up to [until] (applied by the phone on trusted time). */
    fun deviceScheduled(chain: Chain, until: Instant): List<ResolvedConfigValue> =
        rows.values.flatten().filter { r ->
            val k = keys[r.key]
            k != null && k.delivery != "server" && r.from.isAfter(now) && !r.from.isAfter(until) && chain.matches(r) && r.value !is JsonNull && fitsContract(r.value)
        }.sortedWith(compareBy({ it.from }, { it.key }, { it.scope.precedence })).map { r ->
            val k = keys.getValue(r.key)
            ResolvedConfigValue(r.key, r.value, r.scope.wire, r.scopeId, r.from.wire(), r.to?.wire(), r.version, k.requiresAck, k.bounds.takeIf { it.isNotEmpty() })
        }

    companion object {
        /**
         * Contract ConfigValueJson: a scalar, an array of scalars or of flat objects, or an object whose members are
         * scalars, arrays of scalars or flat objects. A value nested deeper (two registry keys today, see
         * docs/requests/backend-config-value-shape.md) is left out of the bundle rather than break a strict phone decoder;
         * the phone uses its built-in default for it.
         */
        fun fitsContract(v: JsonElement): Boolean {
            fun scalar(e: JsonElement) = e is JsonPrimitive && e !is JsonNull
            fun flat(e: JsonElement) = e is JsonObject && e.values.all { scalar(it) || it is JsonNull }
            return when (v) {
                is JsonNull -> false
                is JsonPrimitive -> !v.isString || v.content.length <= 4000
                is kotlinx.serialization.json.JsonArray -> v.size <= 500 && v.all { scalar(it) || flat(it) }
                is JsonObject -> v.values.all { x -> scalar(x) || x is JsonNull || (x is kotlinx.serialization.json.JsonArray && x.size <= 200 && x.all { scalar(it) || it is JsonNull }) || flat(x) }
                else -> false
            }
        }

        private val ALLOWED_BOUNDS = setOf("min", "max", "enum", "max_items", "dynamic_min", "dynamic_max")

        /** Loads the registry and every value row valid at [now] or starting before [until]. */
        fun load(h: Handle, now: Instant, until: Instant): ScopedConfig {
            val keys = h.createQuery("SELECT key, default_value::text AS dv, bounds::text AS b, delivery, requires_ack FROM app.cfg_key WHERE retired_at IS NULL")
                .map { rs, _ ->
                    val bounds = Json.parseToJsonElement(rs.getString("b")) as? JsonObject ?: JsonObject(emptyMap())
                    KeyDef(
                        rs.getString("key"), rs.getString("dv")?.let(Json::parseToJsonElement),
                        JsonObject(bounds.filterKeys { it in ALLOWED_BOUNDS }), rs.getString("delivery"), rs.getBoolean("requires_ack"),
                    )
                }.list().associateBy { it.key }
            val rows = h.createQuery(
                """
                SELECT key, scope_type, scope_id, value::text AS v, effective_from, effective_to, config_version
                FROM app.cfg_value
                WHERE (effective_to IS NULL OR effective_to > :now) AND effective_from <= :until
                """.trimIndent(),
            ).bind("now", OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC)).bind("until", OffsetDateTime.ofInstant(until, java.time.ZoneOffset.UTC))
                .map { rs, _ ->
                    ValueRow(
                        rs.getString("key"), ConfigScopeType.entries.first { it.wire == rs.getString("scope_type") }, rs.getLong("scope_id"),
                        Json.parseToJsonElement(rs.getString("v")), rs.getObject("effective_from", OffsetDateTime::class.java).toInstant(),
                        rs.getObject("effective_to", OffsetDateTime::class.java)?.toInstant(), rs.getLong("config_version"),
                    )
                }.list().groupBy { it.key }
            return ScopedConfig(keys, rows, now)
        }
    }
}
