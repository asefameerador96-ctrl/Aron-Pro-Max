package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jdbi.v3.core.Handle
import java.security.SecureRandom
import java.time.LocalDate

private val PHONE_RE = Regex("^01[3-9][0-9]{8}$")
private val USERNAME_RE = Regex("^[A-Za-z][A-Za-z0-9._-]{2,39}$")
private val MEMO_USERNAME_RE = Regex("^[a-z][a-z0-9]{3,31}$")
private val ROLE_VALUES = Role.entries.map { it.wire }.toSet()
private val SENIOR = setOf(Role.ADMIN, Role.SUPERADMIN)
private const val TEMP_PASSWORD_TTL_H = 72L

/** Users in reach: the home zone or a live route assignment lies in a zone of the caller's reach. */
private val userReach: (Reach, Geo) -> Pred? = { reach, _ ->
    if (reach.national) null else if (reach.zoneIds.isEmpty()) Pred.NONE else Pred(
        "(t.home_zone_id IN (<r_ids>) OR EXISTS (SELECT 1 FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id WHERE a.user_id = t.id AND a.ended_at IS NULL AND r.zone_id IN (<r_ids>)))",
        mapOf("r_ids" to reach.zoneIds.toList()),
    )
}

private fun userJson(r: Map<String, Any?>, p: AronPrincipal) = JsonObject(mapOf(
    "id" to jv(r["id"]), "username" to jv(r["username"]), "full_name" to jv(r["full_name"]), "role" to jv(r["role"]),
    "designation" to jv(r["designation"]), "employee_code" to jv(r["employee_code"]),
    "phone" to jv(if (p.pii) r["phone"] else null), "email" to jv(r["email"]), "locale" to jv(r["locale"]), "home_zone_id" to jv(r["home_zone_id"]),
    "status" to jv(r["status"]), "mfa_enabled" to jv(r["mfa_enabled"]), "pilot" to jv(r["pilot"]), "last_login_at" to jv(r["last_login_at"]),
    "created_at" to jv(r["created_at"]), "updated_at" to jv(r["updated_at"]), "version" to jv(r["version"]),
))

private fun roleOf(v: Any?): Role? = Role.entries.firstOrNull { it.wire == v }

private fun Handle.revokeFull(userId: Any?, reason: String, now: java.time.Instant) {
    createUpdate("UPDATE app.refresh_family SET revoked_at = :at, revoke_reason = :r WHERE user_id = :u AND grant_kind = 'full' AND revoked_at IS NULL")
        .bind("at", odt(now)).bind("r", reason).bind("u", userId).execute()
}

internal val USER = MasterEntity(
    audit = "user", table = "app.app_user", statuses = setOf("active", "disabled"), createReason = false,
    cols = listOf(
        Col("username", Kind.TEXT, required = true, onPatch = false, min = 3, max = 40, pattern = USERNAME_RE),
        Col("full_name", Kind.TEXT, required = true, min = 1, max = 120),
        Col("role", Kind.ENUM, required = true, values = ROLE_VALUES),
        Col("designation", Kind.TEXT, nullable = true, max = 60),
        Col("employee_code", Kind.TEXT, nullable = true, max = 40),
        Col("phone", Kind.TEXT, nullable = true, pattern = PHONE_RE, digits = true),
        Col("email", Kind.TEXT, nullable = true, max = 120),
        Col("locale", Kind.ENUM, required = true, values = setOf("bn", "en")),
        Col("home_zone_id", Kind.LONG, nullable = true, min = 1),
        Col("status", Kind.ENUM, onCreate = false, values = setOf("active", "disabled")),
        Col("pilot", Kind.BOOL),
    ),
    reach = userReach, zoneCol = "home_zone_id", searchCols = listOf("username", "full_name"),
    out = { r, p -> userJson(r, p) },
    validate = { h, ctx, merged, cur, changes ->
        val newRole = roleOf(merged["role"])!!
        val curRole = cur?.let { roleOf(it["role"]) }
        if (ctx.p.role == Role.ADMIN && (newRole in SENIOR || curRole in SENIOR))
            throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "only a SUPERADMIN manages ADMIN and SUPERADMIN users")
        if (cur != null && cur["id"] == ctx.p.userId && ("role" in changes && changes["role"] != cur["role"] || changes["status"] == "disabled"))
            throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "you cannot change your own role or disable yourself")
        if (newRole in setOf(Role.SR, Role.AMO) && !MEMO_USERNAME_RE.matches(merged["username"] as String))
            admBad("body.username", "memo_username", "SR and AMO usernames are 4 to 32 lower-case letters and digits (memo numbers embed them)")
        if (merged["home_zone_id"] != null && (cur == null || "home_zone_id" in changes)) {
            val z = merged["home_zone_id"] as Long
            val ok = ctx.reach.coversZone(z) && h.count("SELECT count(*) FROM app.zone WHERE id = :z AND status = 'active'", "z" to z) > 0
            if (!ok) admBad("body.home_zone_id", "unknown_zone", "the zone does not exist or is outside your reach")
        }
    },
    derive = { _, ctx, _, cur, changes, _ ->
        if (cur == null) emptyMap() else buildMap {
            if ("status" in changes) put("disabled_at", if (changes["status"] == "disabled") odt(ctx.now) else null)
            if (changes.keys.any { it == "role" || it == "status" || it == "home_zone_id" }) put("scope_version", (cur["scope_version"] as Number).toLong() + 1)
        }
    },
    after = { h, ctx, row, cur, changes, _ ->
        if (cur != null && changes["status"] == "disabled") h.revokeFull(row["id"], "user_disabled", ctx.now)
    },
)

private fun tempPassword(): String {
    val rng = SecureRandom()
    val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"; val lower = "abcdefghijkmnpqrstuvwxyz"; val digit = "23456789"
    val all = upper + lower + digit
    val chars = mutableListOf(upper[rng.nextInt(upper.length)], lower[rng.nextInt(lower.length)], digit[rng.nextInt(digit.length)])
    repeat(11) { chars += all[rng.nextInt(all.length)] }
    chars.shuffle(rng)
    return chars.joinToString("")
}

private suspend fun createUser(call: ApplicationCall, d: AdminMasterDeps) {
    val p = call.admPrincipal(MASTER_WRITERS, "user changes")
    val f = call.admBody(USER.allowedCreate)
    val vals = f.read(USER.cols, true)
    val ctx = d.ctx(call, p)
    val temp = tempPassword()
    val hash = d.hashPassword(temp)
    val row = admWrite {
        d.db.jdbi.inTransaction<Map<String, Any?>, Exception> { h ->
            adminInsert(h, ctx, USER, vals, f, null, mapOf("password_hash" to hash, "must_change_password" to true))
        }
    }
    call.admRespond(
        HttpStatusCode.Created,
        JsonObject(mapOf(
            "user" to userJson(row, p), "temporary_password" to JsonPrimitive(temp),
            "temporary_password_expires_at" to jv(ctx.now.plusSeconds(TEMP_PASSWORD_TTL_H * 3600)),
        )),
        (row["version"] as Number).toInt(),
    )
}

// ---- credentials -------------------------------------------------------------------------------------------------

private val CRED_ACTIONS = setOf("reset_password", "unlock", "force_logout", "reset_mfa")

private suspend fun credentials(call: ApplicationCall, d: AdminMasterDeps) {
    val p = call.admPrincipal(setOf(Role.TSO, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN), "credential actions")
    val id = call.admId()
    val f = call.admBody(setOf("action", "reason"))
    val action = Col("action", Kind.ENUM, values = CRED_ACTIONS).let { if (!f.has("action")) admBad("body.action", "required"); f.value(it) as String }
    val reason = f.reason(true, "reason")
    if (p.role == Role.TSO && action !in setOf("reset_password", "unlock")) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "a TSO may reset passwords and unlock only")
    if (action == "reset_mfa" && p.role !in setOf(Role.ADMIN, Role.SUPERADMIN)) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "only an ADMIN resets MFA")
    val ctx = d.ctx(call, p)
    val temp = if (action == "reset_password") tempPassword() else null
    val hash = temp?.let { d.hashPassword(it) }
    val out = admWrite {
        d.db.jdbi.inTransaction<JsonObject, Exception> { h ->
            val u = ctx.visibleRow(h, USER, id, true) ?: admNotFound()
            val role = roleOf(u["role"])!!
            if (p.role == Role.TSO && role !in setOf(Role.SR, Role.AMO)) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "a TSO acts on SR and AMO users only")
            if (role in SENIOR && p.role != Role.SUPERADMIN) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "only a SUPERADMIN acts on ADMIN and SUPERADMIN users")
            when (action) {
                "reset_password" -> {
                    h.createUpdate("UPDATE app.app_user SET password_hash = :h, must_change_password = true, password_changed_at = NULL, scope_version = scope_version + 1 WHERE id = :id")
                        .bind("h", hash).bind("id", id).execute()
                    h.revokeFull(id, "password_changed", ctx.now)
                    unlock(h, u["username"] as String)
                }
                "unlock" -> unlock(h, u["username"] as String)
                "force_logout" -> {
                    h.revokeFull(id, "admin_force_logout", ctx.now)
                    h.createUpdate("UPDATE app.app_user SET scope_version = scope_version + 1 WHERE id = :id").bind("id", id).execute()
                }
                else -> {
                    h.createUpdate("DELETE FROM app.mfa_secret WHERE user_id = :id").bind("id", id).execute()
                    h.createUpdate("UPDATE app.app_user SET mfa_enabled = false, scope_version = scope_version + 1 WHERE id = :id").bind("id", id).execute()
                    h.revokeFull(id, "admin_force_logout", ctx.now)
                }
            }
            ctx.audit(h, "user", id.toString(), "credentials.$action", null, JsonObject(mapOf("action" to JsonPrimitive(action))), reason)
            JsonObject(buildMap {
                put("action", JsonPrimitive(action)); put("done_at", jv(ctx.now))
                if (temp != null) { put("temporary_password", JsonPrimitive(temp)); put("temporary_password_expires_at", jv(ctx.now.plusSeconds(TEMP_PASSWORD_TTL_H * 3600))) }
            })
        }
    }
    call.admRespond(HttpStatusCode.OK, out)
}

/** Lockout keys start with the lower-cased username (`username`, `username|device`, `username|device|ip`). */
private fun unlock(h: Handle, username: String) {
    val u = username.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    h.createUpdate("DELETE FROM app.auth_lockout WHERE lower(lock_key) = :u OR lower(lock_key) LIKE :p").bind("u", username.lowercase()).bind("p", "$u|%").execute()
}

// ---- scope -------------------------------------------------------------------------------------------------------

private val SCOPE_TYPES = setOf("national", "wing", "division", "territory", "zone")
private val SCOPE_TABLES = mapOf("wing" to "app.wing", "division" to "app.division", "territory" to "app.territory", "zone" to "app.zone")

private fun scopeNodes(h: Handle, userId: Long, today: LocalDate): List<Map<String, Any?>> =
    h.createQuery("SELECT node_type, node_id, valid_from, valid_to FROM app.user_scope WHERE user_id = :u AND (valid_to IS NULL OR valid_to > :t) ORDER BY valid_from, node_type, node_id LIMIT 100")
        .bind("u", userId).bind("t", today).mapToMap().list()

private fun scopeJson(h: Handle, userId: Long, today: LocalDate): JsonObject {
    val sv = h.scalarLong("SELECT scope_version FROM app.app_user WHERE id = :u", "u" to userId)
    return JsonObject(mapOf(
        "user_id" to JsonPrimitive(userId), "scope_version" to JsonPrimitive(sv),
        "nodes" to JsonArray(scopeNodes(h, userId, today).map { n ->
            JsonObject(mapOf("node_type" to jv(n["node_type"]), "node_id" to jv(n["node_id"]), "valid_from" to jv(n["valid_from"]), "valid_to" to jv(n["valid_to"])))
        }),
    ))
}

private suspend fun getScope(call: ApplicationCall, d: AdminMasterDeps) {
    val p = call.admPrincipal(USER_READERS, "user scope")
    val id = call.admId()
    val ctx = d.ctx(call, p)
    val out = d.db.jdbi.withHandle<JsonObject, Exception> { h ->
        ctx.visibleRow(h, USER, id, false) ?: admNotFound()
        scopeJson(h, id, ctx.today)
    }
    call.admRespond(HttpStatusCode.OK, out)
}

private suspend fun putScope(call: ApplicationCall, d: AdminMasterDeps) {
    val p = call.admPrincipal(MASTER_WRITERS, "user scope changes")
    val id = call.admId()
    val f = call.admBody(setOf("valid_from", "nodes", "change_reason"))
    if (!f.has("valid_from")) admBad("body.valid_from", "required")
    val from = parseDate("body.valid_from", f.obj["valid_from"]) ?: admBad("body.valid_from")
    val arr = f.obj["nodes"] as? JsonArray ?: admBad("body.nodes", "required")
    if (arr.size > 100) admBad("body.nodes", "length")
    val wanted = arr.mapIndexed { i, el ->
        val o = el as? JsonObject ?: admBad("body.nodes/$i", "invalid_type")
        o.keys.firstOrNull { it !in setOf("node_type", "node_id") }?.let { admBad("body.nodes/$i/$it", "unknown_member") }
        val nf = Fields(o, setOf("node_type", "node_id"))
        if (!nf.has("node_type")) admBad("body.nodes/$i/node_type", "required")
        if (!nf.has("node_id")) admBad("body.nodes/$i/node_id", "required")
        val type = nf.value(Col("node_type", Kind.ENUM, values = SCOPE_TYPES)) as String
        val nid = nf.value(Col("node_id", Kind.LONG, min = 0)) as Long
        if ((type == "national") != (nid == 0L)) admBad("body.nodes/$i/node_id", "scope_mismatch", "national has node_id 0 and every other type a node id")
        type to nid
    }
    if (wanted.toSet().size != wanted.size) admBad("body.nodes", "duplicate_node")
    val reason = f.reason(true)
    val ctx = d.ctx(call, p)
    if (from.isBefore(ctx.today)) throw ApiProblem(ProblemCode.ERR_MASTER_EFFECTIVE_DATE_PAST, "the scope change must take effect today or later", errors = listOf(FieldError("body.valid_from", "past")))
    val out = admWrite {
        d.db.jdbi.inTransaction<JsonObject, Exception> { h ->
            val u = ctx.visibleRow(h, USER, id, true) ?: admNotFound()
            val role = roleOf(u["role"])!!
            if (p.role == Role.ADMIN && role in SENIOR) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "only a SUPERADMIN manages ADMIN and SUPERADMIN users")
            if (role == Role.SR) admBad("body.nodes", "scope_not_applicable", "an SR's reach comes from route assignments, not scope nodes")
            val geo = d.geo.geoCovering()
            for ((i, n) in wanted.withIndex()) {
                val (type, nid) = n
                if (type == "national") { if (!ctx.reach.national) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "national scope needs national reach"); continue }
                val exists = h.count("SELECT count(*) FROM ${SCOPE_TABLES.getValue(type)} WHERE id = :i", "i" to nid) > 0
                if (!exists || (!ctx.reach.national && geo.zonesUnder(type, nid).none { it in ctx.reach.zoneIds })) admBad("body.nodes/$i/node_id", "unknown_node", "the node does not exist or is outside your reach")
            }
            val pending = wanted.toMutableSet()
            val existing = h.createQuery("SELECT id, node_type, node_id, valid_from, valid_to FROM app.user_scope WHERE user_id = :u AND (valid_to IS NULL OR valid_to > :f) ORDER BY id FOR UPDATE")
                .bind("u", id).bind("f", from).mapToMap().list()
            val before = ArrayList<JsonElement>(); val after = ArrayList<JsonElement>()
            fun node(r: Map<String, Any?>) = JsonObject(mapOf("node_type" to jv(r["node_type"]), "node_id" to jv(r["node_id"]), "valid_from" to jv(r["valid_from"]), "valid_to" to jv(r["valid_to"])))
            for (row in existing) {
                val key = (row["node_type"] as String) to (row["node_id"] as Number).toLong()
                val rf = (row["valid_from"] as java.sql.Date).toLocalDate()
                val rt = (row["valid_to"] as java.sql.Date?)?.toLocalDate()
                val inNew = key in pending
                if (!rf.isAfter(from)) {
                    if (inNew) {
                        pending.remove(key)
                        if (rt != null) { before += node(row); h.createUpdate("UPDATE app.user_scope SET valid_to = NULL WHERE id = :i").bind("i", row["id"]).execute() }
                    } else {
                        before += node(row)
                        if (rf == from) h.createUpdate("DELETE FROM app.user_scope WHERE id = :i").bind("i", row["id"]).execute()
                        else h.createUpdate("UPDATE app.user_scope SET valid_to = :t WHERE id = :i").bind("t", from).bind("i", row["id"]).execute()
                    }
                } else {
                    before += node(row); h.createUpdate("DELETE FROM app.user_scope WHERE id = :i").bind("i", row["id"]).execute()
                }
            }
            for ((type, nid) in pending) {
                h.createUpdate("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, created_by, reason) VALUES (:u, :t, :n, :f, :by, :r)")
                    .bind("u", id).bind("t", type).bind("n", nid).bind("f", from).bind("by", p.userId).bind("r", reason).execute()
                after += JsonObject(mapOf("node_type" to JsonPrimitive(type), "node_id" to JsonPrimitive(nid), "valid_from" to JsonPrimitive(from.toString()), "valid_to" to jv(null)))
            }
            if (before.isNotEmpty() || after.isNotEmpty()) {
                h.createUpdate("UPDATE app.app_user SET scope_version = scope_version + 1 WHERE id = :u").bind("u", id).execute()
                ctx.audit(h, "user_scope", id.toString(), "put", JsonArray(before), JsonArray(after), reason)
            }
            scopeJson(h, id, ctx.today)
        }
    }
    call.admRespond(HttpStatusCode.OK, out)
}

/** `GET/POST /v1/admin/users`, `GET/PATCH /v1/admin/users/{id}`, `GET/PUT .../scope`, `POST .../credentials` (tag admin-users; the permission matrix is not here). */
fun Route.adminUsersRoutes(d: AdminMasterDeps) {
    authenticated(d.guard) {
        get("/admin/users") {
            val p = call.admPrincipal(USER_READERS, "users")
            val role = call.request.queryParameters["role"]?.also { if (it !in ROLE_VALUES) admBad("query.role") }
            call.admRespond(HttpStatusCode.OK, adminList(call, d, p, USER, extraWhere = role?.let { "t.role = :role" to mapOf("role" to it) }))
        }
        post("/admin/users") { createUser(call, d) }
        get("/admin/users/{id}") { val p = call.admPrincipal(USER_READERS, "users"); adminGet(call, d, p, USER, call.admId()) }
        patch("/admin/users/{id}") { val p = call.admPrincipal(MASTER_WRITERS, "user changes"); adminPatch(call, d, p, USER, call.admId()) }
        get("/admin/users/{id}/scope") { getScope(call, d) }
        put("/admin/users/{id}/scope") { putScope(call, d) }
        post("/admin/users/{id}/credentials") { credentials(call, d) }
    }
}
