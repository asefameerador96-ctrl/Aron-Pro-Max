package com.aktcl.aron.core.geo.integrity

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.aktcl.aron.core.geo.AndroidDeviceStateReader
import java.io.File

class AndroidRootProbe(context: Context) : RootProbe {
    private val app = context.applicationContext

    override fun exists(path: String): Boolean = File(path).exists()
    override val buildTags: String? get() = Build.TAGS

    @Suppress("PrivateApi")
    override fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    override fun installed(packageName: String): Boolean = try {
        @Suppress("DEPRECATION") app.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun mounts(): String = runCatching { File("/proc/self/mounts").readText().take(256 * 1024) }.getOrDefault("")
    override val userId: Int get() = Process.myUserHandle().hashCode() // UserHandle.hashCode() is the user id
    override val dataDir: String get() = app.applicationInfo.dataDir ?: ""
}

/** Reads [IntegritySignals] (cheap except the package lookups; call at login and before a batch, not per fix). */
class IntegritySignalsReader(
    context: Context,
    private val deviceState: AndroidDeviceStateReader,
    private val probe: RootProbe = AndroidRootProbe(context),
) {
    private val ownPackage = context.applicationContext.packageName

    fun read(): IntegritySignals {
        val fix = deviceState.read()
        return IntegritySignals(
            devOptionsEnabled = fix.devOptionsEnabled,
            adbEnabled = fix.adbEnabled,
            autoTimeEnabled = fix.autoTimeEnabled,
            deviceOwner = fix.deviceOwner,
            mockLocationApps = deviceState.mockApps(),
            rootHints = RootHints.evaluate(probe, ownPackage),
        )
    }
}
