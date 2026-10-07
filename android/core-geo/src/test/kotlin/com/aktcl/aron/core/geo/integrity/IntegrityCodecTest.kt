package com.aktcl.aron.core.geo.integrity

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityCodecTest {
    private val jwkPart = Regex("^[A-Za-z0-9_-]{43}$")

    @Test fun jwkCoordinatesAre43CharBase64urlAndRoundTrip() {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        repeat(200) { // enough keys that some coordinates have leading zero bytes
            val pub = gen.generateKeyPair().public as ECPublicKey
            val jwk = EcJwk.of(pub)
            assertTrue(jwk.x, jwkPart.matches(jwk.x)); assertTrue(jwk.y, jwkPart.matches(jwk.y))
            assertEquals(pub.w.affineX, BigInteger(1, Base64.getUrlDecoder().decode(jwk.x)))
            assertEquals(pub.w.affineY, BigInteger(1, Base64.getUrlDecoder().decode(jwk.y)))
        }
        assertEquals("EC", EcJwk("a", "b").kty); assertEquals("P-256", EcJwk("a", "b").crv)
    }

    @Test fun fixed32PadsAndRejectsOversize() {
        assertArrayEquals(ByteArray(31) + byteArrayOf(1), fixed32(BigInteger.ONE))
        assertEquals(32, fixed32(BigInteger.ONE.shiftLeft(255)).size)
        assertThrows(IllegalArgumentException::class.java) { fixed32(BigInteger.ONE.shiftLeft(256)) }
    }

    @Test fun derSignaturesBecome86CharRawSignaturesThatVerify() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        repeat(300) { i ->
            val data = "aron-proof-v1\nbatch\n$i".toByteArray()
            val der = Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(data); sign() }
            val raw = IntegrityCodec.derToRawB64url(der)
            assertEquals(86, raw.length)
            val p1363 = Base64.getUrlDecoder().decode(raw)
            assertTrue(Signature.getInstance("SHA256withECDSAinP1363Format").run { initVerify(kp.public); update(data); verify(p1363) })
        }
    }

    @Test fun malformedDerIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { IntegrityCodec.derToRawB64url(byteArrayOf(0x31, 0)) }
        assertThrows(IllegalArgumentException::class.java) { IntegrityCodec.derToRawB64url(byteArrayOf(0x30, 5, 2, 1, 1)) }
    }

    @Test fun challengeAndRequestHashAreSha256() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            IntegrityCodec.hex(IntegrityCodec.enrolmentChallenge("")),
        )
        // sha256("abc") with nonce "ab" and device "c"
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", IntegrityCodec.requestHash("ab", "c"))
    }

    @Test fun orphanedKeysAreDeletedExceptTheOneInUse() {
        val present = mutableListOf("aron-device-key-a", "aron-device-key-b", "aron-device-key-c")
        val ks = object : DeviceKeyStore {
            override fun create(alias: String, challenge: ByteArray) = error("unused")
            override fun exists(alias: String) = alias in present
            override fun sign(alias: String, data: ByteArray): String? = null
            override fun delete(alias: String) { present -= alias }
            override fun aliases() = present.toList()
        }
        ks.deleteAllExcept("aron-device-key-b")
        assertEquals(listOf("aron-device-key-b"), present)
        assertTrue(DeviceKeySpecs.newAlias().startsWith(DeviceKeySpecs.ALIAS_PREFIX))
    }
}
