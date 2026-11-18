package com.aktcl.aron.backend.analytics.devices

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

/** One lab exemption as the portal sees it (never the signed token). */
data class LabExemptionRow(val deviceId: Long, val grantedBy: Long, val grantedAt: Instant, val expiresAt: Instant, val note: String?)

/**
 * The server record of lab exemptions (`app.device_lab_exemption`) and the signed grant. At most [MAX_DAYS] days; a new grant replaces the row.
 * The grant is signed ONCE, when it is made, and stored with the row: the bundle that carries it is versioned and ETagged by content, so a grant
 * signed afresh on every request (ECDSA signatures differ each time) would change the bundle on every fetch. The bundle reads the stored token
 * (`BundleService.labGrant`); a grant is withdrawn by [revoke] (deleting the row), not by removing the key, because the token already issued
 * stays valid on the phone until its expiry or the next bundle without it.
 */
class LabExemptionService(private val db: Database, private val signer: LabGrantSigner, private val clock: AronClock = AronClock.SYSTEM) {
    /** True when the server can sign (the environment secret is set): the portal shows the action only then. */
    val canGrant: Boolean get() = signer.configured

    /** Records the exemption and returns its expiry. Needs a configured signer: a grant nobody can sign would only mislead the portal. */
    fun grant(deviceId: Long, grantedBy: Long, days: Int, note: String?): Instant = grantRow(deviceId, grantedBy, days, note).expiresAt

    /** [alsoInTx] runs in the same transaction (the audit row), so a grant is never recorded without it. */
    fun grantRow(deviceId: Long, grantedBy: Long, days: Int, note: String?, alsoInTx: (org.jdbi.v3.core.Handle, LabExemptionRow) -> Unit = { _, _ -> }): LabExemptionRow {
        require(signer.configured) { "lab grant signing key is not configured" }
        require(days in 1..MAX_DAYS) { "days must be 1..$MAX_DAYS" }
        require(note == null || note.length <= 300) { "note too long" }
        // Whole milliseconds, so the stored column and the signed payload name the same instants.
        val now = Instant.ofEpochMilli(clock.now().toEpochMilli()); val exp = now.plus(Duration.ofDays(days.toLong()))
        val row = LabExemptionRow(deviceId, grantedBy, now, exp, note)
        db.jdbi.useTransaction<Exception> { h ->
            val uuid = h.createQuery("SELECT device_uuid::text FROM app.device WHERE id = :d AND status NOT IN ('revoked','replaced')").bind("d", deviceId).mapTo(String::class.java).findOne().orElse(null)
                ?: throw IllegalArgumentException("no usable device")
            val token = signer.sign(uuid, now, exp)
            h.createUpdate(
                "INSERT INTO app.device_lab_exemption (device_id, granted_by, granted_at, expires_at, note, grant_token) VALUES (:d, :u, :g, :e, :n, :t) " +
                    "ON CONFLICT (device_id) DO UPDATE SET granted_by = EXCLUDED.granted_by, granted_at = EXCLUDED.granted_at, expires_at = EXCLUDED.expires_at, note = EXCLUDED.note, grant_token = EXCLUDED.grant_token",
            ).bind("d", deviceId).bind("u", grantedBy).bind("g", java.sql.Timestamp.from(now)).bind("e", java.sql.Timestamp.from(exp)).bind("n", note).bind("t", token).execute()
            alsoInTx(h, row)
        }
        return row
    }

    fun revoke(deviceId: Long, alsoInTx: (org.jdbi.v3.core.Handle) -> Unit = {}): Boolean = db.jdbi.inTransaction<Boolean, Exception> { h ->
        val gone = h.createUpdate("DELETE FROM app.device_lab_exemption WHERE device_id = :d").bind("d", deviceId).execute() > 0
        if (gone) alsoInTx(h)
        gone
    }

    /** The unexpired exemption of [deviceId], or null. */
    fun current(deviceId: Long): LabExemptionRow? = db.jdbi.withHandle<LabExemptionRow?, Exception> { h -> current(h, deviceId, clock.now()) }

    companion object {
        const val MAX_DAYS = 14

        /** Used by the portal's device list too (same handle, one read). */
        fun current(h: org.jdbi.v3.core.Handle, deviceId: Long, now: Instant): LabExemptionRow? =
            h.createQuery("SELECT device_id, granted_by, granted_at, expires_at, note FROM app.device_lab_exemption WHERE device_id = :d AND expires_at > :n")
                .bind("d", deviceId).bind("n", java.sql.Timestamp.from(now))
                .map { rs, _ -> LabExemptionRow(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(), rs.getString(5)) }.findOne().orElse(null)
    }
}
