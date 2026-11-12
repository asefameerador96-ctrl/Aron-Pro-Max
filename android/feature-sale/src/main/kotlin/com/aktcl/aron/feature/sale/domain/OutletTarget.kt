package com.aktcl.aron.feature.sale.domain

/**
 * The outlet's target for the month as the call-start popup and the target page show it (SR manual s8 / 21, D-SR-MAN-S4-02).
 * STD figures are base units (sticks, pieces, dozens: the storage rule); memo counts are whole memos. Everything is integer.
 * The data arrives with the bundle's `outlet_targets[]` (contract, TRAIN 2): until then the host passes null and the screens say so.
 */
data class OutletTarget(
    /** Free-text campaign name from the target upload (owner question Q9), e.g. "Super Star Low"; null when the upload has none. */
    val campaign: String?,
    /** The month in words as uploaded, e.g. "November". */
    val monthLabel: String,
    val targetStd: Long,
    val achievementStd: Long,
    val targetMemo: Long,
    val achievementMemo: Long,
    val rows: List<OutletTargetRow> = emptyList(),
) {
    val remainingStd: Long get() = (targetStd - achievementStd).coerceAtLeast(0)
    val remainingMemo: Long get() = (targetMemo - achievementMemo).coerceAtLeast(0)
}

/** One SKU of the target page: its target and what has been sold this month, both in base units. */
data class OutletTargetRow(val skuCode: String, val categoryCode: String, val target: Long, val sales: Long) {
    /** Whole percent achieved, 0 when there is no target (never a division by zero, never a float). */
    val percent: Long get() = if (target <= 0) 0 else sales * 100 / target
}

/** The category total rows under the SKU table (cigarette, lighter, match): the sums of the rows of that category. */
fun OutletTarget.categoryTotals(): List<OutletTargetRow> =
    rows.groupBy { it.categoryCode }.map { (cat, rs) -> OutletTargetRow(cat, cat, rs.sumOf { it.target }, rs.sumOf { it.sales }) }
        .sortedBy { listOf("cigarette", "bidi", "lighter", "match").indexOf(it.categoryCode).let { i -> if (i < 0) 99 else i } }
