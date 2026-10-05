package com.aktcl.aron.core.database

import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * SQLCipher for the per-user database (docs/24 s5.2). The 32-byte passphrase is random per user and stored wrapped by an
 * Android Keystore key (core-session `DatabaseKeys`); the app wiring passes it here. The native library is loaded once.
 */
object SqlCipher {
    @Volatile private var loaded = false

    fun factory(passphrase: ByteArray): SupportSQLiteOpenHelper.Factory {
        require(passphrase.size >= 32) { "SQLCipher passphrase must be at least 32 bytes" }
        if (!loaded) {
            synchronized(this) {
                if (!loaded) { System.loadLibrary("sqlcipher"); loaded = true }
            }
        }
        return SupportOpenHelperFactory(passphrase)
    }
}
