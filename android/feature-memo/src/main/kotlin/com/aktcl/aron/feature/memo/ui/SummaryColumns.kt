package com.aktcl.aron.feature.memo.ui

/** The relative widths of the six Sales summary columns (memo, quantity, value, discount, discounted value, return): layout weights shared by the table and its docked block. */
internal object SummaryColumns {
    val shares: List<Float> = listOf(1.1f, 0.85f, 1.35f, 1.1f, 1.4f, 0.7f)

    /** The AMO's closing-block screen (D-PAR-AMO-16): the two discount heads get room so "Discount" and "After discount" no longer run together at 360 dp. */
    val amoShares: List<Float> = listOf(0.95f, 0.8f, 1.3f, 1.3f, 1.5f, 0.75f)
}
