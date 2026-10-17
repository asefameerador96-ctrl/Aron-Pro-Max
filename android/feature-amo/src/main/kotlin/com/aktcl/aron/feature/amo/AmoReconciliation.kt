package com.aktcl.aron.feature.amo

/**
 * F-AMO-030: the AMO Sales Submit reconciliation (docs/07): Outlet, Sale, Stock, QC, Promotion, Distribution and OOS
 * performance, Price compliance, Joint call and Survey. A type without records shows 0 on both sides, so the row is there
 * and matches (a zero is not "unsynced"). Other types that have records are shown too, never hidden.
 */
object AmoReconciliation {
    val FIXED_TYPES = listOf(
        "visit", "memo", "stock_movement", "qc_line", "memo_discount",
        "distribution_check", "price_compliance_check", "call_assessment", "survey_response",
    )

    fun deviceCounts(device: Map<String, Int>): Map<String, Int> = FIXED_TYPES.associateWith { 0 } + device

    /** Server totals (null before the first answer stays null); a fixed type the server never saw is 0, not missing. */
    fun serverCounts(server: Map<String, Int>?): Map<String, Int>? = server?.let { FIXED_TYPES.associateWith { 0 } + it }
}
