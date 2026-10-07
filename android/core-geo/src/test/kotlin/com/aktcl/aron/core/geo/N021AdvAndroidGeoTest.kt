package com.aktcl.aron.core.geo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Adversarial checker tests (N-021 platform fallback, N-025 capability flag). */
@RunWith(RobolectricTestRunner::class)
class N021AdvAndroidGeoTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val methods = setOf("onLocationChanged", "onStatusChanged", "onProviderEnabled", "onProviderDisabled")

    @Config(sdk = [29])
    @Test fun c4_belowApi30TheSingleUpdateListenerImplementsEveryAbstractLocationListenerMethod() {
        // API 26-29: onStatusChanged/onProviderEnabled/onProviderDisabled are abstract (defaults only from API 30) and the
        // platform calls them; a listener without them dies with AbstractMethodError.
        val listener = Class.forName("com.aktcl.aron.core.geo.PlatformLocationSource\$currentLocation\$located\$1\$1\$listener\$1")
        assertEquals(methods, listener.declaredMethods.map { it.name }.toSet().intersect(methods))
    }

    @Config(sdk = [30])
    @Test fun c5_rawSupportedOnApi30IsTheSamePhoneCapabilityAsOnApi31() {
        // Below API 31 the capability is unknown until a window shows it; unknown is never reported as supported.
        org.junit.Assert.assertNotEquals(true, AndroidGnssObserver(app, FakeClock()).rawSupported)
    }
}
