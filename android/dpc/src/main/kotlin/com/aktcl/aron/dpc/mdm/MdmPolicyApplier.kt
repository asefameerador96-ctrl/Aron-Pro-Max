package com.aktcl.aron.dpc.mdm

import com.aktcl.aron.dpc.blocking.SuspendGateway
import com.aktcl.aron.dpc.policy.DevicePolicy
import java.io.File

/** Lock-task (kiosk) calls of the device owner. */
interface KioskGateway {
    fun setLockTaskPackages(packages: List<String>)
    /** Starts [pkg] in lock-task mode. */
    fun startLockTask(pkg: String)
    fun clearLockTask()
}

data class MdmOutcome(
    val activeWindows: Int, val suspended: List<String>, val failed: List<String>, val kioskOn: Boolean, val nextBoundaryMs: Long?,
    /** The work-hours allow-list is blocking now (D-NIGHT-MDM-ALLOWLIST-01). */
    val allowlistActive: Boolean = false,
)

/**
 * Applies the MDM policy on top of the device policy: weekday/time block windows (Dhaka, trusted clock), allowed apps,
 * kiosk and the location sampling mode. Runs from the stored policy, so it works offline; [evaluate] is idempotent and is
 * called at start, boot, on the window-boundary alarm and after an `apply_policy` command. The existing
 * [com.aktcl.aron.dpc.blocking.BlockingEngine] is untouched: packages it holds suspended are never released here
 * ([heldByAttendance]), and the Aron app, the device policy's always-allowed list and the allowed list are never suspended.
 */
class MdmPolicyApplier(
    private val gw: SuspendGateway,
    private val kiosk: KioskGateway,
    private val store: MdmPolicyStore,
    private val dir: File,
    private val trustedNowMs: () -> Long,
    private val devicePolicy: () -> DevicePolicy?,
    private val heldByAttendance: () -> Set<String> = { emptySet() },
    /** Business date to working day from the bundle calendar; null when unknown (the allow-list's `calendar` mode then does not block). */
    private val isWorkingDay: (String) -> Boolean? = { null },
) {
    private val stateFile get() = File(dir, "mdm-suspended.txt")

    private fun suspendedBefore(): List<String> = stateFile.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }.orEmpty()

    private fun saveSuspended(l: List<String>) {
        dir.mkdirs()
        val tmp = File(dir, "mdm-suspended.tmp")
        tmp.writeText(l.joinToString("\n"))
        if (!tmp.renameTo(stateFile)) { stateFile.delete(); check(tmp.renameTo(stateFile)) { "cannot store the mdm state" } }
    }

    /** `on_demand` (default), `off` or `interval`; the geo layer reads this instead of a build constant. */
    fun locationMode(): String = store.load()?.locationSampling?.mode ?: "on_demand"
    fun locationIntervalMin(): Int? = store.load()?.locationSampling?.takeIf { it.mode == "interval" }?.intervalMin

    @Synchronized
    fun evaluate(): MdmOutcome {
        val doc = store.load()
        val now = trustedNowMs()
        val prev = suspendedBefore()
        if (!gw.isDeviceOwner()) return MdmOutcome(0, prev, emptyList(), false, null)
        val held = heldByAttendance()
        val windows = doc?.let { MdmWindows.active(it, now) }.orEmpty()
        val notes = mutableListOf<String>()
        // The protected set is resolved only when something may be suspended; if it cannot be resolved the allow-list
        // (which reaches system apps) does not block this round: failing open never locks the dialler away.
        val wantsAllowlist = MdmAllowlist.isActive(doc?.workHoursAllowlist, now, isWorkingDay)
        val resolved = if (windows.isEmpty() && !wantsAllowlist) emptySet() else runCatching { gw.protectedPackages() }.getOrNull()
        if (resolved == null) notes += PROTECTED_UNRESOLVED
        val allowlistActive = wantsAllowlist && resolved != null
        val kioskPkgs = doc?.kiosk?.takeIf { it.enabled }?.packages.orEmpty()
        val never = doc?.allowedPackages.orEmpty().toSet() + devicePolicy()?.appControl?.alwaysAllowedPackages.orEmpty() + gw.ownPackage +
            kioskPkgs + resolved.orEmpty() + ALWAYS_PROTECTED
        val target = if (windows.isEmpty() && !allowlistActive) emptyList() else {
            val installed = gw.installed()
            val names = installed.map { it.packageName }.toSet()
            val fromWindows = windows.flatMap { w ->
                if (w.packages.isEmpty()) installed.filter { it.launchable && !it.system }.map { it.packageName } else w.packages.filter { it in names }
            }
            // The allow-list reaches every launcher-visible app, system apps included (YouTube, Chrome, the camera).
            val fromAllowlist = if (allowlistActive) {
                val allowed = doc!!.workHoursAllowlist!!.packages.toSet()
                installed.filter { it.launchable && it.packageName !in allowed }.map { it.packageName }
            } else emptyList()
            (fromWindows + fromAllowlist).filter { it !in never && !isProtected(it) }.distinct().sorted()
        }
        val toRelease = prev.filter { it !in target && it !in held }
        // Write-ahead like BlockingEngine: record before suspending so a crash never strands a suspended app.
        saveSuspended((prev + target).distinct().sorted())
        val failedRelease = if (toRelease.isNotEmpty()) runCatching { gw.setSuspended(toRelease, false) }.getOrDefault(toRelease) else emptyList()
        val failedSuspend = if (target.isNotEmpty()) runCatching { gw.setSuspended(target, true) }.getOrDefault(target) else emptyList()
        val after = (target + failedRelease).distinct().sorted()
        saveSuspended(after)
        val kioskOn = applyKiosk(doc?.kiosk)
        return MdmOutcome(windows.size, after, (failedRelease + failedSuspend + notes).distinct(), kioskOn, doc?.let { MdmWindows.nextBoundaryMs(it, now) }, allowlistActive)
    }

    private fun applyKiosk(k: MdmKiosk?): Boolean = runCatching {
        if (k != null && k.enabled && k.packages.isNotEmpty()) {
            kiosk.setLockTaskPackages(k.packages + gw.ownPackage)
            kiosk.startLockTask(k.packages.first())
            true
        } else { kiosk.clearLockTask(); false }
    }.getOrDefault(false)

    companion object {
        /** Reported in [MdmOutcome.failed] when the protected set could not be resolved (the allow-list did not block). */
        const val PROTECTED_UNRESOLVED = "protected_unresolved"

        /** Every ARON app and its test copies. */
        const val ARON_PREFIX = "com.aktcl.aron."

        /**
         * Never suspended by MDM, whatever the policy or the phone's resolvers say: system UI, Settings, telephony and the
         * common diallers, emergency and cell broadcast, the installer and permission controller, the AOSP launcher.
         */
        val ALWAYS_PROTECTED = setOf(
            "android", "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.server.telecom",
            "com.android.dialer", "com.google.android.dialer", "com.android.incallui", "com.samsung.android.dialer",
            "com.huawei.contacts", "com.hihonor.contacts", "com.android.contacts", "com.android.emergency",
            "com.google.android.apps.safetyhub", "com.android.cellbroadcastreceiver", "com.google.android.cellbroadcastreceiver",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.packageinstaller",
            "com.google.android.packageinstaller", "com.android.launcher3", "com.android.inputmethod.latin",
        )

        fun isProtected(pkg: String): Boolean = pkg in ALWAYS_PROTECTED || pkg.startsWith(ARON_PREFIX)
    }
}
