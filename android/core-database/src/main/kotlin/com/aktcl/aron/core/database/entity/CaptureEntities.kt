package com.aktcl.aron.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Device-originated rows of the SR day (docs/24 s4.2, s5.2). Every table is keyed by the client UUID minted when the
// row is committed, so a second insert of the same UUID is rejected by SQLite. Rows are never edited after commit:
// an edit is a new record (an edited memo carries supersedes_client_uuid). Columns follow the contract payloads.

/** One on-demand location fix (contract `GeoFix`); stored once and embedded in the record that took it. */
@Entity(
    tableName = "geo_fix",
    indices = [Index(value = ["owner_client_uuid", "purpose"], unique = true)],
)
data class GeoFixEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    /** The attendance event, visit or memo this fix belongs to. */
    @ColumnInfo(name = "owner_client_uuid") val ownerClientUuid: String,
    val purpose: String,
    @ColumnInfo(name = "fix_status") val fixStatus: String,
    val lat: Double?,
    val lng: Double?,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Double?,
    @ColumnInfo(name = "altitude_m") val altitudeM: Double? = null,
    @ColumnInfo(name = "vertical_accuracy_m") val verticalAccuracyM: Double? = null,
    @ColumnInfo(name = "speed_mps") val speedMps: Double? = null,
    @ColumnInfo(name = "bearing_deg") val bearingDeg: Double? = null,
    val provider: String,
    @ColumnInfo(name = "fix_time") val fixTime: String? = null,
    @ColumnInfo(name = "fix_elapsed_realtime_ms") val fixElapsedRealtimeMs: Long? = null,
    @ColumnInfo(name = "fix_age_ms") val fixAgeMs: Long? = null,
    @ColumnInfo(name = "time_to_fix_ms") val timeToFixMs: Long? = null,
    @ColumnInfo(name = "request_priority") val requestPriority: String? = null,
    @ColumnInfo(name = "is_mock") val isMock: Boolean,
    val reused: Boolean,
    @ColumnInfo(name = "refresh_count") val refreshCount: Int? = null,
    /** `GnssSummary` as JSON text (collected only during the fix window), or null. */
    @ColumnInfo(name = "gnss_json") val gnssJson: String? = null,
    /** `RadioEnvironment` as JSON text (only when cfg.geo.radio_env_enabled), or null. */
    @ColumnInfo(name = "radio_json") val radioJson: String? = null,
    @ColumnInfo(name = "device_owner") val deviceOwner: Boolean,
    @ColumnInfo(name = "dev_options_enabled") val devOptionsEnabled: Boolean,
    @ColumnInfo(name = "adb_enabled") val adbEnabled: Boolean,
    @ColumnInfo(name = "auto_time_enabled") val autoTimeEnabled: Boolean,
    @ColumnInfo(name = "mock_app_present") val mockAppPresent: Boolean,
    @ColumnInfo(name = "integrity_ref") val integrityRef: String? = null,
)

/** Check-in or check-out (record `attendance_event`). */
@Entity(tableName = "attendance_event", indices = [Index("business_date")])
data class AttendanceEventEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @Embedded val meta: CaptureMeta,
    /** `check_in` or `check_out`. */
    val kind: String,
    @ColumnInfo(name = "fix_client_uuid") val fixClientUuid: String,
    @ColumnInfo(name = "address_display") val addressDisplay: String? = null,
)

/** One stock event per SKU per Save, an increment never an overwrite (record `stock_movement`). */
@Entity(tableName = "stock_movement", indices = [Index("business_date"), Index("sku_id")])
data class StockMovementEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @Embedded val meta: CaptureMeta,
    val kind: String,
    @ColumnInfo(name = "sku_id") val skuId: Long,
    @ColumnInfo(name = "qty_entered") val qtyEntered: Long,
    @ColumnInfo(name = "unit_entered") val unitEntered: String,
    @ColumnInfo(name = "pack_factor") val packFactor: Int,
    @ColumnInfo(name = "qty_base") val qtyBase: Long,
    @ColumnInfo(name = "reason_code") val reasonCode: String? = null,
    @ColumnInfo(name = "slip_printed") val slipPrinted: Boolean,
)

/** Outlet open: the visit family root (record `visit`, rank 0) with the phone's geofence verdict. */
@Entity(tableName = "visit", indices = [Index("business_date"), Index("outlet_id")])
data class VisitEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @Embedded val meta: CaptureMeta,
    @ColumnInfo(name = "visit_kind") val visitKind: String,
    @ColumnInfo(name = "outlet_id") val outletId: Long,
    @ColumnInfo(name = "opened_at") val openedAt: String,
    @ColumnInfo(name = "sequence_no") val sequenceNo: Int,
    val planned: Boolean,
    @ColumnInfo(name = "assessed_user_id") val assessedUserId: Long? = null,
    @ColumnInfo(name = "fix_client_uuid") val fixClientUuid: String,
    @ColumnInfo(name = "geo_verdict") val geoVerdict: String,
    @ColumnInfo(name = "geo_distance_m") val geoDistanceM: Double?,
    @ColumnInfo(name = "geo_radius_m_used") val geoRadiusMUsed: Int,
    @ColumnInfo(name = "geo_max_accuracy_m_used") val geoMaxAccuracyMUsed: Int,
    @ColumnInfo(name = "geo_location_basis") val geoLocationBasis: String,
    @ColumnInfo(name = "geo_outlet_lat") val geoOutletLat: Double?,
    @ColumnInfo(name = "geo_outlet_lng") val geoOutletLng: Double?,
    @ColumnInfo(name = "geo_action") val geoAction: String,
    @ColumnInfo(name = "geo_force_reason_code") val geoForceReasonCode: String? = null,
    @ColumnInfo(name = "geo_force_photo_uuid") val geoForcePhotoUuid: String? = null,
)

/** End of a visit (record `visit_close`, rank 1 of the visit family); one per visit. */
@Entity(tableName = "visit_close", indices = [Index(value = ["visit_client_uuid"], unique = true), Index("business_date")])
data class VisitCloseEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @Embedded val meta: CaptureMeta,
    @ColumnInfo(name = "visit_client_uuid") val visitClientUuid: String,
    @ColumnInfo(name = "outcome_code") val outcomeCode: String,
    @ColumnInfo(name = "call_started_at") val callStartedAt: String? = null,
    @ColumnInfo(name = "call_declined") val callDeclined: Boolean,
    @ColumnInfo(name = "ended_at") val endedAt: String,
    @ColumnInfo(name = "is_zero_sale") val isZeroSale: Boolean,
)

/** Memo header (record `memo`, rank 1 of the visit family). Money in milli-taka (docs/24 s7). */
@Entity(
    tableName = "memo",
    indices = [Index(value = ["memo_no"], unique = true), Index("visit_client_uuid"), Index("business_date"), Index("outlet_id")],
)
data class MemoEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @Embedded val meta: CaptureMeta,
    @ColumnInfo(name = "visit_client_uuid") val visitClientUuid: String,
    @ColumnInfo(name = "outlet_id") val outletId: Long,
    @ColumnInfo(name = "memo_no") val memoNo: String,
    @ColumnInfo(name = "memo_kind") val memoKind: String,
    @ColumnInfo(name = "committed_at") val committedAt: String,
    @ColumnInfo(name = "price_list_date") val priceListDate: String,
    @ColumnInfo(name = "price_type") val priceType: String,
    @ColumnInfo(name = "gross_mtk") val grossMtk: Long,
    @ColumnInfo(name = "offer_discount_mtk") val offerDiscountMtk: Long,
    @ColumnInfo(name = "drp_discount_mtk") val drpDiscountMtk: Long,
    @ColumnInfo(name = "qc_deduction_mtk") val qcDeductionMtk: Long,
    @ColumnInfo(name = "round_adj_mtk") val roundAdjMtk: Long,
    @ColumnInfo(name = "net_mtk") val netMtk: Long,
    @ColumnInfo(name = "paid_mtk") val paidMtk: Long,
    @ColumnInfo(name = "due_mtk") val dueMtk: Long,
    @ColumnInfo(name = "is_credit") val isCredit: Boolean,
    @ColumnInfo(name = "outstanding_before_mtk") val outstandingBeforeMtk: Long? = null,
    @ColumnInfo(name = "line_count") val lineCount: Int,
    @ColumnInfo(name = "discount_line_count") val discountLineCount: Int,
    @ColumnInfo(name = "qc_line_count") val qcLineCount: Int,
    @ColumnInfo(name = "supersedes_client_uuid") val supersedesClientUuid: String? = null,
    @ColumnInfo(name = "edit_reason_code") val editReasonCode: String? = null,
    @ColumnInfo(name = "edit_fix_client_uuid") val editFixClientUuid: String? = null,
    /** JSON array of offer version ids applied, e.g. `[12,15]`. */
    @ColumnInfo(name = "offer_version_ids_json") val offerVersionIdsJson: String = "[]",
    @ColumnInfo(name = "rounding_mode") val roundingMode: String = "half_up_paisa",
    /** First successful print (UTC), set by the print ledger; local state, not part of the memo record. */
    @ColumnInfo(name = "printed_at") val printedAt: String? = null,
    /** Number of `printed` print events of this memo. */
    @ColumnInfo(name = "print_count", defaultValue = "0") val printCount: Int = 0,
)

/** Memo line (record `memo_line`, rank 2). */
@Entity(
    tableName = "memo_line",
    indices = [Index(value = ["memo_client_uuid", "line_no"], unique = true), Index("business_date")],
)
data class MemoLineEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @Embedded val meta: CaptureMeta,
    @ColumnInfo(name = "memo_client_uuid") val memoClientUuid: String,
    @ColumnInfo(name = "line_no") val lineNo: Int,
    @ColumnInfo(name = "sku_id") val skuId: Long,
    @ColumnInfo(name = "line_kind") val lineKind: String,
    @ColumnInfo(name = "qty_entered") val qtyEntered: Long,
    @ColumnInfo(name = "unit_entered") val unitEntered: String,
    @ColumnInfo(name = "pack_factor") val packFactor: Int,
    @ColumnInfo(name = "qty_base") val qtyBase: Long,
    @ColumnInfo(name = "price_type") val priceType: String,
    @ColumnInfo(name = "price_valid_from") val priceValidFrom: String,
    @ColumnInfo(name = "base_price_mtk") val basePriceMtk: Long,
    @ColumnInfo(name = "price_per_qty") val pricePerQty: Int,
    @ColumnInfo(name = "gross_mtk") val grossMtk: Long,
    @ColumnInfo(name = "offer_id") val offerId: Long? = null,
)

/** One discount component of a memo (record `memo_discount`, rank 2). */
@Entity(tableName = "memo_discount", indices = [Index("memo_client_uuid"), Index("business_date")])
data class MemoDiscountEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @Embedded val meta: CaptureMeta,
    @ColumnInfo(name = "memo_client_uuid") val memoClientUuid: String,
    val kind: String,
    @ColumnInfo(name = "sku_id") val skuId: Long? = null,
    @ColumnInfo(name = "qty_base") val qtyBase: Long? = null,
    @ColumnInfo(name = "value_mtk") val valueMtk: Long,
    @ColumnInfo(name = "offer_id") val offerId: Long? = null,
    @ColumnInfo(name = "offer_version_id") val offerVersionId: Long? = null,
    @ColumnInfo(name = "basis_qty_base") val basisQtyBase: Long? = null,
    @ColumnInfo(name = "line_no") val lineNo: Int? = null,
)

/** Quality-complaint line (record `qc_line`, rank 2 of the visit family). */
@Entity(tableName = "qc_line", indices = [Index("visit_client_uuid"), Index("memo_client_uuid"), Index("business_date")])
data class QcLineEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @Embedded val meta: CaptureMeta,
    @ColumnInfo(name = "visit_client_uuid") val visitClientUuid: String,
    @ColumnInfo(name = "memo_client_uuid") val memoClientUuid: String? = null,
    @ColumnInfo(name = "applied_to_memo") val appliedToMemo: Boolean,
    @ColumnInfo(name = "sku_id") val skuId: Long,
    @ColumnInfo(name = "fault_type_code") val faultTypeCode: String,
    @ColumnInfo(name = "fault_group") val faultGroup: String,
    @ColumnInfo(name = "qty_base") val qtyBase: Long,
    @ColumnInfo(name = "unit_price_mtk") val unitPriceMtk: Long,
    @ColumnInfo(name = "settlement_mtk") val settlementMtk: Long,
)
