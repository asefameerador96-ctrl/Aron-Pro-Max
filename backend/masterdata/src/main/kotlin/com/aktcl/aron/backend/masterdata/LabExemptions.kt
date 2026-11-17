package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Signs lab grants (D-OWNER-MDM-02 iii). Token: `base64url(payload).base64url(signature)`, payload `{"v":1,"d":"<device uuid>","iat":<ms>,"exp":<ms>}`,
 * ECDSA P-256 / SHA-256 (DER), verified offline by the phone with the matching PUBLIC key (an Android resource). The private key (PKCS#8, base64) is
 * read from the environment by the caller and never logged or stored; with no key the signer is not [configured] and signs nothing.
 */
class LabGrantSigner(privateKeyPkcs8Base64: String?) {
    private val key = privateKeyPkcs8Base64?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(it))) }.getOrNull()
    }
    val configured: Boolean get() = key != null

    fun sign(deviceUuid: String, iat: Instant, exp: Instant): String {
        val k = key ?: error("lab grant signing key is not configured")
        val payload = """{"v":1,"d":"$deviceUuid","iat":${iat.toEpochMilli()},"exp":${exp.toEpochMilli()}}""".toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(k); update(payload) }.sign()
        val e = Base64.getUrlEncoder().withoutPadding()
        return e.encodeToString(payload) + "." + e.encodeToString(sig)
    }
}

/** The server record of lab exemptions (`app.device_lab_exemption`) and the signed grant for an unexpired one. At most [MAX_DAYS] days; a new grant replaces the row. */
class LabExemptionService(private val db: Database, private val signer: LabGrantSigner, private val clock: AronClock = AronClock.SYSTEM) {
    /** Records the exemption and returns its expiry. Needs a configured signer: a grant nobody can sign would only mislead the portal. */
    fun grant(deviceId: Long, grantedBy: Long, days: Int, note: String?): Instant {
        require(signer.configured) { "lab grant signing key is not configured" }
        require(days in 1..MAX_DAYS) { "days must be 1..$MAX_DAYS" }
        require(note == null || note.length <= 300) { "note too long" }
        val now = clock.now(); val exp = now.plus(Duration.ofDays(days.toLong()))
        db.jdbi.useTransaction<Exception> { h ->
            h.createUpdate(
                "INSERT INTO app.device_lab_exemption (device_id, granted_by, granted_at, expires_at, note) VALUES (:d, :u, :g, :e, :n) " +
                    "ON CONFLICT (device_id) DO UPDATE SET granted_by = EXCLUDED.granted_by, granted_at = EXCLUDED.granted_at, expires_at = EXCLUDED.expires_at, note = EXCLUDED.note",
            ).bind("d", deviceId).bind("u", grantedBy).bind("g", java.sql.Timestamp.from(now)).bind("e", java.sql.Timestamp.from(exp)).bind("n", note).execute()
        }
        return exp
    }

    fun revoke(deviceId: Long): Boolean = db.jdbi.withHandle<Int, Exception> { h ->
        h.createUpdate("DELETE FROM app.device_lab_exemption WHERE device_id = :d").bind("d", deviceId).execute()
    } > 0

    /** The signed grant for [deviceId] while its row is unexpired and the signer is configured; null otherwise (nothing is sent). */
    fun tokenFor(deviceId: Long): String? {
        if (!signer.configured) return null
        val now = clock.now()
        val row = db.jdbi.withHandle<Pair<String, Instant>?, Exception> { h ->
            h.createQuery("SELECT d.device_uuid::text, x.expires_at FROM app.device_lab_exemption x JOIN app.device d ON d.id = x.device_id WHERE x.device_id = :d AND x.expires_at > :n AND d.status NOT IN ('revoked','replaced')")
                .bind("d", deviceId).bind("n", java.sql.Timestamp.from(now)).map { rs, _ -> rs.getString(1) to rs.getTimestamp(2).toInstant() }.findOne().orElse(null)
        } ?: return null
        return signer.sign(row.first, now, row.second)
    }

    companion object { const val MAX_DAYS = 14 }
}
