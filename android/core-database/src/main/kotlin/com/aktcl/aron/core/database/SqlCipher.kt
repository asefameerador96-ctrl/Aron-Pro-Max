package com.aktcl.aron.core.database

import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * SQLCipher for the per-user database (docs/24 s5.2). The 32-byte key is random per user and stored wrapped by an
 * Android Keystore key (core-session `DatabaseKeys`); it is used as a raw key ([rawKey]), never as a passphrase. The native library is loaded once.
 */
object SqlCipher {
    @Volatile private var loaded = false

    fun factory(passphrase: ByteArray): SupportSQLiteOpenHelper.Factory {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) { System.loadLibrary("sqlcipher"); loaded = true }
            }
        }
        // enableWriteAheadLogging = false as with the one-argument constructor (Room still turns WAL on itself).
        return SupportOpenHelperFactory(rawKey(passphrase), IncrementalVacuumHook, false)
    }

    /**
     * F-SYS-028 follow-up: SQLCipher keys the file and switches it to WAL before any open-helper callback, and after the
     * WAL switch writes page 1 a NONE file can never change mode (checker). postKey runs right after the key and before
     * WAL, so a NEW file becomes auto_vacuum INCREMENTAL; on an existing NONE file the pragma is a silent no-op (no rewrite).
     */
    internal object IncrementalVacuumHook : net.zetetic.database.sqlcipher.SQLiteDatabaseHook {
        override fun preKey(connection: net.zetetic.database.sqlcipher.SQLiteConnection) = Unit
        override fun postKey(connection: net.zetetic.database.sqlcipher.SQLiteConnection) {
            // Only when not already INCREMENTAL: setting it on an existing file is a write on every connection open (checker).
            runCatching {
                if (connection.executeForLong("PRAGMA auto_vacuum", null, null) != 2L) connection.execute("PRAGMA auto_vacuum = INCREMENTAL", null, null)
            } // never fails the open
        }
    }

    /**
     * The key in SQLCipher's raw form `x'<64 hex>'` (AUD-PERF-05): the 32 bytes are already random, so SQLCipher must not run
     * its PBKDF2 (256,000 iterations, hundreds of ms on a low-end phone) on every open. Fixed before the first enrolment;
     * changing it later would need a re-key of every phone's database.
     */
    fun rawKey(key: ByteArray): ByteArray {
        require(key.size == 32) { "SQLCipher raw key must be exactly 32 bytes" }
        return ("x'" + key.joinToString("") { "%02x".format(it) } + "'").toByteArray(Charsets.US_ASCII)
    }
}
