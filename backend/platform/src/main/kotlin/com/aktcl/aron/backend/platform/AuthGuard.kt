package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.ProblemCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext

/** Current scope_version of a user, or null when the user does not exist (docs/24 s8.4). Implemented by auth. */
fun interface ScopeVersionLookup {
    fun current(userId: Long): Long?
}

/** Dependencies of the bearer guard; one instance per application. */
class AuthGuardDeps(
    val verifier: AccessTokenVerifier,
    val scopeVersions: ScopeVersionLookup,
    val config: ServerConfig,
    clock: AronClock = AronClock.SYSTEM,
) {
    val perDevice = RateLimiter(config.int("cfg.api.rl.device_per_min"), 60, clock)
    val perUser = RateLimiter(config.int("cfg.api.rl.user_per_min"), 60, clock)
}

class AuthGuardConfig {
    lateinit var deps: AuthGuardDeps
    var audiences: Set<String> = setOf(Audience.API)
    /** `sync/batch` accepts an access token expired by at most 60 s (docs/24 s8.1). */
    var expiredGraceS: Long = 0
    /** A stale `sv` answers 401 ERR_SCOPE_CHANGED (s8.4); the upload grant is exempt so uploads never stall. */
    var checkScopeVersion: Boolean = true
}

/**
 * Route guard: verifies the Bearer access token, binds phone calls to their device (`X-Device-Id` = `dvu`),
 * rejects a stale scope version, and applies the per-device (phones) or per-user (web) rate limit (s3.6).
 */
val AuthGuard = createRouteScopedPlugin("AronAuthGuard", ::AuthGuardConfig) {
    val cfg = pluginConfig
    onCall { call ->
        val p = authenticate(call, cfg)
        call.attributes.put(PrincipalKey, p)
        val decision = if (p.isPhone && p.deviceUuid != null) cfg.deps.perDevice.tryAcquire("d:" + p.deviceUuid)
        else cfg.deps.perUser.tryAcquire("u:" + p.userId)
        if (!decision.allowed) throw decision.toProblem()
    }
}

private fun authenticate(call: ApplicationCall, cfg: AuthGuardConfig): AronPrincipal {
    val header = call.request.headers["Authorization"] ?: throw ApiProblem(ProblemCode.ERR_UNAUTHENTICATED, "missing bearer token")
    if (!header.startsWith("Bearer ", ignoreCase = true)) throw ApiProblem(ProblemCode.ERR_UNAUTHENTICATED, "missing bearer token")
    val p = cfg.deps.verifier.verify(header.substring(7).trim(), cfg.audiences, cfg.expiredGraceS)
    if (p.deviceUuid != null && call.request.headers["X-Device-Id"]?.lowercase() != p.deviceUuid) {
        throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "X-Device-Id does not match the token")
    }
    if (cfg.checkScopeVersion) {
        val current = cfg.deps.scopeVersions.current(p.userId) ?: throw ApiProblem(ProblemCode.ERR_UNAUTHENTICATED, "unknown user")
        if (current > p.scopeVersion) throw ApiProblem(ProblemCode.ERR_SCOPE_CHANGED, "scope changed; refresh and fetch a full bundle")
    }
    return p
}

val ApplicationCall.principal: AronPrincipal
    get() = attributes.getOrNull(PrincipalKey) ?: throw ApiProblem(ProblemCode.ERR_UNAUTHENTICATED)

/** Wraps [build] in a transparent child route guarded by [AuthGuard]. */
fun Route.authenticated(deps: AuthGuardDeps, configure: AuthGuardConfig.() -> Unit = {}, build: Route.() -> Unit): Route {
    val child = createChild(object : RouteSelector() {
        override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) = RouteSelectorEvaluation.Transparent
        override fun toString() = "(aron-auth)"
    })
    child.install(AuthGuard) { this.deps = deps; configure() }
    child.build()
    return child
}
