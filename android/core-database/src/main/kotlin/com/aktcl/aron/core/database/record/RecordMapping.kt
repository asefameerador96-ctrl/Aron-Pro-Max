package com.aktcl.aron.core.database.record

import com.aktcl.aron.contract.ContractInfo
import com.aktcl.aron.contract.RecordType
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
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
    /** Encodes payloads: only declared members, nulls omitted (the server treats omitted and null the same, s3.1 item 3). */
    val json: Json = Json { encodeDefaults = true; explicitNulls = false }

    /** WGS84 degrees with at most 7 decimals (docs/24 s3.1 item 5). */
    fun coord(value: Double?): Double? =
        value?.takeIf { it.isFinite() }?.let { BigDecimal(it).setScale(7, RoundingMode.HALF_EVEN).toDouble() }

    fun fix(e: GeoFixEntity): GeoFixPayload = GeoFixPayload(
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
        fixAgeMs = e.fixAgeMs,
        timeToFixMs = e.timeToFixMs,
        requestPriority = e.requestPriority,
        isMock = e.isMock,
        reused = e.reused,
        refreshCount = e.refreshCount,
        gnss = e.gnssJson?.let { json.parseToJsonElement(it).jsonObject },
        radio = e.radioJson?.let { json.parseToJsonElement(it).jsonObject },
        device = FixDeviceStatePayload(e.deviceOwner, e.devOptionsEnabled, e.adbEnabled, e.autoTimeEnabled, e.mockAppPresent, e.integrityRef),
    )

    fun attendance(e: AttendanceEventEntity, fix: GeoFixEntity, createdAt: String) = outbox(
        RecordType.ATTENDANCE_EVENT, e.clientUuid, e.clientUuid, 0, e.meta, createdAt,
        AttendanceEventPayload.serializer(), AttendanceEventPayload(e.kind, fix(fix), e.addressDisplay),
    )

    fun stock(e: StockMovementEntity, createdAt: String) = outbox(
        RecordType.STOCK_MOVEMENT, e.clientUuid, e.clientUuid, 0, e.meta, createdAt,
        StockMovementPayload.serializer(),
        StockMovementPayload(e.kind, e.skuId, e.qtyEntered, e.unitEntered, e.packFactor, e.qtyBase, e.reasonCode, e.slipPrinted),
    )

    fun visit(e: VisitEntity, fix: GeoFixEntity, createdAt: String) = outbox(
        RecordType.VISIT, e.clientUuid, e.clientUuid, 0, e.meta, createdAt,
        VisitPayload.serializer(),
        VisitPayload(
            visitKind = e.visitKind, outletId = e.outletId, openedAt = e.openedAt, sequenceNo = e.sequenceNo, planned = e.planned,
            assessedUserId = e.assessedUserId, fix = fix(fix),
            geo = DeviceGeoVerdictPayload(
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
            roundAdjMtk = e.roundAdjMtk, netMtk = e.netMtk, paidMtk = e.paidMtk, dueMtk = e.dueMtk, isCredit = e.isCredit,
            outstandingBeforeMtk = e.outstandingBeforeMtk, lineCount = e.lineCount, discountLineCount = e.discountLineCount,
            qcLineCount = e.qcLineCount, supersedesClientUuid = e.supersedesClientUuid, editReasonCode = e.editReasonCode,
            editFix = editFix?.let(::fix), offerVersionIds = json.decodeFromString(e.offerVersionIdsJson), roundingMode = e.roundingMode,
        ),
    )

    fun memoLine(e: MemoLineEntity, familyUuid: String, createdAt: String) = outbox(
        RecordType.MEMO_LINE, e.clientUuid, familyUuid, 2, e.meta, createdAt,
        MemoLinePayload.serializer(),
        MemoLinePayload(
            e.memoClientUuid, e.lineNo, e.skuId, e.lineKind, e.qtyEntered, e.unitEntered, e.packFactor, e.qtyBase,
            e.priceType, e.priceValidFrom, e.basePriceMtk, e.pricePerQty, e.grossMtk, e.offerId,
        ),
    )

    fun memoDiscount(e: MemoDiscountEntity, familyUuid: String, createdAt: String) = outbox(
        RecordType.MEMO_DISCOUNT, e.clientUuid, familyUuid, 2, e.meta, createdAt,
        MemoDiscountPayload.serializer(),
        MemoDiscountPayload(e.memoClientUuid, e.kind, e.skuId, e.qtyBase, e.valueMtk, e.offerId, e.offerVersionId, e.basisQtyBase, e.lineNo),
    )

    fun qcLine(e: QcLineEntity, createdAt: String) = outbox(
        RecordType.QC_LINE, e.clientUuid, e.visitClientUuid, 2, e.meta, createdAt,
        QcLinePayload.serializer(),
        QcLinePayload(e.visitClientUuid, e.memoClientUuid, e.appliedToMemo, e.skuId, e.faultTypeCode, e.faultGroup, e.qtyBase, e.unitPriceMtk, e.settlementMtk),
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

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
