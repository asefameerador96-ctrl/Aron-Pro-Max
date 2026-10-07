package com.aktcl.aron.core.map

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One pin and one list row: an SR's last synced fix (AMO Team Location, TSO My Team) or an outlet. [fixAtMs] is the
 * fix time as the server recorded it; [source] is the contract's fix source code (check-in, visit, sync) shown as is.
 */
data class MapPoint(
    val id: String,
    val label: String,
    val lat: Double?,
    val lng: Double?,
    val fixAtMs: Long? = null,
    val source: String? = null,
) {
    val located: Boolean get() = lat != null && lng != null && lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
}

/** "last seen HH:MM (n min ago)" in Dhaka time, greyed after the configured age (docs/17 s10.5; 120 min by default). */
data class FixAge(val clock: String, val minutesAgo: Long, val stale: Boolean) {
    companion object {
        const val DEFAULT_STALE_AFTER_MIN = 120
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Dhaka"))

        /** [nowMs] is trusted time from the caller; a fix stamped in the future reads as 0 minutes, never negative. */
        fun of(fixAtMs: Long?, nowMs: Long, staleAfterMin: Int = DEFAULT_STALE_AFTER_MIN): FixAge? {
            fixAtMs ?: return null
            val minutes = ((nowMs - fixAtMs) / 60_000).coerceAtLeast(0)
            return FixAge(HHMM.format(Instant.ofEpochMilli(fixAtMs)), minutes, minutes >= staleAfterMin)
        }
    }
}

/** Centre and zoom that show every located point (lite mode has no gestures, so the first frame must fit them). */
internal object MapFrame {
    data class Frame(val lat: Double, val lng: Double, val zoom: Float)

    fun of(points: List<MapPoint>): Frame? {
        val p = points.filter { it.located }
        if (p.isEmpty()) return null
        val minLat = p.minOf { it.lat!! }; val maxLat = p.maxOf { it.lat!! }
        val minLng = p.minOf { it.lng!! }; val maxLng = p.maxOf { it.lng!! }
        val spanM = maxOf(
            com.aktcl.aron.rules.Geo.haversineM(minLat, minLng, maxLat, minLng),
            com.aktcl.aron.rules.Geo.haversineM(minLat, minLng, minLat, maxLng),
            1.0,
        )
        // About 40,000 km of world width at zoom 0 on a ~360 dp view; one zoom level halves it. Margin of one level.
        val zoom = (Math.log(40_000_000.0 / spanM) / Math.log(2.0) - 1.0).coerceIn(3.0, 17.0).toFloat()
        return Frame((minLat + maxLat) / 2, (minLng + maxLng) / 2, zoom)
    }
}
