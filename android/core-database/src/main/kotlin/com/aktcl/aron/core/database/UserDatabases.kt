package com.aktcl.aron.core.database

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * The open database of each user on this phone (docs/24 s5.2, s5.3): one file per user, opened once per process with its
 * own SQLCipher passphrase from [passphrase] (core-session `DatabaseKeys`, Keystore-wrapped). [passphrase] returns null
 * only in tests, which then get plain SQLite. Feature modules receive this from the app's Hilt graph and call [of] with
 * the active user's id; they never open Room themselves.
 */
class UserDatabases(private val context: Context, private val passphrase: (userId: Long) -> ByteArray?) {
    private val open = ConcurrentHashMap<Long, AronDatabase>()

    fun of(userId: Long): AronDatabase = open.computeIfAbsent(userId) { id ->
        AronDatabase.open(context, id, passphrase(id)?.let(SqlCipher::factory))
    }
}
