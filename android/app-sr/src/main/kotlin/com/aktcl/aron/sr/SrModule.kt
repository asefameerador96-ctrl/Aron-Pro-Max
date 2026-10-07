package com.aktcl.aron.sr

import android.content.Context
import com.aktcl.aron.core.geo.AndroidDeviceStateReader
import com.aktcl.aron.core.geo.AndroidGnssObserver
import com.aktcl.aron.core.geo.AndroidLocationAccess
import com.aktcl.aron.core.geo.FallbackLocationSource
import com.aktcl.aron.core.geo.FixManager
import com.aktcl.aron.core.geo.FixSettings
import com.aktcl.aron.core.geo.PrefsFixLedger
import com.aktcl.aron.core.session.SessionComponents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** SR-day wiring (android-sr-a): the one on-demand fix manager of N-021, shared by attendance, visits and requests. */
@Module
@InstallIn(SingletonComponent::class)
object SrModule {
    @Provides
    @Singleton
    fun fixManager(@ApplicationContext context: Context, components: SessionComponents): FixManager = FixManager(
        FallbackLocationSource.of(context), AndroidLocationAccess(context), AndroidDeviceStateReader(context, components.clock),
        components.clock, PrefsFixLedger(context), AndroidGnssObserver(context, components.clock), settings = { FixSettings() },
    )
}
