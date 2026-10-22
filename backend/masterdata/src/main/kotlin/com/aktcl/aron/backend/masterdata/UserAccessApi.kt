package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jdbi.v3.core.Handle

/** The fixed module list of an ADMIN's grants (docs/34 s1, D-ROLES-02); the db CHECK on `user_module_grant.module` carries the same list. */
internal val ADMIN_MODULES = listOf(
    "masterdata", "routes", "outlets", "products", "prices", "users", "targets", "credentials", "mdm", "reports", "config", "data_entry", "indent",
    "leaderboard", "daily_tracking",
)
private val SENIOR_ROLES = setOf("ADMIN", "SUPERADMIN")

/** The tables are db's (docs/requests/db-backend-authz-roles.md, migrations B and C); until they land the routes answer 503. */
private fun accessTablesReady(h: Handle) =
    h.createQuery("SELECT to_regclass('app.user_module_grant') IS NOT NULL AND to_regclass('app.user_brand_scope') IS NOT NULL").mapTo(Boolean::class.java).one()

private fun notReady(): Nothing = throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "user access grants are not deployed in this environment")

/** The user, or 404; an ADMIN may not see another ADMIN or SUPERADMIN (as for the other user routes), only itself. */
private fun target(h: Handle, p: com.aktcl.aron.backend.platform.AronPrincipal, id: Long, lock: Boolean): Map<String, Any?> {
    val u = h.row("SELECT id, role, scope_version FROM app.app_user WHERE id = :i" + if (lock) " FOR UPDATE" else "", "i" to id) ?: admNotFound()
    if (p.role == Role.ADMIN && u["role"] in SENIOR_ROLES && p.userId != id) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "only a SUPERADMIN manages ADMIN and SUPERADMIN users")
    return u
}

private fun accessJson(h: Handle, u: Map<String, Any?>): JsonObject {
    val id = (u["id"] as Number).toLong()
    val modules = h.createQuery("SELECT module, access FROM app.user_module_grant WHERE user_id = :u").bind("u", id).map { rs, _ -> rs.getString(1) to rs.getString(2) }.list().toMap()
    val brands = h.createQuery("SELECT brand_id FROM app.user_brand_scope WHERE user_id = :u ORDER BY brand_id").bind("u", id).mapTo(Long::class.java).list()
    return JsonObject(mapOf(
        "user_id" to JsonPrimitive(id), "role" to JsonPrimitive(u["role"] as String), "version" to JsonPrimitive((u["scope_version"] as Number).toLong()),
        "modules" to JsonObject(ADMIN_MODULES.filter { it in modules }.associateWith { JsonPrimitive(modules.getValue(it)) }),
        "brand_ids" to JsonArray(brands.map { JsonPrimitive(it) }),
    ))
}

private suspend fun getAccess(call: ApplicationCall, d: AdminMasterDeps) {
    val p = call.admPrincipal(MASTER_WRITERS, "user access")
    val id = call.admId()
    val out = d.db.jdbi.withHandle<Pair<JsonObject, Long>, Exception> { h ->
        if (!accessTablesReady(h)) notReady()
        val u = target(h, p, id, false)
        accessJson(h, u) to (u["scope_version"] as Number).toLong()
    }
    call.admRespond(HttpStatusCode.OK, out.first, out.second.toInt())
}

/**
 * `PUT /v1/admin/users/{id}/access` (SUPERADMIN only, `If-Match: "<version>"`): replaces the module grants of an ADMIN user or the brand scope of a BRAND user.
 * The version is `app_user.scope_version`, bumped by the write so the user's next token carries the new `modules` / `brands` claims and an older token answers
 * 401 ERR_SCOPE_CHANGED. Exactly one of `modules` and `brand_ids`; audited (entity `user_access`) with the reason and the before and after sets.
 */
private suspend fun putAccess(call: ApplicationCall, d: AdminMasterDeps) {
    val p = call.admPrincipal(setOf(Role.SUPERADMIN), "user access changes")
    val id = call.admId()
    val f = call.admBody(setOf("modules", "brand_ids", "change_reason"))
    val ifMatch = call.admIfMatch()
    val hasModules = f.has("modules"); val hasBrands = f.has("brand_ids")
    if (hasModules == hasBrands) admBad(if (hasModules) "body.brand_ids" else "body.modules", if (hasModules) "invalid" else "required", "send exactly one of modules and brand_ids")
    val reason = f.reason(true)!!
    val modules: Map<String, String>? = if (hasModules) {
        val o = f.obj["modules"] as? JsonObject ?: admBad("body.modules", "invalid_type")
        o.entries.associate { (k, v) ->
            if (k !in ADMIN_MODULES) admBad("body.modules/$k", "unknown_member", "unknown module $k")
            val lvl = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (lvl != "r" && lvl != "w") admBad("body.modules/$k", "invalid", "the level is r or w")
            k to lvl
        }
    } else null
    val brands: List<Long>? = if (hasBrands) {
        val a = f.obj["brand_ids"] as? JsonArray ?: admBad("body.brand_ids", "invalid_type")
        if (a.isEmpty()) admBad("body.brand_ids", "required", "a brand user needs at least one brand")
        if (a.size > 200) admBad("body.brand_ids", "length")
        val ids = a.mapIndexed { i, el -> (el as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.takeIf { it >= 1 } ?: admBad("body.brand_ids/$i", "out_of_range") }
        if (ids.toSet().size != ids.size) admBad("body.brand_ids", "duplicate")
        ids.sorted()
    } else null
    val ctx = d.ctx(call, p)
    val out = admWrite {
        d.db.jdbi.inTransaction<Pair<JsonObject, Long>, Exception> { h ->
            if (!accessTablesReady(h)) notReady()
            val u = target(h, p, id, true)
            val role = u["role"] as String
            if (modules != null && role != "ADMIN") admBad("body.modules", "role_mismatch", "module grants are for ADMIN users; this user is $role")
            if (brands != null && role != "BRAND") admBad("body.brand_ids", "role_mismatch", "brand scopes are for BRAND users; this user is $role")
            if ((u["scope_version"] as Number).toLong() != ifMatch.toLong()) throw AdminSupport.preconditionFailed()
            if (brands != null) {
                val known = h.createQuery("SELECT id FROM app.product_node WHERE id IN (<ids>) AND level = 'brand'").bindList("ids", brands).mapTo(Long::class.java).list().toSet()
                brands.withIndex().firstOrNull { it.value !in known }?.let { admBad("body.brand_ids/${it.index}", "unknown", "brand ${it.value} does not exist or is not a brand node") }
            }
            val before = accessJson(h, u)
            if (modules != null) {
                h.createUpdate("DELETE FROM app.user_module_grant WHERE user_id = :u").bind("u", id).execute()
                modules.forEach { (m, lvl) ->
                    h.createUpdate("INSERT INTO app.user_module_grant (user_id, module, access, granted_by) VALUES (:u, :m, :a, :by)").bind("u", id).bind("m", m).bind("a", lvl).bind("by", p.userId).execute()
                }
            } else {
                h.createUpdate("DELETE FROM app.user_brand_scope WHERE user_id = :u").bind("u", id).execute()
                brands!!.forEach { b -> h.createUpdate("INSERT INTO app.user_brand_scope (user_id, brand_id, granted_by) VALUES (:u, :b, :by)").bind("u", id).bind("b", b).bind("by", p.userId).execute() }
            }
            h.createUpdate("UPDATE app.app_user SET scope_version = scope_version + 1, updated_by = :by WHERE id = :u").bind("u", id).bind("by", p.userId).execute()
            val fresh = h.row("SELECT id, role, scope_version FROM app.app_user WHERE id = :i", "i" to id)!!
            val after = accessJson(h, fresh)
            ctx.audit(h, "user_access", id.toString(), if (modules != null) "set_modules" else "set_brands", before, after, reason)
            after to (fresh["scope_version"] as Number).toLong()
        }
    }
    call.admRespond(HttpStatusCode.OK, out.first, out.second.toInt())
}

/** `GET/PUT /v1/admin/users/{id}/access` (tag admin-users). */
fun Route.userAccessRoutes(d: AdminMasterDeps) {
    authenticated(d.guard) {
        get("/admin/users/{id}/access") { getAccess(call, d) }
        put("/admin/users/{id}/access") { putAccess(call, d) }
    }
}
