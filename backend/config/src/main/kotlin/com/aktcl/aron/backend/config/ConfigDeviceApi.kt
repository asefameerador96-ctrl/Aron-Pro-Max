package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.contract.ProblemCode
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

class ConfigDeltaDeps(val delta: ConfigDelta, val service: ConfigService, val guard: AuthGuardDeps)

/** `GET /v1/config/delta` (contract getConfigDelta): every authenticated caller; the chain comes from the token, never the query. */
fun Route.configDeltaRoutes(d: ConfigDeltaDeps) {
    authenticated(d.guard) {
        get("/config/delta") {
            val p = call.principal
            val since = call.request.queryParameters["since"]?.toLongOrNull()?.takeIf { it >= 0 }
                ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "since is a version number", errors = listOf(FieldError("query.since", "invalid_value")))
            when (val r = d.delta.delta(p.userId, p.deviceId, since, call.request.headers[HttpHeaders.IfNoneMatch]) { d.service.applyDue() }) {
                is DeltaOutcome.NotModified -> { call.response.header(HttpHeaders.ETag, d.delta.etag(r.version)); call.respond(HttpStatusCode.NotModified) }
                is DeltaOutcome.Changes -> { call.response.header(HttpHeaders.ETag, d.delta.etag(r.body.to_version)); call.respond(r.body) }
            }
        }
    }
}
