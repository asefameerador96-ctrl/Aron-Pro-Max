package com.aktcl.aron.core.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only parts of core-session (no Android Keystore or native Argon2 under Robolectric). Runs on a phone or the CI emulator. */
@RunWith(AndroidJUnit4::class)
class DeviceCryptoTest {
    @Test
    fun keystoreCipherRoundTripsAndRejectsTampering() {
        val cipher = KeystoreSecretCipher("aron.session.test")
        val plain = "access-token-and-verifier".toByteArray()
        val blob = cipher.encrypt(plain)
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("access-token"))
        assertArrayEquals(plain, cipher.decrypt(blob))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertTrue(runCatching { cipher.decrypt(blob) }.isFailure)
    }

    @Test
    fun argon2idVerifierMatchesOnlyTheRightPasswordWithinTheBudget() {
        val v = Argon2idPasswordVerifier()
        val started = System.nanoTime()
        val encoded = v.create("secret-1")
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(encoded, encoded.startsWith("\$argon2id\$v=19\$m=32768,t=2,p=1\$"))
        assertTrue(v.matches("secret-1", encoded))
        assertFalse(v.matches("secret-2", encoded))
        assertTrue("Argon2id took $ms ms on this phone", ms < 3_000)
    }
}
