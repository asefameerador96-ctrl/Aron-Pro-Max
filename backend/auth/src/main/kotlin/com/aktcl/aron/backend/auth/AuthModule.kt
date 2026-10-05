package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Audience
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.DeviceProof
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.JwtKeys
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.rules.BusinessDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.toJavaLocalDate

/** Everything the auth routes need, wired by backend:app. */
class AuthDeps(
    val login: LoginService,
    val refresh: RefreshService,
    val issuer: TokenIssuer,
    val users: UserStore,
    val devices: DeviceStore,
    val keys: JwtKeys,
    val reach: ReachResolver,
    val config: ServerConfig,
    val guard: AuthGuardDeps,
    val clock: AronClock = AronClock.SYSTEM,
)

/** Mounts the auth endpoints of this build under /v1 (contract tag `auth`). */
fun Route.authRoutes(d: AuthDeps) {
    route("/auth") {
        post("/login") {
            val req = call.receiveStrict(LoginRequest.serializer())
            call.respond(d.login.login(req, LoginContext(call.request.headers["X-App-Version"], call.clientIp())))
        }
        post("/refresh") { call.respond(refresh(call, d)) }
        get("/jwks") {
            call.respond(Jwks(d.keys.publicJwks().map {
                JwkEcPublic("EC", "P-256", it.x.toString(), it.y.toString(), it.keyID, "sig", "ES256")
            }))
        }
    }
    authenticated(d.guard) {
        get("/me") {
            val p = call.principal
            val user = d.users.findById(p.userId) ?: throw ApiProblem(ProblemCode.ERR_UNAUTHENTICATED)
            val reach = d.reach.reach(user.id, user.role, user.scopeVersion, BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate())
            call.respond(
                Me(
                    user = UserSummary(user.id, user.username, user.fullName, user.role, user.designation, user.locale),
                    permissions = p.permissions,
                    scope = ScopeSummary(user.scopeVersion, reach.topNodes.take(16).map { NodeRef(it.type, it.id, it.code, it.name) }),
                    pii = p.pii,
                    mfa_enabled = false,
                ),
            )
        }
    }
}

private suspend fun refresh(call: ApplicationCall, d: AuthDeps): TokenPair {
    val req = call.receiveStrict(RefreshRequest.serializer())
    val token = req.refresh_token ?: call.request.cookies["aron_rt"]
        ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "refresh_token is required", errors = listOf(FieldError("/refresh_token", "required")))
    val grant = if (req.grant == "upload") Grant.UPLOAD else Grant.FULL
    val row = d.refresh.peek(token) ?: throw ApiProblem(ProblemCode.ERR_AUTH_REFRESH_INVALID, "refresh token invalid or expired")
    val fam = row.family

    // A phone grant is bound to its device: X-Device-Id must name it and, once the device has a key, prove it.
    var deviceUuid: String? = null
    if (fam.flavour != "web") {
        val headerUuid = call.request.headers["X-Device-Id"]?.lowercase()
            ?: throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "X-Device-Id is required")
        if (req.device_uuid != null && req.device_uuid != headerUuid) throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "device_uuid differs from X-Device-Id")
        val device = d.devices.findByUuid(headerUuid)
        if (fam.deviceId != null && device?.id != fam.deviceId) throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "grant belongs to another device")
        val key = device?.publicKeyJwk?.let(DeviceProof::publicKey)
        if (key != null) {
            val proof = call.request.headers["X-Device-Proof"] ?: throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "X-Device-Proof is required")
            val ok = DeviceProof.verifyBucketed(key, proof, d.clock.now().epochSecond) { b -> DeviceProof.refreshString(headerUuid, token, b) }
            if (!ok) throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "device proof does not verify")
        } else if (device?.publicKeyJwk != null || (device == null && d.config.bool("cfg.device.require_enrolled"))) {
            throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "device key unknown")
        }
        if (device != null && grant == Grant.FULL) {
            when (device.state) {
                "suspended" -> throw ApiProblem(ProblemCode.ERR_DEVICE_SUSPENDED)
                "revoked", "replaced" -> throw ApiProblem(ProblemCode.ERR_DEVICE_REVOKED)
            }
        }
        deviceUuid = headerUuid
    }

    val rotated = d.refresh.rotate(token, grant)
    val user = d.users.findById(fam.userId) ?: throw ApiProblem(ProblemCode.ERR_AUTH_REFRESH_INVALID)
    // The upload grant survives user disable so captured rows always reach the server (docs/24 s8.1, D24-57).
    if (grant == Grant.FULL && user.status != "active") throw ApiProblem(ProblemCode.ERR_AUTH_USER_DISABLED)
    val access = d.issuer.access(
        TokenSubject(user, fam.deviceId, deviceUuid, fam.flavour),
        if (grant == Grant.UPLOAD) Audience.UPLOAD else Audience.API,
    )
    return TokenPair(
        access_token = access.token,
        access_expires_at = access.expiresAt.wire(),
        refresh_token = rotated.token,
        refresh_expires_at = (rotated.family.absoluteExpiresAt ?: rotated.tokenExpiresAt).wire(),
        scope_version = user.scopeVersion,
        server_time = d.clock.now().wire(),
    )
}

/** Client address for the lockout IP class only (never for rate limits): Front Door's header, then the socket. */
fun ApplicationCall.clientIp(): String? =
    request.headers["X-Azure-ClientIP"] ?: request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
        ?: request.local.remoteAddress
