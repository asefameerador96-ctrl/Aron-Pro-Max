package com.aktcl.aron.dpc.mdm

import com.aktcl.aron.rules.BusinessDate
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Contract `MdmBlockWindow`: ISO weekdays (1 = Monday), Dhaka `HH:MM`; end <= start runs past midnight into the next day. */
@Serializable
data class MdmBlockWindow(val days: List<Int>, val start: String, val end: String, val packages: List<String> = emptyList())

@Serializable
data class MdmKiosk(val enabled: Boolean = false, val packages: List<String> = emptyList())

@Serializable
data class MdmLocationSampling(val mode: String = "on_demand", @SerialName("interval_min") val intervalMin: Int? = null)

/** The device-side copy of contract `MdmPolicy` (the editable fields only), delivered by an `apply_policy` command. */
@Serializable
data class MdmPolicyDoc(
    @SerialName("allowed_packages") val allowedPackages: List<String> = emptyList(),
    @SerialName("block_windows") val blockWindows: List<MdmBlockWindow> = emptyList(),
    val kiosk: MdmKiosk = MdmKiosk(),
    @SerialName("location_sampling") val locationSampling: MdmLocationSampling = MdmLocationSampling(),
)

/** Block-window arithmetic on Dhaka time (UTC+6, no DST), from the trusted clock. */
object MdmWindows {
    private const val DAY_MIN = 1440
    private const val WEEK_MIN = 7 * DAY_MIN

    fun minutes(hhmm: String): Int? =
        Regex("""^([01]\d|2[0-3]):([0-5]\d)$""").matchEntire(hhmm)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }

    /** Minute of the Dhaka week, Monday 00:00 = 0. */
    fun minuteOfWeek(nowMs: Long): Int {
        val local = nowMs + BusinessDate.DHAKA_OFFSET_MS
        val days = Math.floorDiv(local, 86_400_000L)
        val isoDay = Math.floorMod(days + 3, 7L).toInt() // 1970-01-01 was a Thursday; Monday = 0
        val minOfDay = (Math.floorMod(local, 86_400_000L) / 60_000L).toInt()
        return isoDay * DAY_MIN + minOfDay
    }

    /** True when [nowMs] lies in [start, end) of [w]; an overnight window also covers the early hours of the day after a listed day. */
    fun isActive(w: MdmBlockWindow, nowMs: Long): Boolean {
        val s = minutes(w.start) ?: return false
        val e = minutes(w.end) ?: return false
        if (s == e) return false
        val mow = minuteOfWeek(nowMs)
        val len = if (e > s) e - s else DAY_MIN - s + e
        return w.days.filter { it in 1..7 }.any { d -> Math.floorMod(mow - ((d - 1) * DAY_MIN + s), WEEK_MIN) < len }
    }

    fun active(doc: MdmPolicyDoc, nowMs: Long): List<MdmBlockWindow> = doc.blockWindows.filter { isActive(it, nowMs) }

    /** Next start or end of any window after [nowMs] (epoch ms), within a week; null when there are no windows. */
    fun nextBoundaryMs(doc: MdmPolicyDoc, nowMs: Long): Long? {
        val edges = doc.blockWindows.flatMap { w ->
            val s = minutes(w.start)
            val e = minutes(w.end)
            if (s == null || e == null || s == e) emptyList() else w.days.filter { it in 1..7 }.flatMap { d -> listOf((d - 1) * DAY_MIN + s, (d - 1) * DAY_MIN + e) }
        }.map { it % WEEK_MIN }.distinct()
        if (edges.isEmpty()) return null
        val mow = minuteOfWeek(nowMs)
        val deltaMin = edges.minOf { Math.floorMod(it - mow, WEEK_MIN).let { d -> if (d == 0) WEEK_MIN else d } }
        return Math.floorDiv(nowMs, 60_000L) * 60_000L + deltaMin * 60_000L
    }
}

/** Stores the policy doc and its identity in one atomically replaced file; an older version of the same policy is refused. */
class MdmPolicyStore(private val dir: File) {
    @Serializable
    private data class Stored(@SerialName("policy_id") val policyId: Long, val version: Int, val doc: MdmPolicyDoc)

    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(dir, "mdm-policy.json")

    @Synchronized
    fun load(): MdmPolicyDoc? = read()?.doc

    @Synchronized
    fun version(): Pair<Long, Int>? = read()?.let { it.policyId to it.version }

    private fun read(): Stored? = file.takeIf { it.isFile }?.let { runCatching { json.decodeFromString(Stored.serializer(), it.readText()) }.getOrNull() }

    /** Returns true when stored; false for an older version of the same policy (a late or replayed command). */
    @Synchronized
    fun save(policyId: Long, version: Int, doc: MdmPolicyDoc): Boolean {
        val cur = read()
        if (cur != null && cur.policyId == policyId && cur.version > version) return false
        dir.mkdirs()
        val tmp = File(dir, "mdm-policy.json.tmp")
        tmp.writeText(json.encodeToString(Stored.serializer(), Stored(policyId, version, doc)))
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "cannot store the mdm policy" } }
        return true
    }

    @Synchronized
    fun clear() { file.delete() }
}
