package com.aktcl.aron.tso

import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * F-SYS-033 and F-SYS-044 for ARON TSO: its own package id and launcher label, and nothing in the merged manifest that
 * could collide with the other two apps or with a test copy under a fourth package id (-Paron.applicationIdSuffix=.copy):
 * every provider authority and every declared permission is prefixed by this package, there is no shared user id, and
 * only the launcher activity and the device-admin receiver are exported (docs/24 s5.8). The printer pairing is a
 * system Bluetooth bond shared by all apps; printing from each app is checked on the phone (device test).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AppCoexistenceTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val expectedPackage = "com.aktcl.aron.tso"

    @Test
    fun packageIdRoleAndLabelAreFixed() {
        assertEquals("TSO", BuildConfig.ARON_ROLE)
        assertTrue(BuildConfig.APPLICATION_ID == expectedPackage || BuildConfig.APPLICATION_ID.startsWith("$expectedPackage."))
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        assertEquals("ARON TSO", context.packageManager.getApplicationLabel(context.applicationInfo).toString())
        for (tag in listOf("bn", "en")) {
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            assertEquals("ARON TSO", context.createConfigurationContext(config).getString(R.string.app_name))
        }
    }

    @Test
    fun theApiBaseUrlIsABareOrigin() {
        assertTrue(BuildConfig.API_BASE_URL, Regex("^https?://[^/?#]+$").matches(BuildConfig.API_BASE_URL))
    }

    @Test
    fun nothingInTheManifestCollidesWithAnotherPackage() {
        val flags = PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        assertNull("no sharedUserId", info.sharedUserId)
        info.providers.orEmpty().forEach { p ->
            p.authority.split(';').forEach { a -> assertTrue("authority $a of ${p.name}", a.startsWith(context.packageName + ".")) }
        }
        info.permissions.orEmpty().forEach { p -> assertTrue("permission ${p.name}", p.name.startsWith(context.packageName + ".")) }
        val components: List<android.content.pm.ComponentInfo> = info.activities.orEmpty().toList() + info.receivers.orEmpty().toList() + info.services.orEmpty().toList()
        val exported = components
            .filter { it.exported }.map { it.name }.toSet()
        val allowed = setOf(
            "com.aktcl.aron.tso.MainActivity",
            "com.aktcl.aron.dpc.AronDeviceAdminReceiver",
        )
        val unexpected = exported - allowed - exported.filter { it.startsWith("com.google.firebase.") || it.startsWith("androidx.") }.toSet()
        assertTrue("unexpected exported components: $unexpected", unexpected.isEmpty())
        assertTrue((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) == 0)
    }
}
