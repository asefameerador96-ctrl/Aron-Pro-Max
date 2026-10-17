package com.aktcl.aron.feature.amo

import com.aktcl.aron.core.database.repo.ReconciliationRepository

/**
 * F-AMO-030: the AMO Sales Submit reconciliation (docs/07): Outlet, Sale, Stock, QC, Promotion, Distribution and OOS
 * performance, Price compliance, Joint call and Survey. The record types of each row come from `cfg.sync.reconcile_types`
 * (`AMO.<row>` keys); the registry seeds only the SR rows, so [DEFAULT_ROWS] stand in until an admin sets the AMO ones. A type
 * without records shows 0 on both sides, so its row is there and matches (zero is not "unsynced"). `call_assessment` is the
 * Joint Call record (the Control Call assessment is `distribution_check`).
 */
object AmoReconciliation {
    val DEFAULT_ROWS: List<Pair<String, List<String>>> = listOf(
        "outlet" to listOf("visit"), "sale" to listOf("memo"), "stock" to listOf("stock_movement"), "qc" to listOf("qc_line"),
        "promotion" to listOf("memo_discount"),
        "distribution" to listOf("distribution_check", "distribution_check_line"),
        "price_compliance" to listOf("price_compliance_check"),
        "joint_call" to listOf("call_assessment", "call_assessment_answer"),
        "survey" to listOf("survey_response"),
    )

    /** Every record type the AMO rows cover, in row order. */
    fun types(configJson: String?): List<String> =
        ReconciliationRepository.rowTypes(configJson, "AMO").ifEmpty { DEFAULT_ROWS }.flatMap { it.second }.distinct()

    fun deviceCounts(types: List<String>, device: Map<String, Int>): Map<String, Int> = types.associateWith { 0 } + device

    /** Server totals (null before the first answer stays null); a covered type the server never saw is 0, not missing. */
    fun serverCounts(types: List<String>, server: Map<String, Int>?): Map<String, Int>? = server?.let { types.associateWith { 0 } + it }
}
