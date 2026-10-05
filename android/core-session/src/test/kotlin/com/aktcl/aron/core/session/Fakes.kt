package com.aktcl.aron.core.session

import com.aktcl.aron.core.common.WallClock
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/** AES-GCM with a JVM key: the same blob format as the Keystore cipher, without the Android Keystore. */
class JvmAesCipher : SecretCipher {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key)
        return byteArrayOf(1) + c.iv + c.doFinal(plain)
    }
    override fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 1, 12))
        return c.doFinal(blob, 13, blob.size - 13)
    }
}

/** PBKDF2 stands in for the native Argon2id on the JVM; the rules around it are what these tests check. */
class JvmPasswordVerifier : PasswordVerifier {
    var created = 0
    override fun create(password: String): String {
        created++
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        return salt.hex() + ":" + derive(password, salt).hex()
    }
    override fun matches(password: String, encoded: String): Boolean {
        val (s, h) = encoded.split(":")
        return derive(password, s.unhex()).hex() == h
    }
    private fun derive(pw: String, salt: ByteArray) =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw.toCharArray(), salt, 1000, 256)).encoded
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

class FakeClock(var now: Long = 1_759_630_364_120L) : WallClock {
    override fun nowMs() = now
    override fun elapsedRealtimeMs() = now
}
