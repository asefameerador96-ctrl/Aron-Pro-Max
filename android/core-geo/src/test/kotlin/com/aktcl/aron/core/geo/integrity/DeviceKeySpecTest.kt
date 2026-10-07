package com.aktcl.aron.core.geo.integrity

import android.security.keystore.KeyProperties
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Keystore itself runs only on a phone (androidTest DeviceKeyStoreDeviceTest); this checks the spec we ask for. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceKeySpecTest {
    @Test fun theDeviceKeyIsANonExportableP256SigningKeyWithTheEnrolmentChallenge() {
        val challenge = IntegrityCodec.enrolmentChallenge("tok".repeat(15))
        val spec = DeviceKeySpecs.spec("a", challenge, strongBox = true)
        assertEquals("a", spec.keystoreAlias)
        assertEquals(KeyProperties.PURPOSE_SIGN, spec.purposes)
        assertEquals("secp256r1", (spec.algorithmParameterSpec as ECGenParameterSpec).name)
        assertArrayEquals(arrayOf(KeyProperties.DIGEST_SHA256), spec.digests)
        assertArrayEquals(challenge, spec.attestationChallenge)
        assertTrue(spec.isStrongBoxBacked)
        assertFalse(DeviceKeySpecs.spec("a", challenge, strongBox = false).isStrongBoxBacked)
    }

    @Test fun theChainIsCappedAtSixCertificates() {
        assertEquals(emptyList<String>(), DeviceKeySpecs.encodeChain(null))
    }
}
