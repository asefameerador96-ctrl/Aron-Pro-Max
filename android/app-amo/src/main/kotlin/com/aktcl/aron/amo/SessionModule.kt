package com.aktcl.aron.amo

import android.content.Context
import com.aktcl.aron.core.network.ApiOrigin
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
    const val CLIENT = "app_amo"

    @Provides
    @Singleton
    fun sessionComponents(@ApplicationContext context: Context): SessionComponents = SessionComponents.create(
        context = context,
        // The provisioning extra aron.api_base_url (docs/24 s10.4) is passed here once the device-policy lane stores it.
        origin = ApiOrigin.resolve(BuildConfig.API_BASE_URL, provisionedOrigin = null, allowCleartextLoopback = BuildConfig.DEBUG),
        appVersion = BuildConfig.VERSION_NAME + "+" + BuildConfig.VERSION_CODE,
        client = CLIENT,
    )
}
