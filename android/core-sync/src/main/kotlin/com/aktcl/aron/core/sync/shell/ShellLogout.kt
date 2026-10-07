package com.aktcl.aron.core.sync.shell

import android.content.Context
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.core.system.logout.AppRole
import com.aktcl.aron.core.system.logout.DatabaseLogoutPorts
import com.aktcl.aron.core.system.logout.LogoutCheck
import com.aktcl.aron.core.system.logout.LogoutFlow
import com.aktcl.aron.core.system.logout.LogoutResult

/**
 * The F-SYS-022 logout of an app shell (docs/requests/android-sys-logout-wiring.md): SR and AMO end the session and keep
 * their data (it keeps uploading); TSO is refused while anything is unsent and otherwise wipes that user's database, photos
 * and per-user preferences, then forgets the user's credentials.
 */
class ShellLogout(
    context: Context,
    private val components: SessionComponents,
    private val databases: UserDatabases,
    private val syncScheduler: SyncScheduler,
    private val media: MediaShell,
) {
    private val app = context.applicationContext

    fun flow(role: AppRole, userId: Long): Flow = Flow(userId, LogoutFlow(
        role,
        DatabaseLogoutPorts(
            context = app,
            userId = userId,
            db = { databases.of(userId) },
            endSessionAction = { components.session.logout() },
            requestSyncAction = { syncScheduler.requestSync(userId, SyncTrigger.MANUAL); media.scheduler.requestUpload() },
            unsentPhotosCount = { media.unsentPhotos(userId) },
            closeDatabaseAction = { databases.close(userId) },
            forgetCredentialsAction = { components.session.forgetUser(userId) },
        ),
    ))

    /** [LogoutFlow] plus the end of the database's removal window ([UserDatabases.close] blocks reopening while it runs). */
    inner class Flow internal constructor(private val userId: Long, private val flow: LogoutFlow) {
        suspend fun check(): LogoutCheck = flow.check()

        suspend fun logout(): LogoutResult = try { flow.logout() } finally { databases.allowOpen(userId) }

        fun syncNow() = flow.syncNow()
    }
}
