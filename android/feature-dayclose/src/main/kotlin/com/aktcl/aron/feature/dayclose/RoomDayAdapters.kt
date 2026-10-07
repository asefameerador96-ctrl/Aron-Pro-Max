package com.aktcl.aron.feature.dayclose

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.DaySubmitEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * Room reads for Sales Submit (production [DaySource]). Unsent, rejected and quarantined counts are taken over the whole
 * outbox, not only today's rows: anything still unsent blocks the submit, which is the safe side.
 */
class RoomDaySource(private val db: AronDatabase) : DaySource {
    private val outbox = db.outboxDao()
    private val capture = db.captureDao()

    override suspend fun outboxStates(businessDate: String): OutboxStates {
        val pending = outbox.countInState("pending")
        val inFlight = outbox.countInState("in_flight")
        return OutboxStates(pending, inFlight, outbox.countInState("acked"), outbox.countInState("rejected"), outbox.countInState("quarantined"))
    }

    override suspend fun deviceCounts(businessDate: String): Map<String, Int> =
        outbox.committedCounts(businessDate).associate { it.recordType to it.count }

    /** Retailers who still owe, over the 7-day window: live memos with a remaining due after the collections against them. */
    override suspend fun dues(businessDate: String): DuesAtSubmit {
        val from = LocalDate.parse(businessDate).minusDays(6).toString()
        val memos = capture.memosBetween(from, businessDate)
        val superseded = capture.supersededIn(from, businessDate).toSet()
        val owing = memos.filter { it.clientUuid !in superseded }.mapNotNull { m ->
            val left = (m.dueMtk - capture.dueCollectionsOf(m.clientUuid).sumOf { it.amountMtk }).coerceAtLeast(0)
            if (left > 0) m.outletId to left else null
        }
        return DuesAtSubmit(owing.map { it.first }.toSet().size, owing.sumOf { it.second })
    }

    override suspend fun alreadySubmitted(businessDate: String): Boolean = capture.daySubmitsOn(businessDate).isNotEmpty()

    override suspend fun submitSettled(businessDate: String): Boolean =
        capture.daySubmitsOn(businessDate).lastOrNull()?.let { outbox.byClientUuid(it.clientUuid)?.state == "acked" } == true
}

/** Money and quantity totals of the day for the `day_submit` record (contract `MoneyTotals`); built from stored rows only. */
object MoneyTotalsJson {
    suspend fun build(db: AronDatabase, businessDate: String, categoryOf: (Long) -> String): JsonObject {
        val dao = db.captureDao()
        val all = dao.memosOn(businessDate)
        val superseded = all.mapNotNull { it.supersedesClientUuid }.toSet()
        val live = all.filter { it.clientUuid !in superseded }
        val lines = live.flatMap { dao.linesOf(it.clientUuid) }
        val net = live.sumOf { it.netMtk }
        val byCategory = lines.groupBy { categoryOf(it.skuId) }.mapValues { (_, ls) -> ls.sumOf { it.grossMtk } }.toMutableMap()
        val unallocated = net - byCategory.values.sum()
        if (unallocated != 0L) byCategory["other"] = (byCategory["other"] ?: 0L) + unallocated // discounts and QC not tied to a SKU
        return buildJsonObject {
            put("active_memo_count", live.count { it.lineCount > 0 })
            put("gross_mtk", live.sumOf { it.grossMtk })
            put("offer_discount_mtk", live.sumOf { it.offerDiscountMtk })
            put("drp_discount_mtk", live.sumOf { it.drpDiscountMtk })
            put("qc_deduction_mtk", live.sumOf { it.qcDeductionMtk })
            put("net_mtk", net)
            put("paid_mtk", live.sumOf { it.paidMtk })
            put("due_mtk", live.sumOf { it.dueMtk })
            put("due_collected_mtk", dao.dueCollectionsOn(businessDate).sumOf { it.amountMtk })
            put("net_by_category_mtk", JsonObject(byCategory.toSortedMap().mapValues { JsonPrimitive(it.value) }))
            put("issued_qty_base_by_sku", JsonObject(dao.stockBalanceOn(businessDate).associate { it.skuId.toString() to JsonPrimitive(it.qtyBase) }.toSortedMap()))
            put("sold_qty_base_by_sku", JsonObject(lines.groupBy { it.skuId }.mapValues { (_, ls) -> JsonPrimitive(ls.sumOf { it.qtyBase }) }.mapKeys { it.key.toString() }.toSortedMap()))
        }
    }
}

/** Queues the `day_submit` as the route-day's last record ([CaptureRepository.recordDaySubmit]). */
class RoomDaySubmitWriter(
    private val db: AronDatabase,
    private val repo: CaptureRepository,
    private val meta: () -> CaptureMeta,
    private val categoryOf: (Long) -> String,
    private val stockSlipPrinted: suspend () -> Boolean = { false },
    private val newUuid: () -> String = ClientIds::newUuid,
) : DaySubmitWriter {
    override suspend fun queue(businessDate: String, gate: SubmitGate, counts: List<CountRow>) {
        val m = meta()
        val cycle = (db.captureDao().daySubmitsFor(businessDate, m.routeId).maxOfOrNull { it.submitCycle } ?: 0) + 1
        val countsJson = JsonObject(counts.associate { it.recordType to JsonPrimitive(it.device) })
        val rejected = gate.notes.filterIsInstance<SubmitBlock.Rejected>().sumOf { it.count }
        val quarantined = gate.notes.filterIsInstance<SubmitBlock.Quarantined>().sumOf { it.count }
        val dues = gate.duesWarning
        repo.recordDaySubmit(
            DaySubmitEntity(
                clientUuid = newUuid(), meta = m, scope = "route_day", submitCycle = cycle,
                deviceCountsJson = countsJson.toString(), deviceMoneyJson = MoneyTotalsJson.build(db, businessDate, categoryOf).toString(),
                rejectedCount = rejected, quarantinedCount = quarantined, pendingCount = 0,
                submittedWithDues = dues != null, duesOutstandingMtk = dues?.outstandingMtk ?: 0L, retailersWithDues = dues?.retailersWithDues ?: 0,
                stockSlipPrinted = stockSlipPrinted(),
            ),
        )
    }
}
