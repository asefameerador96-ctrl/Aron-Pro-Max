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
    /** `Settings.Global.BOOT_COUNT` on the phone. */
    private val bootCount: () -> Int,
    private val elapsedRealtime: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
) : WallClock, ApiResponseListener {
    private val lock = Any()

    /** One stored anchor: [bootWallMs] = wall − elapsed when it was taken (identifies the boot when BOOT_COUNT is unknown). */
    private data class Stored(val anchor: TimeAnchor, val bootWallMs: Long?, val seq: Long)

    @Volatile private var stored: List<Stored> = load()

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
    fun recentAnchors(): List<TimeAnchor> = stored.sortedBy { it.seq }.takeLast(MAX_SENT).map { it.anchor }

    override fun onApiResponse(meta: ResponseMeta) {
        val serverMs = meta.serverTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return
        record(TimeAnchor(bootCount(), serverMs, elapsedRealtime()))
    }

    fun record(anchor: TimeAnchor) = synchronized(lock) {
        val seq = (stored.maxOfOrNull { it.seq } ?: 0) + 1
        stored = (stored + Stored(anchor, wallClock() - anchor.elapsedMs, seq)).sortedBy { it.seq }.takeLast(MAX_KEPT)
        save(stored)
    }

    /**
     * Trusted now and the offset. Server time + elapsed never runs ahead of true time (a reply's server time is at most as old
     * as the reply), so the largest value over this boot's anchors is the best and it never moves backwards when a slow
     * reply arrives late (F-SYS-049 checker).
     */
    private fun trusted(): Pair<Long, Long?> {
        val boot = bootCount()
        val elapsed = elapsedRealtime()
        val wall = wallClock()
        val best = stored.filter { s ->
            s.anchor.elapsedMs <= elapsed && if (boot > 0) s.anchor.bootCount == boot else s.anchor.bootCount <= 0 &&
                s.bootWallMs != null && kotlin.math.abs((wall - elapsed) - s.bootWallMs) <= SAME_BOOT_TOLERANCE_MS
        }.maxOfOrNull { it.anchor.serverTimeMs + (elapsed - it.anchor.elapsedMs) } ?: return wall to null
        return best to best - wall
    }

    private fun load(): List<Stored> {
        val lines = runCatching { file.takeIf { it.exists() }?.readLines() }.getOrNull().orEmpty()
        return lines.mapIndexedNotNull { i, line ->
            runCatching {
                val p = line.split(',')
                val anchor = TimeAnchor(p[0].toInt(), p[1].toLong(), p[2].toLong())
                Stored(anchor, p.getOrNull(3)?.toLongOrNull(), p.getOrNull(4)?.toLong() ?: i.toLong())
            }.getOrNull()
        }
    }

    private fun save(list: List<Stored>) {
        file.parentFile?.mkdirs()
        val text = list.joinToString("\n") { "${it.anchor.bootCount},${it.anchor.serverTimeMs},${it.anchor.elapsedMs},${it.bootWallMs ?: ""},${it.seq}" }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) file.writeText(text)
    }

    private companion object {
        const val MAX_KEPT = 6
        const val MAX_SENT = 3

        /** Without BOOT_COUNT, an anchor belongs to this boot when its boot instant (wall − elapsed) agrees within 2 min. */
        const val SAME_BOOT_TOLERANCE_MS = 120_000L
        val DHAKA: ZoneOffset = ZoneOffset.ofHours(6)
    }
}
