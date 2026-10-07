package com.aktcl.aron.core.network

import java.security.MessageDigest

/**
 * Signs `X-Device-Proof` strings with the device's Keystore key (docs/24 s8.3): ES256, raw r||s, base64url without
 * padding, 86 characters. The key is created at enrolment (docs/24 s10.4, the device-policy lane); before that no
 * signer exists and calls go out without the header (the dev API accepts that while `cfg.device.require_enrolled`
 * is false).
 */
fun interface DeviceProofSigner {
    /** Returns the 86-character proof, or null when the device key is not available. */
    fun sign(proofString: String): String?
}

/** The proof strings of docs/24 s8.3; lines joined by `\n`, UTF-8. */
object ProofStrings {
    /** `floor(unix_seconds / 300)` of trusted time; the server accepts the current and the previous bucket. */
    fun nonceBucket(trustedEpochMs: Long): Long = Math.floorDiv(trustedEpochMs / 1000L, 300L)

    fun refresh(deviceUuid: String, refreshToken: String, trustedEpochMs: Long): String =
        listOf("aron-proof-v1", "refresh", deviceUuid, sha256Hex(refreshToken.toByteArray(Charsets.UTF_8)), nonceBucket(trustedEpochMs).toString())
            .joinToString("\n")

    fun bind(deviceUuid: String, otp: String, trustedEpochMs: Long): String =
        listOf("aron-proof-v1", "bind", deviceUuid, sha256Hex(otp.toByteArray(Charsets.UTF_8)), nonceBucket(trustedEpochMs).toString())
            .joinToString("\n")

    fun batch(deviceUuid: String, gzipBody: ByteArray, batchUuid: String, attempt: Int): String =
        listOf("aron-proof-v1", "batch", deviceUuid, sha256Hex(gzipBody), batchUuid, attempt.toString()).joinToString("\n")

    /** The `devices/me` calls: [methodPath] is `"<METHOD> <path>"`, e.g. `"PUT /v1/devices/me/push-token"`; [body] empty when none. */
    fun device(deviceUuid: String, methodPath: String, body: ByteArray, trustedEpochMs: Long): String =
        listOf("aron-proof-v1", "device", deviceUuid, methodPath, sha256Hex(body), nonceBucket(trustedEpochMs).toString()).joinToString("\n")

    /**
     * Record `sig` (F-SYS-072): over the record exactly as sent, without its `sig` member, in RFC 8785 form, so the
     * server's check (IngestService step 6) hashes the same bytes whatever the key order or number layout.
     */
    fun record(type: String, clientUuid: String, record: kotlinx.serialization.json.JsonObject): String {
        val unsigned = kotlinx.serialization.json.JsonObject(record.filterKeys { it != "sig" })
        return listOf("aron-sig-v1", type, clientUuid, sha256Hex(Jcs.canonicalize(unsigned).toByteArray(Charsets.UTF_8))).joinToString("\n")
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
