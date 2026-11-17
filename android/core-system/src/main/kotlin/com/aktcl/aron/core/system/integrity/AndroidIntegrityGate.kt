package com.aktcl.aron.core.system.integrity

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.aktcl.aron.core.common.ActionGate
import android.os.Build
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

/** Reads `DEVELOPMENT_SETTINGS_ENABLED`, `ADB_ENABLED` and `adb_wifi_enabled` (API 30+). An unreadable setting counts as off (a phone must not be locked out by a read error). */
class AndroidDevFlagsReader(context: Context) {
    private val cr = context.applicationContext.contentResolver
    fun read(): DevFlags {
        fun on(name: String) = runCatching { Settings.Global.getInt(cr, name, 0) == 1 }.getOrDefault(false)
        return DevFlags(
            devOptions = on(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED), adb = on(Settings.Global.ADB_ENABLED),
            adbWifi = Build.VERSION.SDK_INT >= 30 && on(ADB_WIFI_ENABLED),
        )
    }

    companion object {
        /** `Settings.Global.ADB_WIFI_ENABLED` (API 30), spelled out so older compile targets and tests see the same name. */
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        /** Every setting the gate reads and observes. */
        val SETTINGS = listOf(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, Settings.Global.ADB_ENABLED, ADB_WIFI_ENABLED)
    }
}

/** The process-wide gate, built once from the application context (no DI needed: all three apps and every activity share it). */
object IntegrityGates {
    /** Manifest meta-data set by the apps' build: `"true"` only in the benchmark build types. */
    const val META_EXEMPT = "com.aktcl.aron.integrity_gate_exempt"

    @Volatile private var instance: IntegrityGateController? = null
    @Volatile private var exemptionStore: LabExemptionStore? = null
    @Volatile private var sharedLog: IntegrityEventLog? = null

    /**
     * "Sync now" from the gate screen (set once in `Application.onCreate`): lets queued records upload at once while the
     * gate is up. Null until set; the gate screen shows the button only when it is set.
     */
    @Volatile var syncNow: (() -> Unit)? = null

    /** Installed by the visible gate: asked to re-check at once when a writer found the gate closed (the race window). */
    @Volatile var refreshNow: () -> Unit = {}

    /** `Application.onCreate` of the three apps: builds the gate and ActionGate before ANY activity exists, and wires "Sync now". */
    fun install(context: Context, syncNow: (() -> Unit)? = null) {
        get(context)
        this.syncNow = syncNow
    }

    /** Pure: which build exemption a build carries. Debuggable wins; the meta-data must be exactly "true"; release has neither. */
    fun buildExemption(debuggable: Boolean, metaExempt: String?): Exemption? = when {
        debuggable -> Exemption.DEBUG_BUILD
        metaExempt == "true" -> Exemption.BENCHMARK_BUILD
        else -> null
    }

    fun get(context: Context): IntegrityGateController = instance ?: synchronized(this) {
        instance ?: build(context.applicationContext).also { instance = it }
    }

    /** The store the bundle/config layer writes a server grant into (`grant(untilUtcMs)`); null until [get] ran. */
    fun exemptionStore(context: Context): LabExemptionStore { get(context); return exemptionStore!! }

    /** The pending events the device status reporter sends as `integrity_events` (contract 1.19.0): the same instance the gate writes to. */
    fun eventLog(context: Context): IntegrityEventLog { get(context); return sharedLog!! }

    private fun dir(context: Context) = File(context.applicationContext.noBackupFilesDir, "aron/integrity-gate")

    private fun build(app: Context): IntegrityGateController {
        val dir = dir(app)
        val clock = AndroidGateClock(app)
        val store = LabExemptionStore(dir, clock).also { exemptionStore = it }
        val log = IntegrityEventLog(dir).also { sharedLog = it }
        val reader = AndroidDevFlagsReader(app)
        // FLAG_DEBUGGABLE is set only on debug builds of this app; a release APK is never exempt by it.
        val debug = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val meta = runCatching { app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData?.get(META_EXEMPT)?.toString() }.getOrNull()
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
        val controller = IntegrityGateController(reader::read, store, log, dir, buildExemption(debug, meta), version, clock::wallMs)
        // Writers of new records ask at the moment they act (core-common ActionGate), so the gate cannot be raced.
        ActionGate.check = { !controller.check().blocked }
        ActionGate.onClosed = { refreshNow() }
        return controller
    }
}
