package com.aktcl.aron.core.database.record

import com.aktcl.aron.contract.AttendanceEventPayload
import com.aktcl.aron.contract.ContractInfo
import com.aktcl.aron.contract.DeviceGeoVerdict
import com.aktcl.aron.contract.DeviceStatusReport
import com.aktcl.aron.contract.DeviceStatusReportWire
import com.aktcl.aron.contract.FixDeviceState
import com.aktcl.aron.contract.GeoFix
import com.aktcl.aron.contract.GnssSummary
import com.aktcl.aron.contract.MemoDiscountPayload
import com.aktcl.aron.contract.MemoLinePayload
import com.aktcl.aron.contract.MemoPayload
import com.aktcl.aron.contract.QcLinePayload
import com.aktcl.aron.contract.StockMovementPayload
import com.aktcl.aron.contract.VisitClosePayload
import com.aktcl.aron.contract.VisitPayload
import com.aktcl.aron.contract.RecordType
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.DaySubmitEntity
import com.aktcl.aron.core.database.entity.DueCollectionEntity
import com.aktcl.aron.core.database.entity.OutletChangeRequestEntity
import com.aktcl.aron.core.database.entity.PrintEventEntity
import com.aktcl.aron.core.database.entity.TaskEventEntity
import com.aktcl.aron.core.database.entity.VisitSkipEntity
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.MemoDiscountEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.OutboxEntity
import com.aktcl.aron.core.database.entity.QcLineEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.entity.VisitCloseEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest

/**
 * Turns committed domain rows into the sync records of docs/24 s4.3: envelope + payload, built from the stored row so the
 * outbox can never say something the database does not. Family and rank follow the table of s4.2.
 */
object RecordMapping {
    /**
     * Encodes payloads with the contract's null rules: a member WITHOUT a default in the DTO is a required member and is
     * always written, as `null` when it has no value (GeoFix `lat`, `lng`, `accuracy_m` on a fix that failed); a member
     * WITH a default is optional and is left out while it holds its default. The server rejects a payload that omits a
     * required member (`schema_invalid`), and some optional members may not be null (`refresh_count`).
     */
    val json: Json = Json { encodeDefaults = false; explicitNulls = true }

    /** Reads stored side JSON (the GNSS summary) leniently: a member this build does not know never fails a capture. */
    private val lenient: Json = Json { ignoreUnknownKeys = true }

    /** A stored quantity or amount as the contract's int32; a value outside it fails the capture before anything is written. */
    private fun int(value: Long): Int = Math.toIntExact(value)

    /** A duration in ms as int32, saturating (a fix age is evidence, never a reason to refuse a capture). */
    private fun clampInt(value: Long): Int = value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    /** WGS84 degrees with at most 7 decimals (docs/24 s3.1 item 5). */
    fun coord(value: Double?): Double? =
        value?.takeIf { it.isFinite() }?.let { BigDecimal(it).setScale(7, RoundingMode.HALF_EVEN).toDouble() }

    fun fix(e: GeoFixEntity): GeoFix = GeoFix(
        purpose = e.purpose,
        fixStatus = e.fixStatus,
        lat = coord(e.lat),
        lng = coord(e.lng),
        accuracyM = e.accuracyM,
        altitudeM = e.altitudeM,
        verticalAccuracyM = e.verticalAccuracyM,
        speedMps = e.speedMps,
        bearingDeg = e.bearingDeg,
        provider = e.provider,
        fixTime = e.fixTime,
        fixElapsedRealtimeMs = e.fixElapsedRealtimeMs,
        fixAgeMs = e.fixAgeMs?.let(::clampInt),
        timeToFixMs = e.timeToFixMs?.let(::clampInt),
        requestPriority = e.requestPriority,
        isMock = e.isMock,
        reused = e.reused,
        refreshCount = e.refreshCount,
        // A stored summary that is not a contract GnssSummary is left out rather than failing the sale; the server would
        // reject the whole record as schema_invalid otherwise.
        gnss = e.gnssJson?.let { runCatching { lenient.decodeFromString(GnssSummary.serializer(), it) }.getOrNull() },
        radio = e.radioJson?.let { json.parseToJsonElement(it).jsonObject },
        device = FixDeviceState(e.deviceOwner, e.devOptionsEnabled, e.adbEnabled, e.autoTimeEnabled, e.mockAppPresent, e.integrityRef),
    )

    fun attendance(e: AttendanceEventEntity, fix: GeoFixEntity, createdAt: String) = outbox(
        RecordType.ATTENDANCE_EVENT, e.clientUuid, e.clientUuid, 0, e.meta, createdAt,
        AttendanceEventPayload.serializer(), AttendanceEventPayload(e.kind, fix(fix), e.addressDisplay),
    )

    fun stock(e: StockMovementEntity, createdAt: String) = outbox(
        RecordType.STOCK_MOVEMENT, e.clientUuid, e.clientUuid, 0, e.meta, createdAt,
        StockMovementPayload.serializer(),
        StockMovementPayload(e.kind, e.skuId, int(e.qtyEntered), e.unitEntered, e.packFactor, int(e.qtyBase), e.reasonCode, e.slipPrinted),
    )

    fun visit(e: VisitEntity, fix: GeoFixEntity, createdAt: String) = outbox(
        RecordType.VISIT, e.clientUuid, e.clientUuid, 0, e.meta, createdAt,
        VisitPayload.serializer(),
        VisitPayload(
            visitKind = e.visitKind, outletId = e.outletId, openedAt = e.openedAt, sequenceNo = e.sequenceNo, planned = e.planned,
            assessedUserId = e.assessedUserId, fix = fix(fix),
            geo = DeviceGeoVerdict(
                e.geoVerdict, e.geoDistanceM, e.geoRadiusMUsed, e.geoMaxAccuracyMUsed, e.geoLocationBasis,
                coord(e.geoOutletLat), coord(e.geoOutletLng), e.geoAction, e.geoForceReasonCode, e.geoForcePhotoUuid,
            ),
        ),
    )

    fun visitClose(e: VisitCloseEntity, createdAt: String) = outbox(
        RecordType.VISIT_CLOSE, e.clientUuid, e.visitClientUuid, 1, e.meta, createdAt,
        VisitClosePayload.serializer(),
        VisitClosePayload(e.visitClientUuid, e.outcomeCode, e.callStartedAt, e.callDeclined, e.endedAt, e.isZeroSale),
    )

    fun memo(e: MemoEntity, editFix: GeoFixEntity?, createdAt: String) = outbox(
        RecordType.MEMO, e.clientUuid, e.visitClientUuid, 1, e.meta, createdAt,
        MemoPayload.serializer(),
        MemoPayload(
            visitClientUuid = e.visitClientUuid, outletId = e.outletId, memoNo = e.memoNo, memoKind = e.memoKind,
            committedAt = e.committedAt, priceListDate = e.priceListDate, priceType = e.priceType, grossMtk = e.grossMtk,
            offerDiscountMtk = e.offerDiscountMtk, drpDiscountMtk = e.drpDiscountMtk, qcDeductionMtk = e.qcDeductionMtk,
            roundAdjMtk = int(e.roundAdjMtk), netMtk = e.netMtk, paidMtk = e.paidMtk, dueMtk = e.dueMtk, isCredit = e.isCredit,
            outstandingBeforeMtk = e.outstandingBeforeMtk, lineCount = e.lineCount, discountLineCount = e.discountLineCount,
            qcLineCount = e.qcLineCount, supersedesClientUuid = e.supersedesClientUuid, editReasonCode = e.editReasonCode,
            editFix = editFix?.let(::fix), offerVersionIds = json.decodeFromString<List<Long>>(e.offerVersionIdsJson).ifEmpty { null }, roundingMode = e.roundingMode,
        ),
    )

    fun memoLine(e: MemoLineEntity, familyUuid: String, createdAt: String) = outbox(
        RecordType.MEMO_LINE, e.clientUuid, familyUuid, 2, e.meta, createdAt,
        MemoLinePayload.serializer(),
        MemoLinePayload(
            e.memoClientUuid, e.lineNo, e.skuId, e.lineKind, int(e.qtyEntered), e.unitEntered, e.packFactor, int(e.qtyBase),
            e.priceType, e.priceValidFrom, e.basePriceMtk, e.pricePerQty, e.grossMtk, e.offerId,
        ),
    )

    fun memoDiscount(e: MemoDiscountEntity, familyUuid: String, createdAt: String) = outbox(
        RecordType.MEMO_DISCOUNT, e.clientUuid, familyUuid, 2, e.meta, createdAt,
        MemoDiscountPayload.serializer(),
        MemoDiscountPayload(e.memoClientUuid, e.kind, e.skuId, e.qtyBase?.let(::int), e.valueMtk, e.offerId, e.offerVersionId, e.basisQtyBase?.let(::int), e.lineNo),
    )

    fun qcLine(e: QcLineEntity, createdAt: String) = outbox(
        RecordType.QC_LINE, e.clientUuid, e.visitClientUuid, 2, e.meta, createdAt,
        QcLinePayload.serializer(),
        QcLinePayload(e.visitClientUuid, e.memoClientUuid, e.appliedToMemo, e.skuId, e.faultTypeCode, e.faultGroup, int(e.qtyBase), e.unitPriceMtk, e.settlementMtk),
    )

    /** A due collection is its own family (rank 0, a signed header record; docs/24 s4.2); its visit is a parent reference. */
    fun dueCollection(e: DueCollectionEntity, fix: GeoFixEntity?, createdAt: String) = outbox(
        RecordType.DUE_COLLECTION, e.clientUuid, e.clientUuid, 0, e.meta, createdAt,
        DueCollectionPayload.serializer(),
        DueCollectionPayload(
            e.outletId, e.againstMemoClientUuid, e.againstMemoNo, e.againstMemoBusinessDate, e.amountMtk, e.isFullSettlement,
            e.outstandingBeforeMtk, e.paymentMode, e.visitClientUuid, fix?.let(::fix),
        ),
    )

    fun visitSkip(e: VisitSkipEntity, createdAt: String) = outbox(
        RecordType.VISIT_SKIP, e.clientUuid, e.clientUuid, 0, e.meta, createdAt,
        VisitSkipPayload.serializer(), VisitSkipPayload(e.outletId, e.reasonCode),
    )

    /** Its own family at rank 0 (docs/24 s4.2); it is the route-day's last outbox record because nothing is committed after it. */
    fun daySubmit(e: DaySubmitEntity, createdAt: String) = outbox(
        RecordType.DAY_SUBMIT, e.clientUuid, e.clientUuid, 0, e.meta, createdAt,
        DaySubmitPayload.serializer(),
        DaySubmitPayload(
            e.scope, e.submitCycle, Json.parseToJsonElement(e.deviceCountsJson), Json.parseToJsonElement(e.deviceMoneyJson),
            e.rejectedCount, e.quarantinedCount, e.pendingCount, e.submittedWithDues, e.duesOutstandingMtk, e.retailersWithDues,
            e.stockSlipPrinted,
        ),
    )

    fun outletRequest(e: OutletChangeRequestEntity, fix: GeoFixEntity, createdAt: String) = outbox(
        RecordType.OUTLET_CHANGE_REQUEST, e.clientUuid, e.clientUuid, 0, e.meta, createdAt,
        OutletChangeRequestPayload.serializer(),
        OutletChangeRequestPayload(
            e.requestType, e.outletId, Json.parseToJsonElement(e.proposedJson), fix(fix),
            Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), e.photoUuidsJson),
            e.originVisitClientUuid, e.note,
        ),
    )

    fun taskEvent(e: TaskEventEntity, createdAt: String) = outbox(
        RecordType.TASK_EVENT, e.clientUuid, e.clientUuid, 0, e.meta, createdAt,
        TaskEventPayload.serializer(), TaskEventPayload(e.taskUuid, e.event, e.note),
    )

    /**
     * A phone status report (docs/24 s10.3, D24-53): its own family, rank 0, no route. There is no domain table: the outbox
     * row is the stored record. Encoded with [DeviceStatusReportWire] so `policy_version_applied` is written even when null.
     */
    fun deviceStatus(clientUuid: String, meta: CaptureMeta, report: DeviceStatusReport, createdAt: String) = outbox(
        RecordType.DEVICE_STATUS, clientUuid, clientUuid, 0, meta.copy(routeId = null, actingForUserId = null), createdAt,
        DeviceStatusReportWire, report,
    )

    /** A print event; [payload] is the core-printing `PrintEvent.payload()` (required nullable members written as null). */
    fun printEvent(e: PrintEventEntity, familyUuid: String, rank: Int, payload: JsonObject, createdAt: String) = outbox(
        RecordType.PRINT_EVENT, e.clientUuid, familyUuid, rank, e.meta, createdAt, JsonObject.serializer(), payload,
    )

    /** The record object of docs/24 s4.3: envelope members, then `payload`. `sig` is added by the signing row (Day 3). */
    fun <P> record(type: RecordType, clientUuid: String, familyUuid: String, rank: Int, meta: CaptureMeta, serializer: KSerializer<P>, payload: P): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive(type.wire))
            put("client_uuid", JsonPrimitive(clientUuid))
            put("family_uuid", JsonPrimitive(familyUuid))
            put("rank", JsonPrimitive(rank))
            put("schema_version", JsonPrimitive(ContractInfo.SCHEMA_VERSION))
            put("business_date", JsonPrimitive(meta.businessDate))
            put("captured_at", JsonPrimitive(meta.capturedAt))
            put("captured_elapsed_ms", JsonPrimitive(meta.capturedElapsedMs))
            put("boot_count", JsonPrimitive(meta.bootCount))
            put("clock_offset_ms", meta.clockOffsetMs?.let(::JsonPrimitive) ?: JsonNull)
            put("captured_offline", JsonPrimitive(meta.capturedOffline))
            meta.routeId?.let { put("route_id", JsonPrimitive(it)) }
            meta.actingForUserId?.let { put("acting_for_user_id", JsonPrimitive(it)) }
            put("bundle_version", meta.bundleVersion?.let(::JsonPrimitive) ?: JsonNull)
            put("bundle_stale", JsonPrimitive(meta.bundleStale))
            put("config_version", JsonPrimitive(meta.configVersion))
            put("payload", json.encodeToJsonElement(serializer, payload))
        }

    private fun <P> outbox(
        type: RecordType, clientUuid: String, familyUuid: String, rank: Int, meta: CaptureMeta, createdAt: String,
        serializer: KSerializer<P>, payload: P,
    ): OutboxEntity {
        val text = json.encodeToString(JsonElement.serializer(), record(type, clientUuid, familyUuid, rank, meta, serializer, payload))
        return OutboxEntity(
            clientUuid = clientUuid, recordType = type.wire, familyUuid = familyUuid, rank = rank, businessDate = meta.businessDate,
            payloadJson = text, payloadSha256 = sha256Hex(text), createdAt = createdAt,
        )
    }

    /**
     * The outbox's `payload_sha256`: SHA-256 of the exact `payload_json` bytes, a local integrity check of the stored
     * record. It is not the server's ingest hash, which the server computes itself over the RFC 8785 form (docs/24 s3.3).
     */
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
