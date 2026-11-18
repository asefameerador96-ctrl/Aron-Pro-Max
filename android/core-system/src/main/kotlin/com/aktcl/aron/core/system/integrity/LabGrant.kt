package com.aktcl.aron.core.system.integrity

import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** What a verified grant says: when it was granted and when it ends (UTC ms). */
class LabGrantClaims(val iatMs: Long, val expMs: Long)

/**
 * Verifies a server-signed lab grant offline (D-OWNER-MDM-02 iii). Token: `base64url(payload).base64url(signature)`, payload JSON
 * `{"v":1,"d":"<device uuid>","a":"<api host>","iat":<ms>,"exp":<ms>}`, signature ECDSA P-256 over SHA-256 of the payload bytes in DER
 * (ONLY DER: a raw r||s signature or one with trailing bytes is refused). The public key (X.509 SubjectPublicKeyInfo, base64) is a resource
 * of the app; it is not a secret. With no key, or a key that is not an EC key on P-256, the verifier is not [configured] and accepts nothing,
 * so a build without the key (or with a wrong one pasted by mistake) can never honour a lab exemption. The audience `a` must be the host of
 * the API THIS build talks to, so a grant signed for the test account is never honoured by a final-account build.
 */
class LabGrantVerifier(publicKeySpkiBase64: String?) {
    private val key = publicKeySpkiBase64?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(it))) }.getOrNull()
    }?.takeIf { isP256(it) }
    val configured: Boolean get() = key != null

    /**
     * The claims when the signature is valid, the grant names exactly [deviceUuid] and [audience], and it has not expired at [nowMs]; else null.
     * [nowMs] is the time the expiry is judged against: the SERVER time of a fresh response when a grant is accepted, the phone's own clock when a stored one is re-read.
     */
    fun verify(token: String, deviceUuid: String?, audience: String?, nowMs: Long): LabGrantClaims? = runCatching {
        val k = key ?: return null
        if (deviceUuid.isNullOrBlank() || audience.isNullOrBlank()) return null
        val parts = token.trim().split('.')
        if (parts.size != 2) return null
        val dec = Base64.getUrlDecoder()
        val payload = dec.decode(parts[0]); val sig = dec.decode(parts[1])
        val s = Signature.getInstance("SHA256withECDSA").apply { initVerify(k); update(payload) }
        if (!s.verify(sig)) return null
        val o: JsonObject = Json.parseToJsonElement(String(payload, Charsets.UTF_8)).jsonObject
        if (o["v"]?.jsonPrimitive?.longOrNull != 1L || o["d"]?.jsonPrimitive?.content != deviceUuid || !o["a"]?.jsonPrimitive?.content.equals(audience, ignoreCase = true)) return null
        val iat = o["iat"]?.jsonPrimitive?.longOrNull ?: return null
        val exp = o["exp"]?.jsonPrimitive?.longOrNull ?: return null
        if (exp <= nowMs || exp <= iat) null else LabGrantClaims(iat, exp)
    }.getOrNull()

    private companion object {
        /** True for an EC key on the NIST P-256 curve (secp256r1). */
        fun isP256(k: java.security.PublicKey): Boolean {
            val params = (k as? java.security.interfaces.ECPublicKey)?.params ?: return false
            val p256 = runCatching {
                java.security.AlgorithmParameters.getInstance("EC").apply { init(java.security.spec.ECGenParameterSpec("secp256r1")) }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
            }.getOrNull() ?: return false
            return params.curve == p256.curve && params.generator == p256.generator && params.order == p256.order && params.cofactor == p256.cofactor
        }
    }
}

/**
 * The lab exemption of a release build: honoured only while a stored server-signed grant verifies for THIS device and is inside the
 * [LabExemptionStore] time guards (cap, clock back, reboot). The signed token itself is kept and re-verified on every read, so editing
 * the files on the phone cannot create or extend an exemption (nobody on the phone holds the signing key).
 */
class VerifiedLabExemption(
    private val dir: File,
    private val store: LabExemptionStore,
    private val verifier: LabGrantVerifier,
    private val deviceUuid: () -> String?,
    /** The host of the API this build talks to (the grant's audience); null until known: a grant then verifies for nobody. */
    private val audience: () -> String?,
    private val clock: GateClock,
) {
    private val tokenFile get() = File(dir, "lab-grant-token.txt")
    private val markFile get() = File(dir, "lab-grant-marks.txt")

    /**
     * Two marks that outlive every reset of the store: the highest `iat` ever accepted and the highest wall-clock time this phone has seen (both only ever rise).
     * They are what stops a rolled-back clock or an old token from bringing a grant back after the store cleared itself.
     */
    private class Marks(val iat: Long, val wall: Long)

    /**
     * A grant that arrived in a FRESH, authenticated server response whose own time is [serverTimeMs] (the `meta.server_time` of a full day bundle); never a stored
     * or cached copy. Refused when the response is not fresh (its server time is more than [FRESH_MS] from this phone's clock: a replayed response, or a clock too wrong
     * to trust), when this phone's clock is behind the highest time it has already seen (a rollback), when the token is older than a grant already accepted (`iat` below
     * the persisted mark), or when it does not verify. The expiry is judged against the SERVER time, so setting the phone's clock back cannot revive an expired grant.
     * True when stored.
     */
    @Synchronized
    fun accept(token: String, serverTimeMs: Long?): Boolean {
        val now = clock.wallMs()
        val marks = readMarks()
        if (serverTimeMs == null || kotlin.math.abs(now - serverTimeMs) > FRESH_MS) return false
        val claims = verifier.verify(token, deviceUuid(), audience(), serverTimeMs) ?: return false
        if (marks != null && claims.iatMs < marks.iat) return false
        // The clock is behind the highest reading seen (a rollback, or a tester who moved it FORWARD and back). Only a grant NEWER than any this phone has accepted may recover
        // from that: a fresh grant cannot be a replay (its iat is above every mark), and the response is already known fresh (server time within 15 minutes of this clock).
        val behind = marks != null && now < marks.wall - ROLLBACK_TOLERANCE_MS
        if (behind && claims.iatMs <= marks!!.iat) return false
        return runCatching {
            store.grant(claims.expMs)
            com.aktcl.aron.core.common.AtomicText.write(tokenFile, token.trim())
            writeMarks(Marks(maxOf(claims.iatMs, marks?.iat ?: 0L), if (behind) now else maxOf(now, marks?.wall ?: 0L)))
            true
        }.getOrDefault(false)
    }

    /**
     * What a FULL day bundle says (D-OWNER-MDM-02 iii): [token] is the grant it carried (null when it carried none) and [serverTimeMs] its `meta.server_time`. A token that
     * verifies and is fresh is stored; one that does not changes NOTHING (it never widens, and a transient mismatch must not trap a lab phone, whose standing grant is
     * re-verified on every read anyway). An ABSENT value is the server withdrawing the grant: revoked, whatever the response's age (a withdrawal can only narrow).
     */
    @Synchronized
    fun applyServer(token: String?, serverTimeMs: Long?) {
        if (token.isNullOrBlank()) { revoke(); touchWall() } else accept(token, serverTimeMs)
    }

    @Synchronized
    fun isActive(): Boolean = runCatching {
        val token = com.aktcl.aron.core.common.AtomicText.read(tokenFile) ?: return false
        touchWall()
        verifier.verify(token, deviceUuid(), audience(), clock.wallMs()) != null && store.isActive()
    }.getOrDefault(false)

    /** The server withdrew the grant (an empty value in the bundle) or the rep logged out of a lab phone. The marks stay: an older grant can never be taken again. */
    @Synchronized
    fun revoke() { store.clear(); com.aktcl.aron.core.common.AtomicText.delete(tokenFile) }

    /** Raises the highest-time mark (at most one write a minute). */
    private fun touchWall() {
        val now = clock.wallMs(); val m = readMarks() ?: Marks(0L, 0L)
        if (now > m.wall + 60_000L) runCatching { writeMarks(Marks(m.iat, now)) }
    }

    private fun readMarks(): Marks? = runCatching {
        val m = com.aktcl.aron.core.common.AtomicText.read(markFile)?.split('\n')?.mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].trim().toLong() } }?.toMap() ?: return null
        Marks(m.getValue("iat"), m.getValue("wall"))
    }.getOrNull()

    private fun writeMarks(m: Marks) = com.aktcl.aron.core.common.AtomicText.write(markFile, listOf("iat" to m.iat, "wall" to m.wall).joinToString("\n") { (k, v) -> k + "=" + v })

    companion object {
        /** How far a response's server time may be from this phone's clock for the response to count as fresh. */
        const val FRESH_MS = 15 * 60_000L
        private const val ROLLBACK_TOLERANCE_MS = 5 * 60_000L
    }
}
