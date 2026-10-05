package com.aktcl.aron.core.session

import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.core.common.DispatcherProvider
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.network.AccessTokenSource
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AuthApi
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.core.network.LoginRequest
import com.aktcl.aron.core.network.LoginResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Offline-unlock limits (docs/24 s8.1); the values come from `cfg.auth.*` once the bundle row applies config. */
data class OfflineUnlockPolicy(
    /** `cfg.auth.offline_unlock_max_days` (7). */
    val maxDays: Int = 7,
    /** `cfg.auth.offline_unlock_max_attempts` (10) failures start the cool-down. */
    val maxAttempts: Int = 10,
    /** First cool-down; doubles with every further failure (android-core decision AC-03, docs/status/android-core.md). */
    val cooldownBaseMs: Long = 60_000,
    val cooldownCapMs: Long = 3_600_000,
) {
    fun cooldownAfter(failures: Int): Long {
        if (failures < maxAttempts) return 0
        val doublings = (failures - maxAttempts).coerceAtMost(30)
        return (cooldownBaseMs shl doublings).coerceAtMost(cooldownCapMs).coerceAtLeast(cooldownBaseMs)
    }
}

/** Why an offline unlock was not possible. */
enum class OfflineRefusal {
    NEVER_ONLINE_ON_THIS_PHONE,
    EXPIRED,
    WRONG_PASSWORD,
    COOLDOWN,

    /** The phone's date is before the last online login: wrong clock (or a rollback); offline unlock is refused. */
    CLOCK_INCONSISTENT,
}

/** What the login screen shows after a tap. */
sealed interface LoginOutcome {
    data class LoggedIn(val user: UserProfile, val mode: UnlockMode, val updateRequired: Boolean = false) : LoginOutcome
    data object InvalidCredentials : LoginOutcome
    data class BindRequired(val bindToken: String?) : LoginOutcome {
        override fun toString(): String = "BindRequired(***)"
    }
    data object PasswordChangeRequired : LoginOutcome

    /** 403 and other refusals by the API; [code] picks the localised text (`ERR_AUTH_ACCOUNT_LOCKED`, `ERR_DEVICE_NOT_ENROLLED`, ...). */
    data class Refused(val code: String?, val retryAfterS: Int?) : LoginOutcome

    /** The API could not be reached or could not answer, and offline unlock was not possible. */
    data class OfflineUnavailable(val refusal: OfflineRefusal, val cooldownUntilMs: Long? = null, val serverAnswer: Int? = null) : LoginOutcome
}

/**
 * Login, offline unlock, token storage and refresh for one app (docs/24 s1.2 core-session, s5.3, s8.1).
 *
 * Rules: selling never needs a live token, so a refresh failure only flags [SessionState.Active.reauthRequired];
 * an online "wrong password" never falls back to offline unlock; any transport failure, edge page, 429 or 5xx does
 * (the cloud never blocks the day, docs/23 s5). Several users may log in on one phone; each keeps their own tokens.
 */
class SessionRepository(
    private val authApi: AuthApi,
    private val store: SessionStore,
    private val verifier: PasswordVerifier,
    private val deviceIdentity: DeviceIdentity,
    /** `app_sr`, `app_amo` or `app_tso`. */
    private val client: String,
    private val clock: WallClock = WallClock.System,
    private val policy: OfflineUnlockPolicy = OfflineUnlockPolicy(),
    private val dispatchers: DispatcherProvider = DispatcherProvider.Standard,
) : AccessTokenSource {

    private val loginMutex = Mutex()
    private val refreshMutex = Mutex()
    private val _state = MutableStateFlow(restore())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private fun restore(): SessionState {
        val active = store.active() ?: return SessionState.LoggedOut
        val profile = store.profileByUserId(active.userId) ?: return SessionState.LoggedOut
        val mode = runCatching { UnlockMode.valueOf(active.mode) }.getOrDefault(UnlockMode.OFFLINE)
        return SessionState.Active(profile, mode, store.tokens(profile.userId).reauthRequired)
    }

    suspend fun login(username: String, password: String): LoginOutcome = loginMutex.withLock {
        withContext(dispatchers.io) { loginLocked(SessionStore.normalize(username), password) }
    }

    private suspend fun loginLocked(username: String, password: String): LoginOutcome {
        val request = LoginRequest(username, password, client, deviceIdentity.deviceUuid)
        return when (val r = authApi.login(request)) {
            is ApiResult.Success -> onLoginAnswer(r.value, password)
            is ApiResult.NotModified -> offlineUnlock(username, password, serverAnswer = 304, updateRequired = false)
            is ApiResult.Transport -> offlineUnlock(username, password, serverAnswer = null, updateRequired = false)
            is ApiResult.Failure -> when {
                r.problem.problemCode == ProblemCode.ERR_AUTH_INVALID_CREDENTIALS -> LoginOutcome.InvalidCredentials
                r.httpStatus == 401 -> LoginOutcome.Refused(r.problem.code, null)
                r.httpStatus == 426 -> offlineUnlock(username, password, 426, updateRequired = true).let {
                    if (it is LoginOutcome.OfflineUnavailable) LoginOutcome.Refused(ProblemCode.ERR_APP_VERSION_UNSUPPORTED.wire, null) else it
                }
                r.httpStatus == 429 || r.httpStatus >= 500 -> offlineUnlock(username, password, r.httpStatus, updateRequired = false).let {
                    if (it is LoginOutcome.OfflineUnavailable && r.httpStatus == 429) {
                        LoginOutcome.Refused(r.problem.code ?: ProblemCode.ERR_RATE_LIMITED.wire, r.problem.retryAfterS ?: r.meta.retryAfterS)
                    } else it
                }
                else -> LoginOutcome.Refused(r.problem.code, r.problem.retryAfterS ?: r.meta.retryAfterS)
            }
        }
    }

    private fun onLoginAnswer(answer: LoginResponse, password: String): LoginOutcome = when (answer.status) {
        LoginResponse.STATUS_OK -> {
            val access = answer.accessToken
            val refresh = answer.refreshToken
            if (access == null || refresh == null) {
                LoginOutcome.Refused(null, null)
            } else {
                val previous = store.profileByUserId(answer.user.userId)
                val profile = UserProfile(
                    userId = answer.user.userId,
                    username = SessionStore.normalize(answer.user.username),
                    fullName = answer.user.fullName,
                    role = answer.user.role,
                    designation = answer.user.designation,
                    locale = answer.user.locale,
                    verifier = verifier.create(password),
                    lastOnlineLoginMs = clock.nowMs(),
                    highWaterMs = clock.nowMs(),
                    deviceId = answer.device?.deviceId ?: previous?.deviceId,
                    bindOrdinal = answer.device?.bindOrdinal ?: previous?.bindOrdinal,
                    memoSeqBlockSize = answer.device?.memoSeqBlockSize ?: previous?.memoSeqBlockSize,
                    configVersion = answer.configVersion,
                    scopeVersion = answer.scope?.scopeVersion,
                )
                // Tokens first, then the profile, then the active pointer: a kill between steps leaves either the old
                // session or a complete new one, never a pointer to a user without tokens.
                store.saveTokens(
                    profile.userId,
                    store.tokens(profile.userId).copy(
                        accessToken = access,
                        accessExpiresAt = answer.accessExpiresAt,
                        refreshToken = refresh,
                        refreshExpiresAt = answer.refreshExpiresAt,
                        uploadRefreshToken = answer.uploadRefreshToken ?: store.tokens(profile.userId).uploadRefreshToken,
                        uploadAccessToken = null,
                        uploadAccessExpiresAt = null,
                        scopeVersion = answer.scope?.scopeVersion,
                        reauthRequired = false,
                        uploadReauthRequired = false,
                    ),
                )
                store.saveProfile(profile)
                activate(profile, UnlockMode.ONLINE, reauthRequired = false, updateRequired = false)
                LoginOutcome.LoggedIn(profile, UnlockMode.ONLINE)
            }
        }
        LoginResponse.STATUS_BIND_REQUIRED -> LoginOutcome.BindRequired(answer.bindToken)
        LoginResponse.STATUS_PASSWORD_CHANGE_REQUIRED -> LoginOutcome.PasswordChangeRequired
        else -> LoginOutcome.Refused(null, null) // mfa_required is a web outcome; a phone never gets it
    }

    private fun offlineUnlock(username: String, password: String, serverAnswer: Int?, updateRequired: Boolean): LoginOutcome {
        val stored = store.profileByUsername(username)
            ?: return LoginOutcome.OfflineUnavailable(OfflineRefusal.NEVER_ONLINE_ON_THIS_PHONE, serverAnswer = serverAnswer)
        val wall = clock.nowMs()
        val elapsed = clock.elapsedRealtimeMs()
        if (wall < stored.lastOnlineLoginMs - CLOCK_TOLERANCE_MS) {
            return LoginOutcome.OfflineUnavailable(OfflineRefusal.CLOCK_INCONSISTENT, serverAnswer = serverAnswer)
        }
        // Setting the date back never helps: time only moves forward from the highest value seen.
        val now = maxOf(wall, stored.highWaterMs)
        val profile = stored.copy(highWaterMs = now)
        if (inCooldown(profile, now, elapsed)) {
            store.saveProfile(profile)
            return LoginOutcome.OfflineUnavailable(OfflineRefusal.COOLDOWN, profile.cooldownUntilMs, serverAnswer)
        }
        if (now - profile.lastOnlineLoginMs > policy.maxDays * DAY_MS) {
            store.saveProfile(profile)
            return LoginOutcome.OfflineUnavailable(OfflineRefusal.EXPIRED, serverAnswer = serverAnswer)
        }
        if (!verifier.matches(password, profile.verifier)) {
            val failures = profile.offlineFailures + 1
            val cooldown = policy.cooldownAfter(failures)
            val updated = if (cooldown > 0) {
                profile.copy(
                    offlineFailures = failures, cooldownUntilMs = now + cooldown,
                    cooldownStartElapsedMs = elapsed, cooldownUntilElapsedMs = elapsed + cooldown,
                )
            } else {
                profile.copy(offlineFailures = failures)
            }
            store.saveProfile(updated)
            return if (cooldown > 0) LoginOutcome.OfflineUnavailable(OfflineRefusal.COOLDOWN, updated.cooldownUntilMs, serverAnswer)
            else LoginOutcome.OfflineUnavailable(OfflineRefusal.WRONG_PASSWORD, serverAnswer = serverAnswer)
        }
        val unlocked = profile.copy(offlineFailures = 0, cooldownUntilMs = 0, cooldownStartElapsedMs = 0, cooldownUntilElapsedMs = 0)
        store.saveProfile(unlocked)
        activate(unlocked, UnlockMode.OFFLINE, store.tokens(unlocked.userId).reauthRequired, updateRequired)
        return LoginOutcome.LoggedIn(unlocked, UnlockMode.OFFLINE, updateRequired)
    }

    /**
     * The cool-down runs on elapsedRealtime, which date changes cannot move. After a reboot (elapsed below the start)
     * the monotonic clock restarted, so the wall-clock end (on the high-water time) applies instead.
     */
    private fun inCooldown(p: UserProfile, now: Long, elapsed: Long): Boolean {
        if (p.cooldownUntilMs == 0L) return false
        val rebooted = elapsed < p.cooldownStartElapsedMs
        return if (rebooted) now < p.cooldownUntilMs else elapsed < p.cooldownUntilElapsedMs
    }

    private fun activate(profile: UserProfile, mode: UnlockMode, reauthRequired: Boolean, updateRequired: Boolean) {
        store.saveActive(ActiveSession(profile.userId, mode.name))
        _state.value = SessionState.Active(profile, mode, reauthRequired, updateRequired)
    }

    /**
     * Ends the session on this phone at once. The user's database, outbox and upload grant stay (D24-57): logging out
     * never deletes or blocks pending rows. The server revocation runs afterwards, best effort, with a short timeout;
     * offline it simply fails and the server-side family expires on its own.
     */
    suspend fun logout() {
        val revoke = loginMutex.withLock {
            val active = _state.value as? SessionState.Active ?: return
            val userId = active.user.userId
            val tokens = withContext(dispatchers.io) {
                store.tokens(userId).also { t ->
                    store.saveTokens(userId, t.copy(accessToken = null, accessExpiresAt = null, refreshToken = null, refreshExpiresAt = null))
                    store.saveActive(null)
                }
            }
            _state.value = SessionState.LoggedOut
            tokens
        }
        val access = revoke.accessToken
        if (access != null && revoke.refreshToken != null) {
            withContext(dispatchers.io) { runCatching { authApi.logout("session", revoke.refreshToken, access) } }
        }
    }

    // ---- AccessTokenSource: used by every authenticated call of core-network ----

    override fun currentAccessToken(grant: Grant): String? {
        val active = _state.value as? SessionState.Active ?: return null
        val tokens = store.tokens(active.user.userId)
        return if (grant == Grant.FULL) tokens.accessToken else tokens.uploadAccessToken
    }

    override suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: ProblemCode?): Boolean {
        val active = _state.value as? SessionState.Active ?: return false
        return refresh(active.user.userId, grant, rejectedToken)
    }

    /**
     * Rotates the refresh token of [grant] for [userId] (also used by the sync engine to obtain an upload access token
     * for a user who is not the active one). Returns true when a new access token is stored.
     *
     * The network call runs outside the session lock; the result is written under it and only if the token that was
     * sent is still the stored one, so a logout or a new login that happened meanwhile is never overwritten.
     */
    suspend fun refresh(userId: Long, grant: Grant, rejectedToken: String? = null): Boolean = refreshMutex.withLock {
        val sent = withContext(dispatchers.io) {
            val tokens = store.tokens(userId)
            val current = if (grant == Grant.FULL) tokens.accessToken else tokens.uploadAccessToken
            if (current != null && rejectedToken != null && current != rejectedToken) return@withContext null // rotated meanwhile
            (if (grant == Grant.FULL) tokens.refreshToken else tokens.uploadRefreshToken) ?: ""
        } ?: return@withLock true
        if (sent.isEmpty()) return@withLock false
        val result = withContext(dispatchers.io) { authApi.refresh(sent, grant) }
        loginMutex.withLock {
            withContext(dispatchers.io) {
                val tokens = store.tokens(userId)
                val stillSame = (if (grant == Grant.FULL) tokens.refreshToken else tokens.uploadRefreshToken) == sent
                if (!stillSame) return@withContext false // logged out or logged in again meanwhile: drop the result
                when (result) {
                    is ApiResult.Success -> {
                        val pair = result.value
                        store.saveTokens(
                            userId,
                            if (grant == Grant.FULL) {
                                tokens.copy(
                                    accessToken = pair.accessToken, accessExpiresAt = pair.accessExpiresAt,
                                    refreshToken = pair.refreshToken ?: sent, refreshExpiresAt = pair.refreshExpiresAt,
                                    scopeVersion = pair.scopeVersion, reauthRequired = false,
                                )
                            } else {
                                tokens.copy(
                                    uploadAccessToken = pair.accessToken, uploadAccessExpiresAt = pair.accessExpiresAt,
                                    uploadRefreshToken = pair.refreshToken ?: sent, uploadReauthRequired = false,
                                )
                            },
                        )
                        if (grant == Grant.FULL) setReauth(userId, false)
                        true
                    }
                    is ApiResult.Failure -> {
                        if (endsFamily(grant, result.problem.problemCode)) {
                            // The family is gone (reused, invalid, password changed): keep selling, ask for the password later.
                            store.saveTokens(
                                userId,
                                if (grant == Grant.FULL) tokens.copy(accessToken = null, refreshToken = null, reauthRequired = true)
                                else tokens.copy(uploadAccessToken = null, uploadRefreshToken = null, uploadReauthRequired = true),
                            )
                            if (grant == Grant.FULL) setReauth(userId, true)
                        }
                        false
                    }
                    is ApiResult.NotModified, is ApiResult.Transport -> false
                }
            }
        }
    }

    /**
     * Only these answers end a refresh family (docs/24 s3.4). A bad device proof, a suspended device or a lockout keeps
     * the tokens: the next attempt (fixed key, lifted suspension) can still succeed. The upload grant survives user
     * disable (D24-57), so USER_DISABLED ends only the full grant.
     */
    private fun endsFamily(grant: Grant, code: ProblemCode?): Boolean = when (code) {
        ProblemCode.ERR_AUTH_REFRESH_INVALID, ProblemCode.ERR_AUTH_REFRESH_REUSED, ProblemCode.ERR_PASSWORD_CHANGED -> true
        ProblemCode.ERR_AUTH_USER_DISABLED -> grant == Grant.FULL
        else -> false
    }

    private fun setReauth(userId: Long, value: Boolean) {
        val s = _state.value
        if (s is SessionState.Active && s.user.userId == userId && s.reauthRequired != value) _state.value = s.copy(reauthRequired = value)
    }

    private companion object {
        const val DAY_MS = 86_400_000L

        /** A clock a little behind the server-stamped login time (NTP drift) is not a rollback. */
        const val CLOCK_TOLERANCE_MS = 10 * 60_000L
    }
}
