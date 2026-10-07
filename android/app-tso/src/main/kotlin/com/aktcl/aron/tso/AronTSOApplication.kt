package com.aktcl.aron.tso

import android.app.Application
import androidx.work.Configuration
import com.aktcl.aron.core.sync.AronWorkerFactory
import com.aktcl.aron.core.sync.ConnectivityFlush
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** WorkManager is initialised on demand with the app's worker factory (the default initializer is removed in the manifest). */
@HiltAndroidApp
class AronTSOApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: AronWorkerFactory
    @Inject lateinit var connectivityFlush: ConnectivityFlush

    override fun onCreate() {
        super.onCreate()
        ConnectivityFlush.register(this, connectivityFlush) // T3: flush on reconnect while the process lives
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
