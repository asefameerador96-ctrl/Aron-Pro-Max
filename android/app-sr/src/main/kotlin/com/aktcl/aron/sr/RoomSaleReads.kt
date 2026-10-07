package com.aktcl.aron.sr

import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.feature.memo.domain.DaySummaryCalculator
import com.aktcl.aron.feature.memo.domain.HomeMoney
import com.aktcl.aron.feature.memo.domain.HomeMoneyBuilder
import com.aktcl.aron.feature.memo.domain.Journey
import com.aktcl.aron.feature.memo.domain.JourneyBuilder
import com.aktcl.aron.feature.memo.domain.JourneyVisit
import com.aktcl.aron.feature.memo.domain.KpiStrip
import com.aktcl.aron.feature.memo.domain.KpiStripBuilder
import com.aktcl.aron.feature.memo.domain.PlannedOutlet
import com.aktcl.aron.feature.memo.domain.SummaryDiscount
import com.aktcl.aron.feature.memo.domain.SummaryLine
import com.aktcl.aron.feature.memo.domain.SummaryMemo
import com.aktcl.aron.feature.memo.domain.SummarySku
import com.aktcl.aron.feature.memo.ui.RoomMemoStore
import com.aktcl.aron.feature.sale.domain.SaleSku
import com.aktcl.aron.feature.stock.StockTracker

/**
 * The Room reads behind the sale-side screens (docs/status/android-sr-b.md "Production adapters"): the sale catalog from
 * bundle prices and the stock tracker, the day summary, the journey and the KPI strip. Only a database; no network, no clock.
 */
class RoomSaleReads(private val db: AronDatabase, private val reference: ReferenceRepository) {
    private val dao get() = db.captureDao()
    private val store = RoomMemoStore(db)

    /** Priced SKUs of [priceType] on [date]; current stock = issued minus sold on live memos, null when the SKU has no stock row. */
    suspend fun catalog(date: String, priceType: String): List<SaleSku> {
        val stock = StockTracker.of(db, date)
        return reference.skus().mapNotNull { s ->
            val p = db.referenceDao().priceOn(s.skuId, priceType, date) ?: return@mapNotNull null
            SaleSku(
                skuId = s.skuId, code = s.code, categoryCode = s.categoryCode, name = s.shortName, nameBn = s.nameBn, baseUnit = s.baseUnit,
                packFactor = s.basePerPack, unitPriceMtk = p.amountMtk, pricePerQty = p.perBaseQty, priceValidFrom = p.validFrom,
                stockBase = stock[s.skuId]?.takeIf { it.hasStockRow }?.current,
            )
        }
    }

    /** Today's summary from stored memo rows and the stock tracker (never from the screen). */
    suspend fun summary(date: String): SummaryBundle {
        val cats = reference.skus().associate { it.skuId to SummarySku(it.skuId, it.categoryCode) }
        val memos = store.memos(date)
        val issued = StockTracker.of(db, date).mapValues { (_, b) -> b.issued + b.adjusted - b.qcReturned }
        val sm = memos.map { m ->
            SummaryMemo(m.memoUuid, m.supersedesUuid, m.lines.map { SummaryLine(it.skuId, it.qtyBase, it.grossMtk) }, m.discounts.map { SummaryDiscount(it.skuId, it.valueMtk, it.kind) }, m.qcDeductionMtk, m.netMtk)
        }
        val superseded = memos.mapNotNull { it.supersedesUuid }.toSet()
        val live = memos.filter { it.memoUuid !in superseded }
        return SummaryBundle(DaySummaryCalculator.compute(sm, cats, issued), live.sumOf { it.qcDeductionMtk }, live.sumOf { it.dueMtk })
    }

    /** Route progress of the planned [outlets] from stored visits and closes (F-SR-067). */
    suspend fun journey(date: String, outlets: List<OutletEntity>): Journey {
        val outletOfVisit = dao.visitsOn(date).associate { it.clientUuid to it.outletId }
        val visits = dao.visitClosesOn(date).mapNotNull { c -> outletOfVisit[c.visitClientUuid]?.let { JourneyVisit(it, c.outcomeCode) } }
        val planned = outlets.mapIndexed { i, o -> PlannedOutlet(o.outletId, o.name, o.visitSequence ?: (i + 1)) }
        return JourneyBuilder.build(planned, visits)
    }

    /** The KPI strip and the two money cards, all from Room (airplane mode safe, F-SR-010/069). */
    suspend fun kpi(date: String, outlets: List<OutletEntity>): Pair<KpiStrip, HomeMoney> {
        val j = journey(date, outlets)
        val skus = reference.skus()
        val cats = skus.associate { it.skuId to SummarySku(it.skuId, it.categoryCode) }
        val memos = store.memos(date)
        val stock = StockTracker.of(db, date)
        val issue = stock.mapValues { (_, b) -> b.issued }.filterValues { it != 0L }
        val current = stock.filterValues { it.hasStockRow }.mapValues { (_, b) -> b.current }
        val unitByCat = skus.groupBy { it.categoryCode }.mapValues { (_, v) -> v.first().baseUnit }
        return KpiStripBuilder.build(j.planned, j.visited, j.sold, issue, current, cats) { unitByCat[it].orEmpty() } to HomeMoneyBuilder.build(memos, cats)
    }
}
