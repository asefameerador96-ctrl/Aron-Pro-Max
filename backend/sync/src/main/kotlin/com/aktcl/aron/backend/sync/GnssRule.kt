package com.aktcl.aron.backend.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** N-028: the GNSS-consistency rule of docs/24 s11.4 over one `GeoFix`, pure so it is tested without a database. */
object GnssRule {
    data class Thresholds(val minUsed: Int = 4, val stddevMin: Double = 1.0, val meanMax: Double = 48.0)

    /** Satellites used from which an even signal strength is suspicious (s11.4: "≥ 6 used"). */
    private const val EVEN_STRENGTH_MIN_USED = 6

    /** The evidence of an inconsistent fix, or null when the fix passes or is not judged (not `gps`, not `ok`, no summary). */
    fun check(fix: JsonObject, t: Thresholds): Map<String, JsonElement>? {
        if (str(fix["provider"]) != "gps" || str(fix["fix_status"]) != "ok") return null
        val g = fix["gnss"] as? JsonObject ?: return null
        val used = num(g["satellites_used"])?.toInt() ?: return null
        val stddev = num(g["cn0_used_stddev_dbhz"])
        val mean = num(g["cn0_used_mean_dbhz"])
        val reason = when {
            used < t.minUsed -> "too_few_satellites"
            used >= EVEN_STRENGTH_MIN_USED && stddev != null && stddev < t.stddevMin -> "even_signal_strength"
            mean != null && mean > t.meanMax -> "signal_too_strong"
            else -> return null
        }
        return mapOf(
            "reason" to JsonPrimitive(reason), "satellites_used" to JsonPrimitive(used),
            "cn0_used_stddev_dbhz" to JsonPrimitive(stddev), "cn0_used_mean_dbhz" to JsonPrimitive(mean),
            "min_satellites_used" to JsonPrimitive(t.minUsed), "cn0_stddev_min_dbhz" to JsonPrimitive(t.stddevMin), "cn0_mean_max_dbhz" to JsonPrimitive(t.meanMax),
        )
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
}
