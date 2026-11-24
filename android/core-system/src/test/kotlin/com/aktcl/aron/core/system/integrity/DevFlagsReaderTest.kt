package com.aktcl.aron.core.system.integrity

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real settings reader: all three flags, and the exact list of settings the gate observes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevFlagsReaderTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private fun put(name: String, v: Int) { Settings.Global.putInt(app.contentResolver, name, v) }

    @Test fun eachFlagIsReadOnItsOwn() {
        put(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0); put(Settings.Global.ADB_ENABLED, 0); put("adb_wifi_enabled", 0)
        assertEquals(DevFlags(false, false, false), AndroidDevFlagsReader(app).read())
        put("adb_wifi_enabled", 1)
        assertEquals(DevFlags(false, false, true), AndroidDevFlagsReader(app).read())
        put("adb_wifi_enabled", 0); put(Settings.Global.ADB_ENABLED, 1)
        assertEquals(DevFlags(false, true, false), AndroidDevFlagsReader(app).read())
        put(Settings.Global.ADB_ENABLED, 0); put(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        assertTrue(AndroidDevFlagsReader(app).read().devOptions)
    }

    @Test @Config(sdk = [29]) fun wirelessDebuggingIsNotReadBelowApi30() {
        put("adb_wifi_enabled", 1)
        assertFalse(AndroidDevFlagsReader(app).read().adbWifi)
    }

    @Test fun theGateObservesAllThreeSettings() {
        assertEquals(listOf("development_settings_enabled", "adb_enabled", "adb_wifi_enabled"), AndroidDevFlagsReader.SETTINGS)
    }
}
