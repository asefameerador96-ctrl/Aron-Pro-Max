package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.AuditWriter
import com.aktcl.aron.backend.masterdata.AdminSupport.bad
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** Contract `EntryUnlockWrite` and `EntryUnlock`. */
@Serializable
data class EntryUnlockIn(val scope_type: String, val scope_id: Long, val from: String, val to: String, val ttl_h: Int? = null, val reason: String)

@Serializable
data class EntryUnlockDto(
    val unlock_id: Long, val scope_type: String, val scope_id: Long, val from: String, val to: String, val reason: String, val expires_at: String,
    val created_by_user_id: Long, val status: String,
)

@Serializable
data class EntryUnlockPageDto(val items: List<EntryUnlockDto>, val next_cursor: String?)

class EntryUnlockDeps(val db: Database, val reach: ReachResolver, val config: ServerConfig, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

/**
 * F-API-049: back-dated web entry is only possible under an unlock grant for a zone or route (docs/24 s8.5, s11). A grant is
 * created by the roles of `cfg.web.entry_unlock_roles` (default SUPPORT, ADMIN), lasts `ttl_h` hours (default
 * `cfg.web.entry_unlock_ttl_h`), covers at most `cfg.web.entry_unlock_max_days` business dates, and can be expired early.
 * Every write is audited with its reason.
 */
fun Route.entryUnlockRoutes(d: EntryUnlockDeps) {
    authenticated(d.guard) {
        get("/admin/entry-unlocks") { call.respond(listUnlocks(call, d)) }
        post("/admin/entry-unlocks") { call.respond(HttpStatusCode.Created, createUnlock(call, d)) }
        post("/admin/entry-unlocks/{unlock_id}/expire") { call.respond(expireUnlock(call, d)) }
    }
}

private fun grantRoles(d: EntryUnlockDeps): Set<Role> =
    runCatching { d.config.value("cfg.web.entry_unlock_roles").jsonArray.mapNotNull { e -> Role.entries.firstOrNull { it.wire == e.jsonPrimitive.content } }.toSet() }
        .getOrNull()?.takeIf { it.isNotEmpty() } ?: setOf(Role.SUPPORT, Role.ADMIN)

private fun mapUnlock(rs: ResultSet, now: OffsetDateTime): EntryUnlockDto {
    val expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java)
    val closed = rs.getObject("expired_at", OffsetDateTime::class.java)
    val effectiveEnd = if (closed != null && closed.isBefore(expiresAt)) closed else expiresAt
    return EntryUnlockDto(
        rs.getLong("id"), rs.getString("scope_type"), rs.getLong("scope_id"), rs.getObject("from_date", java.time.LocalDate::class.java).toString(), rs.getObject("to_date", java.time.LocalDate::class.java).toString(),
        rs.getString("reason"), effectiveEnd.toInstant().wire(), rs.getLong("created_by"), if (effectiveEnd.isAfter(now)) "active" else "expired",
    )
}

private const val UNLOCK_COLS = "u.id, u.scope_type, u.scope_id, u.from_date, u.to_date, u.reason, u.expires_at, u.expired_at, u.created_by"

/** The zone a grant covers (a route grant: the route's zone); null when the scope does not exist. */
private fun zoneOfScope(h: Handle, type: String, id: Long): Long? =
    h.createQuery(if (type == "zone") "SELECT id FROM app.zone WHERE id = :i" else "SELECT zone_id FROM app.route WHERE id = :i").bind("i", id).mapTo(Long::class.java).findOne().orElse(null)

private fun inReach(d: EntryUnlockDeps, call: ApplicationCall, type: String, id: Long, zone: Long): Boolean {
    val r = AdminSupport.reach(d.reach, call, d.clock)
    return if (type == "zone") r.coversZone(zone) else r.coversRoute(id, zone)
}

private fun listUnlocks(call: ApplicationCall, d: EntryUnlockDeps): EntryUnlockPageDto {
    val p = call.principal
    if (p.role !in grantRoles(d) + Role.SUPERADMIN) throw AdminSupport.forbidden("entry unlocks are not available to this role")
    val limit = AdminSupport.limit(call)
    val zoneSel = AdminSupport.queryId(call, "zone_id")
    val activeOnly = call.request.queryParameters["active"]?.let { it.toBooleanStrictOrNull() ?: bad("query.active") }
    val cursor = AdminSupport.decodeCursor(call, 1)?.let { (a) -> a.toLongOrNull()?.takeIf { it >= 1 } ?: bad("query.cursor") }
    val reach = AdminSupport.reach(d.reach, call, d.clock)
    if (zoneSel != null && !reach.coversZone(zoneSel)) throw AdminSupport.outOfScope("zone outside your reach")
    val now = AdminSupport.utc(d.clock)
    return d.db.jdbi.withHandle<EntryUnlockPageDto, Exception> { h ->
        val where = mutableListOf<String>()
        // A grant is visible when its zone is in the caller's reach (national sees all).
        if (!reach.national) where += "COALESCE(CASE WHEN u.scope_type = 'zone' THEN u.scope_id ELSE (SELECT r.zone_id FROM app.route r WHERE r.id = u.scope_id) END, 0) IN (<zones>)"
        if (zoneSel != null) where += "CASE WHEN u.scope_type = 'zone' THEN u.scope_id ELSE (SELECT r.zone_id FROM app.route r WHERE r.id = u.scope_id) END = :zone"
        if (activeOnly == true) where += "u.expired_at IS NULL AND u.expires_at > :now"
        if (activeOnly == false) where += "(u.expired_at IS NOT NULL OR u.expires_at <= :now)"
        if (cursor != null) where += "u.id < :c"
        val q = h.createQuery("SELECT $UNLOCK_COLS FROM app.entry_unlock u ${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")} ORDER BY u.id DESC LIMIT :lim").bind("lim", limit + 1).bind("now", now)
        if (!reach.national) q.bindList("zones", reach.zoneIds.toList().ifEmpty { listOf(0L) })
        if (zoneSel != null) q.bind("zone", zoneSel)
        if (cursor != null) q.bind("c", cursor)
        val rows = q.map { rs, _ -> mapUnlock(rs, now) }.list()
        val page = rows.take(limit)
        EntryUnlockPageDto(page, if (rows.size > limit) AdminSupport.encodeCursor(page.last().unlock_id) else null)
    }
}

private suspend fun createUnlock(call: ApplicationCall, d: EntryUnlockDeps): EntryUnlockDto {
    val p = call.principal
    if (p.role !in grantRoles(d) + Role.SUPERADMIN) throw AdminSupport.forbidden("only ${grantRoles(d).joinToString { it.wire }} grant back-dated entry")
    val req = call.receiveStrict(EntryUnlockIn.serializer())
    if (req.scope_type !in setOf("zone", "route")) bad("body.scope_type")
    if (req.scope_id < 1) bad("body.scope_id")
    val from = AdminSupport.date(req.from, "body.from"); val to = AdminSupport.date(req.to, "body.to")
    AdminSupport.noNul("body.reason", req.reason)
    val reason = req.reason.trim()
    if (req.reason.length > 500 || reason.length < 10) bad("body.reason", "length")
    val today = AdminSupport.today(d.clock)
    val maxDays = runCatching { d.config.int("cfg.web.entry_unlock_max_days") }.getOrDefault(7)
    if (to.isBefore(from)) bad("body.to", "before_from")
    if (ChronoUnit.DAYS.between(from, to) + 1 > maxDays) bad("body.to", "above_max_days")
    if (to.isAfter(today)) bad("body.to", "future")
    val ttl = req.ttl_h ?: runCatching { d.config.int("cfg.web.entry_unlock_ttl_h") }.getOrDefault(24)
    if (ttl !in 1..168) bad("body.ttl_h", "out_of_range")
    val now = AdminSupport.utc(d.clock)
    return d.db.jdbi.inTransaction<EntryUnlockDto, Exception> { h ->
        val zone = zoneOfScope(h, req.scope_type, req.scope_id)
        if (zone == null || !inReach(d, call, req.scope_type, req.scope_id, zone)) throw AdminSupport.outOfScope("the ${req.scope_type} does not exist or is outside your reach")
        val id = h.createQuery(
            "INSERT INTO app.entry_unlock (scope_type, scope_id, from_date, to_date, reason, expires_at, created_by, created_at) VALUES (:t, :i, :f, :to, :r, :exp, :by, :now) RETURNING id",
        ).bind("t", req.scope_type).bind("i", req.scope_id).bind("f", from).bind("to", to).bind("r", reason).bind("exp", now.plusHours(ttl.toLong())).bind("by", p.userId).bind("now", now).mapTo(Long::class.java).one()
        AuditWriter.write(h, p, "entry_unlock", id.toString(), "create", null, buildJsonObject {
            put("scope_type", req.scope_type); put("scope_id", req.scope_id); put("from", from.toString()); put("to", to.toString()); put("ttl_h", ttl)
        }, reason, call.requestId)
        h.createQuery("SELECT $UNLOCK_COLS FROM app.entry_unlock u WHERE u.id = :i").bind("i", id).map { rs, _ -> mapUnlock(rs, now) }.one()
    }
}

private suspend fun expireUnlock(call: ApplicationCall, d: EntryUnlockDeps): EntryUnlockDto {
    val p = call.principal
    if (p.role !in grantRoles(d) + Role.SUPERADMIN) throw AdminSupport.forbidden("only ${grantRoles(d).joinToString { it.wire }} expire entry unlocks")
    val id = call.parameters["unlock_id"]?.toLongOrNull()?.takeIf { it >= 1 } ?: bad("path.unlock_id")
    val now = AdminSupport.utc(d.clock)
    return d.db.jdbi.inTransaction<EntryUnlockDto, Exception> { h ->
        val row = h.createQuery("SELECT u.scope_type, u.scope_id, u.expired_at, u.expires_at FROM app.entry_unlock u WHERE u.id = :i FOR UPDATE").bind("i", id)
            .map { rs, _ -> arrayOf(rs.getString(1), rs.getLong(2), rs.getObject(3, OffsetDateTime::class.java), rs.getObject(4, OffsetDateTime::class.java)) }.findOne().orElse(null)
            ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "entry unlock $id")
        val zone = zoneOfScope(h, row[0] as String, row[1] as Long)
        if (zone == null || !inReach(d, call, row[0] as String, row[1] as Long, zone)) throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "entry unlock $id")
        // Already expired (by hand or by time): the answer is the stored grant, nothing is written twice.
        if (row[2] == null && (row[3] as OffsetDateTime).isAfter(now)) {
            h.createUpdate("UPDATE app.entry_unlock SET expired_at = :now, expired_by = :by WHERE id = :i").bind("now", now).bind("by", p.userId).bind("i", id).execute()
            AuditWriter.write(h, p, "entry_unlock", id.toString(), "expire", null, buildJsonObject { put("expired_at", now.toInstant().wire()) }, null, call.requestId)
        }
        h.createQuery("SELECT $UNLOCK_COLS FROM app.entry_unlock u WHERE u.id = :i").bind("i", id).map { rs, _ -> mapUnlock(rs, now) }.one()
    }
}
