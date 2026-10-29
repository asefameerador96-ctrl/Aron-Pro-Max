package com.aktcl.aron.core.map

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What a pin stands for (tso-manual A3, D-TSO-MAN-34): its colour on the interactive map. */
enum class MapPointKind { PERSON, OUTLET, SELF }

/**
 * One pin and one list row: an SR's last synced fix (AMO Team Location, TSO My Team) or an outlet. [fixAtMs] is the
 * fix time as the server recorded it; [source] is the contract's fix source code (check_in, visit, check_out,
 * breadcrumb), shown in the language of the app ([MapSources]); any other text (a label the caller already localized)
 * shows as is. [kind] colours the pin; [note] is one more line under the row and in the pin's bubble (the mock-fix chip,
 * an outlet's distance).
 */
data class MapPoint(
    val id: String,
    val label: String,
    val lat: Double?,
    val lng: Double?,
    val fixAtMs: Long? = null,
    val source: String? = null,
    val kind: MapPointKind = MapPointKind.PERSON,
    val note: String? = null,
) {
    val located: Boolean get() = lat != null && lng != null && lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
}

/** A radius drawn around a centre (the TSO's own fix on Retailer, D-TSO-MAN-34); the first frame holds all of it. */
data class MapCircle(val lat: Double, val lng: Double, val radiusM: Int)

/** The contract's fix sources (TeamLocation.last_fix.source) with their words in the app's language. */
object MapSources {
    val CODES = listOf("check_in", "visit", "check_out", "breadcrumb")

    /** The string resource of a known [code]; null for any other text (the caller's own label is then shown as is). */
    fun labelRes(code: String?): Int? = when (code) {
        "check_in" -> R.string.map_source_check_in
        "visit" -> R.string.map_source_visit
        "check_out" -> R.string.map_source_check_out
        "breadcrumb" -> R.string.map_source_breadcrumb
        else -> null
    }
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

    fun of(points: List<MapPoint>, circle: MapCircle? = null): Frame? {
        val p = points.filter { it.located } + (circle?.let { edges(it) } ?: emptyList())
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

    /** The four points of [c] at north, south, east and west, so a frame that holds them holds the whole circle. */
    fun edges(c: MapCircle): List<MapPoint> {
        val dLat = Math.toDegrees(c.radiusM / EARTH_M)
        val dLng = Math.toDegrees(c.radiusM / (EARTH_M * Math.cos(Math.toRadians(c.lat))))
        return listOf(
            MapPoint("c_n", "", c.lat + dLat, c.lng), MapPoint("c_s", "", c.lat - dLat, c.lng),
            MapPoint("c_e", "", c.lat, c.lng + dLng), MapPoint("c_w", "", c.lat, c.lng - dLng),
        )
    }

    private const val EARTH_M = 6_371_000.0
}
