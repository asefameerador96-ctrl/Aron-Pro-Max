package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.authenticated
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

class ConfigToolsDeps(val tools: ConfigTools, val guard: AuthGuardDeps)

private fun ApplicationCall.scopeType(): String? = request.queryParameters["scope_type"]?.also { if (it !in SCOPE_TYPES) bad("query.scope_type") }
private fun ApplicationCall.scopeId(): Long? = request.queryParameters["scope_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 0 } ?: bad("query.scope_id") }
private fun ApplicationCall.version(): Long = parameters["version"]?.toLongOrNull()?.takeIf { it >= 0 } ?: bad("path.version")
private fun ApplicationCall.zone(): Long? = request.queryParameters["zone_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: bad("query.zone_id") }

/** Blast radius, what-if, version detail and reach (contract configBlastRadius, configWhatIf, getConfigVersion, getConfigReach, listConfigReachPending). */
fun Route.configToolRoutes(d: ConfigToolsDeps) {
    authenticated(d.guard) {
        get("/admin/config/blast-radius") { call.reader(); call.respond(d.tools.blastRadius(call.scopeType(), call.scopeId())) }
        get("/admin/config/whatif") {
            call.reader()
            val q = call.request.queryParameters
            val value = q["value"]?.toIntOrNull() ?: bad("query.value", "required")
            val days = q["days"]?.let { it.toIntOrNull() ?: bad("query.days") } ?: 30
            call.respond(d.tools.whatIf(call.scopeType(), call.scopeId(), value, days))
        }
        get("/admin/config/versions/{version}") { call.reader(); call.respond(d.tools.versionDetail(call.version())) }
        get("/admin/config/reach/{version}") { call.reader(); call.respond(d.tools.reach(call.version(), call.zone())) }
        get("/admin/config/reach/{version}/pending") { call.reader(); call.respond(d.tools.pending(call.version(), call.zone(), call.limit(), call.cursor())) }
    }
}
