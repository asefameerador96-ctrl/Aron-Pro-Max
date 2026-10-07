package com.aktcl.aron.core.geo

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.aktcl.aron.core.common.WallClock

/**
 * Finds installed apps that can act as a mock-location provider: non-system packages other than this app that request
 * `ACCESS_MOCK_LOCATION`. Needs `QUERY_ALL_PACKAGES` (declared by this module; the apps are installed outside Play).
 */
class MockAppScanner(context: Context) {
    private val app = context.applicationContext

    fun scan(): List<String> {
        val pm = app.packageManager
        val packages = runCatching {
            if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            else @Suppress("DEPRECATION") pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }.getOrDefault(emptyList())
        return packages.asSequence()
            .filter { it.packageName != app.packageName }
            .filter { p -> (p.applicationInfo?.flags ?: 0) and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 }
            .filter { p -> p.requestedPermissions?.contains(MOCK_PERMISSION) == true }
            .map { it.packageName }
            .sorted()
            .toList()
    }

    companion object {
        const val MOCK_PERMISSION = "android.permission.ACCESS_MOCK_LOCATION"
    }
}

/**
 * `FixDeviceState` from the system settings and the device-policy manager. The package scan is cached for
 * [scanTtlMs] (by elapsed realtime) so a fix never waits on a full package listing more than once in a while;
 * [invalidate] forces a rescan (package added or removed). [integrityRef] supplies the latest `device_status` UUID that
 * carried a Play Integrity token (N-026).
 */
class AndroidDeviceStateReader(
    context: Context,
    private val clock: WallClock,
    private val scanMockApps: () -> List<String> = MockAppScanner(context)::scan,
    private val scanTtlMs: Long = 10 * 60_000L,
    private val integrityRef: () -> String? = { null },
) : DeviceStateReader {
    private val app = context.applicationContext
    private var cached: List<String>? = null
    private var cachedAt = 0L

    @Synchronized
    fun mockApps(): List<String> {
        val now = clock.elapsedRealtimeMs()
        val c = cached
        if (c != null && now - cachedAt in 0..scanTtlMs) return c
        return scanMockApps().also { cached = it; cachedAt = now }
    }

    @Synchronized
    fun invalidate() { cached = null }

    override fun read(): FixDeviceState {
        val cr = app.contentResolver
        fun global(name: String) = runCatching { Settings.Global.getInt(cr, name, 0) == 1 }.getOrDefault(false)
        val dpm = app.getSystemService(DevicePolicyManager::class.java)
        return FixDeviceState(
            deviceOwner = runCatching { dpm?.isDeviceOwnerApp(app.packageName) == true }.getOrDefault(false),
            devOptionsEnabled = global(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
            adbEnabled = global(Settings.Global.ADB_ENABLED),
            // Unreadable counts as off: auto time off raises CLOCK_SKEW on the server, never blocks here.
            autoTimeEnabled = global(Settings.Global.AUTO_TIME),
            mockAppPresent = mockApps().isNotEmpty(),
            integrityRef = integrityRef(),
        )
    }
}

/** Persistent [FixLedger] in a private preferences file; keeps the current and previous business date only. */
class PrefsFixLedger(context: Context) : FixLedger {
    private val prefs = context.applicationContext.getSharedPreferences("aron-geo-ledger", Context.MODE_PRIVATE)

    @Synchronized
    override fun recordProviderRequest(businessDate: String, busyMs: Long) {
        val last = prefs.getString(KEY_LAST, null)
        val previous = if (last != null && last != businessDate) last else prefs.getString(KEY_PREV, null)
        val keep = setOfNotNull(businessDate, previous)
        val edit = prefs.edit()
        prefs.all.keys.filter { k -> k != KEY_LAST && k != KEY_PREV && keep.none { d -> k.endsWith(":$d") } }.forEach { edit.remove(it) }
        edit.putInt("n:$businessDate", fixes(businessDate) + 1)
            .putLong("ms:$businessDate", busyMs(businessDate) + busyMs.coerceAtLeast(0))
            .putString(KEY_LAST, businessDate)
            .apply { if (previous != null) putString(KEY_PREV, previous) else remove(KEY_PREV) }
            .apply()
    }

    @Synchronized override fun fixes(businessDate: String): Int = prefs.getInt("n:$businessDate", 0)
    @Synchronized override fun busyMs(businessDate: String): Long = prefs.getLong("ms:$businessDate", 0L)

    private companion object {
        const val KEY_LAST = "last"
        const val KEY_PREV = "prev"
    }
}
