package com.aktcl.aron.core.geo.integrity

import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.util.Base64

/** Contract `JwkEcPublicDevice`: an EC P-256 public key, x and y as 32-byte big-endian base64url without padding (43 chars). */
data class EcJwk(val x: String, val y: String) {
    val kty: String get() = "EC"
    val crv: String get() = "P-256"

    companion object {
        fun of(key: ECPublicKey): EcJwk {
            require(key.params.curve.field.fieldSize == 256) { "device key must be P-256" }
            return EcJwk(b64url(fixed32(key.w.affineX)), b64url(fixed32(key.w.affineY)))
        }
    }
}

/** Pure encodings of docs/24 s8.3 and s10.4, shared by enrolment, proofs and integrity requests. */
object IntegrityCodec {
    /** Attestation challenge at enrolment: SHA-256 of the enrolment token's UTF-8 bytes (docs/24 s8.3). */
    fun enrolmentChallenge(enrolmentToken: String): ByteArray = sha256(enrolmentToken.toByteArray(Charsets.UTF_8))

    /**
     * Play Integrity `requestHash` for a server nonce (contract `DeviceNonce`: sha256(nonce + device_uuid)), as lower-case
     * hex (64 chars, inside the API's 500-char limit). REQUEST: docs/requests/android-geo-dpc-integrity-marker.md asks
     * backend to confirm the hex encoding.
     */
    fun requestHash(nonce: String, deviceUuid: String): String = hex(sha256((nonce + deviceUuid).toByteArray(Charsets.UTF_8)))

    /**
     * ES256 DER signature (what `java.security.Signature` returns) to the raw r‖s form, base64url without padding:
     * 64 bytes, 86 characters (docs/24 s8.3).
     */
    fun derToRawB64url(der: ByteArray): String {
        var i = 0
        fun byte(): Int = der[i++].toInt() and 0xff
        require(byte() == 0x30) { "not a DER sequence" }
        var len = byte()
        if (len == 0x81) len = byte()
        require(len == der.size - i) { "bad DER length" }
        fun integer(): ByteArray {
            require(byte() == 0x02) { "not a DER integer" }
            val n = byte()
            require(n in 1..33 && i + n <= der.size) { "bad integer length" }
            val v = der.copyOfRange(i, i + n)
            i += n
            return fixed32(BigInteger(1, v))
        }
        val r = integer()
        val s = integer()
        require(i == der.size) { "trailing bytes" }
        return b64url(r + s)
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

internal fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/** Unsigned big-endian, left-padded to 32 bytes; rejects values that do not fit. */
internal fun fixed32(v: BigInteger): ByteArray {
    require(v.signum() >= 0) { "negative coordinate" }
    val b = v.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    require(b.size <= 32) { "value does not fit 32 bytes" }
    return ByteArray(32 - b.size) + b
}
