package com.aktcl.aron.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Room v3 (2026-10-07): the records the SR lanes asked for (docs/requests/android-sr-a-task-tables.md,
// android-sr-a-outlet-request-capture.md, android-sr-b-core-records.md) and the memo counter of F-SYS-027.

/** A due collected against an earlier credit memo (record `due_collection`). */
@Entity(tableName = "due_collection", indices = [Index("business_date"), Index("against_memo_client_uuid")])
data class DueCollectionEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @Embedded val meta: CaptureMeta,
    @ColumnInfo(name = "outlet_id") val outletId: Long,
    @ColumnInfo(name = "against_memo_client_uuid") val againstMemoClientUuid: String,
    @ColumnInfo(name = "against_memo_no") val againstMemoNo: String,
    @ColumnInfo(name = "against_memo_business_date") val againstMemoBusinessDate: String,
    @ColumnInfo(name = "amount_mtk") val amountMtk: Long,
    @ColumnInfo(name = "is_full_settlement") val isFullSettlement: Boolean,
    @ColumnInfo(name = "outstanding_before_mtk") val outstandingBeforeMtk: Long,
    @ColumnInfo(name = "payment_mode") val paymentMode: String = "cash",
    @ColumnInfo(name = "visit_client_uuid") val visitClientUuid: String? = null,
    @ColumnInfo(name = "fix_client_uuid") val fixClientUuid: String? = null,
)

/** An outlet of the route skipped without a visit (record `visit_skip`): no fix (F-SR-057). */
@Entity(tableName = "visit_skip", indices = [Index("business_date")])
data class VisitSkipEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @Embedded val meta: CaptureMeta,
    @ColumnInfo(name = "outlet_id") val outletId: Long,
    @ColumnInfo(name = "reason_code") val reasonCode: String,
)

/** Sales Submit of a route-day (record `day_submit`); always the last outbox record of its route-day. */
@Entity(tableName = "day_submit", indices = [Index("business_date")])
data class DaySubmitEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @Embedded val meta: CaptureMeta,
    val scope: String,
    @ColumnInfo(name = "submit_cycle") val submitCycle: Int,
    /** `TypeCounts` JSON. */
    @ColumnInfo(name = "device_counts_json") val deviceCountsJson: String,
    /** `MoneyTotals` JSON. */
    @ColumnInfo(name = "device_money_json") val deviceMoneyJson: String,
    @ColumnInfo(name = "rejected_count") val rejectedCount: Int,
    @ColumnInfo(name = "quarantined_count") val quarantinedCount: Int,
    @ColumnInfo(name = "pending_count") val pendingCount: Int,
    @ColumnInfo(name = "submitted_with_dues") val submittedWithDues: Boolean,
    @ColumnInfo(name = "dues_outstanding_mtk") val duesOutstandingMtk: Long,
    @ColumnInfo(name = "retailers_with_dues") val retailersWithDues: Int,
    @ColumnInfo(name = "stock_slip_printed") val stockSlipPrinted: Boolean,
)

/** A new outlet, an edit, a relocation or a closure asked for from the field (record `outlet_change_request`). */
@Entity(tableName = "outlet_change_request", indices = [Index("business_date")])
data class OutletChangeRequestEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @Embedded val meta: CaptureMeta,
    @ColumnInfo(name = "request_type") val requestType: String,
    @ColumnInfo(name = "outlet_id") val outletId: Long?,
    /** `OutletProposal` JSON. */
    @ColumnInfo(name = "proposed_json") val proposedJson: String,
    @ColumnInfo(name = "fix_client_uuid") val fixClientUuid: String,
    /** JSON array of media uuids. */
    @ColumnInfo(name = "photo_uuids_json") val photoUuidsJson: String = "[]",
    @ColumnInfo(name = "origin_visit_client_uuid") val originVisitClientUuid: String? = null,
    val note: String? = null,
)

/** A task event from the phone (record `task_event`), e.g. `resolved`. */
@Entity(tableName = "task_event", indices = [Index("task_uuid")])
data class TaskEventEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @Embedded val meta: CaptureMeta,
    @ColumnInfo(name = "task_uuid") val taskUuid: String,
    val event: String,
    val note: String? = null,
)

/** A task of the bundle (`Task`), reference data; its local status follows the phone's own task events. */
@Entity(tableName = "task", indices = [Index("outlet_id"), Index("status")])
data class TaskEntity(
    @PrimaryKey @ColumnInfo(name = "task_uuid") val taskUuid: String,
    @ColumnInfo(name = "task_type_code") val taskTypeCode: String,
    val title: String,
    val description: String?,
    @ColumnInfo(name = "outlet_id") val outletId: Long?,
    @ColumnInfo(name = "due_date") val dueDate: String?,
    val status: String,
    @ColumnInfo(name = "resolved_at") val resolvedAt: String?,
    /** The task as the server sent it, for fields the screens need beyond these columns. */
    val json: String,
)

/** The memo counter of one business date on this phone (F-SYS-027, docs/24 s7.5): the last `n` taken. */
@Entity(tableName = "memo_counter")
data class MemoCounterEntity(
    @PrimaryKey @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "last_n") val lastN: Int,
)

/** A final print outcome (record `print_event`, contract `PrintEventPayload`); immutable once written. */
@Entity(tableName = "print_event", indices = [Index("memo_client_uuid"), Index("ref_client_uuid")])
data class PrintEventEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @Embedded val meta: CaptureMeta,
    @ColumnInfo(name = "document_kind") val documentKind: String,
    @ColumnInfo(name = "memo_client_uuid") val memoClientUuid: String?,
    @ColumnInfo(name = "ref_client_uuid") val refClientUuid: String?,
    @ColumnInfo(name = "print_count") val printCount: Int,
    val outcome: String,
    @ColumnInfo(name = "user_confirmed") val userConfirmed: Boolean?,
    @ColumnInfo(name = "template_version") val templateVersion: Int,
    @ColumnInfo(name = "printer_model") val printerModel: String?,
    @ColumnInfo(name = "at_ms") val atMs: Long,
)

/** A print job not yet final (docs/17 s9.4), local only, never synced; keyed by the uuid its event will have. */
@Entity(tableName = "print_job")
data class PrintJobEntity(
    @PrimaryKey @ColumnInfo(name = "event_client_uuid") val eventClientUuid: String,
    @ColumnInfo(name = "document_kind") val documentKind: String,
    @ColumnInfo(name = "memo_client_uuid") val memoClientUuid: String?,
    @ColumnInfo(name = "ref_client_uuid") val refClientUuid: String?,
    @ColumnInfo(name = "print_count") val printCount: Int,
    val outcome: String,
    @ColumnInfo(name = "user_confirmed") val userConfirmed: Boolean?,
    @ColumnInfo(name = "template_version") val templateVersion: Int,
    @ColumnInfo(name = "printer_model") val printerModel: String?,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    @ColumnInfo(name = "paper_out") val paperOut: Boolean,
)
