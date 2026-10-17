package com.aktcl.aron.feature.amo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A request of an SR waiting for the AMO (the bundle's `supervisor.pending_outlet_requests`). */
data class PendingRequest(
    val uuid: String, val type: String, val outletId: Long?, val outletName: String?, val requestedBy: Long, val requestedAt: String,
    val routeId: Long?, val clusterId: Long?,
)

data class SubChannel(val id: Long, val labelEn: String, val labelBn: String?)

enum class VerifyError { SUB_CHANNEL_REQUIRED, GEO_CLASS_REQUIRED }

/** One proposed field next to what the outlet has now (F-AMO-024: old versus new). */
data class FieldChange(val field: String, val old: String?, val new: String)

/** The server's view of a request (`GET /v1/outlet-requests/{uuid}`), reduced to what the verification screens show. */
data class VerifyDetail(val type: String, val status: String, val requestedBy: Long?, val proposed: Map<String, String>, val outletId: Long?, val lat: Double?, val lng: Double?, val photos: Int, val flags: List<String>)

/** Pure rules of the AMO verification screens (F-AMO-022/023/024); the app wires them to the API, the store and the camera. */
object OutletVerify {
    val GEO_CLASSES = listOf("Hill", "Urban", "SemiUrban", "Rural")
    private val INFO_FIELDS = listOf("name", "name_bn", "owner_name", "contact_number", "address", "sub_channel_id")

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
    private fun lng(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.longOrNull

    /**
     * Pending requests of others, oldest first; the AMO's own never appear (they go to the web approver). [alreadyDecided] are the
     * request uuids this phone has decided but the bundle still lists, so a verified request leaves the list at once, offline too.
     */
    fun pending(supervisorJson: String?, ownUserId: Long, alreadyDecided: Set<String> = emptySet()): List<PendingRequest> {
        val o = runCatching { Json.parseToJsonElement(supervisorJson ?: return emptyList()) as? JsonObject }.getOrNull() ?: return emptyList()
        return (o["pending_outlet_requests"] as? JsonArray).orEmpty().mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            val uuid = str(r, "request_uuid") ?: return@mapNotNull null
            val by = lng(r, "requested_by_user_id") ?: return@mapNotNull null
            if (str(r, "status") != "pending" || by == ownUserId || uuid in alreadyDecided) return@mapNotNull null
            PendingRequest(uuid, str(r, "request_type") ?: return@mapNotNull null, lng(r, "outlet_id"), str(r, "outlet_name"), by, str(r, "requested_at").orEmpty(), lng(r, "route_id"), lng(r, "cluster_id"))
        }.sortedBy { it.requestedAt }
    }

    /** user id to full name of the zone's team (for the list subtitle). */
    fun teamNames(supervisorJson: String?): Map<Long, String> {
        val o = runCatching { Json.parseToJsonElement(supervisorJson ?: return emptyMap()) as? JsonObject }.getOrNull() ?: return emptyMap()
        return (o["team"] as? JsonArray).orEmpty().mapNotNull { e -> (e as? JsonObject)?.let { m -> lng(m, "user_id")?.let { id -> id to (str(m, "full_name") ?: str(m, "username") ?: return@mapNotNull null) } } }.toMap()
    }

    /** The `sub_channel` code list with its ids (items without an id cannot be sent and are dropped). */
    fun subChannels(codeListsJson: String?): List<SubChannel> {
        val a = runCatching { Json.parseToJsonElement(codeListsJson ?: return emptyList()) as? JsonArray }.getOrNull() ?: return emptyList()
        val list = a.firstOrNull { (it as? JsonObject)?.let { l -> str(l, "list_key") } == "sub_channel" } as? JsonObject ?: return emptyList()
        return (list["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val i = e as? JsonObject ?: return@mapNotNull null
            SubChannel(lng(i, "id") ?: return@mapNotNull null, str(i, "label_en") ?: return@mapNotNull null, str(i, "label_bn"))
        }
    }

    /** Parses the stored or fetched request; null when it is not a request object. */
    fun detail(json: String?): VerifyDetail? {
        val o = runCatching { Json.parseToJsonElement(json ?: return null) as? JsonObject }.getOrNull() ?: return null
        val proposed = (o["proposed"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
        val fix = o["request_fix"] as? JsonObject
        return VerifyDetail(
            str(o, "request_type") ?: return null, str(o, "status") ?: "pending", lng(o, "requested_by_user_id"), proposed, lng(o, "outlet_id"),
            (fix?.get("lat") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(), (fix?.get("lng") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
            (o["photos"] as? JsonArray)?.size ?: 0, (o["flags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }

    /** A fresh server answer that says the request is no longer waiting (decided on the web, lapsed) or is the AMO's own: Save and Reject stay off. */
    fun decidable(d: VerifyDetail, ownUserId: Long): Boolean = d.status == "pending" && d.requestedBy != ownUserId

    /** Info-change rows: only the fields the SR proposed, each with the value the outlet has now. */
    fun changes(proposed: Map<String, String>, current: Map<String, String?>): List<FieldChange> =
        INFO_FIELDS.filter { it in proposed }.map { FieldChange(it, current[it], proposed.getValue(it)) }.filter { it.old != it.new }

    /**
     * Save rules: a Cancel (discard) needs nothing; verifying a new outlet needs the sub-channel and the geo class
     * (docs/07; F-AMO-022). Other types take them optionally.
     */
    fun validate(type: String, verify: Boolean, subChannelId: Long?, geoClass: String?): Set<VerifyError> {
        if (!verify || type != "new") return emptySet()
        return buildSet {
            if (subChannelId == null) add(VerifyError.SUB_CHANNEL_REQUIRED)
            if (geoClass == null || geoClass !in GEO_CLASSES) add(VerifyError.GEO_CLASS_REQUIRED)
        }
    }
}
