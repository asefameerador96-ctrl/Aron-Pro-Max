package com.aktcl.aron.core.session

import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.network.ApiResponseListener
import com.aktcl.aron.core.network.ResponseMeta
import com.aktcl.aron.rules.BusinessDate
import com.aktcl.aron.rules.TimeAnchor
import kotlinx.datetime.LocalDate
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Trusted time of docs/24 s3.8 item 2 (F-SYS-049). Every API response's `X-Server-Time` becomes an anchor
 * `(server_time, elapsedRealtime, boot_count)`; trusted now = anchor.server_time + (elapsedRealtime − anchor.elapsed) while
 * the boot is the same. A phone clock set wrong by hours therefore changes nothing: the business date and the 17:00 gate
 * follow server time plus elapsed time. After a reboot with no new anchor it falls back to the wall clock and
 * [clockOffsetMs] is null (records then carry `clock_offset_ms = null`). Anchors persist across process death.
 */
class TrustedClockSource(
    private val file: File,
    /** `Settings.Global.BOOT_COUNT` on the phone; 0 or less means unknown. */
    private val bootCount: () -> Int,
    private val elapsedRealtime: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    /** A boot identifier (`/proc/sys/kernel/random/boot_id`) for phones without BOOT_COUNT; null when unreadable. */
    private val bootId: () -> String? = { null },
) : WallClock, ApiResponseListener {
    private val lock = Any()

    /**
     * One time estimate: [serverMs] at [elapsedMs] of a boot. [inProcess] marks estimates taken by this process (always this
     * boot); [bootWallMs] (wall − elapsed when taken) and [bootIdText] identify the boot of a loaded one when BOOT_COUNT is unknown.
     */
    private data class Est(val bootCount: Int, val serverMs: Long, val elapsedMs: Long, val bootWallMs: Long?, val bootIdText: String?, val inProcess: Boolean) {
        fun at(elapsed: Long) = serverMs + (elapsed - elapsedMs)
    }

    /** Read once per process: the boot cannot change while the process lives. */
    private val bootIdOnce: String? by lazy { bootId() }

    @Volatile private var best: Est? = null
    @Volatile private var candidate: Est? = null
    @Volatile private var recent: List<TimeAnchor> = emptyList()

    init {
        load()
    }

    override fun nowMs(): Long = trusted().first

    override fun wallClockMs(): Long = wallClock()

    override fun elapsedRealtimeMs(): Long = elapsedRealtime()

    /** Trusted minus wall clock, or null when no anchor of this boot exists. */
    fun clockOffsetMs(): Long? = trusted().second

    fun bootCountNow(): Int = bootCount()

    /** Today's Dhaka business date on trusted time. */
    fun businessDate(cutoffMinutes: Int = 0): LocalDate = BusinessDate.of(nowMs(), cutoffMinutes)

    /** True at or after [time] (Dhaka local, e.g. the 17:00 check-out gate) on trusted time, on today's business date. */
    fun isAtOrAfterDhaka(time: LocalTime): Boolean =
        !Instant.ofEpochMilli(nowMs()).atOffset(DHAKA).toLocalTime().isBefore(time)

    /** The most recently recorded anchors (at most 3) for a batch's `time_anchors`. */
    fun recentAnchors(): List<TimeAnchor> = recent

    override fun onApiResponse(meta: ResponseMeta) {
        val serverMs = meta.serverTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return
        record(TimeAnchor(bootCount(), serverMs, elapsedRealtime()))
    }

    /**
     * Takes one server-time observation. A slightly older reading (a reply that was slow to arrive) is ignored, so time never
     * steps back by it; a newer one moves the estimate forward; a reading more than [JUMP_MS] away in either direction is
     * adopted only when it is closer to the phone's own clock than the estimate or a second reading agrees (one bad server
     * clock cannot move the day).
     */
    fun record(anchor: TimeAnchor) = synchronized(lock) {
        val e = Est(anchor.bootCount, anchor.serverTimeMs, anchor.elapsedMs, wallClock() - anchor.elapsedMs, bootIdOnce, inProcess = true)
        val current = best?.takeIf { valid(it) }
        if (current == null) {
            best = e; candidate = null
        } else {
            val derived = current.at(e.elapsedMs)
            when {
                kotlin.math.abs(e.serverMs - derived) > JUMP_MS -> {
                    // One reading far from the estimate, either way: adopt it at once only when it is closer to the phone's own
                    // clock than the estimate is (the estimate itself came from a bad reading); otherwise a second reading must agree.
                    val wallNow = wallClock()
                    val c = candidate?.takeIf { valid(it) }
                    when {
                        kotlin.math.abs(e.serverMs - wallNow) < kotlin.math.abs(derived - wallNow) -> { best = e; candidate = null }
                        c != null && kotlin.math.abs(c.at(e.elapsedMs) - e.serverMs) <= JUMP_MS -> { best = e; candidate = null }
                        else -> candidate = e
                    }
                }
                e.serverMs >= derived -> { best = e; candidate = null }
                else -> best = current.copy(inProcess = true) // a late reply confirms the estimate for this boot; time never steps back
            }
        }
        recent = (recent + anchor).takeLast(MAX_SENT)
        save()
    }

    private fun valid(est: Est): Boolean {
        val elapsed = elapsedRealtime()
        if (est.elapsedMs > elapsed) return false // uptime went backwards: a reboot
        val boot = bootCount()
        if (boot > 0) return est.bootCount == boot
        if (est.inProcess) return true
        val id = bootIdOnce
        if (id != null && est.bootIdText != null) return id == est.bootIdText
        val wallBoot = wallClock() - elapsed
        return est.bootWallMs != null && kotlin.math.abs(wallBoot - est.bootWallMs) <= SAME_BOOT_TOLERANCE_MS
    }

    private fun trusted(): Pair<Long, Long?> {
        val wall = wallClock()
        val est = best?.takeIf { valid(it) } ?: return wall to null
        val t = est.at(elapsedRealtime())
        return t to t - wall
    }

    private fun load() {
        val lines = runCatching { file.takeIf { it.exists() }?.readLines() }.getOrNull().orEmpty()
        val anchors = ArrayList<TimeAnchor>()
        for (line in lines) {
            runCatching {
                val p = line.split(',')
                when (p[0]) {
                    "B", "C" -> {
                        val est = Est(p[1].toInt(), p[2].toLong(), p[3].toLong(), p[4].toLongOrNull(), p.getOrNull(5)?.takeIf { it.isNotEmpty() }, inProcess = false)
                        if (p[0] == "B") best = est else candidate = est
                    }
                    "A" -> anchors += TimeAnchor(p[1].toInt(), p[2].toLong(), p[3].toLong())
                    else -> { // the first format: bootCount,serverMs,elapsedMs
                        val a = TimeAnchor(p[0].toInt(), p[1].toLong(), p[2].toLong())
                        anchors += a
                        best = Est(a.bootCount, a.serverTimeMs, a.elapsedMs, null, null, inProcess = false)
                    }
                }
            }
        }
        recent = anchors.takeLast(MAX_SENT)
    }

    private fun save() {
        fun line(tag: String, e: Est) = "$tag,${e.bootCount},${e.serverMs},${e.elapsedMs},${e.bootWallMs ?: ""},${e.bootIdText ?: ""}"
        val text = buildList {
            best?.let { add(line("B", it)) }
            candidate?.let { add(line("C", it)) }
            recent.forEach { add("A,${it.bootCount},${it.serverTimeMs},${it.elapsedMs}") }
        }.joinToString("\n")
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) file.writeText(text)
    }

    private companion object {
        const val MAX_SENT = 3

        /** A reading further than this from the estimate is a correction (behind) or needs confirming (ahead). */
        const val JUMP_MS = 5 * 60_000L

        /** Without BOOT_COUNT, an anchor belongs to this boot when its boot instant (wall − elapsed) agrees within 2 min. */
        const val SAME_BOOT_TOLERANCE_MS = 120_000L
        val DHAKA: ZoneOffset = ZoneOffset.ofHours(6)
    }
}
