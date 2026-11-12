package com.aktcl.aron.feature.sale.domain

import java.time.LocalDate

/**
 * The three reference figures under the quantity popup (SR manual s8 / 22, D-SR-MAN-S4-05): the outlet's quantity of this SKU on the previous
 * sale day, the average daily sales (ADS) and the same weekday last week (SDLW). Quantities are in the SKU's entry [unit] (packets, boxes,
 * dozens). [adsHundredths] is the ADS times 100 so the figure keeps its two decimals with integer math ("3.33" is 333). Null = unknown ("N/A").
 */
data class SkuStats(
    val unit: String,
    val previousDay: Long? = null,
    val adsHundredths: Long? = null,
    val sdlw: Long? = null,
)

/** One line the outlet bought on [date] (`yyyy-MM-dd`): the entered quantity and the unit it was entered in. */
data class StatRow(val date: String, val skuId: Long, val qtyEntered: Long, val unit: String)

object SkuStatsBuilder {
    /**
     * Previous day and SDLW from the outlet's sales already on the phone ([rows], the 7-day local window), for the day [today]. The previous
     * day is the latest sale day before today for that SKU; SDLW is exactly seven days back. Lines of an earlier unit are not mixed in.
     * [adsHundredthsOf] is the server-computed ADS from the bundle's `outlet_sku_stats` (TRAIN 2): until it exists the ADS stays unknown.
     */
    fun build(rows: List<StatRow>, today: String, adsHundredthsOf: (Long) -> Long? = { null }): Map<Long, SkuStats> {
        val t = runCatching { LocalDate.parse(today) }.getOrNull() ?: return emptyMap()
        val lastWeek = t.minusDays(7).toString()
        return rows.groupBy { it.skuId }.mapValues { (skuId, rs) ->
            val past = rs.filter { it.date < today }
            val unit = past.maxByOrNull { it.date }?.unit ?: rs.first().unit
            val same = past.filter { it.unit == unit }
            val prevDate = same.maxOfOrNull { it.date }
            SkuStats(
                unit = unit,
                previousDay = prevDate?.let { d -> same.filter { it.date == d }.sumOf { it.qtyEntered } },
                adsHundredths = adsHundredthsOf(skuId),
                sdlw = same.filter { it.date == lastWeek }.takeIf { it.isNotEmpty() }?.sumOf { it.qtyEntered },
            )
        }
    }
}

/** Quick-button steps in the entry unit (rule 5, audit defaults Q10): cigarette and bidi packets, lighter boxes, match dozens. */
object QuickSteps {
    fun forCategory(categoryCode: String): List<Long> = when (categoryCode) {
        "lighter" -> listOf(1, 5, 10)
        "match" -> listOf(1, 5, 10, 20)
        else -> listOf(1, 5, 10, 20, 50, 100)
    }
}
