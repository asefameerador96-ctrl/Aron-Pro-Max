package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.Reach
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The scope an audited action ran under, as stored in the audit row's `after.scope`: national, or the zones (and own routes) of the caller's reach. */
internal fun auditScope(national: Boolean, zoneIds: Collection<Long>?, routeIds: Collection<Long>? = null): JsonElement = buildJsonObject {
    put("national", national)
    zoneIds?.let { z -> put("zone_ids", buildJsonArray { z.sorted().forEach { add(JsonPrimitive(it)) } }) }
    routeIds?.let { r -> put("route_ids", buildJsonArray { r.sorted().forEach { add(JsonPrimitive(it)) } }) }
}

internal fun auditScope(reach: Reach): JsonElement = auditScope(reach.national, if (reach.national) null else reach.zoneIds, if (reach.ownRecordsOnly) reach.routeIds else null)
