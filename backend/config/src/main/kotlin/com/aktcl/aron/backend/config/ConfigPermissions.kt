package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.RequestJson
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable data class MenuPermissionDto(val menu_id: String, val actions: List<String>)
@Serializable data class RolePermissionsDto(val role: String, val menus: List<MenuPermissionDto>)
@Serializable data class RosterEntry(val user_id: Long, val username: String, val role: String, val mfa_enabled: Boolean)
@Serializable data class PermissionMatrixDto(val config_version: Long, val roles: List<RolePermissionsDto>, val admin_roster: List<RosterEntry>)
@Serializable data class RolePermissionsWrite(val menus: List<MenuPermissionDto>, val reason: String)

/**
 * `GET /v1/admin/permissions` and `PUT /v1/admin/permissions/roles/{role}` (F-API-064). The matrix is `cfg.web.menu_by_role`
 * (registry default plus role-scoped overrides). A change is never an immediate grant: it creates a class C3 change request
 * that a second SUPERADMIN approves. The stored shape is the seeded `{menu, page, actions}`; the contract shape is
 * `{menu_id = "menu.page", actions in view|create|edit|approve|export|void}`, mapped both ways (docs/requests/backend-admin-contract-gaps.md item 8).
 */
class ConfigPermissions(private val db: Database, private val service: ConfigService, private val clock: AronClock = AronClock.SYSTEM) {
    private val key = "cfg.web.menu_by_role"
    // The seeded vocabulary says `write` for "may change"; read back it means create and edit. Written values keep create and edit apart.
    private val toContract = mapOf("read" to listOf("view"), "view" to listOf("view"), "write" to listOf("create", "edit"), "create" to listOf("create"), "edit" to listOf("edit"), "export" to listOf("export"), "approve" to listOf("approve"), "submit_void" to listOf("void"), "void" to listOf("void"))
    private val toStored = mapOf("view" to "read", "create" to "create", "edit" to "edit", "approve" to "approve", "export" to "export", "void" to "void")
    private val menuId = Regex("^[a-z][a-z0-9_.]{1,60}$")

    fun matrix(): PermissionMatrixDto {
        val def = service.resolver.registry().getValue(key).default as? JsonObject ?: JsonObject(emptyMap())
        val ordinals = db.jdbi.withHandle<Map<String, Long>, Exception> { h -> h.createQuery("SELECT role, ordinal FROM app.role_def ORDER BY ordinal").map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap() }
        val roles = ordinals.map { (role, ord) ->
            val r = service.resolver.resolve(key, listOf(ScopeNode("role", ord), ScopeNode("global", 0)), clock.now())
            val list = if (r.scopeType == "role") r.value else def[role]
            RolePermissionsDto(role, (list as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val menu = (o["menu"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val page = (o["page"] as? JsonPrimitive)?.content
                val actions = (o["actions"] as? JsonArray).orEmpty().flatMap { a -> toContract[(a as? JsonPrimitive)?.content].orEmpty() }.distinct()
                MenuPermissionDto(if (page != null) "$menu.$page" else menu, actions)
            }).takeIf { it.menus.isNotEmpty() || ordinals.containsKey(role) } ?: RolePermissionsDto(role, emptyList())
        }
        val roster = db.jdbi.withHandle<List<RosterEntry>, Exception> { h ->
            h.createQuery("SELECT u.id, u.username, u.role, EXISTS (SELECT 1 FROM app.mfa_secret m WHERE m.user_id = u.id AND m.confirmed_at IS NOT NULL) AS mfa FROM app.app_user u WHERE u.role IN ('ADMIN','SUPERADMIN') AND u.status = 'active' ORDER BY u.id LIMIT 500")
                .map { rs, _ -> RosterEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)) }.list()
        }
        return PermissionMatrixDto(service.currentVersion(), roles, roster)
    }

    fun write(p: AronPrincipal, role: String, body: RolePermissionsWrite, requestId: String?): ConfigChangeDto {
        if (p.role != Role.SUPERADMIN) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the permission matrix is changed by a SUPERADMIN")
        val ordinal = db.jdbi.withHandle<Long?, Exception> { h -> h.createQuery("SELECT ordinal FROM app.role_def WHERE role = :r").bind("r", role).mapTo(Long::class.java).findOne().orElse(null) }
            ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "unknown role", errors = listOf(FieldError("path.role", "invalid_value")))
        if (body.menus.size > 200) throw ApiProblem(ProblemCode.ERR_VALIDATION, "at most 200 menus", errors = listOf(FieldError("body.menus", "out_of_range")))
        body.menus.forEachIndexed { i, m ->
            if (!menuId.matches(m.menu_id)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad menu_id", errors = listOf(FieldError("body.menus[$i].menu_id", "invalid_value")))
            if (m.actions.size > 10 || m.actions.any { it !in toStored }) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad actions", errors = listOf(FieldError("body.menus[$i].actions", "invalid_value")))
        }
        if (body.menus.map { it.menu_id }.toSet().size != body.menus.size) throw ApiProblem(ProblemCode.ERR_VALIDATION, "duplicate menu_id", errors = listOf(FieldError("body.menus", "duplicate")))
        // A SUPERADMIN may not lock the role out of this very page (nobody could then change the matrix back).
        if (role == "SUPERADMIN" && body.menus.none { it.menu_id == "config.permission_matrix" && "edit" in it.actions }) throw ApiProblem(ProblemCode.ERR_VALIDATION, "the SUPERADMIN role keeps config.permission_matrix with edit", errors = listOf(FieldError("body.menus", "lockout")))
        val stored = buildJsonArray {
            body.menus.forEach { m ->
                add(buildJsonObject {
                    put("menu", m.menu_id.substringBefore('.'))
                    if ('.' in m.menu_id) put("page", m.menu_id.substringAfter('.'))
                    put("actions", buildJsonArray { m.actions.map { toStored.getValue(it) }.distinct().forEach { add(JsonPrimitive(it)) } })
                })
            }
        }
        return service.create(p, ConfigChangeRequestIn(body.reason, false, listOf(ConfigChangeItemIn(key, "role", ordinal, stored))), null, requestId)
    }
}

class ConfigPermissionsDeps(val permissions: ConfigPermissions, val guard: AuthGuardDeps)

fun Route.configPermissionRoutes(d: ConfigPermissionsDeps) {
    authenticated(d.guard) {
        get("/admin/permissions") {
            if (call.principal.role != Role.ADMIN && call.principal.role != Role.SUPERADMIN) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the permission matrix is for admins")
            call.respond(d.permissions.matrix())
        }
        put("/admin/permissions/roles/{role}") {
            val body = try { RequestJson.decodeFromString<RolePermissionsWrite>(call.receiveText()) } catch (e: kotlinx.serialization.SerializationException) {
                throw ApiProblem(ProblemCode.ERR_VALIDATION, "malformed request body", errors = listOf(FieldError("body", "invalid_body")))
            }
            if (body.reason.trim().length < 10 || body.reason.length > 500) throw ApiProblem(ProblemCode.ERR_CFG_REASON_REQUIRED, "a reason of 10 to 500 characters is required", errors = listOf(FieldError("body.reason", "length")))
            call.respond(HttpStatusCode.Accepted, d.permissions.write(call.principal, call.parameters["role"].orEmpty(), body, call.requestId))
        }
    }
}
