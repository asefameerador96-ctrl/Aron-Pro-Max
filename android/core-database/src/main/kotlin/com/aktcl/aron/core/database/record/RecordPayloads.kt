package com.aktcl.aron.core.database.record

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Record payloads of the SR day as contract/openapi.yaml defines them (GeoFix, FixDeviceState, DeviceGeoVerdict and the
// *Payload schemas). REQUEST: docs/requests/android-core-contract-dtos.md asks the shared lane to host these in
// shared:contract; RecordPayloadContractTest checks every member name against the YAML until then.

@Serializable
data class FixDeviceStatePayload(
    @SerialName("device_owner") val deviceOwner: Boolean,
    @SerialName("dev_options_enabled") val devOptionsEnabled: Boolean,
    @SerialName("adb_enabled") val adbEnabled: Boolean,
    @SerialName("auto_time_enabled") val autoTimeEnabled: Boolean,
    @SerialName("mock_app_present") val mockAppPresent: Boolean,
    @SerialName("integrity_ref") val integrityRef: String? = null,
)

@Serializable
data class GeoFixPayload(
    val purpose: String,
    @SerialName("fix_status") val fixStatus: String,
    val lat: Double?,
    val lng: Double?,
    @SerialName("accuracy_m") val accuracyM: Double?,
    @SerialName("altitude_m") val altitudeM: Double? = null,
    @SerialName("vertical_accuracy_m") val verticalAccuracyM: Double? = null,
    @SerialName("speed_mps") val speedMps: Double? = null,
    @SerialName("bearing_deg") val bearingDeg: Double? = null,
    val provider: String,
    @SerialName("fix_time") val fixTime: String? = null,
    @SerialName("fix_elapsed_realtime_ms") val fixElapsedRealtimeMs: Long? = null,
    @SerialName("fix_age_ms") val fixAgeMs: Long? = null,
    @SerialName("time_to_fix_ms") val timeToFixMs: Long? = null,
    @SerialName("request_priority") val requestPriority: String? = null,
    @SerialName("is_mock") val isMock: Boolean,
    val reused: Boolean,
    @SerialName("refresh_count") val refreshCount: Int? = null,
    val gnss: JsonObject? = null,
    val radio: JsonObject? = null,
    val device: FixDeviceStatePayload,
)

@Serializable
data class DeviceGeoVerdictPayload(
    val verdict: String,
    @SerialName("distance_m") val distanceM: Double?,
    @SerialName("radius_m_used") val radiusMUsed: Int,
    @SerialName("max_accuracy_m_used") val maxAccuracyMUsed: Int,
    @SerialName("location_basis") val locationBasis: String,
    @SerialName("outlet_lat") val outletLat: Double?,
    @SerialName("outlet_lng") val outletLng: Double?,
    val action: String,
    @SerialName("force_reason_code") val forceReasonCode: String? = null,
    @SerialName("force_photo_uuid") val forcePhotoUuid: String? = null,
)

@Serializable
data class AttendanceEventPayload(
    val kind: String,
    val fix: GeoFixPayload,
    @SerialName("address_display") val addressDisplay: String? = null,
)

@Serializable
data class StockMovementPayload(
    val kind: String,
    @SerialName("sku_id") val skuId: Long,
    @SerialName("qty_entered") val qtyEntered: Long,
    @SerialName("unit_entered") val unitEntered: String,
    @SerialName("pack_factor") val packFactor: Int,
    @SerialName("qty_base") val qtyBase: Long,
    @SerialName("reason_code") val reasonCode: String? = null,
    @SerialName("slip_printed") val slipPrinted: Boolean,
)

@Serializable
data class VisitPayload(
    @SerialName("visit_kind") val visitKind: String,
    @SerialName("outlet_id") val outletId: Long,
    @SerialName("opened_at") val openedAt: String,
    @SerialName("sequence_no") val sequenceNo: Int,
    val planned: Boolean,
    @SerialName("assessed_user_id") val assessedUserId: Long? = null,
    val fix: GeoFixPayload,
    val geo: DeviceGeoVerdictPayload,
)

@Serializable
data class VisitClosePayload(
    @SerialName("visit_client_uuid") val visitClientUuid: String,
    @SerialName("outcome_code") val outcomeCode: String,
    @SerialName("call_started_at") val callStartedAt: String? = null,
    @SerialName("call_declined") val callDeclined: Boolean,
    @SerialName("ended_at") val endedAt: String,
    @SerialName("is_zero_sale") val isZeroSale: Boolean,
)

@Serializable
data class MemoPayload(
    @SerialName("visit_client_uuid") val visitClientUuid: String,
    @SerialName("outlet_id") val outletId: Long,
    @SerialName("memo_no") val memoNo: String,
    @SerialName("memo_kind") val memoKind: String,
    @SerialName("committed_at") val committedAt: String,
    @SerialName("price_list_date") val priceListDate: String,
    @SerialName("price_type") val priceType: String,
    @SerialName("gross_mtk") val grossMtk: Long,
    @SerialName("offer_discount_mtk") val offerDiscountMtk: Long,
    @SerialName("drp_discount_mtk") val drpDiscountMtk: Long,
    @SerialName("qc_deduction_mtk") val qcDeductionMtk: Long,
    @SerialName("round_adj_mtk") val roundAdjMtk: Long,
    @SerialName("net_mtk") val netMtk: Long,
    @SerialName("paid_mtk") val paidMtk: Long,
    @SerialName("due_mtk") val dueMtk: Long,
    @SerialName("is_credit") val isCredit: Boolean,
    @SerialName("outstanding_before_mtk") val outstandingBeforeMtk: Long? = null,
    @SerialName("line_count") val lineCount: Int,
    @SerialName("discount_line_count") val discountLineCount: Int,
    @SerialName("qc_line_count") val qcLineCount: Int,
    @SerialName("supersedes_client_uuid") val supersedesClientUuid: String? = null,
    @SerialName("edit_reason_code") val editReasonCode: String? = null,
    @SerialName("edit_fix") val editFix: GeoFixPayload? = null,
    @SerialName("offer_version_ids") val offerVersionIds: List<Long> = emptyList(),
    @SerialName("rounding_mode") val roundingMode: String,
)

@Serializable
data class MemoLinePayload(
    @SerialName("memo_client_uuid") val memoClientUuid: String,
    @SerialName("line_no") val lineNo: Int,
    @SerialName("sku_id") val skuId: Long,
    @SerialName("line_kind") val lineKind: String,
    @SerialName("qty_entered") val qtyEntered: Long,
    @SerialName("unit_entered") val unitEntered: String,
    @SerialName("pack_factor") val packFactor: Int,
    @SerialName("qty_base") val qtyBase: Long,
    @SerialName("price_type") val priceType: String,
    @SerialName("price_valid_from") val priceValidFrom: String,
    @SerialName("base_price_mtk") val basePriceMtk: Long,
    @SerialName("price_per_qty") val pricePerQty: Int,
    @SerialName("gross_mtk") val grossMtk: Long,
    @SerialName("offer_id") val offerId: Long? = null,
)

@Serializable
data class MemoDiscountPayload(
    @SerialName("memo_client_uuid") val memoClientUuid: String,
    val kind: String,
    @SerialName("sku_id") val skuId: Long? = null,
    @SerialName("qty_base") val qtyBase: Long? = null,
    @SerialName("value_mtk") val valueMtk: Long,
    @SerialName("offer_id") val offerId: Long? = null,
    @SerialName("offer_version_id") val offerVersionId: Long? = null,
    @SerialName("basis_qty_base") val basisQtyBase: Long? = null,
    @SerialName("line_no") val lineNo: Int? = null,
)

@Serializable
data class QcLinePayload(
    @SerialName("visit_client_uuid") val visitClientUuid: String,
    @SerialName("memo_client_uuid") val memoClientUuid: String? = null,
    @SerialName("applied_to_memo") val appliedToMemo: Boolean,
    @SerialName("sku_id") val skuId: Long,
    @SerialName("fault_type_code") val faultTypeCode: String,
    @SerialName("fault_group") val faultGroup: String,
    @SerialName("qty_base") val qtyBase: Long,
    @SerialName("unit_price_mtk") val unitPriceMtk: Long,
    @SerialName("settlement_mtk") val settlementMtk: Long,
)

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
    val fix: GeoFixPayload? = null,
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
    val fix: GeoFixPayload,
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
