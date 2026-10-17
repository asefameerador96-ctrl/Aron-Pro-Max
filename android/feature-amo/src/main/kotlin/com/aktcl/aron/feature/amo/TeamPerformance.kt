package com.aktcl.aron.feature.amo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

enum class PerfBand { GREEN, AMBER, RED }

/** One bar: [pct] is null for a zero target (a dash, never 0 % or infinity). */
data class PerfBar(val name: String, val unit: String, val target: Double, val achieved: Double, val remaining: Double, val pct: Double?)

/**
 * Team Performance (F-AMO-017) over `GET /v1/dashboards/targets`. The target engine is deferred (docs/27), so the server answers
 * with no rows today and the screen shows its empty state; the parsing and the bars are ready for when targets come back.
 */
object TeamPerformance {
    fun band(pct: Double): PerfBand = if (pct >= 80.0) PerfBand.GREEN else if (pct >= 40.0) PerfBand.AMBER else PerfBand.RED

    /** The printed percent never exceeds 100; the bar is full at 100. */
    fun printed(pct: Double): Double = pct.coerceIn(0.0, 100.0)

    private fun d(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
    private fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    /** Rows of one product level; [tillDate] chooses the till-date target instead of the month's. */
    fun bars(json: String?, level: String, tillDate: Boolean): List<PerfBar> {
        val o = runCatching { Json.parseToJsonElement(json ?: return emptyList()) as? JsonObject }.getOrNull() ?: return emptyList()
        return (o["rows"] as? JsonArray).orEmpty().mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            if (s(r, "product_level") != level) return@mapNotNull null
            val target = (if (tillDate) d(r, "tilldate_target_qty") else d(r, "target_qty")) ?: return@mapNotNull null
            val achieved = d(r, "achieved_qty") ?: 0.0
            PerfBar(s(r, "name").orEmpty(), s(r, "unit").orEmpty(), target, achieved, maxOf(0.0, target - achieved), if (target > 0.0) achieved * 100.0 / target else null)
        }
    }
}
