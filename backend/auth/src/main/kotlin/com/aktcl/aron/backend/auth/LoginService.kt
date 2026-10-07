package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Audience
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.RateLimiter
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.toProblem
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration

/** Network facts of a login attempt that are not in the body. */
data class LoginContext(val appVersion: String?, val clientIp: String?)

/**
 * POST /v1/auth/login (docs/24 s8.1, contract `login`). Order of checks: per-username and per-device rate limits
 * (never per IP), the lockout of the (username, device, IP class) key, the device state, then the Argon2id
 * verification under the [HashLimiter]. Every credential failure is the same 401 ERR_AUTH_INVALID_CREDENTIALS, with
 * a dummy hash for unknown users so timing is uniform too.
 */
class LoginService(
    private val users: UserStore,
    private val devices: DeviceStore,
    private val hasher: PasswordHasher,
    private val limiter: HashLimiter,
    private val lockouts: LockoutStore,
    private val issuer: TokenIssuer,
    private val refresh: RefreshService,
    private val reach: ReachResolver,
    private val config: ServerConfig,
    private val clock: AronClock = AronClock.SYSTEM,
    /** Change-password storage (F-API-004); null where a test wires no database. */
    private val passwords: PasswordStore? = null,
    /** `cfg.auth.password_min_len` resolved for a role (role-scoped value, s9.5); wired to the config resolver. */
    private val minPasswordLen: (Role) -> Int = { if (it in PasswordPolicy.FIELD_ROLES) 8 else 12 },
) {
    private val perUsername = RateLimiter(10, 15 * 60, clock)
    private val perDevice = RateLimiter(30, 15 * 60, clock)

    suspend fun login(req: LoginRequest, ctx: LoginContext): LoginResponse {
        val now = clock.now()
        val username = req.username.lowercase()
        val flavour = when (req.client) { "app_sr" -> "sr"; "app_amo" -> "amo"; "app_tso" -> "tso"; else -> "web" }
        val phone = flavour != "web"
        if (phone && req.device_uuid == null) {
            throw ApiProblem(ProblemCode.ERR_VALIDATION, "device_uuid is required for app clients", errors = listOf(FieldError("/device_uuid", "required")))
        }
        val minVersion = minVersionCode(flavour)
        if (phone && minVersion != null) {
            val code = ctx.appVersion?.substringAfterLast('+', "")?.toIntOrNull()
            if (code != null && code < minVersion) {
                throw ApiProblem(ProblemCode.ERR_APP_VERSION_UNSUPPORTED, "update the app", context = mapOf("min_version_code" to JsonPrimitive(minVersion)))
            }
        }

        val lockKey = lockoutKey(username, req.device_uuid, ctx.clientIp)
        lockouts.lockedUntil(lockKey, now)?.let { until ->
            val s = Duration.between(now, until).seconds.coerceAtLeast(1).toInt()
            throw ApiProblem(ProblemCode.ERR_AUTH_ACCOUNT_LOCKED, "too many failed logins", retryAfterS = s, headers = mapOf("Retry-After" to s.toString()))
        }
        perUsername.tryAcquire("u:$username").let { if (!it.allowed) throw it.toProblem("too many logins for this username") }
        req.device_uuid?.let { d -> perDevice.tryAcquire("d:$d").let { if (!it.allowed) throw it.toProblem("too many logins from this device") } }

        val device = if (phone) checkDevice(req.device_uuid!!) else null

        val user = users.findByUsername(username)
        val ok = limiter.run { hasher.verify(user?.passwordHash ?: hasher.dummyHash, req.password) } && user?.passwordHash != null
        if (!ok || user == null) {
            val n = lockouts.recordFailure(lockKey, now, Duration.ofMinutes(config.int("cfg.auth.lockout_window_min").toLong()))
            if (n >= config.int("cfg.auth.lockout_attempts")) {
                val base = Duration.ofMinutes(config.int("cfg.auth.lockout_min").toLong())
                lockouts.lock(lockKey, now, base)
            }
            throw ApiProblem(ProblemCode.ERR_AUTH_INVALID_CREDENTIALS, "username or password is wrong")
        }
        lockouts.reset(lockKey)
        if (user.status != "active") throw ApiProblem(ProblemCode.ERR_AUTH_USER_DISABLED, "user is disabled")

        return complete(user, device, if (phone) req.device_uuid else null, flavour, minVersion, afterPasswordChange = false)
    }

    /**
     * POST /v1/auth/change-password (F-API-004, F-SYS-004, R15). The current password is verified under the hash
     * limiter (failures count towards a per-user lockout like a login), the new one must meet [PasswordPolicy] for the
     * role, not be the current or one of the earlier [PasswordPolicy.HISTORY_DEPTH] - 1 passwords, and, unless the
     * password is temporary, the last change must be at least [PasswordPolicy.MIN_AGE_HOURS] old. On success the
     * user's other full-grant families are revoked (upload grants survive, D24-57).
     *
     * Returns null for an ordinary access token (204), or, with a web `password_change_token`, the next login step
     * (200: `ok`, or `mfa_required` for an MFA role; TOTP comes after the change).
     */
    suspend fun changePassword(p: AronPrincipal, req: ChangePasswordRequest): LoginResponse? {
        val store = passwords ?: throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "password changes are not available")
        val now = clock.now()
        val viaToken = p.audience == Audience.PWCHANGE
        val user = users.findById(p.userId) ?: throw ApiProblem(ProblemCode.ERR_UNAUTHENTICATED)
        if (user.status != "active") throw ApiProblem(ProblemCode.ERR_AUTH_USER_DISABLED, "user is disabled")
        // The password_change_token is single purpose: once the temporary password is gone it is spent.
        if (viaToken && (!user.mustChangePassword || p.isPhone)) throw ApiProblem(ProblemCode.ERR_UNAUTHENTICATED, "password_change_token already used")

        val lockKey = "pwchange|${user.id}"
        lockouts.lockedUntil(lockKey, now)?.let { until ->
            val sec = Duration.between(now, until).seconds.coerceAtLeast(1).toInt()
            throw ApiProblem(ProblemCode.ERR_AUTH_ACCOUNT_LOCKED, "too many failed attempts", retryAfterS = sec, headers = mapOf("Retry-After" to sec.toString()))
        }
        perUsername.tryAcquire("c:${user.id}").let { if (!it.allowed) throw it.toProblem("too many password changes") }

        val depth = runCatching { config.int("cfg.auth.password_history_depth") }.getOrDefault(PasswordPolicy.HISTORY_DEPTH).coerceIn(1, 24)
        val minAgeH = runCatching { config.int("cfg.auth.password_min_age_h") }.getOrDefault(PasswordPolicy.MIN_AGE_HOURS.toInt()).toLong()
        val state = store.state(user.id, depth - 1) ?: throw ApiProblem(ProblemCode.ERR_UNAUTHENTICATED)
        val ok = state.currentHash != null && limiter.run { hasher.verify(state.currentHash, req.current_password) }
        if (!ok) {
            val n = lockouts.recordFailure(lockKey, now, Duration.ofMinutes(config.int("cfg.auth.lockout_window_min").toLong()))
            if (n >= config.int("cfg.auth.lockout_attempts")) lockouts.lock(lockKey, now, Duration.ofMinutes(config.int("cfg.auth.lockout_min").toLong()))
            throw ApiProblem(ProblemCode.ERR_AUTH_INVALID_CREDENTIALS, "the current password is wrong")
        }
        lockouts.reset(lockKey)

        val denylist = runCatching { config.bool("cfg.auth.password_denylist_enabled") }.getOrDefault(true)
        val errors = PasswordPolicy.violations(req.new_password, user.role, user.username, minPasswordLen(user.role), denylist).toMutableList()
        if (!state.mustChange && state.changedAt != null && Duration.between(state.changedAt, now) < Duration.ofHours(minAgeH)) {
            errors += FieldError("/new_password", "min_age", "at most one change in $minAgeH hours")
        }
        if (errors.isEmpty()) {
            val previous = listOfNotNull(state.currentHash) + state.history
            if (limiter.run { previous.any { hasher.verify(it, req.new_password) } }) {
                errors += FieldError("/new_password", "reused", "not one of the last $depth passwords")
            }
        }
        if (errors.isNotEmpty()) throw ApiProblem(ProblemCode.ERR_AUTH_PASSWORD_POLICY, "the new password does not meet the policy", errors = errors)

        val newHash = limiter.run { hasher.hash(req.new_password) }
        if (!store.change(user.id, state.currentHash, newHash, now, if (p.isPhone) p.deviceId else null)) {
            throw ApiProblem(ProblemCode.ERR_CONFLICT, "the password was changed at the same time; try again")
        }
        users.invalidate(user.id)
        if (!viaToken) return null
        val fresh = users.findById(user.id) ?: throw ApiProblem(ProblemCode.ERR_UNAUTHENTICATED)
        return complete(fresh, null, null, "web", minVersionCode("web"), afterPasswordChange = true)
    }

    /**
     * The steps of a login after the password matched: web temporary password (R15: before TOTP), TOTP for MFA roles,
     * device binding, phone temporary password, then the tokens. [afterPasswordChange] continues a web login after
     * POST /v1/auth/change-password with the password_change_token (the password is no longer temporary).
     */
    private fun complete(user: UserRecord, device: DeviceRecord?, deviceUuid: String?, flavour: String, minVersion: Int?, afterPasswordChange: Boolean): LoginResponse {
        val now = clock.now()
        val phone = flavour != "web"
        val summary = UserSummary(user.id, user.username, user.fullName, user.role, user.designation, user.locale)
        val scope = reach.reach(user.id, user.role, user.scopeVersion, BusinessDate.of(now.toEpochMilli()).toJavaLocalDate())
        val scopeSummary = ScopeSummary(user.scopeVersion, scope.topNodes.take(16).map { NodeRef(it.type, it.id, it.code, it.name) })
        val subject = TokenSubject(user, device?.id, deviceUuid, flavour)
        val blockSize = config.int("cfg.memo.seq_block_size")
        fun base(status: String) = LoginResponse(
            status = status, access_token = null, access_expires_at = null, refresh_token = null, refresh_expires_at = null,
            upload_refresh_token = null, bind_token = null, mfa_token = null, user = summary, scope = scopeSummary,
            device = device?.let { LoginDevice(it.id, null, blockSize) }, config_version = config.configVersion(),
            server_time = now.wire(), min_app_version_code = minVersion,
        )

        if (!phone && user.mustChangePassword && !afterPasswordChange) {
            // Web (R15, R18): no access token; a 10-minute password_change_token accepted only by change-password.
            val t = issuer.mint(subject, Audience.PWCHANGE, Duration.ofMinutes(10))
            return base("password_change_required").copy(password_change_token = t.token, scope = null)
        }
        if (!phone && user.role.wire in mfaRoles()) {
            val mfa = issuer.access(subject, Audience.MFA)
            return base("mfa_required").copy(mfa_token = mfa.token, scope = null)
        }
        val ordinal = device?.let { devices.bindOrdinal(user.id, it.id) }
        if (device != null && ordinal == null && config.bool("cfg.auth.bind_otp_required")) {
            val bind = issuer.access(subject, Audience.BIND)
            return base("bind_required").copy(bind_token = bind.token)
        }
        if (user.mustChangePassword) {
            // Short access token only: enough for POST /v1/auth/change-password, no refresh grant (DECISIONS.md).
            val a = issuer.mint(subject, Audience.API, Duration.ofMinutes(10))
            return base("password_change_required").copy(access_token = a.token, access_expires_at = a.expiresAt.wire())
        }

        val access = issuer.access(subject, Audience.API)
        if (phone && device == null) {
            // REQUEST: docs/requests/backend-refresh-family-device-uuid.md. A refresh family binds a phone by device_id
            // only; a phone with no device row (dev, before enrolment) cannot be bound, so it gets no refresh grant:
            // it logs in again when the access token expires, and its grant can never be replayed from another phone.
            return base("ok").copy(access_token = access.token, access_expires_at = access.expiresAt.wire())
        }
        val full = refresh.issue(user.id, device?.id, Grant.FULL, flavour)
        val upload = if (phone) refresh.issue(user.id, device?.id, Grant.UPLOAD, flavour) else null
        return base("ok").copy(
            access_token = access.token,
            access_expires_at = access.expiresAt.wire(),
            refresh_token = full.token,
            refresh_expires_at = (full.familyExpiresAt ?: full.tokenExpiresAt).wire(),
            upload_refresh_token = upload?.token,
            device = device?.let { LoginDevice(it.id, ordinal, blockSize) },
        )
    }

    private fun checkDevice(uuid: String): DeviceRecord? {
        val d = devices.findByUuid(uuid)
        if (d == null) {
            if (config.bool("cfg.device.require_enrolled")) throw ApiProblem(ProblemCode.ERR_DEVICE_NOT_ENROLLED, "this phone is not enrolled")
            return null
        }
        when (d.state) {
            "suspended" -> throw ApiProblem(ProblemCode.ERR_DEVICE_SUSPENDED, "this phone is suspended")
            "revoked", "replaced" -> throw ApiProblem(ProblemCode.ERR_DEVICE_REVOKED, "this phone is revoked")
        }
        return d
    }

    private fun mfaRoles(): Set<String> =
        config.value("cfg.auth.mfa_required_roles").jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()

    private fun minVersionCode(flavour: String): Int? =
        runCatching { config.value("cfg.release.min_version_code").jsonObject[flavour]?.jsonPrimitive?.intOrNull }.getOrNull()

    private fun lockoutKey(username: String, deviceUuid: String?, ip: String?): String =
        when (config.string("cfg.auth.lockout_key_mode")) {
            "username" -> username
            "username_device" -> "$username|${deviceUuid ?: "-"}"
            else -> "$username|${deviceUuid ?: "-"}|${ipClass(ip)}"
        }

    companion object {
        /** IPv4 /24 or IPv6 /48 of the client (the lockout key's IP class); "-" when unknown. */
        fun ipClass(ip: String?): String {
            if (ip.isNullOrBlank()) return "-"
            val v = ip.trim().removePrefix("::ffff:").removePrefix("::FFFF:")
            return if (v.contains(':')) v.split(':').take(3).joinToString(":") + "::/48"
            else v.split('.').let { if (it.size == 4) "${it[0]}.${it[1]}.${it[2]}.0/24" else "-" }
        }
    }
}
