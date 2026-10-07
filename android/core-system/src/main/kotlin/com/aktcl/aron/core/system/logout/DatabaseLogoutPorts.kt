package com.aktcl.aron.core.system.logout

import android.content.Context
import com.aktcl.aron.core.database.AronDatabase
import java.io.File

/**
 * [LogoutPorts] for an app shell: unsent rows from the user's outbox (every state the server does not hold as accepted),
 * the user's files by the core-database and core-media naming, and the session, sync, photo and close actions passed in
 * by the shell (they live in modules core-system does not depend on).
 */
class DatabaseLogoutPorts(
    private val context: Context,
    private val userId: Long,
    private val db: suspend () -> AronDatabase,
    private val endSessionAction: suspend () -> Unit,
    private val requestSyncAction: () -> Unit,
    private val unsentPhotosCount: suspend () -> Int,
    private val closeDatabaseAction: suspend () -> Unit,
    private val forgetCredentialsAction: suspend () -> Unit = {},
) : LogoutPorts {
    override suspend fun unsentRecords(): Int {
        val outbox = db().outboxDao()
        return UNSENT_STATES.sumOf { outbox.countInState(it) }
    }
    override suspend fun unsentPhotos(): Int = unsentPhotosCount()
    override suspend fun endSession() = endSessionAction()
    override fun requestSync() = requestSyncAction()
    override suspend fun closeUserDatabase() = closeDatabaseAction()
    override suspend fun forgetCredentials() = forgetCredentialsAction()
    override fun userFiles(): List<File> =
        LogoutFlow.databaseFiles(context.getDatabasePath("x").parentFile!!, userId) +
            LogoutFlow.mediaDir(context.filesDir, userId) +
            // Per-user preference files of the feature lanes (e.g. aron-stock-guard-<id>).
            File(context.applicationInfo.dataDir, "shared_prefs").listFiles { f -> f.name.endsWith("-$userId.xml") }.orEmpty()

    companion object {
        /** Everything but `acked`: a row the server has not accepted is still the phone's to keep. */
        val UNSENT_STATES = listOf("pending", "in_flight", "quarantined", "rejected")
    }
}
