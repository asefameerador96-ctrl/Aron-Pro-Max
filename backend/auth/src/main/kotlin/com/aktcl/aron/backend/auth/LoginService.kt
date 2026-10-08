package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.SecurityEvent
import com.aktcl.aron.backend.platform.SecurityEventKind
import com.aktcl.aron.backend.platform.SecurityEvents
import com.aktcl.aron.backend.platform.safely
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
    /** Device binding by OTP (F-SYS-003); null where a test wires no database. */
    private val binds: BindStore? = null,
    private val otpSealer: OtpSealer? = null,
    /**
     * Argon2 capacity for web logins, apart from [limiter] (AUD-SEC-02): a web login needs no enrolled device, so an
     * anonymous flood of web logins must never take the hash slots the 07:00 phone wave needs.
     */
    private val webLimiter: HashLimiter = HashLimiter(concurrency = 1, queueMax = 8),
    /** login_failure, lockout and password_change events (AUD-SEC-03); best effort, never fails the call. */
    private val securityEvents: SecurityEvents = SecurityEvents.LOG,
    /** TOTP enrolment and the second login step (F-WEB-043); null where a test wires no database (503). */
    private val mfa: MfaStore? = null,
    private val mfaCipher: MfaCipher? = null,
) {
    private val perUsername = RateLimiter(10, 15 * 60, clock)
    private val perDevice = RateLimiter(30, 15 * 60, clock)
    /**
     * Logins that need no enrolled device, before any hashing (AUD-SEC-02). Web: per caller IP class first, so one
     * address cannot keep everyone else out, then a replica-wide backstop (the WAF per-IP limit is a final-account
     * item). Phones unknown to the server (only where `cfg.device.require_enrolled` is off): a backstop of their own,
     * never per IP (phones share carrier-NAT addresses).
     */
    private val webPerIpClass = RateLimiter(30, 60, clock)
    private val anonGlobal = RateLimiter(300, 60, clock)
    /** Enrolments and codes per user (a backstop per replica; the lockout counts only wrong codes). */
    private val mfaLimiter = RateLimiter(30, 15 * 60, clock)

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
        // Only a phone the server knows hashes in the phones' pool; everyone else in the small anonymous one.
        val enrolled = phone && device != null
        if (!phone) webPerIpClass.tryAcquire("ip:" + ipClass(ctx.clientIp)).let { if (!it.allowed) throw it.toProblem("too many logins from this address; retry later") }
        if (!enrolled) anonGlobal.tryAcquire(if (phone) "phone" else "web").let { if (!it.allowed) throw it.toProblem("too many logins; retry later") }

        val user = users.findByUsername(username)
        val ok = (if (enrolled) limiter else webLimiter).run { hasher.verify(user?.passwordHash ?: hasher.dummyHash, req.password) } && user?.passwordHash != null
        if (!ok || user == null) {
            val n = lockouts.recordFailure(lockKey, now, Duration.ofMinutes(config.int("cfg.auth.lockout_window_min").toLong()))
            val facts = mapOf("username_hash" to SecurityEvents.usernameHash(username), "client" to if (phone) "phone" else "web", "ip_class" to ipClass(ctx.clientIp))
            securityEvents.safely(SecurityEvent(SecurityEventKind.LOGIN_FAILURE, now, user?.id, req.device_uuid?.lowercase(), detail = facts))
            if (n >= config.int("cfg.auth.lockout_attempts")) {
                val base = Duration.ofMinutes(config.int("cfg.auth.lockout_min").toLong())
                lockouts.lock(lockKey, now, base)
                securityEvents.safely(SecurityEvent(SecurityEventKind.LOCKOUT, now, user?.id, req.device_uuid?.lowercase(), detail = facts + ("failures" to n.toString())))
            }
            throw ApiProblem(ProblemCode.ERR_AUTH_INVALID_CREDENTIALS, "username or password is wrong")
        }
        lockouts.reset(lockKey)
        if (user.status != "active") throw ApiProblem(ProblemCode.ERR_AUTH_USER_DISABLED, "user is disabled")
        // A phone app serves its own field role only: a web role (and so every MFA role) never logs in through a
        // phone client, where no TOTP step exists (checker finding, 2026-10-07).
        if (phone && PHONE_ROLE[flavour] != user.role) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "this account cannot use this app")

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
    suspend fun changePassword(p: AronPrincipal, req: ChangePasswordRequest, callerRefreshToken: String? = null): LoginResponse? {
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
            if (n >= config.int("cfg.auth.lockout_attempts")) {
                lockouts.lock(lockKey, now, Duration.ofMinutes(config.int("cfg.auth.lockout_min").toLong()))
                securityEvents.safely(SecurityEvent(SecurityEventKind.LOCKOUT, now, user.id, p.deviceUuid, detail = mapOf("flow" to "change_password", "failures" to n.toString())))
            }
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
        // The caller's own session survives: the phone's family by its device, a web BFF's by the aron_rt it forwards.
        val keepFamily = callerRefreshToken?.let { refresh.peek(it) }?.family?.takeIf { it.userId == user.id && it.grant == Grant.FULL }?.id
        if (!store.change(user.id, state.currentHash, newHash, now, if (p.isPhone) p.deviceId else null, keepFamily)) {
            throw ApiProblem(ProblemCode.ERR_CONFLICT, "the password was changed at the same time; try again")
        }
        users.invalidate(user.id)
        securityEvents.safely(SecurityEvent(SecurityEventKind.PASSWORD_CHANGE, now, user.id, p.deviceUuid, detail = mapOf("client" to if (p.isPhone) "phone" else "web")))
        if (!viaToken) return null
        val fresh = users.findById(user.id) ?: throw ApiProblem(ProblemCode.ERR_UNAUTHENTICATED)
        return complete(fresh, null, null, "web", minVersionCode("web"), afterPasswordChange = true)
    }

    /**
     * POST /v1/auth/mfa/enrol (contract enrolMfa, F-WEB-043, docs/21 s2.8): a new TOTP secret (RFC 6238, SHA-1, 6
     * digits, 30 s; sealed with [MfaCipher]) and ten single-use recovery codes, shown once; only their keyed verifiers
     * are stored. Web only. The caller is a full web session, or, for the first enrolment of an MFA role (which has no
     * full session before TOTP), the login's `mfa_token`: an unenrolled MFA user enrols from the login itself
     * (trust on first use; DECISIONS.md). The enrolment stays unconfirmed, and a later enrol replaces it, until the first
     * TOTP code verifies; once confirmed, enrol answers 409 and only an ADMIN's `reset_mfa` starts over.
     */
    fun enrolMfa(p: AronPrincipal): MfaEnrolment {
        val store = mfa ?: throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "MFA is not available")
        val cipher = mfaCipher ?: throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "MFA is not available")
        if (p.isPhone) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "MFA is for web sign-in only")
        val user = users.findById(p.userId) ?: throw ApiProblem(ProblemCode.ERR_UNAUTHENTICATED)
        if (user.status != "active") throw ApiProblem(ProblemCode.ERR_AUTH_USER_DISABLED, "user is disabled")
        // Only the MFA roles: confirmation happens in the login's TOTP step, which other roles never reach (checker finding 3).
        if (user.role.wire !in mfaRoles()) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "MFA is required only for the admin roles (cfg.auth.mfa_required_roles)")
        // An mfa_token minted before an admin's reset_mfa (which bumps scope_version) is spent.
        if (p.audience == Audience.MFA && p.scopeVersion != user.scopeVersion) throw ApiProblem(ProblemCode.ERR_AUTH_MFA_INVALID, "sign in again")
        mfaLimiter.tryAcquire("e:${user.id}").let { if (!it.allowed) throw it.toProblem("too many enrolments") }
        val secret = cipher.newSecret()
        val codes = cipher.recoveryCodes()
        if (!store.enrol(user.id, cipher.seal(secret, user.id), codes.map { cipher.recoveryMac(it, user.id) }, clock.now(), p)) {
            throw ApiProblem(ProblemCode.ERR_CONFLICT, "MFA is already set up; an administrator can reset it")
        }
        return MfaEnrolment(Totp.uri(user.username, secret), codes)
    }

    /**
     * POST /v1/auth/mfa/verify (contract verifyMfa): the second step of a web login, with the login's `mfa_token`
     * (verified by the route) and a TOTP code or a recovery code. A TOTP code is accepted in the steps now - 1 .. now + 1
     * and only above the last used step (no replay); the first good code confirms the enrolment. A recovery code works
     * only on a confirmed enrolment and is spent. Wrong codes count towards `cfg.auth.lockout_attempts` per user
     * (then 403 ERR_AUTH_ACCOUNT_LOCKED for `cfg.auth.lockout_min`, doubling); every failure is the same 401
     * ERR_AUTH_MFA_INVALID. Success continues the login with `amr` ["pwd","mfa"].
     */
    fun verifyMfa(p: AronPrincipal, rawCode: String): LoginResponse {
        val code = rawCode.trim().uppercase()
        val store = mfa ?: throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "MFA is not available")
        val cipher = mfaCipher ?: throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "MFA is not available")
        if (p.audience != Audience.MFA || p.isPhone) throw ApiProblem(ProblemCode.ERR_AUTH_MFA_INVALID, "not an mfa_token")
        if (!MFA_CODE.matches(code)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "6 digits or a recovery code XXXX-XXXX", errors = listOf(FieldError("/code", "pattern")))
        val now = clock.now()
        val user = users.findById(p.userId) ?: throw ApiProblem(ProblemCode.ERR_AUTH_MFA_INVALID, "sign in again")
        if (user.status != "active") throw ApiProblem(ProblemCode.ERR_AUTH_USER_DISABLED, "user is disabled")
        if (p.scopeVersion != user.scopeVersion) throw ApiProblem(ProblemCode.ERR_AUTH_MFA_INVALID, "sign in again")
        val lockKey = "mfa|${user.id}"
        lockouts.lockedUntil(lockKey, now)?.let { until ->
            val sec = Duration.between(now, until).seconds.coerceAtLeast(1).toInt()
            throw ApiProblem(ProblemCode.ERR_AUTH_ACCOUNT_LOCKED, "too many wrong codes", retryAfterS = sec, headers = mapOf("Retry-After" to sec.toString()))
        }
        mfaLimiter.tryAcquire("m:${user.id}").let { if (!it.allowed) throw it.toProblem("too many codes") }
        val outcome = store.verify(user.id, now, p) { state ->
            val secret = state?.let { cipher.open(it.secretCipher, user.id) }
            when {
                state == null -> MfaUpdate.None
                secret == null -> MfaUpdate.Unreadable
                code.length == 6 -> Totp.matchingStep(secret, code, now)?.takeIf { s -> state.lastUsedStep == null || s > state.lastUsedStep }
                    ?.let { MfaUpdate.TotpUsed(it) } ?: MfaUpdate.None
                !state.confirmed -> MfaUpdate.None
                else -> cipher.matchRecovery(code, user.id, state.recoveryMacs)?.let { MfaUpdate.RecoverySpent(state.recoveryMacs - it) } ?: MfaUpdate.None
            }
        }
        if (outcome == MfaUpdate.Unreadable) {
            // A lost or rotated-away key (checker finding 1): not the user's fault, so no lockout count; an admin resets MFA.
            securityEvents.safely(SecurityEvent(SecurityEventKind.LOGIN_FAILURE, now, user.id, null, detail = mapOf("flow" to "mfa_unreadable", "client" to "web")))
            throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "the authenticator setup cannot be read; ask an administrator to reset MFA")
        }
        if (outcome == MfaUpdate.None) {
            val n = lockouts.recordFailure(lockKey, now, Duration.ofMinutes(config.int("cfg.auth.lockout_window_min").toLong()))
            val facts = mapOf("flow" to "mfa", "client" to "web")
            securityEvents.safely(SecurityEvent(SecurityEventKind.LOGIN_FAILURE, now, user.id, null, detail = facts))
            if (n >= config.int("cfg.auth.lockout_attempts")) {
                lockouts.lock(lockKey, now, Duration.ofMinutes(config.int("cfg.auth.lockout_min").toLong()))
                securityEvents.safely(SecurityEvent(SecurityEventKind.LOCKOUT, now, user.id, null, detail = facts + ("failures" to n.toString())))
            }
            throw ApiProblem(ProblemCode.ERR_AUTH_MFA_INVALID, "the code is wrong")
        }
        lockouts.reset(lockKey)
        users.invalidate(user.id)
        val fresh = users.findById(user.id) ?: throw ApiProblem(ProblemCode.ERR_AUTH_MFA_INVALID, "sign in again")
        return complete(fresh, null, null, "web", minVersionCode("web"), afterPasswordChange = false, afterMfa = true)
    }

    private fun cfgInt(key: String, default: Int): Int = runCatching { config.int(key) }.getOrDefault(default)

    /**
     * POST /v1/auth/bind-device (F-API-003, F-SYS-003): with the login's bind_token, the phone proves its key over
     * `aron-proof-v1 / bind / device_uuid / hex sha256(otp) / bucket` and sends the OTP the TSO read out. The OTP is
     * single use, expires after `cfg.auth.otp_ttl_min`, and after `cfg.auth.otp_max_attempts` (5) wrong tries no
     * attempt is accepted, not even the right one. A match binds the user to the phone with the next free bind ordinal
     * and continues the login (tokens).
     */
    fun bindDevice(p: AronPrincipal, req: BindDeviceRequest, proof: String?): LoginResponse {
        val store = binds ?: throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "device binding is not available")
        val sealer = otpSealer ?: throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "device binding is not available")
        val now = clock.now()
        val uuid = p.deviceUuid ?: throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "the bind token names no device")
        if (req.device_uuid.lowercase() != uuid) throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "device_uuid differs from the token's device")
        val device = checkDevice(uuid) ?: throw ApiProblem(ProblemCode.ERR_DEVICE_NOT_ENROLLED, "this phone is not enrolled")
        if (device.id != p.deviceId) throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "the bind token names another device")
        val key = device.publicKeyJwk?.let(com.aktcl.aron.backend.platform.DeviceProof::publicKey)
        if (key != null) {
            val sig = proof ?: throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "X-Device-Proof is required")
            val ok = com.aktcl.aron.backend.platform.DeviceProof.verifyBucketed(key, sig, now.epochSecond) { b ->
                listOf("aron-proof-v1", "bind", uuid, com.aktcl.aron.backend.platform.DeviceProof.sha256Hex(req.otp.toByteArray()), b.toString()).joinToString("\n")
            }
            if (!ok) throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "device proof does not verify")
        } else if (config.bool("cfg.device.require_enrolled")) {
            throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "device key unknown")
        }
        val user = users.findById(p.userId) ?: throw ApiProblem(ProblemCode.ERR_UNAUTHENTICATED)
        if (user.status != "active") throw ApiProblem(ProblemCode.ERR_AUTH_USER_DISABLED, "user is disabled")
        when (store.bind(user.id, device.id, req.otp, now, cfgInt("cfg.auth.otp_max_attempts", 5), sealer)) {
            is BindResult.Bound -> Unit
            BindResult.Invalid -> throw ApiProblem(ProblemCode.ERR_AUTH_OTP_INVALID, "the code is wrong")
            BindResult.Expired -> throw ApiProblem(ProblemCode.ERR_AUTH_OTP_EXPIRED, "the code has expired; ask the TSO for a new one")
            BindResult.AttemptsExceeded -> throw ApiProblem(ProblemCode.ERR_AUTH_OTP_ATTEMPTS_EXCEEDED, "too many wrong codes; ask the TSO for a new one")
            BindResult.NoFreeOrdinal -> throw ApiProblem(ProblemCode.ERR_DEVICE_LIMIT_REACHED, "this user is bound to four phones already (docs/24 s7.5)")
        }
        return complete(user, device, uuid, p.flavour, minVersionCode(p.flavour), afterPasswordChange = false)
    }

    /**
     * The steps of a login after the password matched: web temporary password (R15: before TOTP), TOTP for MFA roles,
     * device binding, phone temporary password, then the tokens. [afterPasswordChange] continues a web login after
     * POST /v1/auth/change-password with the password_change_token (the password is no longer temporary).
     */
    private fun complete(
        user: UserRecord, device: DeviceRecord?, deviceUuid: String?, flavour: String, minVersion: Int?, afterPasswordChange: Boolean,
        afterMfa: Boolean = false,
    ): LoginResponse {
        val now = clock.now()
        val phone = flavour != "web"
        val summary = UserSummary(user.id, user.username, user.fullName, user.role, user.designation, user.locale)
        val scope = reach.reach(user.id, user.role, user.scopeVersion, BusinessDate.of(now.toEpochMilli()).toJavaLocalDate())
        val scopeSummary = ScopeSummary(user.scopeVersion, scope.topNodes.take(16).map { NodeRef(it.type, it.id, it.code, it.name) })
        val subject = TokenSubject(user, device?.id, deviceUuid, flavour, amr = if (afterMfa) listOf("pwd", "mfa") else listOf("pwd"))
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
        // TOTP for the MFA roles and for anyone who confirmed an enrolment of their own (F-WEB-043).
        if (!phone && !afterMfa && (user.role.wire in mfaRoles() || user.mfaEnabled)) {
            val mfa = issuer.access(subject, Audience.MFA)
            return base("mfa_required").copy(mfa_token = mfa.token, scope = null)
        }
        val ordinal = device?.let { devices.bindOrdinal(user.id, it.id) }
        if (device != null && ordinal == null && config.bool("cfg.auth.bind_otp_required")) {
            // The OTP the TSO reads on the Device OTP panel is created here, sealed and with a keyed verifier (s8.1).
            if (binds != null && otpSealer != null) {
                binds.ensureOtp(user.id, now, Duration.ofMinutes(cfgInt("cfg.auth.otp_ttl_min", 120).toLong()), cfgInt("cfg.auth.otp_length", 4), otpSealer)
            }
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
        private val PHONE_ROLE = mapOf("sr" to Role.SR, "amo" to Role.AMO, "tso" to Role.TSO)
        private val MFA_CODE = Regex("^([0-9]{6}|[A-Z0-9]{4}-[A-Z0-9]{4})$")

        /** IPv4 /24 or IPv6 /48 of the client (the lockout key's IP class); "-" when unknown. */
        private val IPV6_LITERAL = Regex("^[0-9A-Fa-f:.]{2,45}$")

        fun ipClass(ip: String?): String {
            if (ip.isNullOrBlank()) return "-"
            val v = ip.trim().removePrefix("::ffff:").removePrefix("::FFFF:")
            // IPv6: expand the literal first ("2001::5:6" must not keep groups from after "::"); never a DNS lookup.
            return if (v.contains(':')) {
                if (!IPV6_LITERAL.matches(v)) return "-"
                val b = runCatching { java.net.InetAddress.getByName(v).address }.getOrNull()?.takeIf { it.size == 16 } ?: return "-"
                (0 until 3).joinToString(":") { i -> "%x".format(((b[2 * i].toInt() and 0xff) shl 8) or (b[2 * i + 1].toInt() and 0xff)) } + "::/48"
            }
            else v.split('.').let { if (it.size == 4) "${it[0]}.${it[1]}.${it[2]}.0/24" else "-" }
        }
    }
}
