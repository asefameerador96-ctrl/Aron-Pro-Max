package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/** Registry row (`app.cfg_key`, docs/24 s9.1). */
data class KeyDef(
    val key: String, val area: String, val kind: String, val valueType: String, val default: JsonElement, val bounds: JsonObject,
    val boundsRule: String?, val scopeLevels: List<String>, val riskClass: Int, val riskRule: String?, val effect: String,
    val delivery: String, val requiresAck: Boolean, val futureDatedOnly: Boolean, val restrictiveDir: String,
    val editorPermission: String, val descriptionEn: String, val descriptionBn: String?, val retired: Boolean,
)

/** One node of a scope chain. */
data class ScopeNode(val type: String, val id: Long)

/** The winning row of a resolution (null `row` = registry default). */
data class Resolved(
    val key: KeyDef, val value: JsonElement, val scopeType: String, val scopeId: Long?, val effectiveFrom: Instant?,
    val effectiveTo: Instant?, val configVersion: Long?,
)

/** docs/24 s9.2 precedence: device 120 > user 110 > outlet 100 > route 90 > zone 80 > geo_class 70 > territory 50 > division 40 > wing 30 > role 10 > global 0. */
object Precedence {
    val rank: Map<String, Int> = mapOf(
        "device" to 120, "user" to 110, "outlet" to 100, "route" to 90, "zone" to 80, "geo_class" to 70,
        "territory" to 50, "division" to 40, "wing" to 30, "role" to 10, "global" to 0,
    )
    /** Levels above a zone; used to widen a risk class and to find the nodes a change touches. */
    val wideLevels = setOf("division", "wing", "global")
}

/**
 * Registry cache, scope-chain construction and resolution (docs/24 s9.2). Resolution is one SQL statement per call
 * over the chain; `GET /admin/config/resolve` shows the winning row (provenance). Ties on one level (a subject with
 * two zones) break on the smaller `scope_id`, so the answer is deterministic.
 */
class ConfigResolver(private val db: Database, private val clock: AronClock = AronClock.SYSTEM, private val registryTtlMs: Long = 60_000) {
    private data class Cached(val at: Long, val keys: Map<String, KeyDef>)
    private val cache = AtomicReference<Cached?>(null)

    fun registry(): Map<String, KeyDef> {
        val now = clock.now().toEpochMilli()
        cache.get()?.let { if (now - it.at < registryTtlMs) return it.keys }
        val keys = db.jdbi.withHandle<Map<String, KeyDef>, Exception> { h ->
            h.createQuery("SELECT * FROM app.cfg_key ORDER BY key").map { rs, _ ->
                KeyDef(
                    key = rs.getString("key"), area = rs.getString("area"), kind = rs.getString("kind"), valueType = rs.getString("value_type"),
                    default = rs.getString("default_value")?.let { Json.parseToJsonElement(it) } ?: JsonNull,
                    bounds = (Json.parseToJsonElement(rs.getString("bounds")) as? JsonObject) ?: JsonObject(emptyMap()),
                    boundsRule = rs.getString("bounds_rule"), scopeLevels = (rs.getArray("scope_levels").array as Array<*>).map { it as String },
                    riskClass = rs.getInt("risk_class"), riskRule = rs.getString("risk_rule"), effect = rs.getString("effect"),
                    delivery = rs.getString("delivery"), requiresAck = rs.getBoolean("requires_ack"), futureDatedOnly = rs.getBoolean("future_dated_only"),
                    restrictiveDir = rs.getString("restrictive_dir"), editorPermission = rs.getString("editor_permission"),
                    descriptionEn = rs.getString("description_en"), descriptionBn = rs.getString("description_bn"), retired = rs.getObject("retired_at") != null,
                )
            }.list().associateBy { it.key }
        }
        cache.set(Cached(now, keys))
        return keys
    }

    fun invalidate() = cache.set(null)

    /**
     * The chain (most specific first, ending with global) above one node. Unknown nodes yield only `global`, so a
     * stale id can never widen what a value reaches. `user` adds the role and the home zone's hierarchy; `outlet`
     * adds its route, zone and geo class.
     */
    fun chainOf(nodeType: String, nodeId: Long): List<ScopeNode> = db.jdbi.withHandle<List<ScopeNode>, Exception> { h -> chainOf(h, nodeType, nodeId) }

    fun chainOf(h: org.jdbi.v3.core.Handle, nodeType: String, nodeId: Long): List<ScopeNode> {
        val out = mutableListOf<ScopeNode>()
        fun zoneUp(zoneId: Long?) {
            if (zoneId == null) return
            val row = h.createQuery(
                "SELECT z.id zid, t.id tid, d.id did, w.id wid FROM app.zone z JOIN app.territory t ON t.id = z.territory_id " +
                    "JOIN app.division d ON d.id = t.division_id JOIN app.wing w ON w.id = d.wing_id WHERE z.id = :z",
            ).bind("z", zoneId).map { rs, _ -> listOf(rs.getLong("zid"), rs.getLong("tid"), rs.getLong("did"), rs.getLong("wid")) }.findOne().orElse(null) ?: return
            out += ScopeNode("zone", row[0]); out += ScopeNode("territory", row[1]); out += ScopeNode("division", row[2]); out += ScopeNode("wing", row[3])
        }
        when (nodeType) {
            "device" -> out += ScopeNode("device", nodeId)
            "user" -> {
                val u = h.createQuery("SELECT u.home_zone_id, r.ordinal FROM app.app_user u JOIN app.role_def r ON r.role = u.role WHERE u.id = :u")
                    .bind("u", nodeId).map { rs, _ -> (rs.getObject("home_zone_id") as Long?) to rs.getInt("ordinal").toLong() }.findOne().orElse(null)
                if (u != null) { out += ScopeNode("user", nodeId); zoneUp(u.first); out += ScopeNode("role", u.second) }
            }
            "outlet" -> {
                val o = h.createQuery("SELECT route_id, zone_id, geo_class FROM app.outlet WHERE id = :o").bind("o", nodeId)
                    .map { rs, _ -> Triple(rs.getObject("route_id") as Long?, rs.getLong("zone_id"), rs.getString("geo_class")) }.findOne().orElse(null)
                if (o != null) {
                    out += ScopeNode("outlet", nodeId)
                    o.first?.let { out += ScopeNode("route", it) }
                    zoneUp(o.second)
                    o.third?.let { gc ->
                        h.createQuery("SELECT ordinal FROM app.geo_class_def WHERE geo_class = :g").bind("g", gc).mapTo(Long::class.java).findOne().ifPresent { ord ->
                            val at = out.indexOfFirst { it.type == "territory" }.let { if (it < 0) out.size else it }
                            out.add(at, ScopeNode("geo_class", ord))
                        }
                    }
                }
            }
            "route" -> {
                val z = h.createQuery("SELECT zone_id FROM app.route WHERE id = :r").bind("r", nodeId).mapTo(Long::class.java).findOne().orElse(null)
                if (z != null) { out += ScopeNode("route", nodeId); zoneUp(z) }
            }
            "zone" -> zoneUp(nodeId)
            "territory" -> {
                val r = h.createQuery("SELECT d.id did, w.id wid FROM app.territory t JOIN app.division d ON d.id = t.division_id JOIN app.wing w ON w.id = d.wing_id WHERE t.id = :t")
                    .bind("t", nodeId).map { rs, _ -> rs.getLong("did") to rs.getLong("wid") }.findOne().orElse(null)
                if (r != null) { out += ScopeNode("territory", nodeId); out += ScopeNode("division", r.first); out += ScopeNode("wing", r.second) }
            }
            "division" -> {
                val w = h.createQuery("SELECT wing_id FROM app.division WHERE id = :d").bind("d", nodeId).mapTo(Long::class.java).findOne().orElse(null)
                if (w != null) { out += ScopeNode("division", nodeId); out += ScopeNode("wing", w) }
            }
            "wing" -> h.createQuery("SELECT 1 FROM app.wing WHERE id = :w").bind("w", nodeId).mapTo(Int::class.java).findOne().ifPresent { out += ScopeNode("wing", nodeId) }
            "role", "geo_class" -> out += ScopeNode(nodeType, nodeId)
            "global" -> {}
        }
        out += ScopeNode("global", 0)
        return out.distinct()
    }

    /**
     * The chain of a calling phone or web user: device, user, role, and the hierarchy of the home zone and of the
     * zones of the user's current route assignments (docs/24 s9.3 item 3: "the caller's chain").
     */
    fun subjectChain(userId: Long, deviceId: Long?, at: Instant): List<ScopeNode> = db.jdbi.withHandle<List<ScopeNode>, Exception> { h ->
        val chain = mutableListOf<ScopeNode>()
        deviceId?.let { chain += ScopeNode("device", it) }
        chain += chainOf(h, "user", userId).filter { it.type != "global" }
        val day = java.time.LocalDate.ofInstant(at, java.time.ZoneId.of("Asia/Dhaka"))
        val zones = h.createQuery(
            "SELECT DISTINCT r.zone_id FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id WHERE a.user_id = :u " +
                "AND a.valid_from <= :d AND (a.valid_to IS NULL OR a.valid_to > :d) AND a.ended_at IS NULL ORDER BY 1",
        ).bind("u", userId).bind("d", day).mapTo(Long::class.java).list()
        zones.forEach { z -> chain += chainOf(h, "zone", z).filter { it.type != "global" } }
        chain += ScopeNode("global", 0)
        chain.distinct()
    }

    private fun rankCase(): String = "CASE v.scope_type " + Precedence.rank.entries.joinToString(" ") { "WHEN '${it.key}' THEN ${it.value}" } + " END"

    private fun chainPredicate(chain: List<ScopeNode>): String =
        chain.indices.joinToString(" OR ") { "(v.scope_type = :t$it AND v.scope_id = :i$it)" }

    private fun org.jdbi.v3.core.statement.Query.bindChain(chain: List<ScopeNode>): org.jdbi.v3.core.statement.Query {
        chain.forEachIndexed { i, n -> bind("t$i", n.type).bind("i$i", n.id) }
        return this
    }

    /** True when the scope node exists (role and geo_class ids are ordinals, docs/24 s14a R3). Global is id 0. */
    fun nodeExists(type: String, id: Long): Boolean {
        if (type == "global") return id == 0L
        val sql = when (type) {
            "wing" -> "SELECT 1 FROM app.wing WHERE id = :i"; "division" -> "SELECT 1 FROM app.division WHERE id = :i"
            "territory" -> "SELECT 1 FROM app.territory WHERE id = :i"; "zone" -> "SELECT 1 FROM app.zone WHERE id = :i"
            "route" -> "SELECT 1 FROM app.route WHERE id = :i"; "outlet" -> "SELECT 1 FROM app.outlet WHERE id = :i"
            "user" -> "SELECT 1 FROM app.app_user WHERE id = :i"; "device" -> "SELECT 1 FROM app.device WHERE id = :i"
            "role" -> "SELECT 1 FROM app.role_def WHERE ordinal = :i"; "geo_class" -> "SELECT 1 FROM app.geo_class_def WHERE ordinal = :i"
            else -> return false
        }
        return db.jdbi.withHandle<Boolean, Exception> { h -> h.createQuery(sql).bind("i", id).mapTo(Int::class.java).findOne().isPresent }
    }

    /** The winning value of [key] for [chain] at [at]; the registry default when no row applies. */
    fun resolve(key: String, chain: List<ScopeNode>, at: Instant): Resolved {
        val def = registry()[key] ?: error("unknown config key $key")
        return db.jdbi.withHandle<Resolved, Exception> { h ->
            val row = h.createQuery(
                "SELECT v.* FROM app.cfg_value v WHERE v.key = :k AND v.effective_from <= :at AND (v.effective_to IS NULL OR v.effective_to > :at) " +
                    "AND (${chainPredicate(chain)}) ORDER BY ${rankCase()} DESC, v.scope_id ASC LIMIT 1",
            ).bind("k", key).bind("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).bindChain(chain).map { rs, _ -> mapRow(rs, def) }.findOne().orElse(null)
            row ?: Resolved(def, def.default, "default", null, null, null, null)
        }
    }

    /** Every key's winning row for [chain] at [at] (one statement); keys without a row take the registry default. */
    fun resolveAll(chain: List<ScopeNode>, at: Instant, keysOnly: Collection<String>? = null): Map<String, Resolved> {
        val reg = registry()
        val rows = db.jdbi.withHandle<Map<String, Resolved>, Exception> { h ->
            h.createQuery(
                "SELECT DISTINCT ON (v.key) v.* FROM app.cfg_value v WHERE v.effective_from <= :at AND (v.effective_to IS NULL OR v.effective_to > :at) " +
                    "AND (${chainPredicate(chain)}) ORDER BY v.key, ${rankCase()} DESC, v.scope_id ASC",
            ).bind("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).bindChain(chain).map { rs, _ ->
                val def = reg[rs.getString("key")]
                if (def == null) null else def.key to mapRow(rs, def)
            }.list().filterNotNull().toMap()
        }
        return reg.values.filter { !it.retired && (keysOnly == null || it.key in keysOnly) }
            .associate { d -> d.key to (rows[d.key] ?: Resolved(d, d.default, "default", null, null, null, null)) }
    }

    private fun mapRow(rs: java.sql.ResultSet, def: KeyDef) = Resolved(
        def, Json.parseToJsonElement(rs.getString("value")), rs.getString("scope_type"), rs.getLong("scope_id"),
        rs.getObject("effective_from", OffsetDateTime::class.java).toInstant(),
        rs.getObject("effective_to", OffsetDateTime::class.java)?.toInstant(), rs.getLong("config_version"),
    )
}
