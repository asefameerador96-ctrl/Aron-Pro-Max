package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.Database
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * The OTP's sealing and keyed verifier (masterdata `OtpCipher`, wired in the app): the TSO panel opens the seal, the
 * bind check recomputes the MAC. Never a bare hash: four digits fall to a 10^4 search (lead ruling, 2026-10-07).
 */
interface OtpSealer {
    fun seal(otp: String, userId: Long): ByteArray
    fun mac(otp: String, userId: Long): ByteArray
    fun digits(length: Int): String
}

/** Outcome of one bind attempt; the attempt counter is committed whatever the outcome. */
sealed class BindResult {
    data class Bound(val ordinal: Int) : BindResult()
    data object Invalid : BindResult()
    data object Expired : BindResult()
    data object AttemptsExceeded : BindResult()
    /** All four bind ordinals of the user are taken by other phones (docs/24 s7.5). */
    data object NoFreeOrdinal : BindResult()
}

interface BindStore {
    /** Creates a bind OTP for [userId] unless an unexpired, unused one exists (the TSO keeps seeing the same code). */
    fun ensureOtp(userId: Long, now: Instant, ttl: Duration, length: Int, sealer: OtpSealer)

    /** Checks [otp] against the user's live OTP and, when it matches, consumes it and binds [deviceId] (one transaction). */
    fun bind(userId: Long, deviceId: Long, otp: String, now: Instant, maxAttempts: Int, sealer: OtpSealer): BindResult
}

/** F-SYS-003, F-API-003 on app.device_otp and app.device_binding (docs/24 s8.1, s8.7). */
class JdbiBindStore(private val db: Database) : BindStore {
    /** The same lock as the TSO panel's issue (the user row), so a login and a re-issue never leave two live OTPs. */
    private fun lockUser(h: org.jdbi.v3.core.Handle, userId: Long) {
        h.createQuery("SELECT id FROM app.app_user WHERE id = :u FOR UPDATE").bind("u", userId).mapTo(Long::class.java).findOne()
    }

    override fun ensureOtp(userId: Long, now: Instant, ttl: Duration, length: Int, sealer: OtpSealer) {
        db.jdbi.useTransaction<Exception> { h ->
            lockUser(h, userId)
            val live = h.createQuery(
                "SELECT count(*) FROM app.device_otp WHERE user_id = :u AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > :now",
            ).bind("u", userId).bind("now", now.odt()).mapTo(Long::class.java).one()
            if (live > 0) return@useTransaction
            val otp = sealer.digits(length)
            h.createUpdate(
                "INSERT INTO app.device_otp (user_id, otp_cipher, otp_sha256, reason, created_at, expires_at) VALUES (:u, :c, :m, 'bind_attempt', :now, :exp)",
            ).bind("u", userId).bind("c", sealer.seal(otp, userId)).bind("m", sealer.mac(otp, userId))
                .bind("now", now.odt()).bind("exp", now.plus(ttl).odt()).execute()
        }
    }

    override fun bind(userId: Long, deviceId: Long, otp: String, now: Instant, maxAttempts: Int, sealer: OtpSealer): BindResult =
        db.jdbi.inTransaction<BindResult, Exception> { h ->
            lockUser(h, userId)
            val row = h.createQuery(
                "SELECT id, otp_sha256, attempts, expires_at FROM app.device_otp WHERE user_id = :u AND consumed_at IS NULL AND revoked_at IS NULL ORDER BY created_at DESC, id DESC LIMIT 1 FOR UPDATE",
            ).bind("u", userId).map { rs, _ -> listOf(rs.getLong(1), rs.getBytes(2), rs.getInt(3), rs.instant("expires_at")) }.findOne().orElse(null)
                ?: return@inTransaction BindResult.Invalid
            val id = row[0] as Long
            val attempts = row[2] as Int
            if (attempts >= maxAttempts) return@inTransaction BindResult.AttemptsExceeded
            if (!(row[3] as Instant).isAfter(now)) return@inTransaction BindResult.Expired
            if (!MessageDigest.isEqual(row[1] as ByteArray, sealer.mac(otp, userId))) {
                h.createUpdate("UPDATE app.device_otp SET attempts = attempts + 1 WHERE id = :id").bind("id", id).execute()
                return@inTransaction BindResult.Invalid
            }
            fun consume() = h.createUpdate("UPDATE app.device_otp SET consumed_at = :now WHERE id = :id").bind("now", now.odt()).bind("id", id).execute()
            h.createQuery("SELECT bind_ordinal FROM app.device_binding WHERE user_id = :u AND device_id = :d AND status = 'active'")
                .bind("u", userId).bind("d", deviceId).mapTo(Int::class.java).findOne().orElse(null)
                ?.let { consume(); return@inTransaction BindResult.Bound(it) }
            // A full phone list is refused before the code is spent: the TSO's code stays usable once a phone is unbound.
            val taken = h.createQuery("SELECT bind_ordinal FROM app.device_binding WHERE user_id = :u AND status = 'active'")
                .bind("u", userId).mapTo(Int::class.java).set()
            val free = (0..3).firstOrNull { it !in taken } ?: return@inTransaction BindResult.NoFreeOrdinal
            consume()
            h.createUpdate("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, bound_at, bound_via) VALUES (:d, :u, :o, :now, 'otp')")
                .bind("d", deviceId).bind("u", userId).bind("o", free).bind("now", now.odt()).execute()
            BindResult.Bound(free)
        }
}
