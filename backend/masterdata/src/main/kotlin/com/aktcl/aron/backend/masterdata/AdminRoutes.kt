package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.contract.ProblemCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jdbi.v3.core.Handle
import java.time.LocalDate

private val OPEN_ASSIGNMENT = "(valid_to IS NULL OR valid_to > :today) AND ended_at IS NULL"

/** Routes of the caller's zones, plus any route assigned to the caller. */
internal fun routeReachPred(alias: String): (Reach, Geo) -> Pred? = { reach, _ ->
    if (reach.national) null else {
        val z = reach.zoneIds.toList(); val r = reach.routeIds.toList()
        when {
            z.isEmpty() && r.isEmpty() -> Pred.NONE
            r.isEmpty() -> Pred("$alias.zone_id IN (<r_z>)", mapOf("r_z" to z))
            z.isEmpty() -> Pred("$alias.id IN (<r_r>)", mapOf("r_r" to r))
            else -> Pred("($alias.zone_id IN (<r_z>) OR $alias.id IN (<r_r>))", mapOf("r_z" to z, "r_r" to r))
        }
    }
}

private fun pastProblem(pointer: String, msg: String) = ApiProblem(ProblemCode.ERR_MASTER_EFFECTIVE_DATE_PAST, msg, errors = listOf(FieldError(pointer, "past")))

private fun kindMatches(h: Handle, kind: String?, mask: Int): Boolean =
    h.createQuery("SELECT app.visit_kind_matches(CAST(:k AS text), CAST(:m AS smallint))").bindAny("k", kind).bind("m", mask).mapTo(Boolean::class.java).one()

private fun Handle.openAssignments(routeId: Any?, today: LocalDate): Long =
    count("SELECT count(*) FROM app.route_assignment WHERE route_id = :r AND $OPEN_ASSIGNMENT", "r" to routeId, "today" to today)

internal val ROUTE = MasterEntity(
    audit = "route", table = "app.route", createReason = false,
    cols = listOf(
        Col("code", Kind.TEXT, required = true, onPatch = false, min = 1, max = 40, pattern = CODE_RE),
        Col("name", Kind.TEXT, required = true, min = 1, max = 120),
        Col("display_label", Kind.TEXT, nullable = true, max = 60),
        Col("zone_id", Kind.LONG, required = true, min = 1),
        Col("kind", Kind.ENUM, required = true, onPatch = false, values = setOf("sr", "amo")),
        Col("visit_kind", Kind.ENUM, nullable = true, values = setOf("daily", "3f", "2f")),
        Col("visit_days_mask", Kind.INT, required = true, min = 1, max = 127),
        Col("sequence_no", Kind.INT, nullable = true, min = 1, max = 100000),
        Col("status", Kind.ENUM, onCreate = false, values = STATUS_VALUES),
    ),
    extraKeys = setOf("effective_from"),
    reach = routeReachPred("t"), zoneCol = "zone_id", searchCols = listOf("code", "name"),
    out = { r, _ ->
        JsonObject(mapOf(
            "id" to jv(r["id"]), "code" to jv(r["code"]), "name" to jv(r["name"]), "display_label" to jv(r["display_label"]), "zone_id" to jv(r["zone_id"]),
            "kind" to jv(r["kind"]), "visit_kind" to jv(r["visit_kind"]), "visit_days_mask" to jv(r["visit_days_mask"]), "sequence_no" to jv(r["sequence_no"]),
            "status" to jv(r["status"]), "created_at" to jv(r["created_at"]), "updated_at" to jv(r["updated_at"]), "version" to jv(r["version"]),
        ))
    },
    validate = { h, ctx, merged, cur, changes ->
        if (cur == null || "zone_id" in changes) {
            val z = merged["zone_id"] as Long
            val ok = ctx.reach.coversZone(z) && h.createQuery("SELECT status FROM app.zone WHERE id = :z").bind("z", z).mapTo(String::class.java).findOne().orElse(null) == "active"
            if (!ok) admBad("body.zone_id", "unknown_zone", "the zone does not exist, is inactive, or is outside your reach")
            if (cur != null && (h.count("SELECT count(*) FROM app.outlet WHERE route_id = :r", "r" to cur["id"]) > 0 || h.openAssignments(cur["id"], ctx.today) > 0))
                inUse("the route has outlets or live assignments; move them before moving the route to another zone")
        }
        if (cur == null || "visit_kind" in changes || "visit_days_mask" in changes) {
            if (!kindMatches(h, merged["visit_kind"] as String?, (merged["visit_days_mask"] as Number).toInt()))
                admBad("body.visit_kind", "mismatch", "the visit kind does not match the number of visit days")
        }
    },
    derive = { h, ctx, merged, cur, changes, f ->
        // A change of visit days is also a body that names them equal to the route's columns while a different schedule is still pending in route_planned (reverting a pending change).
        val pendingDiffers = cur != null && f != null && f.has("effective_from") && (f.has("visit_kind") || f.has("visit_days_mask")) && h.createQuery(
            "SELECT visit_kind IS DISTINCT FROM :k OR visit_days_mask <> :m FROM app.route_planned WHERE route_id = :r ORDER BY valid_from DESC LIMIT 1",
        ).bind("r", cur["id"]).bindAny("k", merged["visit_kind"]).bind("m", (merged["visit_days_mask"] as Number).toInt()).mapTo(Boolean::class.java).findOne().orElse(false)
        if (cur == null) emptyMap()
        else if ("visit_kind" in changes || "visit_days_mask" in changes || pendingDiffers) {
            // A visit-days change is effective-dated: the history stays in route_planned, the planner reads it from there (docs/24 s12.1).
            if (f == null || !f.has("effective_from")) admBad("body.effective_from", "required", "a visit-days change needs effective_from (a future Dhaka date)")
            val eff = parseDate("body.effective_from", f.obj["effective_from"]) ?: admBad("body.effective_from")
            if (!eff.isAfter(ctx.today)) throw pastProblem("body.effective_from", "a visit-days change takes effect on a future date")
            val rid = cur["id"]
            h.createUpdate("DELETE FROM app.route_planned WHERE route_id = :r AND valid_from >= :e").bind("r", rid).bind("e", eff).execute()
            h.createUpdate("UPDATE app.route_planned SET valid_to = :e WHERE route_id = :r AND valid_from < :e AND (valid_to IS NULL OR valid_to > :e)").bind("r", rid).bind("e", eff).execute()
            h.createUpdate("INSERT INTO app.route_planned (route_id, visit_kind, visit_days_mask, valid_from, created_by) VALUES (:r, :k, :m, :e, :by)")
                .bind("r", rid).bindAny("k", merged["visit_kind"]).bind("m", (merged["visit_days_mask"] as Number).toInt()).bind("e", eff).bind("by", ctx.p.userId).execute()
            buildMap {
                put("!visit_kind", true); put("!visit_days_mask", true); put("@effective_from", eff.toString()); put("__touch", true)
            }
        } else if (f != null && f.has("effective_from")) admBad("body.effective_from", "unexpected", "effective_from applies to visit-days changes only")
        else emptyMap()
    },
    guardDeactivate = { h, ctx, id ->
        val outlets = h.count("SELECT count(*) FROM app.outlet WHERE route_id = :r AND status = 'active'", "r" to id)
        val asg = h.openAssignments(id, ctx.today)
        if (outlets > 0 || asg > 0) inUse("the route still has $outlets active outlets and $asg live assignments")
    },
    after = { h, ctx, row, cur, _, _ ->
        if (cur == null) h.createUpdate("INSERT INTO app.route_planned (route_id, visit_kind, visit_days_mask, valid_from, created_by) VALUES (:r, :k, :m, :f, :by)")
            .bind("r", row["id"]).bindAny("k", row["visit_kind"]).bind("m", (row["visit_days_mask"] as Number).toInt()).bind("f", ctx.today).bind("by", ctx.p.userId).execute()
    },
)

// ---- assignments -------------------------------------------------------------------------------------------------

private fun assignmentJson(r: Map<String, Any?>) = JsonObject(mapOf(
    "id" to jv(r["id"]), "route_id" to jv(r["route_id"]), "user_id" to jv(r["user_id"]), "kind" to jv(r["kind"]), "valid_from" to jv(r["valid_from"]),
    "valid_to" to jv(r["valid_to"]), "reason" to jv(r["reason"]), "created_at" to jv(r["created_at"]),
))

private fun listAssignments(call: ApplicationCall, d: AdminMasterDeps): JsonObject {
    val p = call.admPrincipal(MASTER_READERS, "route assignments")
    val limit = call.admLimit()
    val routeSel = call.admQueryLong("route_id"); val userSel = call.admQueryLong("user_id"); val zoneSel = call.admQueryLong("zone_id")
    val validOn = call.admDate("valid_on")
    val cursor = call.request.queryParameters["cursor"]?.let { AdminCursor.decode(it, false) }
    val ctx = d.ctx(call, p)
    if (zoneSel != null && !ctx.reach.coversZone(zoneSel)) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "zone outside your reach")
    val pred = routeReachPred("r")(ctx.reach, d.geo.geo())
    val rows = d.db.jdbi.withHandle<List<Map<String, Any?>>, Exception> { h ->
        val where = mutableListOf<String>()
        if (pred != null) where += "(${pred.sql})"
        if (routeSel != null) where += "a.route_id = :route"
        if (userSel != null) where += "a.user_id = :user"
        if (zoneSel != null) where += "r.zone_id = :zone"
        where += if (validOn != null) "a.valid_from <= :on AND (a.valid_to IS NULL OR a.valid_to > :on)" else "(a.valid_to IS NULL OR a.valid_to > :today)"
        if (cursor != null) where += "a.id > :c"
        val q = h.createQuery("SELECT a.* FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id WHERE ${where.joinToString(" AND ")} ORDER BY a.id LIMIT :lim")
            .bind("lim", limit + 1).bind("today", ctx.today).bindPred(pred)
        if (routeSel != null) q.bind("route", routeSel)
        if (userSel != null) q.bind("user", userSel)
        if (zoneSel != null) q.bind("zone", zoneSel)
        if (validOn != null) q.bind("on", validOn)
        if (cursor != null) q.bind("c", cursor.id)
        q.mapToMap().list()
    }
    val page = rows.take(limit)
    return JsonObject(mapOf(
        "items" to JsonArray(page.map(::assignmentJson)),
        "next_cursor" to (if (rows.size > limit) JsonPrimitive(AdminCursor(null, (page.last()["id"] as Number).toLong()).encode()) else JsonNull),
    ))
}

private suspend fun createAssignment(call: ApplicationCall, d: AdminMasterDeps) {
    val p = call.admPrincipal(MASTER_WRITERS, "route assignment changes")
    val f = call.admBody(setOf("route_id", "user_id", "kind", "valid_from", "valid_to", "reason"))
    fun need(k: String) { if (!f.has(k)) admBad("body.$k", "required", "$k is required") }
    need("route_id"); need("user_id"); need("kind"); need("valid_from")
    val routeId = f.value(Col("route_id", Kind.LONG, min = 1)) as Long
    val userId = f.value(Col("user_id", Kind.LONG, min = 1)) as Long
    val kind = f.value(Col("kind", Kind.ENUM, values = setOf("primary", "cover"))) as String
    val from = parseDate("body.valid_from", f.obj["valid_from"]) ?: admBad("body.valid_from")
    val to = f.obj["valid_to"]?.takeIf { it !is JsonNull }?.let { parseDate("body.valid_to", it) }
    val reason = if (f.has("reason")) f.value(Col("reason", Kind.TEXT, nullable = true, max = 300)) as String? else null
    if (to != null && !to.isAfter(from)) admBad("body.valid_to", "before_valid_from", "valid_to is exclusive and must be after valid_from")
    val ctx = d.ctx(call, p)
    if (from.isBefore(ctx.today)) throw pastProblem("body.valid_from", "an assignment starts today or later")
    val row = admWrite {
        d.db.jdbi.inTransaction<Map<String, Any?>, Exception> { h ->
            val route = ctx.visibleRow(h, ROUTE, routeId, false) ?: admBad("body.route_id", "unknown_route", "the route does not exist or is outside your reach")
            if (route["status"] != "active") admBad("body.route_id", "route_inactive", "the route is inactive")
            val user = ctx.visibleRow(h, USER, userId, true) ?: admBad("body.user_id", "unknown_user", "the user does not exist or is outside your reach")
            if (user["status"] != "active") admBad("body.user_id", "user_disabled", "the user is disabled")
            val want = if (route["kind"] == "amo") "AMO" else "SR"
            if (user["role"] != want) admBad("body.user_id", "role_mismatch", "a ${route["kind"]} route is assigned to a $want user")
            val r = h.createQuery(
                "INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, valid_to, reason, created_by) VALUES (:r, :u, :k, :f, :t, :why, :by) RETURNING *",
            ).bind("r", routeId).bind("u", userId).bind("k", kind).bind("f", from).bindAny("t", to).bindAny("why", reason).bind("by", p.userId).mapToMap().one()
            h.createUpdate("UPDATE app.app_user SET scope_version = scope_version + 1 WHERE id = :u").bind("u", userId).execute()
            ctx.audit(h, "route_assignment", r["id"].toString(), "create", null, assignmentJson(r), reason)
            r
        }
    }
    call.admRespond(HttpStatusCode.Created, assignmentJson(row))
}

private suspend fun endAssignment(call: ApplicationCall, d: AdminMasterDeps) {
    val p = call.admPrincipal(MASTER_WRITERS, "route assignment changes")
    val id = call.admId()
    val f = call.admBody(setOf("valid_to", "reason"))
    if (!f.has("valid_to")) admBad("body.valid_to", "required")
    val to = parseDate("body.valid_to", f.obj["valid_to"]) ?: admBad("body.valid_to")
    val reason = f.reason(true, "reason")
    val ctx = d.ctx(call, p)
    if (to.isBefore(ctx.today)) throw pastProblem("body.valid_to", "an assignment is ended today or later")
    val row = admWrite {
        d.db.jdbi.inTransaction<Map<String, Any?>, Exception> { h ->
            val cur = h.createQuery("SELECT a.*, r.zone_id AS route_zone FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id WHERE a.id = :i FOR UPDATE OF a").bind("i", id).mapToMap().findOne().orElse(null)
                ?: admNotFound()
            if (!ctx.reach.coversRoute(cur["route_id"] as Long, cur["route_zone"] as Long)) admNotFound()
            val from = (cur["valid_from"] as java.sql.Date).toLocalDate()
            val curTo = (cur["valid_to"] as java.sql.Date?)?.toLocalDate()
            if (cur["ended_at"] != null) {
                if (curTo == to) return@inTransaction cur // the same end request replayed
                throw ApiProblem(ProblemCode.ERR_CONFLICT, "the assignment already ended on $curTo")
            }
            if (!to.isAfter(from)) admBad("body.valid_to", "before_valid_from", "valid_to is exclusive and must be after valid_from")
            if (curTo != null && to.isAfter(curTo)) admBad("body.valid_to", "after_current_end", "an end date can only bring the end forward")
            val r = h.createQuery("UPDATE app.route_assignment SET valid_to = :t, ended_at = :at, ended_by = :by WHERE id = :i RETURNING *")
                .bind("t", to).bind("at", odt(ctx.now)).bind("by", p.userId).bind("i", id).mapToMap().one()
            h.createUpdate("UPDATE app.app_user SET scope_version = scope_version + 1 WHERE id = :u").bind("u", cur["user_id"]).execute()
            ctx.audit(h, "route_assignment", id.toString(), "end", JsonObject(mapOf("valid_to" to jv(cur["valid_to"]))), JsonObject(mapOf("valid_to" to jv(to))), reason)
            r
        }
    }
    call.admRespond(HttpStatusCode.OK, assignmentJson(row))
}

/** `GET/POST /v1/admin/routes`, `GET/PATCH /v1/admin/routes/{id}`, `GET/POST /v1/admin/route-assignments`, `POST .../{id}/end` (tag admin-routes). */
fun Route.adminRoutesRoutes(d: AdminMasterDeps) {
    authenticated(d.guard) {
        get("/admin/routes") { val p = call.admPrincipal(MASTER_READERS, "routes"); call.admRespond(HttpStatusCode.OK, withAssignees(call, d, adminList(call, d, p, ROUTE))) }
        post("/admin/routes") { val p = call.admPrincipal(MASTER_WRITERS, "route changes"); adminCreate(call, d, p, ROUTE) }
        get("/admin/routes/{id}") { val p = call.admPrincipal(MASTER_READERS, "routes"); adminGet(call, d, p, ROUTE, call.admId()) }
        patch("/admin/routes/{id}") { val p = call.admPrincipal(MASTER_WRITERS, "route changes"); adminPatch(call, d, p, ROUTE, call.admId()) }
        get("/admin/route-assignments") { call.admRespond(HttpStatusCode.OK, listAssignments(call, d)) }
        post("/admin/route-assignments") { createAssignment(call, d) }
        post("/admin/route-assignments/{id}/end") { endAssignment(call, d) }
    }
}


/**
 * `include=assignees` (contract v1.2): adds `assignees` [{user_id, full_name, role, username}] to every item of the page with ONE query
 * over the page's route ids (assignments valid today, primary and cover). Anything else for `include` is a 400.
 */
private fun withAssignees(call: ApplicationCall, d: AdminMasterDeps, page: JsonObject): JsonObject {
    val inc = call.request.queryParameters["include"] ?: return page
    if (inc != "assignees") admBad("query.include", "invalid_value")
    val items = (page["items"] as? JsonArray) ?: return page
    val ids = items.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.content?.toLongOrNull() }
    val today = java.time.LocalDate.ofInstant(d.clock.now(), java.time.ZoneId.of("Asia/Dhaka"))
    val byRoute = if (ids.isEmpty()) emptyMap() else d.db.jdbi.withHandle<Map<Long, List<JsonObject>>, Exception> { h ->
        h.createQuery(
            "SELECT a.route_id, u.id, u.full_name, u.role, u.username FROM app.route_assignment a JOIN app.app_user u ON u.id = a.user_id " +
                "WHERE a.route_id IN (<ids>) AND a.ended_at IS NULL AND a.valid_from <= :today AND (a.valid_to IS NULL OR a.valid_to > :today) ORDER BY a.route_id, a.kind, u.id",
        ).bindList("ids", ids).bind("today", today).map { rs, _ ->
            rs.getLong(1) to JsonObject(mapOf("user_id" to JsonPrimitive(rs.getLong(2)), "full_name" to JsonPrimitive(rs.getString(3)), "role" to JsonPrimitive(rs.getString(4)), "username" to JsonPrimitive(rs.getString(5))))
        }.list().groupBy({ it.first }, { it.second })
    }
    return JsonObject(page + ("items" to JsonArray(items.map { it2 ->
        val o = it2 as JsonObject
        val id = (o["id"] as? JsonPrimitive)?.content?.toLongOrNull()
        JsonObject(o + ("assignees" to JsonArray(byRoute[id].orEmpty())))
    })))
}
