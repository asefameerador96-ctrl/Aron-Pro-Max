package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jdbi.v3.core.Handle

/**
 * The generic `/v1/admin` master-data framework (F-API-035): one descriptor per entity, one engine for the five
 * verbs. Every write runs in one transaction that holds the row lock, checks `If-Match` against the row `version`
 * (412 `ERR_PRECONDITION_FAILED`), applies the change (the database trigger bumps `version`), and appends the audit
 * row through `AuditWriter` before it commits. Lists are filtered by the caller's server-side reach and keyset-paged.
 * Rows are never deleted: `status` is the soft switch, guarded by [MasterEntity.guardDeactivate] (`ERR_MASTER_IN_USE`).
 */
internal class MasterEntity(
    val audit: String,
    val table: String,
    val cols: List<Col>,
    /** Reach predicate on alias `t`; null means unrestricted (national). */
    val reach: (Reach, Geo) -> Pred?,
    val out: (Map<String, Any?>, AronPrincipal) -> JsonObject,
    /** JSON members accepted on top of [cols] (for example `effective_from`, or a null `parent_id` on a wing). */
    val extraKeys: Set<String> = emptySet(),
    val statuses: Set<String> = setOf("active", "inactive"),
    val zoneCol: String? = null,
    val parentCol: String? = null,
    val searchCols: List<String> = listOf("name"),
    val hasUpdatedAt: Boolean = true,
    /** Whether the contract's create schema carries `change_reason` (user, route and cluster creates do not). */
    val createReason: Boolean = true,
    val actorCols: Boolean = true,
    /** Business checks; [merged] is the row as it will be (current values over the changes), [changes] only what was sent. */
    val validate: (Handle, AdminCtx, Map<String, Any?>, Map<String, Any?>?, Map<String, Any?>) -> Unit = { _, _, _, _, _ -> },
    /** Extra columns derived at write time (location basis, scope_version, ...). Reserved keys: `__touch` forces a version bump, `!col` drops a requested change from the row, `@note` is audit-only. */
    val derive: (Handle, AdminCtx, Map<String, Any?>, Map<String, Any?>?, Map<String, Any?>, Fields?) -> Map<String, Any?> = { _, _, _, _, _, _ -> emptyMap() },
    val guardDeactivate: (Handle, AdminCtx, Long) -> Unit = { _, _, _ -> },
    /** Side effects inside the write transaction (history rows, scope_version bumps, token revocation). */
    val after: (Handle, AdminCtx, Map<String, Any?>, Map<String, Any?>?, Map<String, Any?>, Fields?) -> Unit = { _, _, _, _, _, _ -> },
    val isActive: (Map<String, Any?>) -> Boolean = { it["status"] == "active" },
) {
    val allowedCreate: Set<String> get() = cols.filter { it.onCreate }.map { it.name }.toSet() + extraKeys + (if (createReason) setOf("change_reason") else emptySet())
    val allowedPatch: Set<String> get() = cols.filter { it.onPatch }.map { it.name }.toSet() + extraKeys + "change_reason"
}

private fun same(a: Any?, b: Any?): Boolean = if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b

internal fun AdminCtx.visibleRow(h: Handle, e: MasterEntity, id: Long, lock: Boolean): Map<String, Any?>? {
    val pred = e.reach(reach, d.geo.geo())
    val sql = "SELECT t.* FROM ${e.table} t WHERE t.id = :id" + (pred?.let { " AND (${it.sql})" } ?: "") + (if (lock) " FOR UPDATE OF t" else "")
    return h.createQuery(sql).bind("id", id).bindPred(pred).mapToMap().findOne().orElse(null)
}

internal fun conflictVersion(current: Number): ApiProblem =
    ApiProblem(ProblemCode.ERR_PRECONDITION_FAILED, "the row changed since it was read; fetch it again", headers = mapOf("ETag" to "\"${current.toInt()}\""))

// ---- list ----------------------------------------------------------------------------------------------------------

internal fun adminList(call: ApplicationCall, d: AdminMasterDeps, p: AronPrincipal, e: MasterEntity, extraWhere: Pair<String, Map<String, Any>>? = null): JsonObject {
    val q = call.request.queryParameters
    val limit = call.admLimit()
    val status = call.admStatus(e.statuses)
    val since = if (e.hasUpdatedAt) call.admUpdatedSince() else null
    val search = call.admSearch()
    val cursor = q["cursor"]?.let { AdminCursor.decode(it, since != null) }
    val zoneSel = e.zoneCol?.let { call.admQueryLong("zone_id") }
    val terrSel = e.zoneCol?.let { call.admQueryLong("territory_id") }
    val parentSel = e.parentCol?.let { call.admQueryLong("parent_id") }
    val ctx = d.ctx(call, p)
    val geo = d.geo.geo()
    var selZones: Set<Long>? = null
    fun narrow(zs: Set<Long>) { selZones = (selZones ?: zs).intersect(zs) }
    if (zoneSel != null) {
        if (!ctx.reach.coversZone(zoneSel)) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "zone outside your reach")
        narrow(setOf(zoneSel))
    }
    if (terrSel != null) {
        val zs = geo.zonesUnder("territory", terrSel)
        if (!ctx.reach.national && zs.none { it in ctx.reach.zoneIds }) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "territory outside your reach")
        narrow(zs)
    }
    val pred = e.reach(ctx.reach, geo)
    val where = mutableListOf<String>()
    if (pred != null) where += "(${pred.sql})"
    if (selZones != null) where += if (selZones!!.isEmpty()) "FALSE" else "t.${e.zoneCol} IN (<selzones>)"
    if (status != null) where += "t.status = :status"
    if (since != null) where += "t.updated_at > :since"
    if (search != null) where += "(" + e.searchCols.joinToString(" OR ") { "t.$it ILIKE :search" } + ")"
    if (parentSel != null) where += "t.${e.parentCol} = :parent"
    if (extraWhere != null) where += extraWhere.first
    if (cursor != null) where += if (since != null) "(t.updated_at, t.id) > (:c_at, :c_id)" else "t.id > :c_id"
    val order = if (since != null) "t.updated_at, t.id" else "t.id"
    val rows = d.db.jdbi.withHandle<List<Map<String, Any?>>, Exception> { h ->
        val st = h.createQuery("SELECT t.* FROM ${e.table} t ${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")} ORDER BY $order LIMIT :lim")
            .bind("lim", limit + 1).bindPred(pred)
        if (selZones != null && selZones!!.isNotEmpty()) st.bindList("selzones", selZones!!.toList())
        if (status != null) st.bind("status", status)
        if (since != null) st.bind("since", odt(since))
        if (search != null) st.bind("search", search)
        if (parentSel != null) st.bind("parent", parentSel)
        extraWhere?.second?.forEach { (k, v) -> st.bind(k, v) }
        if (cursor != null) { st.bind("c_id", cursor.id); cursor.at?.let { st.bind("c_at", odt(it)) } }
        st.mapToMap().list()
    }
    val page = rows.take(limit)
    val next = if (rows.size > limit) page.last().let { r ->
        AdminCursor(if (since != null) (r["updated_at"] as java.sql.Timestamp).toInstant() else null, (r["id"] as Number).toLong()).encode()
    } else null
    return JsonObject(mapOf("items" to JsonArray(page.map { e.out(it, p) }), "next_cursor" to (next?.let { JsonPrimitive(it) } ?: JsonNull)))
}

// ---- read one --------------------------------------------------------------------------------------------------------

internal suspend fun adminGet(call: ApplicationCall, d: AdminMasterDeps, p: AronPrincipal, e: MasterEntity, id: Long) {
    val ctx = d.ctx(call, p)
    val row = d.db.jdbi.withHandle<Map<String, Any?>?, Exception> { h -> ctx.visibleRow(h, e, id, false) } ?: admNotFound()
    call.admRespond(HttpStatusCode.OK, e.out(row, p), (row["version"] as Number).toInt())
}

// ---- create ----------------------------------------------------------------------------------------------------------

/** Validates, inserts, audits and runs the after-hook; returns the stored row. Call inside the caller's transaction. */
internal fun adminInsert(h: Handle, ctx: AdminCtx, e: MasterEntity, vals: Map<String, Any?>, fields: Fields?, reason: String?, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> {
    e.validate(h, ctx, vals, null, vals)
    val derived = e.derive(h, ctx, vals, null, vals, fields)
    val all = LinkedHashMap<String, Any?>()
    all.putAll(vals); all.putAll(extra); all.putAll(derived)
    if (e.actorCols) { all["created_by"] = ctx.p.userId; all["updated_by"] = ctx.p.userId }
    val names = all.keys.toList()
    val override = if ("id" in all) " OVERRIDING SYSTEM VALUE" else ""
    val st = h.createQuery("INSERT INTO ${e.table} (${names.joinToString(",")})$override VALUES (${names.joinToString(",") { ":$it" }}) RETURNING *")
    names.forEach { st.bindAny(it, all[it]) }
    val row = st.mapToMap().one()
    val id = (row["id"] as Number).toLong()
    ctx.audit(h, e.audit, id.toString(), "create", null, JsonObject(vals.mapValues { jv(it.value) } + ("id" to JsonPrimitive(id))), reason)
    e.after(h, ctx, row, null, vals, fields)
    return row
}

internal suspend fun adminCreate(call: ApplicationCall, d: AdminMasterDeps, p: AronPrincipal, e: MasterEntity) {
    val f = call.admBody(e.allowedCreate)
    val vals = f.read(e.cols, true)
    val reason = f.reason(d.requireReason && e.createReason)
    val ctx = d.ctx(call, p)
    val row = admWrite { d.db.jdbi.inTransaction<Map<String, Any?>, Exception> { h -> adminInsert(h, ctx, e, vals, f, reason) } }
    d.geo.invalidate()
    call.admRespond(HttpStatusCode.Created, e.out(row, p), (row["version"] as Number).toInt())
}

// ---- update ----------------------------------------------------------------------------------------------------------

/** One guarded update: lock, If-Match, validate, apply, audit. A patch that changes nothing returns the row untouched (no new version, no audit row). */
internal fun adminUpdate(h: Handle, ctx: AdminCtx, e: MasterEntity, id: Long, ifMatch: Int, vals: Map<String, Any?>, fields: Fields?, reason: String?): Map<String, Any?> {
    val cur = ctx.visibleRow(h, e, id, true) ?: admNotFound()
    if ((cur["version"] as Number).toInt() != ifMatch) throw conflictVersion(cur["version"] as Number)
    val merged = LinkedHashMap(cur).also { it.putAll(vals) }
    e.validate(h, ctx, merged, cur, vals)
    val changes = vals.filter { (k, v) -> !same(cur[k], v) }
    if ("status" in changes && e.isActive(cur) && changes["status"] !in setOf("active")) e.guardDeactivate(h, ctx, id)
    val extra = e.derive(h, ctx, merged, cur, changes, fields)
    val touch = extra["__touch"] == true
    val notes = extra.filterKeys { it.startsWith("@") }
    val columns = extra.filterKeys { it != "__touch" && !it.startsWith("@") && !it.startsWith("!") }
    val set = LinkedHashMap<String, Any?>().also { m ->
        m.putAll(changes); extra.keys.filter { it.startsWith("!") }.forEach { m.remove(it.substring(1)) }
        columns.forEach { (k, v) -> if (!same(cur[k], v)) m[k] = v }
    }
    if (set.isEmpty() && !touch) return cur
    if (e.actorCols) set["updated_by"] = ctx.p.userId
    val st = h.createQuery("UPDATE ${e.table} SET ${set.keys.joinToString(",") { "$it = :$it" }} WHERE id = :id RETURNING *")
    set.forEach { (k, v) -> st.bindAny(k, v) }
    val row = st.bind("id", id).mapToMap().one()
    val beforeObj = HashMap<String, JsonElement>().also { m -> changes.keys.forEach { m[it] = jv(cur[it]) }; m["version"] = jv(cur["version"]) }
    val afterObj = HashMap<String, JsonElement>().also { m -> changes.forEach { (k, v) -> m[k] = jv(v) }; m["version"] = jv(row["version"]) }
    columns.forEach { (k, v) -> if (k != "updated_by" && !same(cur[k], v) && k !in changes) { beforeObj[k] = jv(cur[k]); afterObj[k] = jv(v) } }
    notes.forEach { (k, v) -> afterObj[k.substring(1)] = jv(v) }
    ctx.audit(h, e.audit, id.toString(), "update", JsonObject(beforeObj), JsonObject(afterObj), reason)
    e.after(h, ctx, row, cur, changes, fields)
    return row
}

internal suspend fun adminPatch(call: ApplicationCall, d: AdminMasterDeps, p: AronPrincipal, e: MasterEntity, id: Long) {
    val ifMatch = call.admIfMatch()
    val f = call.admBody(e.allowedPatch)
    val vals = f.read(e.cols, false)
    if (f.obj.keys.none { it != "change_reason" } ) admBad("body", "empty", "at least one field is required")
    val reason = f.reason(d.requireReason)
    val ctx = d.ctx(call, p)
    val row = admWrite { d.db.jdbi.inTransaction<Map<String, Any?>, Exception> { h -> adminUpdate(h, ctx, e, id, ifMatch, vals, f, reason) } }
    d.geo.invalidate()
    call.admRespond(HttpStatusCode.OK, e.out(row, p), (row["version"] as Number).toInt())
}

/** `count(*)` helper for the in-use guards. */
internal fun Handle.count(sql: String, vararg binds: Pair<String, Any?>): Long = scalarLong(sql, *binds)

internal fun inUse(what: String): Nothing = throw ApiProblem(ProblemCode.ERR_MASTER_IN_USE, what)

/** Roles allowed to see credentials-adjacent data are decided by the caller; this only exposes the role check for entities. */
internal fun Role.isNationalAdmin() = this == Role.ADMIN || this == Role.SUPERADMIN
