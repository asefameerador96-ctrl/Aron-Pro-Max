package com.aktcl.aron.core.geo.spoof

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.geo.MockAppScanner
import com.aktcl.aron.core.geo.R
import com.aktcl.aron.core.geo.integrity.AndroidRootProbe
import com.aktcl.aron.core.geo.integrity.RootHints
import java.io.File

/** The fake-GPS package list, from the one resource file (see `res/values/fake_gps_packages.xml`). */
object FakeGpsPackages {
    fun list(context: Context): List<String> = context.resources.getStringArray(R.array.aron_fake_gps_packages).toList()
}

/**
 * The Android side of the spoof checks. Every probe is cheap and none of them starts a sensor, a request or a radio:
 * - once per 10 minutes (cached by elapsed realtime): which listed fake-GPS packages are installed (declared in the
 *   manifest `<queries>`, no QUERY_ALL_PACKAGES), whether one of them is the SELECTED mock-location app (one AppOps read per
 *   installed candidate), the root hints, emulator markers and hook-framework markers (a class lookup and one read of
 *   `/proc/self/maps`, a few milliseconds) and the "gps" provider's properties (one binder call); all of these are in the
 *   cached facts, and [AndroidSpoofChecker.prepare] reads them off the main thread before a fix is built;
 * - per fix: only the last-known NETWORK location, read passively (no request, so no radio and no battery).
 * Nothing here proves anything: a rooted phone that hides these tools defeats all of it.
 */
class AndroidSpoofProbes(
    context: Context,
    private val clock: WallClock,
    private val candidates: List<String> = FakeGpsPackages.list(context),
    private val ttlMs: Long = 10 * 60_000L,
) {
    private val app = context.applicationContext
    private var cached: SpoofFacts? = null
    private var cachedAt = 0L

    @Synchronized
    fun facts(): SpoofFacts {
        val now = clock.elapsedRealtimeMs()
        val c = cached
        if (c != null && now - cachedAt in 0..ttlMs) return c
        return read().also { cached = it; cachedAt = now }
    }

    @Synchronized fun invalidate() { cached = null }

    private fun read(): SpoofFacts {
        // The listed fake-GPS apps that are installed, plus every package the platform lets this app see that requests
        // ACCESS_MOCK_LOCATION (what an ordinary app can see without QUERY_ALL_PACKAGES: only packages declared in the manifest
        // <queries> and the few the platform always shows; any other installed mock-location app is invisible here and is
        // caught only by the mock flag on the fix itself).
        val installed = (candidates.filter { isInstalled(it) } + runCatching { MockAppScanner(app).scan() }.getOrDefault(emptyList())).distinct()
        return SpoofFacts(
            mockAppSelected = installed.any { isSelectedMockApp(it) },
            fakeGpsAppInstalled = installed.isNotEmpty(),
            rootHints = runCatching { RootHints.evaluate(AndroidRootProbe(app), app.packageName) }.getOrDefault(emptyList()),
            emulator = runCatching { looksLikeEmulator() }.getOrDefault(false),
            hookFramework = runCatching { hookMarkers() }.getOrDefault(false),
            gpsProviderLooksLikeTestProvider = runCatching { gpsHasNoSatelliteRequirement() }.getOrDefault(false),
        )
    }

    private fun isInstalled(pkg: String): Boolean = try {
        @Suppress("DEPRECATION") app.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** AppOps `android:mock_location` is MODE_ALLOWED for the app the user selected in Developer options. */
    private fun isSelectedMockApp(pkg: String): Boolean = runCatching {
        val ops = app.getSystemService(AppOpsManager::class.java) ?: return false
        val uid = app.packageManager.getApplicationInfo(pkg, 0).uid
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(OP_MOCK_LOCATION, uid, pkg)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(OP_MOCK_LOCATION, uid, pkg)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** A real GPS provider needs satellites; a test provider added by a fake-GPS app usually says it does not. Soft until walked on the pilot phones. */
    @Suppress("DEPRECATION")
    private fun gpsHasNoSatelliteRequirement(): Boolean {
        val lm = app.getSystemService(LocationManager::class.java) ?: return false
        if (!lm.allProviders.contains(LocationManager.GPS_PROVIDER)) return false
        return if (Build.VERSION.SDK_INT >= 31) lm.getProviderProperties(LocationManager.GPS_PROVIDER)?.hasSatelliteRequirement() == false
        else lm.getProvider(LocationManager.GPS_PROVIDER)?.requiresSatellite() == false
    }

    private fun looksLikeEmulator(): Boolean {
        val fp = Build.FINGERPRINT.lowercase(); val hw = Build.HARDWARE.lowercase(); val model = Build.MODEL.lowercase(); val product = Build.PRODUCT.lowercase()
        return fp.startsWith("generic") || fp.contains("emulator") || fp.contains("vbox") || hw.contains("goldfish") || hw.contains("ranchu") ||
            model.contains("emulator") || model.contains("android sdk built for") || product.startsWith("sdk_") || product.contains("emulator")
    }

    private fun hookMarkers(): Boolean {
        if (runCatching { Class.forName("de.robv.android.xposed.XposedBridge"); true }.getOrDefault(false)) return true
        val maps = runCatching { File("/proc/self/maps").bufferedReader().useLines { lines -> lines.take(20_000).any { l -> HOOK_MARKERS.any { it in l } } } }.getOrDefault(false)
        return maps
    }

    /** The last-known NETWORK location (passive: no request, no radio). Null when there is none or it is not usable. */
    @android.annotation.SuppressLint("MissingPermission") // checked just below; a revoked permission gives null, never a crash
    fun networkFix(nowElapsedNanos: Long): NetworkFix? = runCatching {
        if (app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            app.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = app.getSystemService(LocationManager::class.java) ?: return null
        val l: Location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: return null
        NetworkFix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy.toDouble() else 5_000.0, (nowElapsedNanos - l.elapsedRealtimeNanos) / 1_000_000L)
    }.getOrNull()

    private companion object {
        const val OP_MOCK_LOCATION = "android:mock_location"
        val HOOK_MARKERS = listOf("frida", "xposed", "lsposed", "substrate")
    }
}
