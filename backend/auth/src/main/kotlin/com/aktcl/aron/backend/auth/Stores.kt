package com.aktcl.aron.backend.auth

import com.aktcl.aron.contract.Role
import java.time.Instant

/** Credential view of an `app_user` row (docs/24 s12.1). */
data class UserRecord(
    val id: Long,
    val username: String,
    val fullName: String,
    val role: Role,
    /** `active`, `disabled` (anything but active refuses a full login). */
    val status: String,
    val locale: String,
    val designation: String?,
    val passwordHash: String?,
    val scopeVersion: Long,
    val mustChangePassword: Boolean,
)

/** Login view of a `device` row and the caller's binding to it (docs/24 s8.7). */
data class DeviceRecord(
    val id: Long,
    val uuid: String,
    val state: String,
    val flavour: String?,
    /** Device public key (JWK JSON) when enrolled; proofs are verified against it. */
    val publicKeyJwk: String?,
)

interface UserStore {
    /** Case-insensitive lookup. */
    fun findByUsername(username: String): UserRecord?
    fun findById(id: Long): UserRecord?
    /** Drops any per-replica cache of this user's gate (scope version, temporary password) after a change. */
    fun invalidate(userId: Long) {}
}

interface DeviceStore {
    fun findByUuid(uuid: String): DeviceRecord?
    /** The user's active binding ordinal on this device, or null when unbound. */
    fun bindOrdinal(userId: Long, deviceId: Long): Int?
}

enum class Grant(val wire: String) { FULL("full"), UPLOAD("upload") }

data class NewFamily(
    val userId: Long,
    val deviceId: Long?,
    val grant: Grant,
    val flavour: String,
    val createdAt: Instant,
    /** Absolute end of the family (full grant: 90 ± 15 days); null for the idle-only upload grant. */
    val absoluteExpiresAt: Instant?,
)

data class FamilyView(
    val id: Long,
    val userId: Long,
    val deviceId: Long?,
    val grant: Grant,
    val flavour: String,
    val absoluteExpiresAt: Instant?,
    val revokedAt: Instant?,
    val revokeReason: String?,
)

data class TokenView(
    val hash: String,
    val family: FamilyView,
    val expiresAt: Instant,
    val usedAt: Instant?,
    val replacedByHash: String?,
)

/**
 * Persistence of refresh families and tokens (hashes only, docs/24 s8.1). [rotate] MUST be atomic: of two callers
 * presenting the same unused token, exactly one wins.
 */
interface RefreshStore {
    fun createFamily(family: NewFamily, tokenHash: String, tokenExpiresAt: Instant): Long
    fun findToken(hash: String): TokenView?
    /** Marks [hash] used at [at] and stores the child token; false when the token was already used. */
    fun rotate(hash: String, at: Instant, childHash: String, childExpiresAt: Instant): Boolean
    fun revokeFamily(familyId: Long, at: Instant, reason: String)
    /** Revokes the user's open [grant] families on one phone (by device row or, before enrolment, by device_uuid). */
    fun revokeDeviceGrant(userId: Long, deviceId: Long?, deviceUuid: String?, grant: Grant, at: Instant, reason: String)
}

/** Failed-login counters and locks per lockout key (docs/24 s8.1). */
/** docs/21 s2.4, D-102: a lock doubles from `cfg.auth.lockout_min` up to 2 h, never more (AUD-SEC-02). */
object LockoutLimits {
    const val MAX_LOCK_S: Long = 7_200
    /** About one failure in this many purges idle `auth_lockout` rows. */
    const val PURGE_EVERY: Int = 100
}

interface LockoutStore {
    fun lockedUntil(key: String, now: Instant): Instant?
    /** Records a failure; returns the number of failures inside the window ending now. */
    fun recordFailure(key: String, now: Instant, window: java.time.Duration): Int
    /** Locks [key]; the duration doubles with every lock since the last success (15, 30, 60 ... min). */
    fun lock(key: String, now: Instant, base: java.time.Duration): Instant
    fun reset(key: String)
}
