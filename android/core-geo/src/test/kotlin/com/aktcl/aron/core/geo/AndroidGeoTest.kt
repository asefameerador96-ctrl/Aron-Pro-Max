package com.aktcl.aron.core.geo

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidGeoTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun theMockFlagAndOptionalFieldsComeFromTheAndroidLocation() {
        val l = Location("gps").apply {
            latitude = 23.78; longitude = 90.41; accuracy = 8f; time = 1_791_000_000_000L; elapsedRealtimeNanos = 5_000_000_000L
            isMock = true
        }
        val raw = l.toRaw()
        assertTrue(raw.isMock)
        assertEquals(8.0, raw.accuracyM!!, 0.0)
        assertNull(raw.speedMps); assertNull(raw.bearingDeg); assertNull(raw.altitudeM); assertNull(raw.verticalAccuracyM)
        assertEquals("gps", raw.provider)
        assertFalse(Location("fused").apply { latitude = 1.0 }.toRaw().isMock)
        assertNull(Location("fused").toRaw().accuracyM)
    }

    @Config(sdk = [30])
    @Test fun belowApi31TheMockFlagIsIsFromMockProvider() {
        val l = Location("gps").apply { latitude = 23.78; longitude = 90.41 }
        Location::class.java.getDeclaredMethod("setIsFromMockProvider", Boolean::class.javaPrimitiveType).invoke(l, true)
        assertTrue(l.toRaw().isMock)
    }

    @Test fun accessNeedsPreciseLocationAndTheLocationSwitch() {
        val access = AndroidLocationAccess(app)
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        assertEquals(LocationAccessState.PERMISSION_DENIED, access.state(requirePrecise = true))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals(LocationAccessState.PERMISSION_DENIED, access.state(requirePrecise = true))
        shadowOf(lm).setLocationEnabled(true)
        assertEquals(LocationAccessState.OK, access.state(requirePrecise = false))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(LocationAccessState.OK, access.state(requirePrecise = true))
        shadowOf(lm).setLocationEnabled(false)
        assertEquals(LocationAccessState.LOCATION_OFF, access.state(requirePrecise = true))
    }

    @Test fun deviceStateReadsSettingsAndCachesTheMockAppScan() {
        val cr = app.contentResolver
        Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
        Settings.Global.putInt(cr, Settings.Global.AUTO_TIME, 0)
        var scans = 0
        var installed = listOf<String>()
        val clock = FakeClock()
        val reader = AndroidDeviceStateReader(app, clock, scanMockApps = { scans++; installed }, integrityRef = { "ref" })
        val s = reader.read()
        assertTrue(s.devOptionsEnabled); assertTrue(s.adbEnabled); assertFalse(s.autoTimeEnabled); assertFalse(s.deviceOwner)
        assertEquals("ref", s.integrityRef)
        assertFalse(s.mockAppPresent)
        installed = listOf("com.lexa.fakegps")
        assertFalse(reader.read().mockAppPresent) // cached for ten minutes
        clock.advance(10 * 60_000 + 1)
        assertTrue(reader.read().mockAppPresent)
        installed = emptyList()
        reader.invalidate()
        assertFalse(reader.read().mockAppPresent)
        assertEquals(3, scans)
    }

    @Test fun theScannerFindsNonSystemMockProvidersOnly() {
        val pm = shadowOf(app.packageManager)
        fun pkg(name: String, system: Boolean, vararg perms: String) = android.content.pm.PackageInfo().apply {
            packageName = name
            requestedPermissions = arrayOf(*perms)
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = name
                flags = if (system) android.content.pm.ApplicationInfo.FLAG_SYSTEM else 0
            }
        }
        pm.installPackage(pkg("com.lexa.fakegps", false, MockAppScanner.MOCK_PERMISSION, Manifest.permission.INTERNET))
        pm.installPackage(pkg("com.android.oem.gnss", true, MockAppScanner.MOCK_PERMISSION))
        pm.installPackage(pkg("com.whatsapp", false, Manifest.permission.INTERNET))
        assertEquals(listOf("com.lexa.fakegps"), MockAppScanner(app).scan())
    }

    @Test fun theLedgerPersistsPerBusinessDateAndDropsOldDates() {
        val ledger = PrefsFixLedger(app)
        ledger.recordProviderRequest("2026-10-06", 1_000)
        ledger.recordProviderRequest("2026-10-07", 2_000)
        ledger.recordProviderRequest("2026-10-07", 3_000)
        val again = PrefsFixLedger(app)
        assertEquals(2, again.fixes("2026-10-07"))
        assertEquals(5_000L, again.busyMs("2026-10-07"))
        assertEquals(1, again.fixes("2026-10-06"))
        again.recordProviderRequest("2026-10-08", 0)
        assertEquals(0, again.fixes("2026-10-06"))
        assertEquals(2, again.fixes("2026-10-07"))
    }
}
