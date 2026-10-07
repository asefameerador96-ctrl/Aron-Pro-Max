package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.OutletChangeRequestEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Maps a validated [OutletRequestDraft] onto the Room entity and its fix (contract `OutletChangeRequestPayload`).
 * The contract requires a fix on every request, so [fix] is the capture's fix or one taken at submission; a failed
 * fix is stored as it is (the server decides what to do with it).
 */
fun OutletRequestDraft.toEntities(meta: CaptureMeta, fix: FixReading, fixUuid: String = ClientIds.newUuid()): Pair<OutletChangeRequestEntity, GeoFixEntity> {
    val proposed = buildJsonObject {
        when (kind) {
            OutletRequestKind.NEW -> {
                name?.let { put("name", it) }; ownerName?.let { put("owner_name", it) }; contactNumber?.let { put("contact_number", it) }
                clusterId?.let { put("cluster_id", it) }
                if (fix.isOk) { put("lat", fix.lat!!); put("lng", fix.lng!!) }
            }
            OutletRequestKind.INFO -> { name?.let { put("name", it) }; ownerName?.let { put("owner_name", it) }; contactNumber?.let { put("contact_number", it) } }
            OutletRequestKind.CLOSE -> put("close_reason_code", closeReasonCode ?: "shop_closed")
            OutletRequestKind.CLUSTER -> clusterId?.let { put("cluster_id", it) }
            OutletRequestKind.LOCATION -> if (fix.isOk) { put("lat", fix.lat!!); put("lng", fix.lng!!) }
            OutletRequestKind.ROUTE_ADD -> Unit
        }
    }
    val entity = OutletChangeRequestEntity(
        clientUuid = requestUuid, meta = meta, requestType = kind.wire, outletId = outletId, proposedJson = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), proposed),
        fixClientUuid = fixUuid, photoUuidsJson = Json.encodeToString(JsonArray.serializer(), JsonArray(photoUuids.map { JsonPrimitive(it) })),
        originVisitClientUuid = originVisitUuid, note = note,
    )
    return entity to fix.toEntity(fixUuid, requestUuid, if (kind == OutletRequestKind.LOCATION) "outlet_capture" else "outlet_capture", 0)
}
