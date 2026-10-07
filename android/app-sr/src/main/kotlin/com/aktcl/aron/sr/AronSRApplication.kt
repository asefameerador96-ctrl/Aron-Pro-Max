package com.aktcl.aron.sr

import android.app.Application
import androidx.work.Configuration
import com.aktcl.aron.core.sync.AronWorkerFactory
import com.aktcl.aron.core.sync.ConnectivityFlush
import com.aktcl.aron.core.sync.WorkManagerSyncScheduler
import com.aktcl.aron.core.sync.device.DeviceRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** WorkManager is initialised on demand with the app's worker factory (the default initializer is removed in the manifest). */
@HiltAndroidApp
class AronSRApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: AronWorkerFactory
    @Inject lateinit var connectivityFlush: ConnectivityFlush
    @Inject lateinit var deviceRuntime: DeviceRuntime
    @Inject lateinit var syncScheduler: WorkManagerSyncScheduler
    @Inject lateinit var updateShell: com.aktcl.aron.core.sync.shell.UpdateShell
    @Inject lateinit var pushShell: com.aktcl.aron.core.sync.shell.PushShell
    @Inject lateinit var sessionComponents: com.aktcl.aron.core.session.SessionComponents
    @Inject lateinit var mediaShell: MediaShell
    @Inject lateinit var systemShell: SystemShell

    override fun onCreate() {
        super.onCreate()
        ConnectivityFlush.register(this, connectivityFlush) // T3: flush on reconnect while the process lives
        // DPC: trusted clock and calendar, re-apply the stored policy; integrity evidence after each online login.
        // Off the main thread; nothing here touches the network or waits for it.
        deviceRuntime.start(CoroutineScope(SupervisorJob() + Dispatchers.Default), syncScheduler)
        // F-SYS-020: an update check after every online login (the 12 h throttle is skipped at login); resume checks too.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            sessionComponents.session.onlineLogins.collect { updateShell.check(atLogin = true) }
        }
        // N-038: push nudges (token for the signed-in user, notices, jittered pulls); off without google-services.json.
        pushShell.install()
        // Photos (F-SYS-010): every user's queue uploads on its own job, whoever is signed in; no network work here.
        mediaShell.install()
        // PDA to Support (F-SYS-021): the worker finds the signed-in user's queue; no network work here.
        systemShell.install()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
