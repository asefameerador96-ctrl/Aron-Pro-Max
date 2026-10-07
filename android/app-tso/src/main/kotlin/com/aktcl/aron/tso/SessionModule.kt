package com.aktcl.aron.tso

import android.content.Context
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.network.ApiOrigin
import androidx.work.WorkManager
import com.aktcl.aron.core.sync.AronWorkerFactory
import com.aktcl.aron.core.sync.BundleDownloaders
import com.aktcl.aron.core.sync.ConnectivityFlush
import com.aktcl.aron.core.sync.SessionSyncRunner
import com.aktcl.aron.core.sync.ResumeConfigCheck
import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.core.sync.device.DeviceRuntime
import com.aktcl.aron.core.sync.WorkManagerSyncScheduler
import com.aktcl.aron.core.sync.shell.ShellLogout
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
        // X-Device-Proof over the enrolled Keystore key (docs/24 s8.3); no header before enrolment (a dev phone).
        proofSigner = DeviceRuntime.proofSigner(context),
    )

    /** Geo, integrity and device-owner wiring owed to android-geo-dpc (docs/requests/android-geo-dpc-wiring.md). */
    @Provides
    @Singleton
    fun deviceRuntime(@ApplicationContext context: Context, components: SessionComponents, databases: UserDatabases): DeviceRuntime =
        DeviceRuntime(context, components, databases, BuildConfig.PLAY_INTEGRITY_PROJECT_NUMBER)

    /** The on-demand fix manager (N-021): `cfg.geo.*` from the bundle and `integrity_ref` from the last token report. */
    @Provides
    fun fixManager(runtime: DeviceRuntime): com.aktcl.aron.core.geo.FixManager = runtime.fixManager

    /** Resume config check (F-SYS-092, ruling R9): launched from onResume, never awaited. */
    @Provides
    @Singleton
    fun resumeConfigCheck(databases: UserDatabases, components: SessionComponents): ResumeConfigCheck =
        ResumeConfigCheck({ databases.of(it) }, components.syncApi, components.trustedClock)

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
        WorkManagerSyncScheduler({ WorkManager.getInstance(context) }, hold = com.aktcl.aron.core.sync.SyncHold.Prefs(context))

    @Provides
    fun syncScheduler(scheduler: WorkManagerSyncScheduler): SyncScheduler = scheduler

    @Provides
    @Singleton
    fun workerFactory(
        databases: UserDatabases, components: SessionComponents, scheduler: WorkManagerSyncScheduler, runtime: DeviceRuntime, bundles: BundleDownloaders,
        resumeConfigCheck: ResumeConfigCheck, push: com.aktcl.aron.core.sync.shell.PushShell,
    ): AronWorkerFactory = AronWorkerFactory({ SessionSyncRunner(databases, components, runtime::beforeBatch, bundles) }, { scheduler },
        // N-038: the pull a push asks for; it never gets the upload runner.
        { com.aktcl.aron.core.sync.push.SessionPushPull(push::activeUser, bundles, resumeConfigCheck) },
    )

    /** N-038 push: the FCM token for the signed-in user, the Bangla/English notice and the jittered pull (never an upload). */
    @Provides
    @Singleton
    fun pushShell(@ApplicationContext context: Context, components: SessionComponents): com.aktcl.aron.core.sync.shell.PushShell =
        com.aktcl.aron.core.sync.shell.PushShell(
            context, components, com.aktcl.aron.contract.AppFlavour.TSO,
            localized = { com.aktcl.aron.core.ui.AppLocale.wrap(it) },
            bangla = { com.aktcl.aron.core.ui.AppLocale.current(it) == com.aktcl.aron.core.common.AppLanguage.BN },
        )

    /** F-SYS-020 updater: update check, download, install (android-sys). */
    @Provides
    @Singleton
    fun updateShell(@ApplicationContext context: Context, components: SessionComponents): com.aktcl.aron.core.sync.shell.UpdateShell =
        com.aktcl.aron.core.sync.shell.UpdateShell(context, components, com.aktcl.aron.contract.AppFlavour.TSO, BuildConfig.VERSION_CODE)

    /** F-SYS-022 logout (docs/requests/android-sys-logout-wiring.md). */
    @Provides
    @Singleton
    fun shellLogout(@ApplicationContext context: Context, components: SessionComponents, databases: UserDatabases, scheduler: WorkManagerSyncScheduler): ShellLogout =
        ShellLogout(context, components, databases, scheduler) // no camera in this app yet: no photos to count

    /** Connectivity trigger (F-SYS-046): uploads for every user on the phone with rows waiting, after a 5 s quiet period. */
    @Provides
    @Singleton
    fun connectivityFlush(databases: UserDatabases, components: SessionComponents, scheduler: WorkManagerSyncScheduler): ConnectivityFlush =
        ConnectivityFlush(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
            healthy = { components.syncApi.healthy() },
            // One unreadable database must not hide the others' rows.
            usersWithPendingRows = { databases.knownUserIds().filter { id -> runCatching { databases.of(id).outboxDao().unsentCount() > 0 }.getOrDefault(false) } },
            scheduler = scheduler,
        )
}
