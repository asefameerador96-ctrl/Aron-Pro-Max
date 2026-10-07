package com.aktcl.aron.core.session

import kotlinx.serialization.Serializable

/** A user who has logged in on this phone (shared phones keep several, docs/24 s5.3). Stored encrypted. */
@Serializable
data class UserProfile(
    val userId: Long,
    val username: String,
    val fullName: String,
    val role: String,
    val designation: String? = null,
    val locale: String,
    /** Argon2id verifier of the password of the last online login (docs/24 s8.1). */
    val verifier: String,
    /** Trusted time of the last successful online login; offline unlock is allowed for 7 days after it. */
    val lastOnlineLoginMs: Long,
    /** elapsedRealtime at that login: while the phone has not rebooted, real time since login is at least elapsed minus this. */
    val lastOnlineLoginElapsedMs: Long = 0,
    val offlineFailures: Int = 0,
    /** Wall-clock end of the offline cool-down (shown to the user; used only after a reboot). */
    val cooldownUntilMs: Long = 0,
    /** elapsedRealtime at which the cool-down started and ends: date changes cannot shorten it (no reboot in between). */
    val cooldownStartElapsedMs: Long = 0,
    val cooldownUntilElapsedMs: Long = 0,
    /**
     * Highest wall-clock time seen for this user on this phone. Offline-unlock age is measured from max(now, this), so
     * setting the date back never reopens an expired unlock (docs/24 s8.1, checker finding on N-001).
     */
    val highWaterMs: Long = 0,
    /**
     * Real time proven to have passed since the last online login, summed from elapsedRealtime across reboots (F-SYS-052
     * checker): a reboot plus setting the date back cannot reopen the window, because every stretch of uptime the app saw
     * still counts. Time while the phone was off cannot be proven offline; the date and [highWaterMs] cover what they can.
     */
    val provenAgeMs: Long = 0,
    /** elapsedRealtime at the last time [provenAgeMs] was brought up to date (0: not yet; then the login's value). */
    val observedElapsedMs: Long = 0,
    /** Boot count at that time (0: unknown): a different boot means everything since boot is new uptime. */
    val observedBootCount: Int = 0,
    val deviceId: Long? = null,
    val bindOrdinal: Int? = null,
    val memoSeqBlockSize: Int? = null,
    val configVersion: Long? = null,
    val scopeVersion: Long? = null,
) {
    override fun toString(): String =
        "UserProfile(userId=$userId, username=$username, role=$role, locale=$locale, offlineFailures=$offlineFailures, verifier=***)"
}

/** Token set of one user on this phone. Stored encrypted; never logged. */
@Serializable
data class StoredTokens(
    val accessToken: String? = null,
    val accessExpiresAt: String? = null,
    val refreshToken: String? = null,
    val refreshExpiresAt: String? = null,
    /** The upload-only grant (D24-57): survives logout and password change so captured rows always reach the server. */
    val uploadRefreshToken: String? = null,
    val uploadAccessToken: String? = null,
    val uploadAccessExpiresAt: String? = null,
    val scopeVersion: Long? = null,
    /** Set when the full grant was refused (refresh reused, password changed): ask for the password at the next online moment. */
    val reauthRequired: Boolean = false,
    /** Set when the upload grant was refused; the outbox waits for a new online login. */
    val uploadReauthRequired: Boolean = false,
) {
    override fun toString(): String = "StoredTokens(scopeVersion=$scopeVersion, reauthRequired=$reauthRequired, uploadReauthRequired=$uploadReauthRequired, tokens=***)"
}

/** How the current session was unlocked. */
enum class UnlockMode { ONLINE, OFFLINE }

@Serializable
data class ActiveSession(val userId: Long, val mode: String)

/** Session state seen by the app shell. Local work never waits on it: selling needs only [Active]. */
sealed interface SessionState {
    /** Cold start: the stored session is being read off the main thread (AUD-PERF-05). The shells show the splash. */
    data object Restoring : SessionState

    data object LoggedOut : SessionState

    data class Active(
        val user: UserProfile,
        val mode: UnlockMode,
        /** The server refused the refresh token: keep working, ask for the password when online. */
        val reauthRequired: Boolean,
        /** Login answered 426: the app must be updated, but an open offline day may finish first (docs/24 s3.7). */
        val updateRequired: Boolean = false,
    ) : SessionState
}
