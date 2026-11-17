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

/**
 * Verifies a server-signed lab grant offline (D-OWNER-MDM-02 iii). Token: `base64url(payload).base64url(signature)`, payload JSON
 * `{"v":1,"d":"<device uuid>","iat":<ms>,"exp":<ms>}`, signature ECDSA P-256 over SHA-256 of the payload bytes (DER). The public key
 * (X.509 SubjectPublicKeyInfo, base64) is a resource of the app; it is not a secret. With no key the verifier is not [configured] and
 * accepts nothing, so a build without the key can never honour a lab exemption.
 */
class LabGrantVerifier(publicKeySpkiBase64: String?) {
    private val key = publicKeySpkiBase64?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(it))) }.getOrNull()
    }
    val configured: Boolean get() = key != null

    /** The grant's expiry (UTC ms) when the signature is valid, the grant names exactly [deviceUuid] and has not expired at [nowMs]; else null. */
    fun verify(token: String, deviceUuid: String?, nowMs: Long): Long? = runCatching {
        val k = key ?: return null
        if (deviceUuid.isNullOrBlank()) return null
        val parts = token.trim().split('.')
        if (parts.size != 2) return null
        val dec = Base64.getUrlDecoder()
        val payload = dec.decode(parts[0]); val sig = dec.decode(parts[1])
        val s = Signature.getInstance("SHA256withECDSA").apply { initVerify(k); update(payload) }
        if (!s.verify(sig)) return null
        val o: JsonObject = Json.parseToJsonElement(String(payload, Charsets.UTF_8)).jsonObject
        if (o["v"]?.jsonPrimitive?.longOrNull != 1L || o["d"]?.jsonPrimitive?.content != deviceUuid) return null
        val exp = o["exp"]?.jsonPrimitive?.longOrNull ?: return null
        if (exp <= nowMs) null else exp
    }.getOrNull()
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
    private val clock: GateClock,
) {
    private val tokenFile get() = File(dir, "lab-grant-token.txt")

    /** A token from the server (bundle or admin channel). True when it was valid and stored; false changes nothing. */
    @Synchronized
    fun accept(token: String): Boolean {
        val exp = verifier.verify(token, deviceUuid(), clock.wallMs()) ?: return false
        return runCatching {
            store.grant(exp)
            com.aktcl.aron.core.common.AtomicText.write(tokenFile, token.trim())
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun isActive(): Boolean = runCatching {
        val token = com.aktcl.aron.core.common.AtomicText.read(tokenFile) ?: return false
        verifier.verify(token, deviceUuid(), clock.wallMs()) != null && store.isActive()
    }.getOrDefault(false)

    /** The server withdrew the grant (an empty value in the bundle) or the rep logged out of a lab phone. */
    @Synchronized
    fun revoke() { store.clear(); com.aktcl.aron.core.common.AtomicText.delete(tokenFile) }
}
