package com.aktcl.aron.feature.amo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/** One SR of the zone with the last synced fix; no fix yet means the SR is listed without a pin (F-AMO-016). */
data class TeamPin(val userId: Long, val name: String, val routeIds: List<Long>, val lat: Double?, val lng: Double?, val atMs: Long?, val source: String?)

object TeamLocations {
    /** Parses `GET /v1/team/locations`; SRs first in name order. A malformed answer is an empty list, never a crash. */
    fun parse(json: String?): List<TeamPin> {
        val o = runCatching { Json.parseToJsonElement(json ?: return emptyList()) as? JsonObject }.getOrNull() ?: return emptyList()
        return (o["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val fix = m["last_fix"] as? JsonObject
            TeamPin(
                (m["user_id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null, (m["full_name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                (m["route_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull },
                (fix?.get("lat") as? JsonPrimitive)?.doubleOrNull, (fix?.get("lng") as? JsonPrimitive)?.doubleOrNull,
                (fix?.get("at") as? JsonPrimitive)?.contentOrNull?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                (fix?.get("source") as? JsonPrimitive)?.contentOrNull,
            )
        }.sortedBy { it.name }
    }
}
