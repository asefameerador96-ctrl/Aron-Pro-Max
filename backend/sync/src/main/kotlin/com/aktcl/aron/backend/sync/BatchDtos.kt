package com.aktcl.aron.backend.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Wire shapes of POST /v1/sync/batch (contract SyncBatchRequest / SyncBatchResponse, docs/24 s4.4, s4.5). Records stay
 * JSON objects here: each one is validated and acknowledged on its own, so one bad record never fails the batch.
 */

@Serializable
data class TimeAnchor(val boot_count: Int, val server_time: String, val elapsed_ms: Long)

@Serializable
data class SyncBatchRequest(
    val batch_uuid: String,
    val device_uuid: String,
    val schema_version: Int,
    val app_version: String,
    val trigger: String,
    val sent_at_device: String,
    val pending_rows: Int,
    val time_anchors: List<TimeAnchor>,
    val device_counts: Map<String, Map<String, Int>>,
    val device_money: Map<String, JsonObject>? = null,
    val telemetry: JsonObject? = null,
    val records: List<JsonObject>,
)

@Serializable
data class RecordAck(
    val client_uuid: String,
    val type: String,
    val status: String,
    val code: String? = null,
    val retryable: Boolean? = null,
    val message_key: String? = null,
    val server_id: Long? = null,
)

@Serializable
data class AckSummary(val accepted: Int, val duplicate: Int, val rejected: Int, val quarantined: Int)

@Serializable
data class TypeOutcomeCounts(val accepted: Int, val rejected: Int, val quarantined: Int)

@Serializable
data class MoneyTotals(
    val active_memo_count: Int,
    val gross_mtk: Long,
    val offer_discount_mtk: Long,
    val drp_discount_mtk: Long,
    val qc_deduction_mtk: Long,
    val net_mtk: Long,
    val paid_mtk: Long,
    val due_mtk: Long,
    val due_collected_mtk: Long,
    val net_by_category_mtk: Map<String, Long>,
    val issued_qty_base_by_sku: Map<String, Long>,
    val sold_qty_base_by_sku: Map<String, Long>,
)

@Serializable
data class ServerTotals(val business_date: String, val as_of: String, val by_type: Map<String, TypeOutcomeCounts>, val money: MoneyTotals)

/** GET /v1/sync/totals (contract SyncTotalsResponse): the Server column of Sales Submit for one date. */
@Serializable
data class SyncTotalsResponse(val totals: ServerTotals, val day_states: List<RouteDayStateDto>, val supervisor_day: SupervisorDayStateDto?)

@Serializable
data class Resolution(val client_uuid: String, val type: String, val resolution: String, val resolved_at: String)

@Serializable
data class SyncBatchResponse(
    val batch_uuid: String,
    val replayed: Boolean,
    val received_at: String,
    val acks: List<RecordAck>,
    val summary: AckSummary,
    val server_totals: List<ServerTotals>,
    val day_states: List<RouteDayStateDto>,
    val resolutions: List<Resolution>,
    val hold_s: Int,
    val config_version: Long,
    val bundle_version_current: String?,
    val generation: String,
)
