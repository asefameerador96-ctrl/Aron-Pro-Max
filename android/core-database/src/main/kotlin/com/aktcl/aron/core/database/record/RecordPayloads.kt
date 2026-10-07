package com.aktcl.aron.core.database.record

import com.aktcl.aron.contract.GeoFix
import com.aktcl.aron.contract.GeoFixWire
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// The SR day's record payloads (GeoFix, FixDeviceState, DeviceGeoVerdict, AttendanceEventPayload, StockMovementPayload,
// VisitPayload, VisitClosePayload, MemoPayload, MemoLinePayload, MemoDiscountPayload, QcLinePayload) are shared:contract's
// generated `com.aktcl.aron.contract.*` (N-002). The payloads below are not generated there yet; RecordPayloadContractTest
// checks their member names against contract/openapi.yaml.

// ---- Room v3 records (contract DueCollectionPayload, VisitSkipPayload, DaySubmitPayload, OutletChangeRequestPayload,
// TaskEventPayload). Members without a default are required; optional members default to null and are left out. ----

@Serializable
data class DueCollectionPayload(
    @SerialName("outlet_id") val outletId: Long,
    @SerialName("against_memo_client_uuid") val againstMemoClientUuid: String,
    @SerialName("against_memo_no") val againstMemoNo: String,
    @SerialName("against_memo_business_date") val againstMemoBusinessDate: String,
    @SerialName("amount_mtk") val amountMtk: Long,
    @SerialName("is_full_settlement") val isFullSettlement: Boolean,
    @SerialName("outstanding_before_mtk") val outstandingBeforeMtk: Long,
    @SerialName("payment_mode") val paymentMode: String,
    @SerialName("visit_client_uuid") val visitClientUuid: String? = null,
    @Serializable(with = GeoFixWire::class) val fix: GeoFix? = null,
)

@Serializable
data class VisitSkipPayload(
    @SerialName("outlet_id") val outletId: Long,
    @SerialName("reason_code") val reasonCode: String,
)

@Serializable
data class DaySubmitPayload(
    val scope: String,
    @SerialName("submit_cycle") val submitCycle: Int,
    @SerialName("device_counts") val deviceCounts: JsonElement,
    @SerialName("device_money") val deviceMoney: JsonElement,
    @SerialName("rejected_count") val rejectedCount: Int,
    @SerialName("quarantined_count") val quarantinedCount: Int,
    @SerialName("pending_count") val pendingCount: Int,
    @SerialName("submitted_with_dues") val submittedWithDues: Boolean,
    @SerialName("dues_outstanding_mtk") val duesOutstandingMtk: Long,
    @SerialName("retailers_with_dues") val retailersWithDues: Int,
    @SerialName("stock_slip_printed") val stockSlipPrinted: Boolean,
)

@Serializable
data class OutletChangeRequestPayload(
    @SerialName("request_type") val requestType: String,
    @SerialName("outlet_id") val outletId: Long? = null,
    val proposed: JsonElement,
    @Serializable(with = GeoFixWire::class) val fix: GeoFix,
    @SerialName("photo_uuids") val photoUuids: List<String>,
    @SerialName("origin_visit_client_uuid") val originVisitClientUuid: String? = null,
    val note: String? = null,
)

@Serializable
data class TaskEventPayload(
    @SerialName("task_uuid") val taskUuid: String,
    val event: String,
    val note: String? = null,
)
