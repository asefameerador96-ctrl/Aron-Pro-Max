package com.aktcl.aron.core.system.integrity

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.provider.Settings
import java.io.File

/** [GateClock] over the phone's clocks and boot counter. */
class AndroidGateClock(context: Context) : GateClock {
    private val cr = context.applicationContext.contentResolver
    override fun wallMs(): Long = System.currentTimeMillis()
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
    override fun bootCount(): Int = runCatching { Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
}

/** Reads `DEVELOPMENT_SETTINGS_ENABLED` and `ADB_ENABLED`. An unreadable setting counts as off (a phone must not be locked out by a read error). */
class AndroidDevFlagsReader(context: Context) {
    private val cr = context.applicationContext.contentResolver
    fun read(): DevFlags {
        fun on(name: String) = runCatching { Settings.Global.getInt(cr, name, 0) == 1 }.getOrDefault(false)
        return DevFlags(devOptions = on(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED), adb = on(Settings.Global.ADB_ENABLED))
    }
}

/** The process-wide gate, built once from the application context (no DI needed: all three apps and every activity share it). */
object IntegrityGates {
    @Volatile private var instance: IntegrityGateController? = null
    @Volatile private var exemptionStore: LabExemptionStore? = null

    fun get(context: Context): IntegrityGateController = instance ?: synchronized(this) {
        instance ?: build(context.applicationContext).also { instance = it }
    }

    /** The store the bundle/config layer writes a server grant into (`grant(untilUtcMs)`); null until [get] ran. */
    fun exemptionStore(context: Context): LabExemptionStore { get(context); return exemptionStore!! }

    /** The pending events for the next device status (slice 3 wires them to the contract). */
    fun eventLog(context: Context): IntegrityEventLog = IntegrityEventLog(dir(context))

    private fun dir(context: Context) = File(context.applicationContext.noBackupFilesDir, "aron/integrity-gate")

    private fun build(app: Context): IntegrityGateController {
        val dir = dir(app)
        val clock = AndroidGateClock(app)
        val store = LabExemptionStore(dir, clock).also { exemptionStore = it }
        val reader = AndroidDevFlagsReader(app)
        // FLAG_DEBUGGABLE is set only on debug builds of this app; a release APK is never exempt by it.
        val debug = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
        return IntegrityGateController(reader::read, store, IntegrityEventLog(dir), dir, debug, version, clock::wallMs)
    }
}
