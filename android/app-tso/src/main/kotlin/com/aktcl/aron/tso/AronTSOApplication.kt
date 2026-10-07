package com.aktcl.aron.tso

import android.app.Application
import androidx.work.Configuration
import com.aktcl.aron.core.sync.AronWorkerFactory
import com.aktcl.aron.core.sync.ConnectivityFlush
import com.aktcl.aron.core.sync.WorkManagerSyncScheduler
import com.aktcl.aron.core.sync.device.DeviceRuntime
import com.aktcl.aron.core.sync.shell.MediaShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** WorkManager is initialised on demand with the app's worker factory (the default initializer is removed in the manifest). */
@HiltAndroidApp
class AronTSOApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: AronWorkerFactory
    @Inject lateinit var connectivityFlush: ConnectivityFlush
    @Inject lateinit var deviceRuntime: DeviceRuntime
    @Inject lateinit var syncScheduler: WorkManagerSyncScheduler
    @Inject lateinit var mediaShell: MediaShell

    override fun onCreate() {
        super.onCreate()
        ConnectivityFlush.register(this, connectivityFlush) // T3: flush on reconnect while the process lives
        // DPC: trusted clock and calendar, re-apply the stored policy; integrity evidence after each online login.
        // Off the main thread; nothing here touches the network or waits for it.
        deviceRuntime.start(CoroutineScope(SupervisorJob() + Dispatchers.Default), syncScheduler)
        // Photos (F-SYS-010): every user's queue uploads on its own job, whoever is signed in; no network work here.
        mediaShell.install()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
