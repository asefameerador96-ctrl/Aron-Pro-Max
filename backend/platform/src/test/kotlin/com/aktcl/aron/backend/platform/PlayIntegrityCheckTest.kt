package com.aktcl.aron.backend.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/** N-027: a decoded payload counts only when it binds this nonce, device and package and is fresh (contract R12). */
class PlayIntegrityCheckTest {
    private val nonce = "n".repeat(43)
    private val device = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val now = 1_800_000_000_000L
    private val cert = ByteArray(32) { (it + 7).toByte() }
    private val certB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(cert)

    private fun payload(verdicts: String, hash: String = PlayIntegrityCheck.requestHash(nonce, device), pkg: String = "com.aktcl.aron.sr", at: String = "\"${now - 60_000}\"", appPkg: String = pkg, certs: String = "\"$certB64\"") =
        """{"requestDetails":{"requestPackageName":"$pkg","requestHash":"$hash","timestampMillis":$at},""" +
            """"appIntegrity":{"appRecognitionVerdict":"UNRECOGNIZED_VERSION","packageName":"$appPkg","certificateSha256Digest":[$certs],"versionCode":"1"},"deviceIntegrity":{"deviceRecognitionVerdict":[$verdicts]}}"""

    private fun eval(p: String) = PlayIntegrityCheck.evaluate(p, "com.aktcl.aron.sr", cert, nonce, device, now).verdict

    @Test
    fun theRequestHashIsSha256OfTheNonceThenTheDeviceUuid() {
        assertEquals(64, PlayIntegrityCheck.requestHash(nonce, device).length)
        assertEquals(PlayIntegrityCheck.requestHash(nonce, device), PlayIntegrityCheck.requestHash(nonce, device.uppercase()), "the canonical lower-case uuid")
        assertEquals(
            java.security.MessageDigest.getInstance("SHA-256").digest((nonce + device).toByteArray()).joinToString("") { "%02x".format(it) },
            PlayIntegrityCheck.requestHash(nonce, device),
        )
    }

    @Test
    fun onlyTheDeviceVerdictDecidesAndTheAppVerdictIsNeverRequired() {
        assertEquals("pass", eval(payload("\"MEETS_BASIC_INTEGRITY\",\"MEETS_DEVICE_INTEGRITY\"")))
        assertEquals("pass", eval("""{"tokenPayloadExternal":${payload("\"MEETS_DEVICE_INTEGRITY\"")}}"""))
        assertEquals("fail", eval(payload("\"MEETS_BASIC_INTEGRITY\"")))
        assertEquals("fail", eval(payload("")))
        assertEquals("fail", eval(payload("").replace(""""deviceRecognitionVerdict":[]""", "")), "Google omits the list for a device that meets nothing")
    }

    @Test
    fun evidenceThatDoesNotBindThisRequestIsUnevaluatedNeverAFail() {
        assertEquals("unevaluated", eval(payload("", hash = PlayIntegrityCheck.requestHash("m".repeat(43), device))))
        assertEquals("unevaluated", eval(payload("", pkg = "com.aktcl.aron.amo")))
        assertEquals("unevaluated", eval(payload("", at = "\"${now - 16 * 60_000}\"")), "too old")
        assertEquals("unevaluated", eval(payload("", at = "\"${now + 6 * 60_000}\"")), "from the future")
        assertEquals("unevaluated", eval(payload("", at = "null")))
        assertEquals("unevaluated", eval("not json"))
        // A token of a re-signed copy (another certificate) or another app on a genuine phone never passes this one.
        assertEquals("unevaluated", eval(payload("\"MEETS_DEVICE_INTEGRITY\"", certs = "\"${"A".repeat(43)}\"")))
        assertEquals("unevaluated", eval(payload("\"MEETS_DEVICE_INTEGRITY\"", certs = "")))
        assertEquals("unevaluated", eval(payload("\"MEETS_DEVICE_INTEGRITY\"", appPkg = "com.example.clone")))
        assertEquals("unevaluated", eval(payload("\"MEETS_DEVICE_INTEGRITY\"").replace(Regex(""""deviceIntegrity":\{[^}]*\}"""), "\"x\":1")), "no device block")
        assertEquals("unevaluated", eval("""{"deviceIntegrity":{"deviceRecognitionVerdict":["MEETS_DEVICE_INTEGRITY"]}}"""))
        assertEquals("pass", eval(payload("\"MEETS_DEVICE_INTEGRITY\"", at = "${now - 60_000}")), "a numeric timestamp too")
    }
}
