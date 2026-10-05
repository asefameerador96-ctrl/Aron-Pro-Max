package com.aktcl.aron.backend.platform

import com.nimbusds.jose.jwk.ECKey
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * `X-Device-Proof` verification (docs/24 s8.3): an ES256 signature, raw r‖s in base64url (86 characters), by the
 * device's Keystore key over UTF-8 lines joined by `\n`. Proofs with a nonce bucket (`floor(unix_s / 300)`) are
 * accepted for the current and the previous bucket.
 */
object DeviceProof {
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun refreshString(deviceUuid: String, refreshToken: String, bucket: Long): String =
        listOf("aron-proof-v1", "refresh", deviceUuid, sha256Hex(refreshToken.toByteArray()), bucket.toString()).joinToString("\n")

    fun bucket(epochS: Long): Long = Math.floorDiv(epochS, 300L)

    fun publicKey(jwkJson: String): ECPublicKey? = runCatching { ECKey.parse(jwkJson).toECPublicKey() }.getOrNull()

    /** True when [proof] is a valid raw-r‖s ES256 signature of [message] by [key]. */
    fun verify(key: ECPublicKey, message: String, proof: String): Boolean {
        val raw = runCatching { Base64.getUrlDecoder().decode(proof) }.getOrNull() ?: return false
        if (raw.size != 64) return false
        return runCatching {
            Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initVerify(key); update(message.toByteArray()); verify(raw)
            }
        }.getOrDefault(false)
    }

    /** Verifies a bucketed proof against the current and previous bucket of [nowEpochS]. */
    fun verifyBucketed(key: ECPublicKey, proof: String, nowEpochS: Long, build: (Long) -> String): Boolean {
        val b = bucket(nowEpochS)
        return verify(key, build(b), proof) || verify(key, build(b - 1), proof)
    }
}
