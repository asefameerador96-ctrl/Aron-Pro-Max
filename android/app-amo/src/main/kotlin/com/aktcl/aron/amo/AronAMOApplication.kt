package com.aktcl.aron.amo

import android.app.Application
import androidx.work.Configuration
import com.aktcl.aron.core.sync.AronWorkerFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** WorkManager is initialised on demand with the app's worker factory (the default initializer is removed in the manifest). */
@HiltAndroidApp
class AronAMOApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: AronWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
