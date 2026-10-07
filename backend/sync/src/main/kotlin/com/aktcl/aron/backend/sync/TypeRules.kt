package com.aktcl.aron.backend.sync

/**
 * Per-record-type ingest rules (docs/24 s4.2): the server table, the payload members that reference a parent record by
 * client_uuid (a missing parent parks the child, s4.2 rule 3), whether the record is a signed header (`sig`, s4.3) and
 * whether it is telemetry (never quarantined for scope, s4.14 item 6).
 */
data class TypeRule(
    val type: String,
    val table: String,
    /** Payload members holding a parent's client_uuid that MUST already be stored (null values are skipped). */
    val parents: List<String> = emptyList(),
    val signedHeader: Boolean = false,
    val telemetry: Boolean = false,
)

object TypeRules {
    private val list = listOf(
        TypeRule("day_open", "route_day_event"),
        TypeRule("day_submit", "route_day_event"),
        TypeRule("attendance_event", "attendance_event", signedHeader = true),
        TypeRule("stock_movement", "stock_movement", signedHeader = true),
        TypeRule("visit", "visit", signedHeader = true),
        TypeRule("visit_close", "visit", parents = listOf("visit_client_uuid")),
        TypeRule("visit_skip", "visit_skip"),
        TypeRule("memo", "memo", parents = listOf("visit_client_uuid", "supersedes_client_uuid"), signedHeader = true),
        TypeRule("memo_line", "memo_line", parents = listOf("memo_client_uuid")),
        TypeRule("memo_discount", "memo_discount", parents = listOf("memo_client_uuid")),
        TypeRule("qc_line", "qc_entry_line", parents = listOf("visit_client_uuid", "memo_client_uuid")),
        TypeRule("print_event", "print_event", parents = listOf("memo_client_uuid")),
        TypeRule("memo_void", "memo_void", parents = listOf("memo_client_uuid"), signedHeader = true),
        TypeRule("due_collection", "due_collection", parents = listOf("against_memo_client_uuid", "visit_client_uuid"), signedHeader = true),
        TypeRule("survey_response", "survey_response", parents = listOf("visit_client_uuid")),
        TypeRule("distribution_check", "distribution_check", parents = listOf("visit_client_uuid")),
        TypeRule("distribution_check_line", "distribution_check_line", parents = listOf("check_client_uuid")),
        TypeRule("call_assessment", "call_assessment", parents = listOf("visit_client_uuid", "visit_plan_outlet_client_uuid")),
        TypeRule("call_assessment_answer", "call_assessment_answer", parents = listOf("assessment_client_uuid")),
        TypeRule("outlet_change_request", "outlet_change_request", parents = listOf("origin_visit_client_uuid"), signedHeader = true),
        TypeRule("outlet_request_verification", "outlet_request_event", parents = listOf("request_uuid")),
        TypeRule("task", "task", parents = listOf("source_visit_client_uuid")),
        TypeRule("task_event", "task_event", parents = listOf("task_uuid")),
        TypeRule("visit_plan", "visit_plan"),
        TypeRule("visit_plan_outlet", "visit_plan_outlet", parents = listOf("plan_client_uuid")),
        TypeRule("leave_application", "leave_application"),
        TypeRule("feedback", "feedback"),
        TypeRule("day_exception", "day_exception"),
        TypeRule("media_meta", "media", parents = listOf("ref_client_uuid")),
        TypeRule("geo_breadcrumb", "geo_breadcrumb", telemetry = true),
        TypeRule("device_status", "device_status_report", telemetry = true),
        TypeRule("config_ack", "cfg_ack", telemetry = true),
        TypeRule("content_view", "content_view", parents = listOf("visit_client_uuid"), telemetry = true),
        TypeRule("redemption", "redemption", parents = listOf("visit_client_uuid"), signedHeader = true),
        TypeRule("redemption_line", "redemption_line", parents = listOf("redemption_client_uuid")),
        TypeRule("gift_photo", "gift_photo", parents = listOf("redemption_client_uuid"), signedHeader = true),
        TypeRule("price_compliance_check", "price_compliance_check", parents = listOf("visit_client_uuid")),
        TypeRule("risk_review", "risk_signal_review"),
        TypeRule("activity_log", "activity_log", telemetry = true),
        TypeRule("app_error", "app_error", telemetry = true),
        TypeRule("sale_abort", "sale_abort", parents = listOf("visit_client_uuid")),
        TypeRule("consent_accept", "user_consent", telemetry = true),
    )

    val BY_TYPE: Map<String, TypeRule> = list.associateBy { it.type }
}
