package com.aktcl.aron.feature.memo.domain

/** Home KPI strip (F-SR-010), computed only from Room data so it works in airplane mode. Sales only: no targets (docs/27). */
data class KpiStrip(
    val plannedOutlets: Int,
    val visitedOutlets: Int,
    /** Percent with two decimals as an integer of hundredths (10.00 % = 1000); null when nothing is planned (s7.6). */
    val strikeRateHundredths: Int?,
    val nonVisitOutlets: Int,
    val noSaleOutlets: Int,
    val categories: List<KpiCategory>,
)

data class KpiCategory(val categoryCode: String, val issueBase: Long, val currentStockBase: Long, val unit: String)

object KpiStripBuilder {
    /** `strike = visited / planned`, half-up to hundredths from integers; 6 of 60 gives 10.00 %. */
    fun strikeRateHundredths(visited: Int, planned: Int): Int? =
        if (planned <= 0) null else ((visited.toLong() * 10_000L * 2 + planned) / (planned.toLong() * 2)).toInt()

    fun build(
        planned: Int,
        visited: Int,
        soldOutlets: Int,
        issueBySku: Map<Long, Long>,
        currentBySku: Map<Long, Long>,
        skus: Map<Long, SummarySku>,
        unitOf: (String) -> String,
    ): KpiStrip {
        val cats = (issueBySku.keys + currentBySku.keys).groupBy { skus[it]?.categoryCode ?: "" }.toSortedMap().map { (c, ids) ->
            KpiCategory(c, ids.sumOf { issueBySku[it] ?: 0L }, ids.sumOf { currentBySku[it] ?: 0L }, unitOf(c))
        }
        return KpiStrip(planned, visited, strikeRateHundredths(visited, planned), (planned - visited).coerceAtLeast(0), (visited - soldOutlets).coerceAtLeast(0), cats)
    }
}

/** One Home money card line per category and the two cards' totals (F-SR-069). */
data class MoneyCategory(val categoryCode: String, val valueMtk: Long, val discountMtk: Long, val qcMtk: Long, val netMtk: Long)
data class HomeMoney(
    val categories: List<MoneyCategory>,
    val grossMtk: Long,
    val totalDiscountMtk: Long,
    val drpDiscountMtk: Long,
    val totalQcMtk: Long,
    val grandTotalMtk: Long,
)

object HomeMoneyBuilder {
    fun build(memos: List<StoredMemo>, skus: Map<Long, SummarySku>): HomeMoney {
        val superseded = memos.mapNotNull { it.supersedesUuid }.toSet()
        val live = memos.filter { it.memoUuid !in superseded }
        val cats = live.flatMap { m -> m.lines.map { skus[it.skuId]?.categoryCode.orEmpty() to it } }.groupBy({ it.first }, { it.second }).toSortedMap().map { (c, ls) ->
            val value = ls.sumOf { it.grossMtk }
            val disc = live.sumOf { m -> m.discounts.filter { skus[it.skuId]?.categoryCode.orEmpty() == c && it.kind != "drp" }.sumOf { it.valueMtk } }
            val qc = live.sumOf { m -> m.qcLines.filter { skus[it.skuId]?.categoryCode.orEmpty() == c }.sumOf { it.settlementMtk } }
            MoneyCategory(c, value, disc, qc, value - disc - qc)
        }
        return HomeMoney(
            cats, live.sumOf { it.grossMtk }, live.sumOf { it.offerDiscountMtk + it.drpDiscountMtk }, live.sumOf { it.drpDiscountMtk },
            live.sumOf { it.qcDeductionMtk }, live.sumOf { it.netMtk },
        )
    }
}
