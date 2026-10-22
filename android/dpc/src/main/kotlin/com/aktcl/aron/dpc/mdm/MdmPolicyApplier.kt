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

data class MdmOutcome(val activeWindows: Int, val suspended: List<String>, val failed: List<String>, val kioskOn: Boolean, val nextBoundaryMs: Long?)

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
        val never = doc?.allowedPackages.orEmpty().toSet() + devicePolicy()?.appControl?.alwaysAllowedPackages.orEmpty() + gw.ownPackage
        val target = if (windows.isEmpty()) emptyList() else {
            val installed = gw.installed()
            val names = installed.map { it.packageName }.toSet()
            windows.flatMap { w ->
                if (w.packages.isEmpty()) installed.filter { it.launchable && !it.system }.map { it.packageName } else w.packages.filter { it in names }
            }.filter { it !in never }.distinct().sorted()
        }
        val toRelease = prev.filter { it !in target && it !in held }
        // Write-ahead like BlockingEngine: record before suspending so a crash never strands a suspended app.
        saveSuspended((prev + target).distinct().sorted())
        val failedRelease = if (toRelease.isNotEmpty()) runCatching { gw.setSuspended(toRelease, false) }.getOrDefault(toRelease) else emptyList()
        val failedSuspend = if (target.isNotEmpty()) runCatching { gw.setSuspended(target, true) }.getOrDefault(target) else emptyList()
        val after = (target + failedRelease).distinct().sorted()
        saveSuspended(after)
        val kioskOn = applyKiosk(doc?.kiosk)
        return MdmOutcome(windows.size, after, (failedRelease + failedSuspend).distinct(), kioskOn, doc?.let { MdmWindows.nextBoundaryMs(it, now) })
    }

    private fun applyKiosk(k: MdmKiosk?): Boolean = runCatching {
        if (k != null && k.enabled && k.packages.isNotEmpty()) {
            kiosk.setLockTaskPackages(k.packages + gw.ownPackage)
            kiosk.startLockTask(k.packages.first())
            true
        } else { kiosk.clearLockTask(); false }
    }.getOrDefault(false)
}
