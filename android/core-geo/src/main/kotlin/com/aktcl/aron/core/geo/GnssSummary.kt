package com.aktcl.aron.core.geo

import kotlin.math.sqrt

/** One satellite of a GnssStatus snapshot. [constellation] is the GnssStatus.CONSTELLATION_* value. */
data class SatelliteSample(val constellation: Int, val cn0DbHz: Double, val usedInFix: Boolean, val hasEphemeris: Boolean)

/**
 * Collects GnssStatus snapshots and raw-measurement counts for the duration of one fix window and turns them into the
 * contract `GnssSummary` (docs/24 s11.1). The server's GEO_GNSS_INCONSISTENT rule reads it: too few used satellites,
 * a C/N0 spread too flat for real sky, or a mean too strong are what simulators produce.
 */
class GnssAccumulator(private val startedElapsedMs: Long, rawSupported: Boolean?) {
    /** True or false once known; null (unknown) is reported as not supported. Measurements received prove support. */
    @Volatile private var raw: Boolean? = rawSupported

    @Synchronized fun markRawSupported(supported: Boolean) { if (raw != true || !supported) raw = supported }

    private var last: List<SatelliteSample>? = null
    private var measurementCount = 0L
    private var measurementEvents = 0
    private val agc = mutableListOf<Double>()

    @Synchronized fun onStatus(satellites: List<SatelliteSample>) { last = satellites }

    @Synchronized fun onMeasurements(count: Int, agcLevelsDb: List<Double>) {
        if (raw == null) raw = true
        measurementEvents++
        measurementCount += count.coerceAtLeast(0)
        agc += agcLevelsDb.filter { it.isFinite() }
    }

    /** The summary as JSON text. With no GnssStatus at all (GNSS not running for this fix) the counts are zero. */
    @Synchronized
    fun summaryJson(endedElapsedMs: Long): String {
        val sats = last.orEmpty()
        val used = sats.filter { it.usedInFix }
        fun List<Double>.mean() = if (isEmpty()) null else sum() / size
        val usedCn0 = used.map { it.cn0DbHz }.filter { it.isFinite() && it >= 0 }
        val allCn0 = sats.map { it.cn0DbHz }.filter { it.isFinite() && it >= 0 }
        val mean = usedCn0.mean()
        val std = mean?.let { m -> sqrt(usedCn0.sumOf { (it - m) * (it - m) } / usedCn0.size) }
        val fields = linkedMapOf<String, String>(
            "window_ms" to (endedElapsedMs - startedElapsedMs).coerceIn(0, 60_000).toString(),
            "satellites_visible" to sats.size.coerceAtMost(200).toString(),
            "satellites_used" to used.size.coerceAtMost(200).toString(),
            "constellations_used" to used.map { constellationName(it.constellation) }.distinct().sortedBy { ORDER.indexOf(it) }
                .take(8).joinToString(",", "[", "]") { "\"$it\"" },
            "cn0_used_mean_dbhz" to num(mean?.coerceIn(0.0, 70.0)),
            "cn0_used_max_dbhz" to num(usedCn0.maxOrNull()?.coerceIn(0.0, 70.0)),
            "cn0_used_stddev_dbhz" to num(std?.coerceIn(0.0, 40.0)),
            "cn0_all_mean_dbhz" to num(allCn0.mean()?.coerceIn(0.0, 70.0)),
            "ephemeris_share" to num(if (sats.isEmpty()) null else sats.count { it.hasEphemeris }.toDouble() / sats.size),
            "raw_supported" to (raw == true).toString(),
            "raw_measurement_count" to (if (raw == true) measurementCount.toString() else "null"),
            "agc_db_mean" to num(agc.mean()),
        )
        return fields.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
    }

    companion object {
        private val ORDER = listOf("GPS", "GLONASS", "GALILEO", "BEIDOU", "QZSS", "IRNSS", "SBAS", "UNKNOWN")

        /** GnssStatus.CONSTELLATION_* to the contract `GnssConstellation`. */
        fun constellationName(c: Int): String = when (c) {
            1 -> "GPS"; 2 -> "SBAS"; 3 -> "GLONASS"; 4 -> "QZSS"; 5 -> "BEIDOU"; 6 -> "GALILEO"; 7 -> "IRNSS"; else -> "UNKNOWN"
        }

        private fun num(v: Double?): String = when {
            v == null || !v.isFinite() -> "null"
            else -> (Math.round(v * 100) / 100.0).toString()
        }
    }
}
