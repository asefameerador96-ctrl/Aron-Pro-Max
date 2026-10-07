package com.aktcl.aron.tso

import android.content.Context
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.network.ApiOrigin
import androidx.work.WorkManager
import com.aktcl.aron.core.sync.AronWorkerFactory
import com.aktcl.aron.core.sync.BundleDownloaders
import com.aktcl.aron.core.sync.SessionSyncRunner
import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.core.sync.WorkManagerSyncScheduler
import java.io.File
import com.aktcl.aron.core.session.SessionComponents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** App-level wiring of the core modules (docs/24 s5.1: Hilt only in app modules). */
@Module
@InstallIn(SingletonComponent::class)
object SessionModule {
    /** `LoginRequest.client` of this app. */
    const val CLIENT = "app_tso"

    @Provides
    @Singleton
    fun sessionComponents(@ApplicationContext context: Context): SessionComponents = SessionComponents.create(
        context = context,
        // The provisioning extra aron.api_base_url (docs/24 s10.4) is passed here once the device-policy lane stores it.
        origin = ApiOrigin.resolve(BuildConfig.API_BASE_URL, provisionedOrigin = null, allowCleartextLoopback = BuildConfig.DEBUG),
        appVersion = BuildConfig.VERSION_NAME + "+" + BuildConfig.VERSION_CODE,
        client = CLIENT,
    )

    /** Each user's encrypted Room database (docs/24 s5.2), keyed by a Keystore-wrapped passphrase. */
    @Provides
    @Singleton
    fun userDatabases(@ApplicationContext context: Context, components: SessionComponents): UserDatabases =
        UserDatabases(context) { userId -> components.databaseKeys.passphrase(userId) }

    /** Day-bundle download per user (F-SYS-006); login and the day start call `of(userId).download()`. */
    @Provides
    @Singleton
    fun bundleDownloaders(@ApplicationContext context: Context, databases: UserDatabases, components: SessionComponents): BundleDownloaders =
        BundleDownloaders(File(context.noBackupFilesDir, "aron/bundle-staging"), databases, components.syncApi, components.clock)

    /** Upload scheduling (F-SYS-011): feature code calls `requestSync(userId, trigger)` after every commit. */
    @Provides
    @Singleton
    fun workManagerSyncScheduler(@ApplicationContext context: Context): WorkManagerSyncScheduler =
        WorkManagerSyncScheduler({ WorkManager.getInstance(context) })

    @Provides
    fun syncScheduler(scheduler: WorkManagerSyncScheduler): SyncScheduler = scheduler

    @Provides
    @Singleton
    fun workerFactory(databases: UserDatabases, components: SessionComponents, scheduler: WorkManagerSyncScheduler): AronWorkerFactory =
        AronWorkerFactory({ SessionSyncRunner(databases, components) }, { scheduler })
}
