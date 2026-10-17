package com.aktcl.aron.feature.amo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** One open risk signal of the zone (the bundle's `supervisor.risk_signals`), reduced to what the Exceptions screen shows. */
data class RiskSignalRow(
    val id: Long, val code: String, val severity: Int, val businessDate: String, val subjectType: String, val subjectId: String,
    val userId: Long?, val routeId: Long?, val score: Double, val evidence: List<Pair<String, String>>,
)

enum class ExceptionGroup { GEO, DEVICE, CLOCK, OTHER }

/** Pure rules of the Exceptions screen (F-AMO-038). Reviews are offline `risk_review` records; nothing here needs the network. */
object Exceptions {
    val ACTIONS = listOf("reviewed", "dismissed", "confirmed")

    private fun prim(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)

    /** A code the app does not know is shown by its code, never dropped (contract: extensible enum). */
    fun group(code: String): ExceptionGroup = when {
        code.startsWith("GEO_") -> ExceptionGroup.GEO
        code.startsWith("DEVICE_") -> ExceptionGroup.DEVICE
        code == "CLOCK_SKEW" -> ExceptionGroup.CLOCK
        else -> ExceptionGroup.OTHER
    }

    /**
     * Today's open signals, worst first (severity, then score). [decided] are the signal ids this phone has already reviewed, so a
     * reviewed signal leaves the list at once, offline too, and a retried review never shows again.
     */
    fun open(supervisorJson: String?, today: String, decided: Set<Long> = emptySet()): List<RiskSignalRow> {
        val o = runCatching { Json.parseToJsonElement(supervisorJson ?: return emptyList()) as? JsonObject }.getOrNull() ?: return emptyList()
        return (o["risk_signals"] as? JsonArray).orEmpty().mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            val id = prim(r, "signal_id")?.longOrNull ?: return@mapNotNull null
            if (prim(r, "status")?.contentOrNull != "open" || prim(r, "business_date")?.contentOrNull != today || id in decided) return@mapNotNull null
            val evidence = (r["evidence"] as? JsonObject).orEmpty().map { (k, v) ->
                k to when (v) { is JsonArray -> v.joinToString(", ") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }; is JsonPrimitive -> v.contentOrNull.orEmpty(); else -> "" }
            }
            RiskSignalRow(
                id, prim(r, "code")?.contentOrNull ?: return@mapNotNull null, prim(r, "severity")?.intOrNull ?: 1, today,
                prim(r, "subject_type")?.contentOrNull.orEmpty(), prim(r, "subject_id")?.contentOrNull.orEmpty(),
                prim(r, "user_id")?.longOrNull, prim(r, "route_id")?.longOrNull, prim(r, "score")?.doubleOrNull ?: 0.0, evidence,
            )
        }.sortedWith(compareByDescending<RiskSignalRow> { it.severity }.thenByDescending { it.score }.thenBy { it.id })
    }
}
