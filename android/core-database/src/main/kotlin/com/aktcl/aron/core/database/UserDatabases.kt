package com.aktcl.aron.core.database

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The open database of each user on this phone (docs/24 s5.2, s5.3): one file per user, opened once per process with its
 * own SQLCipher passphrase from [passphrase] (core-session `DatabaseKeys`, Keystore-wrapped). [passphrase] returns null
 * only in tests, which then get plain SQLite. Feature modules receive this from the app's Hilt graph and call [of] with
 * the active user's id; they never open Room themselves.
 */
class UserDatabases(private val context: Context, private val passphrase: (userId: Long) -> ByteArray?) {
    private val open = ConcurrentHashMap<Long, AronDatabase>()

    /** Opens (once) the database of [userId]; the Keystore unwrap and the first open run off the main thread. */
    suspend fun of(userId: Long): AronDatabase = open[userId] ?: withContext(Dispatchers.IO) {
        open.computeIfAbsent(userId) { id -> AronDatabase.open(context, id, passphrase(id)?.let(SqlCipher::factory)) }
    }

    /**
     * Closes and forgets the open database of [userId] so its files can be deleted (F-SYS-022 TSO wipe, android-sys). The
     * next [of] opens it again; callers make sure no sync run is using it.
     */
    fun close(userId: Long) {
        open.remove(userId)?.close()
    }

    /** Users with a database on this phone (`aron-u<id>.db`), signed in or not: their rows still upload (D24-57). */
    fun knownUserIds(): List<Long> =
        context.getDatabasePath("x").parentFile?.list().orEmpty()
            .mapNotNull { Regex("^aron-u(\\d+)\\.db$").find(it)?.groupValues?.get(1)?.toLongOrNull() }.sorted()
}
