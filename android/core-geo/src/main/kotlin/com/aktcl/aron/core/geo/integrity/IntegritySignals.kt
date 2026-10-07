package com.aktcl.aron.core.geo.integrity

import java.io.File

/**
 * Device integrity signals read at login and before each sync batch (F-SYS-031, docs/05). They are evidence for the
 * server's risk rules (DEVICE_DEBUG_ENABLED, DEVICE_MOCK_APP_PRESENT, DEVICE_INTEGRITY_FAIL) and are never used on the
 * phone to block a sale: nothing here returns a decision.
 */
data class IntegritySignals(
    val devOptionsEnabled: Boolean,
    val adbEnabled: Boolean,
    val autoTimeEnabled: Boolean,
    val deviceOwner: Boolean,
    val mockLocationApps: List<String>,
    /** Stable codes from [RootHints]; REQUEST: docs/requests/android-geo-dpc-root-hints.md (no contract member yet). */
    val rootHints: List<String>,
)

/** What the root-hint rules look at; the Android implementation is [AndroidRootProbe]. */
interface RootProbe {
    fun exists(path: String): Boolean
    val buildTags: String?
    fun systemProperty(name: String): String?
    fun installed(packageName: String): Boolean
    fun mounts(): String
    /** Android user id of this process (0 = the phone's main user). */
    val userId: Int
    val dataDir: String
}

/**
 * Root, hook and app-clone hints. Each is a hint, not proof: a careful rooter hides all of them, and a hardware-level
 * attack shows none (docs/status/android-geo-dpc.md, "What software cannot stop"). Play Integrity is the stronger signal.
 */
object RootHints {
    val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/data/local/xbin/su", "/data/local/bin/su",
        "/system/sd/xbin/su", "/vendor/bin/su", "/system/bin/.ext/su", "/system/usr/we-need-root/su",
    )
    val ROOT_APPS = listOf(
        "com.topjohnwu.magisk", "io.github.vvb2060.magisk", "io.github.huskydg.magisk", "me.weishu.kernelsu",
        "me.bmax.apatch", "eu.chainfire.supersu", "com.koushikdutta.superuser", "com.noshufou.android.su",
        "com.thirdparty.superuser", "com.kingroot.kinguser", "com.kingo.root",
    )
    val HOOK_APPS = listOf("de.robv.android.xposed.installer", "org.lsposed.manager", "org.meowcat.edxposed.manager", "com.saurik.substrate")
    val CLONE_APPS = listOf(
        "com.lbe.parallel.intl", "com.parallel.space.lite", "com.excelliance.dualaid", "com.ludashi.dualspace",
        "com.oasisfeng.island", "com.applisto.appcloner", "com.app.hider.master.dual.app", "com.polestar.multiaccount",
        "info.cloneapp.mochat.in.goast", "com.cloneapp.parallelspace.dualspace", "com.vmos.pro", "com.f1player",
    )

    fun evaluate(p: RootProbe, ownPackage: String): List<String> {
        val hints = mutableListOf<String>()
        fun safe(block: () -> Boolean) = runCatching(block).getOrDefault(false)
        if (SU_PATHS.any { safe { p.exists(it) } }) hints += "su_binary"
        if (safe { p.buildTags?.contains("test-keys") == true }) hints += "test_keys"
        if (safe { p.systemProperty("ro.debuggable") == "1" }) hints += "ro_debuggable"
        if (safe { p.systemProperty("ro.secure") == "0" }) hints += "ro_secure_off"
        if (ROOT_APPS.any { safe { p.installed(it) } }) hints += "root_app"
        if (HOOK_APPS.any { safe { p.installed(it) } }) hints += "hook_framework"
        if (safe { p.mounts().let { m -> "magisk" in m || "/sbin/.magisk" in m || "kernelsu" in m } }) hints += "root_mount"
        if (CLONE_APPS.any { safe { p.installed(it) } }) hints += "clone_app_installed"
        // A cloned or work-profile copy of the app runs as another Android user or from a foreign data directory.
        if (safe { p.userId != 0 }) hints += "secondary_user"
        if (safe { !(p.dataDir.startsWith("/data/user/0/$ownPackage") || p.dataDir.startsWith("/data/data/$ownPackage")) }) {
            hints += "foreign_data_dir"
        }
        return hints
    }
}

/**
 * Remembers the last signals sent so a change is reported as a `device_status` with trigger `integrity_change`
 * (docs/24 s10.3) before the next batch, and only once per change.
 */
class IntegritySignalsTracker(private val dir: File) {
    private val file get() = File(dir, "integrity-signals.txt")

    private fun fingerprint(s: IntegritySignals): String = listOf(
        "dev=${s.devOptionsEnabled}", "adb=${s.adbEnabled}", "time=${s.autoTimeEnabled}", "owner=${s.deviceOwner}",
        "mock=${s.mockLocationApps.sorted().joinToString(",")}", "root=${s.rootHints.sorted().joinToString(",")}",
    ).joinToString("\n")

    /** True when [current] differs from what was last recorded (or nothing was recorded); then records it. */
    @Synchronized
    fun changed(current: IntegritySignals): Boolean {
        val fp = fingerprint(current)
        val previous = file.takeIf { it.isFile }?.readText()
        if (previous == fp) return false
        dir.mkdirs()
        val tmp = File(dir, "integrity-signals.tmp")
        tmp.writeText(fp)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        return true
    }
}
