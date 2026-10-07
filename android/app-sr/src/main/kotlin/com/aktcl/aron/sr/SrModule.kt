package com.aktcl.aron.sr

import android.content.Context
import com.aktcl.aron.core.printing.bt.BluetoothSppTransport
import com.aktcl.aron.core.printing.bt.PrefsSavedPrinterStore
import com.aktcl.aron.core.printing.bt.PrinterManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** SR-day wiring (android-sr-a): the printer manager. */
@Module
@InstallIn(SingletonComponent::class)
object SrModule {
    /** One printer manager per process (F-SYS-044 device part: the printer is paired once). */
    @Provides
    @Singleton
    fun printerManager(@ApplicationContext context: Context): PrinterManager = PrinterManager(
        BluetoothSppTransport.factory(context), PrefsSavedPrinterStore(context), CoroutineScope(SupervisorJob() + Dispatchers.Default),
        idleDisconnectMs = 120_000,
    )

    // The FixManager is built by core-sync's DeviceRuntime (SessionModule): cfg.geo.* from the bundle, integrity_ref.
}
