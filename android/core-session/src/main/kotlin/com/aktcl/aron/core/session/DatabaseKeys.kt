package com.aktcl.aron.core.session

import java.io.File
import java.security.SecureRandom

/**
 * The SQLCipher passphrase of each user's database (docs/24 s5.2): 32 random bytes per user, stored only wrapped by the
 * Keystore [cipher] in `<dir>/dbkey-u<id>.bin`. A key that can no longer be unwrapped (Keystore lost after a restore)
 * is an error, never silently replaced: a new key would make the existing database, and its unsent rows, unreadable.
 */
class DatabaseKeys(private val dir: File, private val cipher: SecretCipher, private val random: SecureRandom = SecureRandom()) {
    private val lock = Any()

    fun passphrase(userId: Long): ByteArray = synchronized(lock) {
        require(userId > 0) { "user id must be positive" }
        val file = File(dir, "dbkey-u$userId.bin")
        if (file.exists()) return cipher.decrypt(file.readBytes())
        val key = ByteArray(32).also(random::nextBytes)
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeBytes(cipher.encrypt(key))
        check(tmp.renameTo(file)) { "could not store the database key" }
        key
    }
}
