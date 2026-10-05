package com.aktcl.aron.core.session

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.security.SecureRandom

/** Offline-unlock verifier (docs/24 s8.1): never the password itself, only a slow salted hash of it. */
interface PasswordVerifier {
    /** Returns an encoded verifier (algorithm, parameters, salt and hash). */
    fun create(password: String): String

    fun matches(password: String, encoded: String): Boolean
}

/** Argon2id, m = 32 MiB, t = 2, p = 1, 16-byte salt, 32-byte hash (docs/24 s8.1), through the native argon2kt. */
class Argon2idPasswordVerifier(private val random: SecureRandom = SecureRandom()) : PasswordVerifier {
    private val argon2 by lazy { Argon2Kt() }

    override fun create(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val pw = password.toByteArray(Charsets.UTF_8)
        try {
            return argon2.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = pw,
                salt = salt,
                tCostInIterations = 2,
                mCostInKibibyte = 32 * 1024,
                parallelism = 1,
                hashLengthInBytes = 32,
            ).encodedOutputAsString()
        } finally {
            pw.fill(0)
        }
    }

    override fun matches(password: String, encoded: String): Boolean {
        val pw = password.toByteArray(Charsets.UTF_8)
        return try {
            argon2.verify(Argon2Mode.ARGON2_ID, encoded, pw)
        } catch (_: IllegalArgumentException) {
            false
        } finally {
            pw.fill(0)
        }
    }
}
