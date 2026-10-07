package com.aktcl.aron.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/**
 * F-SYS-072 residual (c): one signed record shared with backend-core (docs/requests/android-core-backend-record-sig-vector.md).
 * The record is sent in a non-canonical layout (member order, `1.5e3`, `90.40`, `12.0`, `1E21`, an escaped quote and Bangla);
 * both sides must reach the same RFC 8785 bytes, the same message and accept the same sig. The private key was thrown away
 * after signing: only the public key is here. A change to either canonicaliser that breaks parity fails this test on one side.
 */
class RecordSignatureVectorTest {

    @Test fun thePhoneReachesTheSharedCanonicalFormMessageAndSignature() {
        val record = Json.parseToJsonElement(WIRE).jsonObject
        val unsigned = JsonObject(record.filterKeys { it != "sig" })
        assertEquals(CANONICAL, Jcs.canonicalize(unsigned))
        assertEquals(SHA256_HEX, ProofStrings.sha256Hex(CANONICAL.toByteArray(Charsets.UTF_8)))
        val message = ProofStrings.record("visit", UUID, record)
        assertEquals(listOf("aron-sig-v1", "visit", UUID, SHA256_HEX).joinToString("\n"), message)
        assertTrue(verify(message, record["sig"]!!.jsonPrimitive.content))
    }

    @Test fun anyChangeToTheSignedContentFailsTheSignature() {
        val record = Json.parseToJsonElement(WIRE.replace("1250000", "1250001")).jsonObject
        assertFalse(verify(ProofStrings.record("visit", UUID, record), record["sig"]!!.jsonPrimitive.content))
    }

    private fun verify(message: String, sig: String): Boolean {
        val raw = Base64.getUrlDecoder().decode(sig)
        return Signature.getInstance("SHA256withECDSAinP1363Format").run { initVerify(publicKey()); update(message.toByteArray(Charsets.UTF_8)); verify(raw) }
    }

    private fun publicKey(): ECPublicKey {
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        fun coord(b64: String) = BigInteger(1, Base64.getUrlDecoder().decode(b64))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(coord(JWK_X), coord(JWK_Y)), params)) as ECPublicKey
    }

    companion object {
        const val UUID = "7d1f3c2a-5b4e-4f6a-9c8d-1e2f3a4b5c6d"
        /** Public JWK of the test key: {"kty":"EC","crv":"P-256","x":JWK_X,"y":JWK_Y}. */
        const val JWK_X = "cq08ZII0pgKMNoz1iDBej6jWE1hunj_VMbhAzZU9Ofs"
        const val JWK_Y = "iJ90f0fLPNmSZObwdOgjrUOn9XS8Kp47czd8PKzp9tk"
        const val WIRE = """{"type":"visit","client_uuid":"$UUID","ratios":[1.5e3,0.0000010,1E21,-0.50],""" +
            """"geo":{"mock":false,"lng":90.40,"lat":23.7925120},"note":"দোকান \"১\"","amount_mtk":1250000,"accuracy_m":12.0,""" +
            """"captured_at":"2026-10-05T04:36:00.000Z","business_date":"2026-10-05",""" +
            """"sig":"WQaua1-grkZ_K22PSezYv4YfX0YRw36mFJOvv3Bc_JojzsBg-oVphU7IbTPPzFPLDFqEnZlkHdtyHTFSyforGA"}"""
        const val CANONICAL = """{"accuracy_m":12,"amount_mtk":1250000,"business_date":"2026-10-05","captured_at":"2026-10-05T04:36:00.000Z",""" +
            """"client_uuid":"$UUID","geo":{"lat":23.792512,"lng":90.4,"mock":false},"note":"দোকান \"১\"",""" +
            """"ratios":[1500,0.000001,1e+21,-0.5],"type":"visit"}"""
        const val SHA256_HEX = "f519c2fcc16ba77af3e9eafda02f879086ef389a53aa5a730bddd197d60a5308"
    }
}
