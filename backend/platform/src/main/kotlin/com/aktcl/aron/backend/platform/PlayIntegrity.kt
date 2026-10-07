package com.aktcl.aron.backend.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/**
 * Decodes a Play Integrity token server-side (docs/24 s10.4 item 3: Google's `decodeIntegrityToken`). Returns the
 * `tokenPayloadExternal` object as JSON text; throws on any failure (network, refused token, no credentials).
 */
fun interface PlayIntegrityDecoder {
    fun decode(packageName: String, token: String): String
}

/** What the server concluded from one decoded token (N-027). */
data class IntegrityEvaluation(
    /** `pass` or `fail` from a genuine verdict; `unevaluated` when the evidence cannot be trusted or read (never a fail). */
    val verdict: String,
    val reason: String,
    val deviceVerdicts: List<String> = emptyList(),
    val appVerdict: String? = null,
    /** The token's `timestampMillis`, for ordering verdicts (null when unevaluated before it was read). */
    val atMs: Long? = null,
)

/**
 * Checks a decoded standard-request payload against the request the server issued (N-027, contract R12):
 * `requestPackageName` is the flavour's package, `requestHash` is the lower-case hex SHA-256 of the UTF-8 `nonce`
 * immediately followed by the canonical `device_uuid`, and the token is fresh. Only then does the device verdict count:
 * `pass` when `deviceRecognitionVerdict` contains `MEETS_DEVICE_INTEGRITY`, else `fail`. `appRecognitionVerdict` is
 * recorded, never required (the APK is installed outside Play, D24-18); because of that the token must still name our
 * app: `appIntegrity.packageName` is the package and `certificateSha256Digest` contains the enrolled signing certificate,
 * else a re-signed copy of the app on a genuine phone could relay a pass to a rooted one. Evidence that does not bind to
 * this request (another nonce, package or certificate, too old, no device verdict) is `unevaluated`: a replayed or stale
 * token neither passes nor fails a phone.
 */
object PlayIntegrityCheck {
    const val MEETS_DEVICE_INTEGRITY = "MEETS_DEVICE_INTEGRITY"

    fun requestHash(nonce: String, deviceUuid: String): String =
        MessageDigest.getInstance("SHA-256").digest((nonce + deviceUuid.lowercase()).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun evaluate(payloadJson: String, expectedPackage: String, expectedCertSha256: ByteArray, nonce: String, deviceUuid: String, nowMs: Long, maxAgeMs: Long = 15 * 60_000L): IntegrityEvaluation {
        val root = runCatching { Json.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull()
            ?: return IntegrityEvaluation("unevaluated", "payload is not a JSON object")
        val p = (root["tokenPayloadExternal"] as? JsonObject) ?: root
        val req = p["requestDetails"] as? JsonObject ?: return IntegrityEvaluation("unevaluated", "no requestDetails")
        if (str(req["requestPackageName"]) != expectedPackage) return IntegrityEvaluation("unevaluated", "requestPackageName is not $expectedPackage")
        val hash = str(req["requestHash"]) ?: return IntegrityEvaluation("unevaluated", "no requestHash (not a standard request)")
        if (!MessageDigest.isEqual(hash.toByteArray(), requestHash(nonce, deviceUuid).toByteArray())) return IntegrityEvaluation("unevaluated", "requestHash does not bind this nonce and device")
        val at = (req["timestampMillis"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
            ?: return IntegrityEvaluation("unevaluated", "no timestampMillis")
        if (at < nowMs - maxAgeMs || at > nowMs + 5 * 60_000L) return IntegrityEvaluation("unevaluated", "token is not fresh")
        val appObj = p["appIntegrity"] as? JsonObject ?: return IntegrityEvaluation("unevaluated", "no appIntegrity")
        if (str(appObj["packageName"]) != expectedPackage) return IntegrityEvaluation("unevaluated", "appIntegrity.packageName is not $expectedPackage")
        val certs = (appObj["certificateSha256Digest"] as? JsonArray)?.mapNotNull { str(it) }.orEmpty()
        val want = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(expectedCertSha256)
        if (certs.none { it.trimEnd('=').replace('+', '-').replace('/', '_') == want }) return IntegrityEvaluation("unevaluated", "the token is not from the enrolled signing certificate")
        // Google leaves the verdict list out for a device that meets nothing: an absent list in a present block is a fail.
        val devBlock = p["deviceIntegrity"] as? JsonObject ?: return IntegrityEvaluation("unevaluated", "no deviceIntegrity")
        val device = (devBlock["deviceRecognitionVerdict"] as? JsonArray)?.mapNotNull { str(it) }.orEmpty()
        val app = str(appObj["appRecognitionVerdict"])
        return if (MEETS_DEVICE_INTEGRITY in device) IntegrityEvaluation("pass", "meets device integrity", device, app, at)
        else IntegrityEvaluation("fail", "deviceRecognitionVerdict lacks $MEETS_DEVICE_INTEGRITY", device, app, at)
    }

    private fun str(e: kotlinx.serialization.json.JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
}
