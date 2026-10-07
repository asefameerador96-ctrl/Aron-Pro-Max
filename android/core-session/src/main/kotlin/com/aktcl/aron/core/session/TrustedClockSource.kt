package com.aktcl.aron.core.session

import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.network.ApiResponseListener
import com.aktcl.aron.core.network.ResponseMeta
import com.aktcl.aron.rules.BusinessDate
import com.aktcl.aron.rules.TimeAnchor
import com.aktcl.aron.rules.TrustedClock
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

    @Volatile private var anchors: List<TimeAnchor> = load()

    override fun nowMs(): Long = trusted().epochMs

    override fun elapsedRealtimeMs(): Long = elapsedRealtime()

    /** Trusted minus wall clock, or null when no anchor of this boot exists. */
    fun clockOffsetMs(): Long? = trusted().clockOffsetMs

    fun bootCountNow(): Int = bootCount()

    /** Today's Dhaka business date on trusted time. */
    fun businessDate(cutoffMinutes: Int = 0): LocalDate = BusinessDate.of(nowMs(), cutoffMinutes)

    /** True at or after [time] (Dhaka local, e.g. the 17:00 check-out gate) on trusted time, on today's business date. */
    fun isAtOrAfterDhaka(time: LocalTime): Boolean =
        !Instant.ofEpochMilli(nowMs()).atOffset(DHAKA).toLocalTime().isBefore(time)

    /** The most recent anchors (at most 3) for a batch's `time_anchors`. */
    fun recentAnchors(): List<TimeAnchor> = anchors.sortedWith(compareBy({ it.bootCount }, { it.elapsedMs })).takeLast(MAX_SENT)

    override fun onApiResponse(meta: ResponseMeta) {
        val serverMs = meta.serverTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return
        record(TimeAnchor(bootCount(), serverMs, elapsedRealtime()))
    }

    fun record(anchor: TimeAnchor) = synchronized(lock) {
        // Keep the newest few, current boot first; older boots are useless for deriving now but travel for the server.
        anchors = (anchors + anchor).sortedWith(compareBy({ it.bootCount }, { it.elapsedMs })).takeLast(MAX_KEPT)
        save(anchors)
    }

    private fun trusted() = TrustedClock.trustedNow(anchors, bootCount(), elapsedRealtime(), wallClock())

    private fun load(): List<TimeAnchor> = runCatching {
        file.takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
            val p = line.split(',')
            if (p.size != 3) null else TimeAnchor(p[0].toInt(), p[1].toLong(), p[2].toLong())
        }
    }.getOrNull().orEmpty()

    private fun save(list: List<TimeAnchor>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(list.joinToString("\n") { "${it.bootCount},${it.serverTimeMs},${it.elapsedMs}" })
        tmp.renameTo(file)
    }

    private companion object {
        const val MAX_KEPT = 6
        const val MAX_SENT = 3
        val DHAKA: ZoneOffset = ZoneOffset.ofHours(6)
    }
}
