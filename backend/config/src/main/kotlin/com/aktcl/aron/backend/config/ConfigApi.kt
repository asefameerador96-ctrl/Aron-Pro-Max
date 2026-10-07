package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.RequestJson
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ConfigDeps(val service: ConfigService, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

/** Roles that read the registry, values, changes and versions on the web (docs/24 s8.5). Field roles read config only through the bundle and delta. */
private val CONFIG_READERS = setOf(Role.TSO, Role.DMO, Role.WM, Role.TOP, Role.ANALYST, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)

internal fun ApplicationCall.reader() = principal.also {
    if (it.role !in CONFIG_READERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "config is not available to this role")
}

internal fun bad(pointer: String, code: String = "invalid_value"): Nothing = throw ApiProblem(ProblemCode.ERR_VALIDATION, "invalid $pointer", errors = listOf(FieldError(pointer, code)))

internal fun ApplicationCall.limit(): Int = request.queryParameters["limit"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..200 } ?: bad("query.limit", "out_of_range") } ?: 50
internal fun ApplicationCall.cursor(): Long? = request.queryParameters["cursor"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: bad("query.cursor") }
private fun ApplicationCall.keyParam(): String = request.queryParameters["key"]?.takeIf { it.matches(Regex("^cfg\\.[a-z]+\\.[a-z0-9_]+(\\.[a-z0-9_]+)?$")) } ?: bad("query.key")
private fun ApplicationCall.date(name: String): LocalDate? = request.queryParameters[name]?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: bad("query.$name") }

internal val SCOPE_TYPES = Precedence.rank.keys

/** The `admin-config` surface of the contract: registry, values, resolve, change requests, decisions and versions. */
fun Route.configAdminRoutes(d: ConfigDeps) {
    val s = d.service
    authenticated(d.guard) {
        get("/admin/config/keys") {
            call.reader()
            val area = call.request.queryParameters["area"]?.also { if (!it.matches(Regex("^[a-z]+$")) || it.length > 32) bad("query.area") }
            call.respond(ConfigKeyList(s.keys(area)))
        }
        get("/admin/config/values") {
            call.reader()
            val q = call.request.queryParameters
            val st = q["scope_type"]?.also { if (it !in SCOPE_TYPES) bad("query.scope_type") }
            val si = q["scope_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 0 } ?: bad("query.scope_id") }
            call.respond(s.values(call.keyParam(), st, si, q["include_history"] == "true", call.limit(), call.cursor()))
        }
        get("/admin/config/resolve") {
            call.reader()
            val q = call.request.queryParameters
            val type = q["node_type"]?.takeIf { it in SCOPE_TYPES } ?: bad("query.node_type")
            val id = q["node_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 0 } ?: bad("query.node_id") } ?: bad("query.node_id", "required")
            val at = q["at"]?.let { runCatching { Instant.parse(it) }.getOrNull() ?: bad("query.at") } ?: d.clock.now()
            call.respond(s.resolve(call.keyParam(), type, id, at))
        }
        get("/admin/config/changes") {
            call.reader()
            val q = call.request.queryParameters
            val status = q["status"]?.also { if (it !in setOf("pending_approval", "scheduled", "applied", "rejected", "cancelled", "expired", "reverted")) bad("query.status") }
            val key = q["key"]?.also { if (!it.matches(Regex("^cfg\\.[a-z]+\\.[a-z0-9_]+(\\.[a-z0-9_]+)?$"))) bad("query.key") }
            call.respond(s.changes(status, key, call.date("from"), call.date("to"), call.limit(), call.cursor()))
        }
        post("/admin/config/changes") {
            val p = call.principal
            val body = parse<ConfigChangeRequestIn>(call.receiveText())
            val idem = call.request.headers["Idempotency-Key"]?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: bad("header.Idempotency-Key") }
            call.respond(HttpStatusCode.Created, s.create(p, body, idem, call.requestId))
        }
        get("/admin/config/changes/{change_id}") {
            call.reader()
            call.respond(s.change(call.parameters["change_id"]?.toLongOrNull()?.takeIf { it >= 1 } ?: bad("path.change_id")))
        }
        post("/admin/config/changes/{change_id}/decision") {
            val p = call.principal
            val id = call.parameters["change_id"]?.toLongOrNull()?.takeIf { it >= 1 } ?: bad("path.change_id")
            call.respond(s.decide(p, id, parse<ConfigDecisionIn>(call.receiveText()), call.requestId))
        }
        post("/admin/config/versions/{version}/rollback") {
            val p = call.principal
            val v = call.parameters["version"]?.toLongOrNull()?.takeIf { it >= 1 } ?: bad("path.version")
            val body = parse<ConfigRollbackIn>(call.receiveText())
            if (body.reason.trim().length < 10 || body.reason.length > 500) throw ApiProblem(ProblemCode.ERR_CFG_REASON_REQUIRED, "a reason of 10 to 500 characters is required", errors = listOf(FieldError("body.reason", "length")))
            call.respond(HttpStatusCode.Created, s.rollback(p, v, body.mode, body.reason, call.requestId))
        }
        get("/admin/config/versions") {
            call.reader()
            call.respond(s.versions(call.limit(), call.cursor()))
        }
    }
}

private inline fun <reified T> parse(text: String): T = try {
    RequestJson.decodeFromString<T>(text)
} catch (e: kotlinx.serialization.SerializationException) {
    throw ApiProblem(ProblemCode.ERR_VALIDATION, "malformed request body", errors = listOf(FieldError("body", "invalid_body")))
}
