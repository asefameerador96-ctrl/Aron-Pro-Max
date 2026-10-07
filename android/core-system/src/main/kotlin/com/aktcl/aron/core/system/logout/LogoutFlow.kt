package com.aktcl.aron.core.system.logout

import java.io.File

/** Which app is logging out. The rule differs by role (F-SYS-022). */
enum class AppRole { SR, AMO, TSO }

/** What the logout needs from the app shell; every call is local except [requestSync], which only schedules. */
interface LogoutPorts {
    /**
     * Outbox rows the server does not hold as accepted: pending, in flight, quarantined and rejected. A rejected or
     * quarantined row is the phone's only copy of what the rep did, so it blocks a wipe until support has it (F-SYS-021).
     */
    suspend fun unsentRecords(): Int
    /** Photos still to upload (attached or uploaded without their media_meta). */
    suspend fun unsentPhotos(): Int
    /** Ends the session (`SessionRepository.logout()`): the upload grant survives it. */
    suspend fun endSession()
    /** Schedules an upload now (`SyncTrigger.MANUAL` and the media job). Never waits for it. */
    fun requestSync()
    /** Closes the user's database so its files can be deleted. */
    suspend fun closeUserDatabase()
    /** After a wipe: forget this user's tokens and offline verifier on the phone (android-core; default keeps them). */
    suspend fun forgetCredentials() {}
    /** The user's local files: database (with -wal, -shm, -journal), `files/media/u<id>/`, per-user preference files. */
    fun userFiles(): List<File>
}

sealed interface LogoutCheck {
    /** Logout may go ahead; [wipe] says whether the user's local data is removed afterwards. */
    data class Proceed(val wipe: Boolean) : LogoutCheck
    /** TSO with items not yet sent: refused, offer Sync now or Cancel. */
    data class Refused(val unsent: Int) : LogoutCheck
}

sealed interface LogoutResult {
    data class LoggedOut(val wiped: Boolean) : LogoutResult
    data class Refused(val unsent: Int) : LogoutResult
    /** The session ended but some local files could not be removed (named); the next logout tries again. */
    data class WipeIncomplete(val left: List<String>) : LogoutResult
    /** The session ended, but rows were saved while it was ending: the data is kept (not wiped) and keeps uploading. */
    data class LoggedOutDataKept(val unsent: Int) : LogoutResult
}

/**
 * The logout rule (F-SYS-022, docs/24 s5.3, D24-57).
 * - SR and AMO (shared phones, several users): logout ends the session only. The database stays and the sync engine keeps
 *   uploading this user's rows with the upload grant, so nothing captured is ever lost or blocked.
 * - TSO: refused while anything is unsent ("N items not yet sent", Sync now or Cancel). Only a fully reconciled device
 *   (no unsent row, no unsent photo) is wiped: the session ends first, then the database and the photos are deleted.
 * The count is read again inside [logout], right before the session ends, so a capture made after the dialog is never
 * wiped; a failed count is an exception (no logout), never a zero.
 */
class LogoutFlow(private val role: AppRole, private val ports: LogoutPorts) {

    suspend fun check(): LogoutCheck = when (role) {
        AppRole.SR, AppRole.AMO -> LogoutCheck.Proceed(wipe = false)
        AppRole.TSO -> {
            val unsent = ports.unsentRecords() + ports.unsentPhotos()
            if (unsent > 0) LogoutCheck.Refused(unsent) else LogoutCheck.Proceed(wipe = true)
        }
    }

    suspend fun logout(): LogoutResult {
        when (val c = check()) {
            is LogoutCheck.Refused -> return LogoutResult.Refused(c.unsent)
            is LogoutCheck.Proceed -> {
                if (!c.wipe) {
                    ports.endSession()
                    ports.requestSync() // whatever is still queued keeps going out after logout
                    return LogoutResult.LoggedOut(wiped = false)
                }
            }
        }
        ports.endSession()
        // Count again: a worker or a screen may have saved a row while the session was ending. Anything unsent keeps
        // the data (it uploads with the upload grant like an SR's); a failed count throws, never reads as zero.
        val unsentNow = ports.unsentRecords() + ports.unsentPhotos()
        if (unsentNow > 0) {
            ports.requestSync()
            return LogoutResult.LoggedOutDataKept(unsentNow)
        }
        ports.closeUserDatabase()
        val left = ports.userFiles().filter { it.exists() && !it.deleteRecursively() }.map { it.name }
        if (left.isEmpty()) ports.forgetCredentials()
        return if (left.isEmpty()) LogoutResult.LoggedOut(wiped = true) else LogoutResult.WipeIncomplete(left)
    }

    /** "Sync now" on the refusal: schedule an upload; the rep tries logout again when the count reaches zero. */
    fun syncNow() = ports.requestSync()

    companion object {
        /** The database file and its SQLite companions for [userId] (core-database names it `aron-u<id>.db`). */
        fun databaseFiles(databaseDir: File, userId: Long): List<File> =
            listOf("", "-wal", "-shm", "-journal").map { File(databaseDir, "aron-u$userId.db$it") }

        fun mediaDir(filesDir: File, userId: Long) = File(filesDir, "media/u$userId")
    }
}
