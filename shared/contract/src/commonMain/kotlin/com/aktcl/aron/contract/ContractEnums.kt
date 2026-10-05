package com.aktcl.aron.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Day-1 seed of the contract mirror. The values MUST equal the enums of the same name in contract/openapi.yaml
 * (components.schemas.*); ContractDriftTest enforces it. The contract lane extends this file with every DTO.
 */
object ContractInfo {
    const val API_BASE_PATH: String = "/v1"
    /** Payload schema version the phone stamps on every record (docs/24 s4.3); the server accepts 1..current (s3.7). */
    const val SCHEMA_VERSION: Int = 1
}

/** Sync record types of Phase 1 (docs/24 s4.2). Wire names are snake_case. */
@Serializable
enum class RecordType(val wire: String) {
    @SerialName("geo_breadcrumb") GEO_BREADCRUMB("geo_breadcrumb"),
    @SerialName("attendance_event") ATTENDANCE_EVENT("attendance_event"),
    @SerialName("day_open") DAY_OPEN("day_open"),
    @SerialName("day_submit") DAY_SUBMIT("day_submit"),
    @SerialName("day_exception") DAY_EXCEPTION("day_exception"),
    @SerialName("stock_movement") STOCK_MOVEMENT("stock_movement"),
    @SerialName("visit") VISIT("visit"),
    @SerialName("visit_close") VISIT_CLOSE("visit_close"),
    @SerialName("visit_skip") VISIT_SKIP("visit_skip"),
    @SerialName("memo") MEMO("memo"),
    @SerialName("memo_line") MEMO_LINE("memo_line"),
    @SerialName("memo_discount") MEMO_DISCOUNT("memo_discount"),
    @SerialName("qc_line") QC_LINE("qc_line"),
    @SerialName("print_event") PRINT_EVENT("print_event"),
    @SerialName("memo_void") MEMO_VOID("memo_void"),
    @SerialName("due_collection") DUE_COLLECTION("due_collection"),
    @SerialName("survey_response") SURVEY_RESPONSE("survey_response"),
    @SerialName("outlet_change_request") OUTLET_CHANGE_REQUEST("outlet_change_request"),
    @SerialName("outlet_request_verification") OUTLET_REQUEST_VERIFICATION("outlet_request_verification"),
    @SerialName("distribution_check") DISTRIBUTION_CHECK("distribution_check"),
    @SerialName("distribution_check_line") DISTRIBUTION_CHECK_LINE("distribution_check_line"),
    @SerialName("call_assessment") CALL_ASSESSMENT("call_assessment"),
    @SerialName("call_assessment_answer") CALL_ASSESSMENT_ANSWER("call_assessment_answer"),
    @SerialName("task") TASK("task"),
    @SerialName("task_event") TASK_EVENT("task_event"),
    @SerialName("visit_plan") VISIT_PLAN("visit_plan"),
    @SerialName("visit_plan_outlet") VISIT_PLAN_OUTLET("visit_plan_outlet"),
    @SerialName("leave_application") LEAVE_APPLICATION("leave_application"),
    @SerialName("feedback") FEEDBACK("feedback"),
    @SerialName("media_meta") MEDIA_META("media_meta"),
    @SerialName("device_status") DEVICE_STATUS("device_status"),
    @SerialName("config_ack") CONFIG_ACK("config_ack"),
}

/** Roles (docs/24 s8.5). */
@Serializable
enum class Role(val wire: String) {
    @SerialName("SR") SR("SR"),
    @SerialName("AMO") AMO("AMO"),
    @SerialName("TSO") TSO("TSO"),
    @SerialName("DMO") DMO("DMO"),
    @SerialName("WM") WM("WM"),
    @SerialName("TOP") TOP("TOP"),
    @SerialName("ANALYST") ANALYST("ANALYST"),
    @SerialName("SUPPORT") SUPPORT("SUPPORT"),
    @SerialName("ADMIN") ADMIN("ADMIN"),
    @SerialName("SUPERADMIN") SUPERADMIN("SUPERADMIN"),
}

/** Per-record acknowledgement status (docs/24 s4.5). */
@Serializable
enum class AckStatus(val wire: String) {
    @SerialName("accepted") ACCEPTED("accepted"),
    @SerialName("duplicate") DUPLICATE("duplicate"),
    @SerialName("rejected") REJECTED("rejected"),
    @SerialName("quarantined") QUARANTINED("quarantined"),
}
