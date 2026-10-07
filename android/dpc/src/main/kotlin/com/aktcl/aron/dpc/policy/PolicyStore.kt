package com.aktcl.aron.dpc.policy

import java.io.File

/**
 * The received policy, kept on the phone so it is applied on boot and app start with no network (docs/24 s10.5).
 * One JSON file in no-backup storage, replaced atomically. A policy older than the stored one is refused, so a late or
 * replayed delivery cannot loosen the phone. (docs/24 s5.2 names `aron-device.db`; that database does not exist yet:
 * see docs/status/android-geo-dpc.md GD-05.)
 */
class PolicyStore(private val dir: File) {
    private val file get() = File(dir, "device-policy.json")

    @Synchronized
    fun load(): DevicePolicy? = file.takeIf { it.isFile }?.let { runCatching { DevicePolicy.parse(it.readText()) }.getOrNull() }

    /** Stores [policy] unless a newer version is already stored; returns the policy now in force. */
    @Synchronized
    fun save(policy: DevicePolicy): DevicePolicy {
        val current = load()
        if (current != null && current.policyVersion > policy.policyVersion) return current
        dir.mkdirs()
        val tmp = File(dir, "device-policy.json.tmp")
        tmp.writeText(policy.encode())
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "cannot store the device policy" } }
        return policy
    }
}
