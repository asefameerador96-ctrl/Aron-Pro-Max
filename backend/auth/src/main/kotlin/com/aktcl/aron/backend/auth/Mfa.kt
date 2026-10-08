package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuditLog
import com.aktcl.aron.backend.platform.Database
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Contract `MfaEnrolment`. */
@Serializable
data class MfaEnrolment(val otpauth_uri: String, val recovery_codes: List<String>)

/** Contract `MfaVerifyRequest`. */
@Serializable
data class MfaVerifyRequest(val mfa_token: String, val code: String)

/**
 * Seals the TOTP secret of `app.mfa_secret.secret_cipher` (AES-256-GCM; 12-byte nonce, then ciphertext and tag; the AAD
 * binds the row to its user, so a cipher copied to another user's row does not open) and computes the keyed verifier
 * of a recovery code (HMAC-SHA256, hex; never a bare hash: a code has about 41 bits). Encryption and MAC keys are
 * derived separately from each key secret. [secrets] is a key ring, current first: sealing uses the current key,
 * opening and recovery checks accept every key, so a rotation (Key Vault `aron-mfa-key`, the old one kept as previous)
 * never locks a user out. The ring is wired in the app.
 */
class MfaCipher(secrets: List<ByteArray>) {
    constructor(secret: ByteArray) : this(listOf(secret))

    private class Keys(val enc: SecretKeySpec, val mac: SecretKeySpec)
    private val ring: List<Keys> = secrets.also { require(it.isNotEmpty()) { "an MFA key is required" } }.map { s ->
        fun derive(label: String) = MessageDigest.getInstance("SHA-256").digest(label.toByteArray() + s)
        Keys(SecretKeySpec(derive("aron-mfa-enc-v1|"), "AES"), SecretKeySpec(derive("aron-mfa-mac-v1|"), "HmacSHA256"))
    }
    private val rng = SecureRandom()

    fun seal(totpSecret: ByteArray, userId: Long): ByteArray {
        val nonce = ByteArray(12).also(rng::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, ring.first().enc, GCMParameterSpec(128, nonce)); updateAAD("mfa_secret:$userId".toByteArray()) }
        return nonce + c.doFinal(totpSecret)
    }

    fun open(blob: ByteArray, userId: Long): ByteArray? = ring.firstNotNullOfOrNull { k ->
        runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k.enc, GCMParameterSpec(128, blob.copyOfRange(0, 12))); updateAAD("mfa_secret:$userId".toByteArray()) }
            c.doFinal(blob, 12, blob.size - 12)
        }.getOrNull()
    }

    /** The verifier under the current key (what enrolment stores). */
    fun recoveryMac(code: String, userId: Long): String = macWith(ring.first(), code, userId)

    /** The stored verifier [code] matches under any key of the ring, or null (constant-time compares). */
    fun matchRecovery(code: String, userId: Long, stored: List<String>): String? {
        val candidates = ring.map { macWith(it, code, userId) }
        return stored.firstOrNull { s -> candidates.any { MessageDigest.isEqual(it.toByteArray(), s.toByteArray()) } }
    }

    private fun macWith(k: Keys, code: String, userId: Long): String =
        Mac.getInstance("HmacSHA256").apply { init(k.mac) }.doFinal("mfa-recovery:$userId:$code".toByteArray()).joinToString("") { "%02x".format(it) }

    fun newSecret(): ByteArray = ByteArray(20).also(rng::nextBytes)

    /** Ten codes `XXXX-XXXX` from A-Z and 0-9 (contract pattern), unique. */
    fun recoveryCodes(): List<String> {
        val out = LinkedHashSet<String>()
        while (out.size < 10) out += (0 until 8).map { ALPHABET[rng.nextInt(ALPHABET.length)] }.joinToString("").let { it.take(4) + "-" + it.drop(4) }
        return out.toList()
    }

    private companion object {
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }
}

/** RFC 6238 TOTP: HMAC-SHA1, 6 digits, 30-second steps (what every authenticator app reads from an otpauth URI). */
object Totp {
    const val STEP_S = 30L

    fun step(at: Instant): Long = Math.floorDiv(at.epochSecond, STEP_S)

    fun code(secret: ByteArray, step: Long): String {
        val h = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array())
        val o = h[h.size - 1].toInt() and 0x0f
        val bin = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        return (bin % 1_000_000).toString().padStart(6, '0')
    }

    /** The step in `now - 1 .. now + 1` whose code equals [code] (constant-time compare), or null. */
    fun matchingStep(secret: ByteArray, code: String, now: Instant): Long? {
        val s = step(now)
        return (s - 1..s + 1).firstOrNull { MessageDigest.isEqual(code(secret, it).toByteArray(), code.toByteArray()) }
    }

    fun base32(bytes: ByteArray): String {
        val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var buffer = 0; var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff); bits += 8
            while (bits >= 5) { sb.append(a[(buffer shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(a[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun uri(username: String, secret: ByteArray): String {
        val label = java.net.URLEncoder.encode("Aron:$username", Charsets.UTF_8).replace("+", "%20")
        return "otpauth://totp/$label?secret=${base32(secret)}&issuer=Aron&algorithm=SHA1&digits=6&period=30"
    }
}

/** One `app.mfa_secret` row as the verify step sees it, under the row lock. */
class MfaState(val secretCipher: ByteArray, val recoveryMacs: List<String>, val confirmed: Boolean, val lastUsedStep: Long?)

/** What a verify attempt changes on the row (nothing, a used TOTP step, or a spent recovery code). */
sealed class MfaUpdate {
    data object None : MfaUpdate()
    /** The sealed secret does not open with any key of the ring (a lost key): nothing changes, and it is not the user's fault. */
    data object Unreadable : MfaUpdate()
    data class TotpUsed(val step: Long) : MfaUpdate()
    data class RecoverySpent(val remaining: List<String>) : MfaUpdate()
}

interface MfaStore {
    /** Stores a new, unconfirmed enrolment (replacing an unconfirmed one); false when a confirmed one exists. */
    fun enrol(userId: Long, secretCipher: ByteArray, recoveryMacs: List<String>, now: Instant, actor: AronPrincipal? = null): Boolean

    /**
     * Runs [decide] on the user's row under a row lock and applies its update in the same transaction; a used TOTP
     * step confirms an unconfirmed enrolment and sets `app_user.mfa_enabled`.
     */
    fun verify(userId: Long, now: Instant, actor: AronPrincipal? = null, decide: (MfaState?) -> MfaUpdate): MfaUpdate
}

/**
 * F-WEB-043 on app.mfa_secret and app_user.mfa_enabled (V0002). Enrolment, confirmation and a spent recovery code each
 * write an audit row in the same transaction (the hash-chained log; under trust on first use an unexpected enrolment
 * is how a hijack is spotted). Security-event kinds `mfa_enrol` and `mfa_verify_failure` wait on
 * docs/requests/backend-core-mfa-security-events.md.
 */
class JdbiMfaStore(private val db: Database) : MfaStore {
    override fun enrol(userId: Long, secretCipher: ByteArray, recoveryMacs: List<String>, now: Instant, actor: AronPrincipal?): Boolean = db.jdbi.inTransaction<Boolean, Exception> { h ->
        h.createQuery("SELECT id FROM app.app_user WHERE id = :u FOR UPDATE").bind("u", userId).mapTo(Long::class.java).findOne()
        val existing = h.createQuery("SELECT confirmed_at IS NOT NULL FROM app.mfa_secret WHERE user_id = :u").bind("u", userId).mapTo(Boolean::class.java).findOne().orElse(null)
        if (existing == true) return@inTransaction false
        h.createUpdate(
            """
            INSERT INTO app.mfa_secret (user_id, secret_cipher, recovery_code_hashes, created_at, confirmed_at, last_used_step)
            VALUES (:u, :c, :r, :now, NULL, NULL)
            ON CONFLICT (user_id) DO UPDATE SET secret_cipher = EXCLUDED.secret_cipher, recovery_code_hashes = EXCLUDED.recovery_code_hashes,
                created_at = EXCLUDED.created_at, confirmed_at = NULL, last_used_step = NULL
            """.trimIndent(),
        ).bind("u", userId).bind("c", secretCipher).bindArray("r", String::class.java, recoveryMacs).bind("now", now.odt()).execute()
        audit(h, actor, userId, "mfa.enrol", buildJsonObject { put("replaced_unconfirmed", existing == false) })
        true
    }

    override fun verify(userId: Long, now: Instant, actor: AronPrincipal?, decide: (MfaState?) -> MfaUpdate): MfaUpdate = db.jdbi.inTransaction<MfaUpdate, Exception> { h ->
        val state = h.createQuery("SELECT secret_cipher, recovery_code_hashes, confirmed_at IS NOT NULL AS confirmed, last_used_step FROM app.mfa_secret WHERE user_id = :u FOR UPDATE")
            .bind("u", userId)
            .map { rs, _ ->
                @Suppress("UNCHECKED_CAST")
                val macs = (rs.getArray("recovery_code_hashes")?.array as? Array<String>)?.toList() ?: emptyList()
                MfaState(rs.getBytes("secret_cipher"), macs, rs.getBoolean("confirmed"), rs.getObject("last_used_step") as Long?)
            }.findOne().orElse(null)
        val update = decide(state)
        when (update) {
            MfaUpdate.None, MfaUpdate.Unreadable -> Unit
            is MfaUpdate.TotpUsed -> {
                h.createUpdate("UPDATE app.mfa_secret SET last_used_step = :s, confirmed_at = COALESCE(confirmed_at, :now) WHERE user_id = :u")
                    .bind("s", update.step).bind("now", now.odt()).bind("u", userId).execute()
                val confirmed = h.createUpdate("UPDATE app.app_user SET mfa_enabled = true WHERE id = :u AND NOT mfa_enabled").bind("u", userId).execute()
                if (confirmed > 0) audit(h, actor, userId, "mfa.confirm", null)
            }
            is MfaUpdate.RecoverySpent ->
                h.createUpdate("UPDATE app.mfa_secret SET recovery_code_hashes = :r WHERE user_id = :u")
                    .bindArray("r", String::class.java, update.remaining).bind("u", userId).execute()
                    .also { audit(h, actor, userId, "mfa.recovery_spent", buildJsonObject { put("codes_left", update.remaining.size) }) }
        }
        update
    }

    private fun audit(h: org.jdbi.v3.core.Handle, actor: AronPrincipal?, userId: Long, action: String, after: kotlinx.serialization.json.JsonElement?) {
        if (actor == null) return
        AuditLog.write(h, actor, "mfa_secret", userId.toString(), action, null, after, null, null, via = "web")
    }
}
