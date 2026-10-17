package com.aktcl.aron.core.geo

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-lab helper for D-05 (docs/status/device-checks.md): holds a FAKE GPS fix on the phone for `holdSeconds`
 * (instrumentation argument, default 90) so the laptop session can walk the field app through a visit over adb and see
 * the mock verdict ("নকল লোকেশন (Fake GPS)"). The test APK needs the mock-location app-op:
 * `adb shell appops set com.aktcl.aron.core.geo.test android:mock_location allow` after installing it. A shell-created
 * test provider cannot be used instead: Android removes it as soon as the `cmd location` process exits.
 *
 * Run: `adb shell am instrument -w -e class com.aktcl.aron.core.geo.MockGpsHoldTest -e holdSeconds 120 -e lat 23.8069
 * -e lon 90.3687 com.aktcl.aron.core.geo.test/androidx.test.runner.AndroidJUnitRunner`.
 */
@RunWith(AndroidJUnit4::class)
class MockGpsHoldTest {
    @Test
    fun holdMockGpsFix() {
        val args = InstrumentationRegistry.getArguments()
        val holdSeconds = args.getString("holdSeconds")?.toIntOrNull() ?: 90
        val lat = args.getString("lat")?.toDoubleOrNull() ?: 23.8069
        val lon = args.getString("lon")?.toDoubleOrNull() ?: 90.3687
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = LocationManager.GPS_PROVIDER
        runCatching { lm.removeTestProvider(provider) }
        if (Build.VERSION.SDK_INT >= 31) {
            lm.addTestProvider(provider, false, true, false, false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE)
        } else {
            @Suppress("DEPRECATION")
            lm.addTestProvider(provider, false, true, false, false, true, true, true, 3, 1)
        }
        lm.setTestProviderEnabled(provider, true)
        // The field app reads Google's fused client (AndroidLocation.kt), which ignores platform test providers: feed it too.
        val fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(ctx)
        com.google.android.gms.tasks.Tasks.await(fused.setMockMode(true))
        val until = SystemClock.elapsedRealtime() + holdSeconds * 1000L
        var ticks = 0
        while (SystemClock.elapsedRealtime() < until) {
            val fix = Location(provider).apply {
                latitude = lat; longitude = lon; accuracy = 8f; time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            lm.setTestProviderLocation(provider, fix)
            runCatching { com.google.android.gms.tasks.Tasks.await(fused.setMockLocation(fix)) }
            ticks++
            Thread.sleep(1000)
        }
        runCatching { com.google.android.gms.tasks.Tasks.await(fused.setMockMode(false)) }
        runCatching { lm.removeTestProvider(provider) }
        assertTrue("held $ticks fixes", ticks > 0)
    }
}
