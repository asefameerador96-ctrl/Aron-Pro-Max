package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.SecurityEvent
import com.aktcl.aron.backend.platform.SecurityEventKind
import com.aktcl.aron.backend.platform.SecurityEvents
import com.aktcl.aron.backend.platform.safely
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.contract.ProblemCode
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class IssuedRefresh(val token: String, val familyId: Long, val familyExpiresAt: Instant?, val tokenExpiresAt: Instant)

data class Rotated(val token: String, val family: FamilyView, val tokenExpiresAt: Instant, val replay: Boolean)

/**
 * Refresh-token families with rotation and reuse detection (docs/24 s8.1):
 * - every use rotates the token; the store keeps SHA-256 hashes only;
 * - a replaced token presented again within cfg.auth.refresh_grace_s (60 s) gets the SAME replacement (the child is
 *   derived as HMAC(rotation key, parent), so no plaintext token is ever stored);
 * - presented after the grace, the whole family is revoked (401 ERR_AUTH_REFRESH_REUSED);
 * - full grant: sliding cfg.auth.refresh_ttl_days (30) inside an absolute 90 ± 15 days drawn per family;
 *   upload grant: idle cfg.auth.upload_grant_idle_days (7), no absolute end.
 */
class RefreshService(
    private val store: RefreshStore,
    private val config: ServerConfig,
    rotationKey: ByteArray,
    private val clock: AronClock = AronClock.SYSTEM,
    private val random: SecureRandom = SecureRandom(),
    private val securityEvents: SecurityEvents = SecurityEvents.LOG,
) {
    private val keySpec = SecretKeySpec(rotationKey, "HmacSHA256")

    fun issue(userId: Long, deviceId: Long?, grant: Grant, flavour: String): IssuedRefresh {
        val now = clock.now()
        val absolute = if (grant == Grant.FULL) {
            val base = config.int("cfg.auth.refresh_absolute_days").toLong()
            val jitter = config.int("cfg.auth.refresh_absolute_jitter_days").toLong()
            val offsetS = if (jitter > 0) random.nextLong(-jitter * 86_400, jitter * 86_400 + 1) else 0
            now.plus(Duration.ofDays(base)).plusSeconds(offsetS)
        } else null
        val token = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val expires = slidingExpiry(grant, now, absolute)
        val id = store.createFamily(NewFamily(userId, deviceId, grant, flavour, now, absolute), sha256(token), expires)
        return IssuedRefresh(token, id, absolute, expires)
    }

    /** Rotates [token]; the caller has already checked grant and device binding against [peek]. */
    fun rotate(token: String, expectedGrant: Grant): Rotated {
        val now = clock.now()
        val hash = sha256(token)
        val row = store.findToken(hash) ?: throw invalid()
        val fam = row.family
        if (fam.grant != expectedGrant) throw invalid()
        if (fam.revokedAt != null) {
            throw when (fam.revokeReason) {
                REASON_REUSE -> ApiProblem(ProblemCode.ERR_AUTH_REFRESH_REUSED, "refresh grant revoked after token reuse")
                REASON_PASSWORD -> ApiProblem(ProblemCode.ERR_PASSWORD_CHANGED, "password changed; log in again")
                else -> invalid()
            }
        }
        if (fam.absoluteExpiresAt != null && !now.isBefore(fam.absoluteExpiresAt)) throw invalid()
        val child = derive(token)
        val childHash = sha256(child)
        if (row.usedAt == null) {
            if (!now.isBefore(row.expiresAt)) throw invalid()
            val childExpiry = slidingExpiry(fam.grant, now, fam.absoluteExpiresAt)
            if (store.rotate(hash, now, childHash, childExpiry)) return Rotated(child, fam, childExpiry, replay = false)
            // Lost a race with a concurrent rotation of the same token: fall through to the replay rule.
            return replay(store.findToken(hash) ?: throw invalid(), child, childHash, now)
        }
        return replay(row, child, childHash, now)
    }

    private fun replay(row: TokenView, child: String, childHash: String, now: Instant): Rotated {
        val used = row.usedAt ?: throw invalid()
        val grace = config.int("cfg.auth.refresh_grace_s").toLong()
        if (row.replacedByHash == childHash && !now.isAfter(used.plusSeconds(grace))) {
            val childRow = store.findToken(childHash) ?: throw invalid()
            return Rotated(child, row.family, childRow.expiresAt, replay = true)
        }
        store.revokeFamily(row.family.id, now, REASON_REUSE)
        // Exactly once per reuse: later presentations meet a revoked family and are refused before this point.
        securityEvents.safely(SecurityEvent(SecurityEventKind.REFRESH_REUSE, now, row.family.userId, detail = mapOf("family" to row.family.id.toString(), "grant" to row.family.grant.name.lowercase())))
        throw ApiProblem(ProblemCode.ERR_AUTH_REFRESH_REUSED, "refresh token reused; grant revoked")
    }

    /** Looks a token up without changing anything (grant and device checks before rotation). */
    fun peek(token: String): TokenView? = store.findToken(sha256(token))

    fun revoke(familyId: Long, reason: String) = store.revokeFamily(familyId, clock.now(), reason)

    fun revokeDeviceGrant(userId: Long, deviceId: Long?, deviceUuid: String?, grant: Grant, reason: String) =
        store.revokeDeviceGrant(userId, deviceId, deviceUuid, grant, clock.now(), reason)

    private fun slidingExpiry(grant: Grant, now: Instant, absolute: Instant?): Instant {
        val days = if (grant == Grant.FULL) config.int("cfg.auth.refresh_ttl_days") else config.int("cfg.auth.upload_grant_idle_days")
        val sliding = now.plus(Duration.ofDays(days.toLong()))
        return if (absolute != null && absolute.isBefore(sliding)) absolute else sliding
    }

    private fun derive(parent: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(keySpec) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("aron-refresh-v1|$parent").toByteArray()))
    }

    private fun invalid() = ApiProblem(ProblemCode.ERR_AUTH_REFRESH_INVALID, "refresh token invalid or expired")

    companion object {
        const val REASON_REUSE = "reuse_detected"
        const val REASON_PASSWORD = "password_changed"
        const val REASON_LOGOUT = "logout"

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
