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

    /** Users whose database is closed for removal: [of] refuses them until [allowOpen]. */
    private val closing: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * Opens (once) the database of [userId]; the Keystore unwrap and the first open run off the main thread. Throws
     * [IllegalStateException] while the user's files are being removed ([close]), so a worker never reopens, and caches,
     * a database whose files are about to be deleted (its writes would be lost).
     */
    suspend fun of(userId: Long): AronDatabase {
        check(userId !in closing) { "database of user $userId is being removed" }
        return open[userId] ?: withContext(Dispatchers.IO) {
            open.computeIfAbsent(userId) { id ->
                check(id !in closing) { "database of user $id is being removed" }
                AronDatabase.open(context, id, passphrase(id)?.let(SqlCipher::factory))
            }
        }
    }

    /**
     * Closes and forgets the open database of [userId] so its files can be deleted (F-SYS-022 TSO wipe, android-sys).
     * [of] refuses the user from now on until [allowOpen], which the caller runs once the files are gone (or the removal
     * failed). A run that already held the instance fails on its next query and is retried by WorkManager.
     */
    fun close(userId: Long) {
        closing += userId
        open.remove(userId)?.close()
    }

    /** Ends the [close] window of [userId]: the next [of] opens (or creates) the database again. */
    fun allowOpen(userId: Long) {
        closing -= userId
    }

    /** Users with a database on this phone (`aron-u<id>.db`), signed in or not: their rows still upload (D24-57). */
    fun knownUserIds(): List<Long> =
        context.getDatabasePath("x").parentFile?.list().orEmpty()
            .mapNotNull { Regex("^aron-u(\\d+)\\.db$").find(it)?.groupValues?.get(1)?.toLongOrNull() }.sorted()
}
