package com.aktcl.aron.dpc.release

import java.io.File

/** What the release needs from the platform; the Android version is [AndroidOwnerGateway], tests use a fake. */
interface OwnerGateway {
    fun isDeviceOwner(): Boolean
    /** Packages worth un-suspending that the platform lets this app see (may be incomplete without package visibility). */
    fun visiblePackages(): List<String>
    /** Un-suspends [packages]; returns the ones the platform could not change. */
    fun unsuspend(packages: List<String>): List<String>
    /** True while [pkg] is installed and still suspended; false when it is not installed any more. */
    fun stillSuspended(pkg: String): Boolean
    fun clearLockTask()
    fun clearRestrictions()
    fun unblockUninstallAndUserControl()
    fun resetPermissionPins()
    /** Switches USB debugging off (only used when the old policy had forced it ON, see [DeviceOwnerRelease]). */
    fun turnAdbOff()
    /** `clearDeviceOwnerApp(ownPackage)`. */
    fun clearDeviceOwner()
}

/**
 * Releases device-owner mode on the first start after an upgrade (D-OWNER-MDM-02). Idempotent and kill-safe: the "done"
 * flag is written last, so a kill at any point repeats the steps from the top, and every step is harmless to repeat.
 * Order matters: apps this app suspended are un-suspended BEFORE ownership is cleared (a suspension would otherwise
 * outlive its owner and strand the rep without the phone's apps). If packages cannot be un-suspended the release waits
 * for the next start, at most [MAX_ATTEMPTS] starts, then clears anyway and says so in [Outcome.PartialReleased].
 */
class DeviceOwnerRelease(
    private val gateway: OwnerGateway,
    private val dir: File,
    /** Files the old policy code kept the suspended package names in (read format-agnostically). */
    private val stateFiles: List<String> = listOf("blocking-state.txt", "mdm-suspended.txt"),
    /**
     * False in debuggable builds (lab phones keep USB debugging for their ADB walks; the gate does not apply to them).
     * In a release build, USB debugging that the OLD policy had forced ON is switched off again, because without the
     * policy it would be off and the Developer options gate would otherwise lock the phone right after the upgrade.
     */
    private val restoreAdb: Boolean = true,
) {
    enum class Outcome { ALREADY_DONE, NOT_OWNER, RELEASED, PARTIAL_RELEASED, WAITING }

    private val doneFile get() = File(dir, "owner-release-done.txt")
    private val attemptsFile get() = File(dir, "owner-release-attempts.txt")

    fun isDone(): Boolean = doneFile.isFile

    @Synchronized
    fun run(): Outcome {
        if (isDone()) return Outcome.ALREADY_DONE
        // A platform error is not "not owner": the flag is written only on a real answer, so the next start tries again.
        val owner = try { gateway.isDeviceOwner() } catch (e: Exception) { return Outcome.WAITING }
        if (!owner) { markDone("not_owner"); return Outcome.NOT_OWNER }

        val candidates = (storedSuspended() + runCatching { gateway.visiblePackages() }.getOrDefault(emptyList())).distinct()
        val failed = runCatching { gateway.unsuspend(candidates) }.getOrDefault(candidates)
        val stuck = failed.filter { runCatching { gateway.stillSuspended(it) }.getOrDefault(true) }
        runCatching { gateway.clearLockTask() }
        runCatching { gateway.clearRestrictions() }
        runCatching { gateway.unblockUninstallAndUserControl() }
        runCatching { gateway.resetPermissionPins() }
        val adbOff = restoreAdb && policyForcedAdbOn() && runCatching { gateway.turnAdbOff() }.isSuccess

        if (stuck.isNotEmpty()) {
            val attempts = bumpAttempts()
            if (attempts < MAX_ATTEMPTS) return Outcome.WAITING
            clearOwnerAndMark("partial:${stuck.size}" + if (adbOff) "+adb_off" else "")
            return Outcome.PARTIAL_RELEASED
        }
        clearOwnerAndMark("released" + if (adbOff) "+adb_off" else "")
        return Outcome.RELEASED
    }

    private fun clearOwnerAndMark(note: String) {
        // Only when ownership is really gone is the flag written; a throw leaves the flag missing and the next start retries.
        gateway.clearDeviceOwner()
        markDone(note)
    }

    /** True when the stored old policy forced `adb_enabled` ON (a dev-lockdown phone); the app itself set it, so the app undoes it. */
    private fun policyForcedAdbOn(): Boolean = runCatching {
        val text = File(dir, "device-policy.json").takeIf { it.isFile }?.readText() ?: return false
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        val g = root["global_settings"] as? kotlinx.serialization.json.JsonObject
        (g?.get("adb_enabled") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    }.getOrDefault(false)

    private fun storedSuspended(): List<String> = stateFiles.flatMap { name ->
        runCatching { File(dir, name).takeIf { it.isFile }?.readText().orEmpty() }.getOrDefault("")
            .split(Regex("[^A-Za-z0-9._]+")).filter { PACKAGE.matches(it) }
    }

    private fun bumpAttempts(): Int {
        val n = (runCatching { attemptsFile.takeIf { it.isFile }?.readText()?.trim()?.toInt() }.getOrNull() ?: 0) + 1
        runCatching { dir.mkdirs(); attemptsFile.writeText(n.toString()) }
        return n
    }

    private fun markDone(note: String) {
        dir.mkdirs()
        val tmp = File(dir, "owner-release-done.tmp")
        tmp.writeText(note)
        if (!tmp.renameTo(doneFile)) { doneFile.delete(); check(tmp.renameTo(doneFile)) { "cannot store the release flag" } }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    }
}
