package com.aktcl.aron.feature.dayclose

/**
 * D-AGATE-04 round 2: the code-to-text table of a refused record's outcome code, STATIC (compile-checked, shrink-safe), replacing a runtime
 * resource-name lookup. One Bangla and English text per code of [com.aktcl.aron.contract.RecordOutcomeCode], per code the phone sets itself and per transport
 * code. `unknown_status:*` and `http_*` read through their prefix; anything else is [R.string.submit_code_other]. A raw code is never shown.
 */
internal object OutcomeCodeTexts {
    fun of(code: String): Int = when {
        code.startsWith("unknown_status") -> R.string.submit_code_unknown_status
        code.startsWith("http_") -> R.string.submit_code_http_error
        code == "scope_stale" -> R.string.submit_code_scope_stale
        code == "config_version_unknown" -> R.string.submit_code_config_version_unknown
        code == "parent_missing" -> R.string.submit_code_parent_missing
        code == "outlet_pending_approval" -> R.string.submit_code_outlet_pending_approval
        code == "price_list_unknown" -> R.string.submit_code_price_list_unknown
        code == "schema_invalid" -> R.string.submit_code_schema_invalid
        code == "unknown_record_type" -> R.string.submit_code_unknown_record_type
        code == "unknown_sku" -> R.string.submit_code_unknown_sku
        code == "unknown_outlet" -> R.string.submit_code_unknown_outlet
        code == "unknown_route" -> R.string.submit_code_unknown_route
        code == "arithmetic_mismatch" -> R.string.submit_code_arithmetic_mismatch
        code == "memo_no_invalid" -> R.string.submit_code_memo_no_invalid
        code == "memo_no_duplicate" -> R.string.submit_code_memo_no_duplicate
        code == "edit_not_allowed" -> R.string.submit_code_edit_not_allowed
        code == "chain_too_deep" -> R.string.submit_code_chain_too_deep
        code == "voided_by_admin" -> R.string.submit_code_voided_by_admin
        code == "content_duplicate" -> R.string.submit_code_content_duplicate
        code == "lines_exceed_max" -> R.string.submit_code_lines_exceed_max
        code == "qty_invalid" -> R.string.submit_code_qty_invalid
        code == "price_invalid" -> R.string.submit_code_price_invalid
        code == "attendance_duplicate" -> R.string.submit_code_attendance_duplicate
        code == "cluster_name_exists" -> R.string.submit_code_cluster_name_exists
        code == "checkout_too_early" -> R.string.submit_code_checkout_too_early
        code == "server_error" -> R.string.submit_code_server_error
        code == "payload_conflict" -> R.string.submit_code_payload_conflict
        code == "business_date_out_of_window" -> R.string.submit_code_business_date_out_of_window
        code == "scope_out_of_reach" -> R.string.submit_code_scope_out_of_reach
        code == "no_assignment_on_date" -> R.string.submit_code_no_assignment_on_date
        code == "device_integrity_failed" -> R.string.submit_code_device_integrity_failed
        code == "device_not_enrolled" -> R.string.submit_code_device_not_enrolled
        code == "user_disabled" -> R.string.submit_code_user_disabled
        code == "device_revoked" -> R.string.submit_code_device_revoked
        code == "app_version_blocked" -> R.string.submit_code_app_version_blocked
        code == "after_month_close" -> R.string.submit_code_after_month_close
        code == "unknown_gift" -> R.string.submit_code_unknown_gift
        code == "insufficient_points" -> R.string.submit_code_insufficient_points
        code == "gift_photo_exists" -> R.string.submit_code_gift_photo_exists
        code == "programme_inactive" -> R.string.submit_code_programme_inactive
        code == "after_final_submit" -> R.string.submit_code_after_final_submit
        code == "day_final_submitted" -> R.string.submit_code_day_final_submitted
        code == "ticket_state_invalid" -> R.string.submit_code_ticket_state_invalid
        code == "issue_type_unknown" -> R.string.submit_code_issue_type_unknown
        code == "retry_exhausted" -> R.string.submit_code_retry_exhausted
        code == "discarded" -> R.string.submit_code_discarded
        code == "resolved_accepted" -> R.string.submit_code_resolved_accepted
        code == "resolved_accepted_with_fix" -> R.string.submit_code_resolved_accepted_with_fix
        code == "resync" -> R.string.submit_code_resync
        code == "digest_resend" -> R.string.submit_code_digest_resend
        code == "ack_mismatch" -> R.string.submit_code_ack_mismatch
        code == "unknown_status" -> R.string.submit_code_unknown_status
        code == "transport_offline" -> R.string.submit_code_transport_offline
        code == "transport_timeout" -> R.string.submit_code_transport_timeout
        code == "transport_edge_response" -> R.string.submit_code_transport_edge_response
        code == "transport_malformed" -> R.string.submit_code_transport_malformed
        code == "assembly_race" -> R.string.submit_code_assembly_race
        else -> R.string.submit_code_other
    }
}
