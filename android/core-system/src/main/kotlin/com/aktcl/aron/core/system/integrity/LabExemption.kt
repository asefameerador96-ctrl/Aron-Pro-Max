package com.aktcl.aron.core.system.integrity

import java.io.File

/** Clocks the exemption is judged on; tests drive them. */
interface GateClock {
    fun wallMs(): Long
    /** SystemClock.elapsedRealtime(): monotonic since boot. */
    fun elapsedMs(): Long
    /** Settings.Global.BOOT_COUNT, or -1 when unreadable. */
    fun bootCount(): Int
}

/**
 * The per-device lab exemption (D-OWNER-MDM-02 iii): granted by the server for this device, delivered in the bundle
 * config, stored here with an expiry (at most [MAX_LOCAL_MS], default 7 days, refreshed online). FAIL CLOSED: a missing,
 * expired, unreadable, or clock-inconsistent record is no exemption, and an inconsistent record is deleted.
 *
 * Clock changes cannot extend it: (1) the wall clock moving back past a high-water mark kept on the phone voids it;
 * (2) inside one boot, the monotonic clock bounds how long it can have been held; (3) it never outlives `until`.
 */
class LabExemptionStore(private val dir: File, private val clock: GateClock) {
    private val file get() = File(dir, "lab-exemption.txt")

    /** Stores a grant until [untilUtcMs] (capped to [MAX_LOCAL_MS] from now). A grant already in the past clears it. */
    @Synchronized
    fun grant(untilUtcMs: Long) {
        val now = clock.wallMs()
        val until = minOf(untilUtcMs, now + MAX_LOCAL_MS)
        if (until <= now) { clear(); return }
        write(Record(until = until, issued = now, elapsedAtStore = clock.elapsedMs(), boot = clock.bootCount(), highWater = now))
    }

    @Synchronized
    fun clear() { runCatching { file.delete() } }

    @Synchronized
    fun isActive(): Boolean {
        val r = read() ?: return false
        val now = clock.wallMs()
        val elapsed = clock.elapsedMs()
        val boot = clock.bootCount()
        val backwards = now < r.highWater - TOLERANCE_MS || now < r.issued - TOLERANCE_MS
        val expired = now >= r.until
        val heldTooLong = boot != -1 && boot == r.boot && (elapsed < r.elapsedAtStore || elapsed - r.elapsedAtStore > (r.until - r.issued) + TOLERANCE_MS)
        if (backwards || heldTooLong) { clear(); return false }
        if (expired) { clear(); return false }
        if (now > r.highWater + HIGH_WATER_STEP_MS) write(r.copy(highWater = now))
        return true
    }

    private data class Record(val until: Long, val issued: Long, val elapsedAtStore: Long, val boot: Int, val highWater: Long)

    private fun read(): Record? = runCatching {
        val m = file.takeIf { it.isFile }?.readText()?.split('\n')
            ?.mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap() ?: return null
        Record(m.getValue("until").toLong(), m.getValue("issued").toLong(), m.getValue("elapsed").toLong(), m.getValue("boot").toInt(), m.getValue("hw").toLong())
    }.getOrNull()

    private fun write(r: Record) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "lab-exemption.tmp")
            tmp.writeText(listOf("until" to r.until, "issued" to r.issued, "elapsed" to r.elapsedAtStore, "boot" to r.boot, "hw" to r.highWater).joinToString("\n") { (k, v) -> k + "=" + v })
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object {
        const val MAX_LOCAL_MS = 7L * 24 * 3600_000
        private const val TOLERANCE_MS = 5 * 60_000L
        private const val HIGH_WATER_STEP_MS = 60_000L
    }
}
