package com.aktcl.aron.core.geo.integrity

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.math.BigInteger
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** DEVICE-PENDING: runs on the Galaxy A06 / A07 / Honor X5c Plus or the CI emulator (docs/status/device-checks.md). */
@RunWith(AndroidJUnit4::class)
class DeviceKeyStoreDeviceTest {
    private val store = AndroidDeviceKeyStore(ApplicationProvider.getApplicationContext())
    private val alias = "aron-test-key"

    @After fun tearDown() = store.delete(alias)

    @Test fun createsAnAttestedKeyWhoseSignaturesVerifyWithTheSentJwk() {
        val key = store.create(alias, IntegrityCodec.enrolmentChallenge("t".repeat(43)))
        assertTrue(key.chain.size in 1..6)
        assertTrue("no attestation extension", key.attested)
        val data = "aron-proof-v1\ndevice".toByteArray()
        val raw = Base64.getUrlDecoder().decode(store.sign(alias, data)!!)
        assertEquals(64, raw.size)
        val params = java.security.AlgorithmParameters.getInstance("EC").run {
            init(java.security.spec.ECGenParameterSpec("secp256r1")); getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        }
        val pub = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(BigInteger(1, Base64.getUrlDecoder().decode(key.publicKey.x)), BigInteger(1, Base64.getUrlDecoder().decode(key.publicKey.y))), params),
        )
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(data); verify(toDer(raw)) })
    }

    private fun toDer(raw: ByteArray): ByteArray {
        fun int(b: ByteArray): ByteArray { val v = BigInteger(1, b).toByteArray(); return byteArrayOf(2, v.size.toByte()) + v }
        val body = int(raw.copyOfRange(0, 32)) + int(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
