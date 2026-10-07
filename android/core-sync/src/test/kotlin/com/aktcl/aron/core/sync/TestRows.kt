package com.aktcl.aron.core.sync

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.MemoDiscountEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.QcLineEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.entity.VisitCloseEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.SaleCapture

/** Builders of realistic SR-day rows (a copy of core-database's test TestRows: test sources are not shared between modules). */
object TestRows {
    fun meta(routeId: Long? = 10231, offline: Boolean = true) = CaptureMeta(
        businessDate = "2026-10-05", capturedAt = "2026-10-05T04:31:07.120Z", capturedElapsedMs = 9_120_331, bootCount = 41,
        clockOffsetMs = -1_250, capturedOffline = offline, routeId = routeId, bundleVersion = "2026-10-05:3", configVersion = 318,
    )

    fun fix(owner: String, purpose: String = "visit_open", uuid: String = ClientIds.newUuid()) = GeoFixEntity(
        clientUuid = uuid, ownerClientUuid = owner, purpose = purpose, fixStatus = "ok", lat = 23.793812345678, lng = 90.404112349,
        accuracyM = 11.5, provider = "fused", fixTime = "2026-10-05T04:31:05.000Z", fixElapsedRealtimeMs = 9_118_000, fixAgeMs = 2_331,
        timeToFixMs = 3_200, requestPriority = "balanced", isMock = false, reused = false, refreshCount = 0,
        gnssJson = """{"window_ms":3200,"satellites_visible":14,"satellites_used":9,"constellations_used":["GPS","GALILEO"]}""",
        deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
    )

    fun attendance(uuid: String = ClientIds.newUuid()): Pair<AttendanceEventEntity, GeoFixEntity> {
        val f = fix(uuid, "attendance_in")
        return AttendanceEventEntity(uuid, meta(routeId = null), "check_in", f.clientUuid, null) to f
    }

    fun visit(uuid: String = ClientIds.newUuid(), outletId: Long = 50001, seq: Int = 1): Pair<VisitEntity, GeoFixEntity> {
        val f = fix(uuid)
        return VisitEntity(
            clientUuid = uuid, meta = meta(), visitKind = "sr_call", outletId = outletId, openedAt = "2026-10-05T04:31:07.120Z",
            sequenceNo = seq, planned = true, fixClientUuid = f.clientUuid, geoVerdict = "in_range", geoDistanceM = 18.4,
            geoRadiusMUsed = 100, geoMaxAccuracyMUsed = 50, geoLocationBasis = "master", geoOutletLat = 23.7937, geoOutletLng = 90.4042,
            geoAction = "sale_allowed",
        ) to f
    }

    fun stock(skuId: Long = 100, qty: Long = 10, uuid: String = ClientIds.newUuid()) = StockMovementEntity(
        uuid, meta(), "issue", skuId, qty, "pack", 10, qty * 10, null, false,
    )

    fun sale(visitUuid: String, memoNo: String = "sr334001-261005-017", memoUuid: String = ClientIds.newUuid()): SaleCapture {
        val lines = listOf(
            MemoLineEntity(ClientIds.newUuid(), meta(), memoUuid, 1, 100, "sale", 2, "pack", 10, 20, "outlet", "2026-09-01", 9_000, 1, 180_000),
            MemoLineEntity(ClientIds.newUuid(), meta(), memoUuid, 2, 103, "sale", 3, "piece", 1, 3, "outlet", "2026-09-01", 12_500, 1, 37_500),
        )
        val discounts = listOf(MemoDiscountEntity(ClientIds.newUuid(), meta(), memoUuid, "offer", 100, 20, 7_500, offerId = 9, offerVersionId = 12, lineNo = 1))
        val qc = listOf(QcLineEntity(ClientIds.newUuid(), meta(), visitUuid, memoUuid, true, 100, "torn_pack", "MFC", 2, 9_000, 18_000))
        val memo = MemoEntity(
            clientUuid = memoUuid, meta = meta(), visitClientUuid = visitUuid, outletId = 50001, memoNo = memoNo, memoKind = "sale",
            committedAt = "2026-10-05T04:35:00.000Z", priceListDate = "2026-10-05", priceType = "outlet", grossMtk = 217_500,
            offerDiscountMtk = 7_500, drpDiscountMtk = 0, qcDeductionMtk = 18_000, roundAdjMtk = 0, netMtk = 192_000, paidMtk = 192_000,
            dueMtk = 0, isCredit = false, lineCount = 2, discountLineCount = 1, qcLineCount = 1, offerVersionIdsJson = "[12]",
        )
        return SaleCapture(memo, lines, discounts, qc)
    }

    fun close(visitUuid: String, uuid: String = ClientIds.newUuid()) =
        VisitCloseEntity(uuid, meta(), visitUuid, "sold", "2026-10-05T04:32:00.000Z", false, "2026-10-05T04:36:00.000Z", false)
}
