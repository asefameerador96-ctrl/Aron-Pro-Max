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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** Front Door profile id (`X-Azure-FDID`); only then is `X-Azure-ClientIP` trusted for the lockout IP class. */
    val trustedFrontDoorId: String? = null,
    /** The caller's role row of `cfg.web.menu_by_role` (contract v1.2 `Me.menus`, R10); wired to ConfigPermissions.menusForRole. */
    val menusForRole: (String) -> List<kotlinx.serialization.json.JsonElement>? = { null },
)

/** Mounts the auth endpoints of this build under /v1 (contract tag `auth`). */
fun Route.authRoutes(d: AuthDeps) {
    route("/auth") {
        post("/login") {
            val req = call.receiveStrict(LoginRequest.serializer())
            val res = withContext(Dispatchers.IO) { d.login.login(req, LoginContext(call.request.headers["X-App-Version"], call.clientIp(d.trustedFrontDoorId))) }
            call.respond(if (req.client == "web") call.webCookie(res.refresh_token, res.refresh_expires_at).let { res.copy(refresh_token = null) } else res)
        }
        post("/refresh") {
            val req = call.receiveStrict(RefreshRequest.serializer())
            val pair = withContext(Dispatchers.IO) { refresh(call, req, d) }
            val web = req.refresh_token == null
            call.respond(if (web) call.webCookie(pair.refresh_token, pair.refresh_expires_at).let { pair.copy(refresh_token = null) } else pair)
        }
        get("/jwks") {
            call.respond(Jwks(d.keys.publicJwks().map {
                JwkEcPublic("EC", "P-256", it.x.toString(), it.y.toString(), it.keyID, "sig", "ES256")
            }))
        }
    }
    // An access token (204) or a web login's password_change_token (200 LoginResponse, R15); a temporary password may call it.
    authenticated(d.guard, { audiences = setOf(Audience.API, Audience.PWCHANGE); allowPasswordChangeRequired = true }) {
        post("/auth/change-password") {
            val req = call.receiveStrict(ChangePasswordRequest.serializer())
            val p = call.principal
            // A web BFF forwards its aron_rt so its own session survives the change (docs/21 s2: "except the caller's").
            val callerRt = call.request.cookies["aron_rt"]
            val res = withContext(Dispatchers.IO) { d.login.changePassword(p, req, callerRt) }
            if (res == null) call.respond(io.ktor.http.HttpStatusCode.NoContent)
            else call.respond(call.webCookie(res.refresh_token, res.refresh_expires_at).let { res.copy(refresh_token = null) })
        }
    }
    // The login's bind_token only (aud aron-bind); X-Device-Id must name the token's device (the guard checks it).
    authenticated(d.guard, { audiences = setOf(Audience.BIND); allowPasswordChangeRequired = true }) {
        post("/auth/bind-device") {
            val req = call.receiveStrict(BindDeviceRequest.serializer())
            val p = call.principal
            call.respond(withContext(Dispatchers.IO) { d.login.bindDevice(p, req, call.request.headers["X-Device-Proof"]) })
        }
    }
    authenticated(d.guard) {
        get("/me") {
            val p = call.principal
            withContext(Dispatchers.IO) { me(call, d) }.let { call.respond(it) }
        }
    }
}

private fun me(call: ApplicationCall, d: AuthDeps): Me {
            val p = call.principal
            val user = d.users.findById(p.userId) ?: throw ApiProblem(ProblemCode.ERR_UNAUTHENTICATED)
            val reach = d.reach.reach(user.id, user.role, user.scopeVersion, BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate())
            return Me(
                user = UserSummary(user.id, user.username, user.fullName, user.role, user.designation, user.locale),
                permissions = p.permissions,
                scope = ScopeSummary(user.scopeVersion, reach.topNodes.take(16).map { NodeRef(it.type, it.id, it.code, it.name) }),
                pii = p.pii,
                mfa_enabled = false,
                menus = d.menusForRole(user.role.wire)?.take(200),
            )
}

private fun refresh(call: ApplicationCall, req: RefreshRequest, d: AuthDeps): TokenPair {
    val token = req.refresh_token ?: call.request.cookies["aron_rt"]
        ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "refresh_token is required", errors = listOf(FieldError("/refresh_token", "required")))
    val grant = if (req.grant == "upload") Grant.UPLOAD else Grant.FULL
    val row = d.refresh.peek(token) ?: throw ApiProblem(ProblemCode.ERR_AUTH_REFRESH_INVALID, "refresh token invalid or expired")
    val fam = row.family

    // A phone grant is bound to its device: X-Device-Id must name it and, once the device has a key, prove it.
    var deviceUuid: String? = null
    if (fam.flavour != "web") {
        // A phone family must be bound to a device row; an unbound one could be replayed from any phone.
        if (fam.deviceId == null) throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "grant is not bound to a device")
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
        } else if (d.config.bool("cfg.device.require_enrolled")) {
            // In production every phone has a usable Keystore key; without one there is nothing to prove with.
            throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "device key unknown")
        }
        // Dev database (require_enrolled false): a phone with no usable key (none, or the seed's placeholder) is bound
        // by device_id and X-Device-Id only (DECISIONS.md).
        if (device != null && grant == Grant.FULL) {
            when (device.state) {
                "suspended" -> throw ApiProblem(ProblemCode.ERR_DEVICE_SUSPENDED)
                "revoked", "replaced" -> throw ApiProblem(ProblemCode.ERR_DEVICE_REVOKED)
            }
        }
        deviceUuid = headerUuid
    }

    // Every refusal happens BEFORE rotation, so a refused attempt never consumes the token.
    val user = d.users.findById(fam.userId) ?: throw ApiProblem(ProblemCode.ERR_AUTH_REFRESH_INVALID)
    // The upload grant survives user disable so captured rows always reach the server (docs/24 s8.1, D24-57).
    if (grant == Grant.FULL && user.status != "active") throw ApiProblem(ProblemCode.ERR_AUTH_USER_DISABLED)
    val rotated = d.refresh.rotate(token, grant)
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

/**
 * Client address for the lockout IP class only (never for rate limits). `X-Azure-ClientIP` is trusted only when the
 * request carries our Front Door id (`X-Azure-FDID` = ARON_FRONT_DOOR_ID); `X-Forwarded-For` is never trusted
 * (the client writes it). Otherwise the socket address.
 */
fun ApplicationCall.clientIp(trustedFrontDoorId: String?): String? {
    val fd = request.headers["X-Azure-FDID"]
    if (trustedFrontDoorId != null && fd == trustedFrontDoorId) request.headers["X-Azure-ClientIP"]?.let { return it }
    return request.local.remoteAddress
}

/**
 * Web refresh token as the `aron_rt` cookie (contract: null in the body on the web; HttpOnly, Secure,
 * SameSite=Strict, Path=/v1/auth/refresh). The BFF is the cookie jar for the browser.
 */
private fun ApplicationCall.webCookie(token: String?, expiresAt: String?) {
    if (token == null) return
    val maxAge = expiresAt?.let { java.time.Duration.between(java.time.Instant.now(), java.time.Instant.parse(it)).seconds.coerceAtLeast(0) }
    response.headers.append(
        "Set-Cookie",
        "aron_rt=$token; Path=/v1/auth/refresh; HttpOnly; Secure; SameSite=Strict" + (maxAge?.let { "; Max-Age=$it" } ?: ""),
        safeOnly = false,
    )
}
