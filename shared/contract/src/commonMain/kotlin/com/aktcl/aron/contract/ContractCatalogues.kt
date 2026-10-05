package com.aktcl.aron.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire catalogues mirrored from contract/openapi.yaml. ContractDriftTest checks every value against the YAML and
// SpecCrossCheckTest checks the HTTP statuses and ack outcomes against docs/24-build-spec.md (s3.4, s4.5).
// Lane: shared (contract). Add a value here in the same PR that adds it to the YAML.

/** Route-day state (docs/24 s4.9). */
@Serializable
enum class DayState(val wire: String) {
    @SerialName("not_started") NOT_STARTED("not_started"),
    @SerialName("logged_in") LOGGED_IN("logged_in"),
    @SerialName("in_field") IN_FIELD("in_field"),
    @SerialName("synced") SYNCED("synced"),
    @SerialName("submit_pending_rows") SUBMIT_PENDING_ROWS("submit_pending_rows"),
    @SerialName("sales_submitted") SALES_SUBMITTED("sales_submitted"),
    @SerialName("final_submitted") FINAL_SUBMITTED("final_submitted"),
}

/** Why a batch was sent (docs/24 s4.7). */
@Serializable
enum class SyncTrigger(val wire: String) {
    @SerialName("write_debounce") WRITE_DEBOUNCE("write_debounce"),
    @SerialName("foreground") FOREGROUND("foreground"),
    @SerialName("connectivity") CONNECTIVITY("connectivity"),
    @SerialName("workmanager_connectivity") WORKMANAGER_CONNECTIVITY("workmanager_connectivity"),
    @SerialName("periodic") PERIODIC("periodic"),
    @SerialName("manual") MANUAL("manual"),
    @SerialName("day_submit") DAY_SUBMIT("day_submit"),
    @SerialName("checkout") CHECKOUT("checkout"),
    @SerialName("resync") RESYNC("resync"),
    @SerialName("digest_resend") DIGEST_RESEND("digest_resend"),
    @SerialName("directive") DIRECTIVE("directive"),
}

/** Field app flavour; application id com.aktcl.aron.<wire> (docs/24 s2.1). */
@Serializable
enum class AppFlavour(val wire: String) {
    @SerialName("sr") SR("sr"),
    @SerialName("amo") AMO("amo"),
    @SerialName("tso") TSO("tso"),
}

/** Device-owner lockdown level (docs/24 s10.2). */
@Serializable
enum class LockdownLevel(val wire: String) {
    @SerialName("dev") DEV("dev"),
    @SerialName("prod") PROD("prod"),
}

/** Device life cycle (docs/24 s8.7). */
@Serializable
enum class DeviceState(val wire: String) {
    @SerialName("enrolled") ENROLLED("enrolled"),
    @SerialName("active") ACTIVE("active"),
    @SerialName("suspended") SUSPENDED("suspended"),
    @SerialName("revoked") REVOKED("revoked"),
    @SerialName("replaced") REPLACED("replaced"),
}

/** Geofence verdict (docs/24 s11.2). */
@Serializable
enum class GeoVerdict(val wire: String) {
    @SerialName("in_range") IN_RANGE("in_range"),
    @SerialName("out_of_range") OUT_OF_RANGE("out_of_range"),
    @SerialName("accuracy_too_low") ACCURACY_TOO_LOW("accuracy_too_low"),
    @SerialName("no_fix") NO_FIX("no_fix"),
    @SerialName("mocked") MOCKED("mocked"),
    @SerialName("no_outlet_location") NO_OUTLET_LOCATION("no_outlet_location"),
}

/** Why a location fix was taken (docs/24 s11.1). */
@Serializable
enum class FixPurpose(val wire: String) {
    @SerialName("attendance_in") ATTENDANCE_IN("attendance_in"),
    @SerialName("attendance_out") ATTENDANCE_OUT("attendance_out"),
    @SerialName("visit_open") VISIT_OPEN("visit_open"),
    @SerialName("force_sale") FORCE_SALE("force_sale"),
    @SerialName("outlet_capture") OUTLET_CAPTURE("outlet_capture"),
    @SerialName("outlet_verification") OUTLET_VERIFICATION("outlet_verification"),
    @SerialName("memo_edit") MEMO_EDIT("memo_edit"),
    @SerialName("memo_void") MEMO_VOID("memo_void"),
    @SerialName("due_collection") DUE_COLLECTION("due_collection"),
    @SerialName("refresh") REFRESH("refresh"),
    @SerialName("breadcrumb") BREADCRUMB("breadcrumb"),
    @SerialName("gift_photo") GIFT_PHOTO("gift_photo"),
    @SerialName("redemption") REDEMPTION("redemption"),
}

/** Risk-signal catalogue (docs/24 s11.4). */
@Serializable
enum class RiskSignalCode(val wire: String) {
    @SerialName("GEO_MOCK") GEO_MOCK("GEO_MOCK"),
    @SerialName("GEO_TELEPORT") GEO_TELEPORT("GEO_TELEPORT"),
    @SerialName("GEO_ZERO_JITTER") GEO_ZERO_JITTER("GEO_ZERO_JITTER"),
    @SerialName("GEO_PERFECT_ACCURACY") GEO_PERFECT_ACCURACY("GEO_PERFECT_ACCURACY"),
    @SerialName("GEO_SAME_POINT") GEO_SAME_POINT("GEO_SAME_POINT"),
    @SerialName("GEO_ROUTE_SINGLE_POINT") GEO_ROUTE_SINGLE_POINT("GEO_ROUTE_SINGLE_POINT"),
    @SerialName("GEO_STALE_FIX") GEO_STALE_FIX("GEO_STALE_FIX"),
    @SerialName("GEO_GNSS_INCONSISTENT") GEO_GNSS_INCONSISTENT("GEO_GNSS_INCONSISTENT"),
    @SerialName("GEO_GNSS_TIME_SKEW") GEO_GNSS_TIME_SKEW("GEO_GNSS_TIME_SKEW"),
    @SerialName("GEO_DEVICE_SERVER_MISMATCH") GEO_DEVICE_SERVER_MISMATCH("GEO_DEVICE_SERVER_MISMATCH"),
    @SerialName("GEO_SHORT_VISIT_GAPS") GEO_SHORT_VISIT_GAPS("GEO_SHORT_VISIT_GAPS"),
    @SerialName("DEVICE_NOT_OWNER") DEVICE_NOT_OWNER("DEVICE_NOT_OWNER"),
    @SerialName("DEVICE_INTEGRITY_FAIL") DEVICE_INTEGRITY_FAIL("DEVICE_INTEGRITY_FAIL"),
    @SerialName("DEVICE_DEBUG_ENABLED") DEVICE_DEBUG_ENABLED("DEVICE_DEBUG_ENABLED"),
    @SerialName("DEVICE_MOCK_APP_PRESENT") DEVICE_MOCK_APP_PRESENT("DEVICE_MOCK_APP_PRESENT"),
    @SerialName("DEVICE_POLICY_DRIFT") DEVICE_POLICY_DRIFT("DEVICE_POLICY_DRIFT"),
    @SerialName("CLOCK_SKEW") CLOCK_SKEW("CLOCK_SKEW"),
    @SerialName("GEO_OUT_OF_BOUNDS") GEO_OUT_OF_BOUNDS("GEO_OUT_OF_BOUNDS"),
    @SerialName("CONFIG_STAMP_REGRESS") CONFIG_STAMP_REGRESS("CONFIG_STAMP_REGRESS"),
}

/** Report registry (docs/24 s12.3). */
@Serializable
enum class ReportKey(val wire: String) {
    @SerialName("std-memo") STD_MEMO("std-memo"),
    @SerialName("sr-efficiency") SR_EFFICIENCY("sr-efficiency"),
    @SerialName("route-std") ROUTE_STD("route-std"),
    @SerialName("route-memo") ROUTE_MEMO("route-memo"),
    @SerialName("route-bsr-cpr") ROUTE_BSR_CPR("route-bsr-cpr"),
    @SerialName("data-entry-log") DATA_ENTRY_LOG("data-entry-log"),
    @SerialName("final-submit-log") FINAL_SUBMIT_LOG("final-submit-log"),
    @SerialName("final-submit-status") FINAL_SUBMIT_STATUS("final-submit-status"),
    @SerialName("by-outlet") BY_OUTLET("by-outlet"),
    @SerialName("by-outlet-by-day") BY_OUTLET_BY_DAY("by-outlet-by-day"),
    @SerialName("gigo") GIGO("gigo"),
    @SerialName("attendance") ATTENDANCE("attendance"),
    @SerialName("discount") DISCOUNT("discount"),
    @SerialName("online-offline") ONLINE_OFFLINE("online-offline"),
    @SerialName("tso-top-sheet") TSO_TOP_SHEET("tso-top-sheet"),
    @SerialName("daily-tracking") DAILY_TRACKING("daily-tracking"),
    @SerialName("sr-outlets") SR_OUTLETS("sr-outlets"),
    @SerialName("leaderboard") LEADERBOARD("leaderboard"),
    @SerialName("suspicious-location") SUSPICIOUS_LOCATION("suspicious-location"),
    @SerialName("sync-health") SYNC_HEALTH("sync-health"),
    @SerialName("ds-rrs") DS_RRS("ds-rrs"),
    @SerialName("dss") DSS("dss"),
    @SerialName("amo-call") AMO_CALL("amo-call"),
    @SerialName("dues-ageing") DUES_AGEING("dues-ageing"),
    @SerialName("settlement") SETTLEMENT("settlement"),
    @SerialName("qc-report") QC_REPORT("qc-report"),
    @SerialName("stock-summary") STOCK_SUMMARY("stock-summary"),
    @SerialName("memo-number-gaps") MEMO_NUMBER_GAPS("memo-number-gaps"),
    @SerialName("sales-summary") SALES_SUMMARY("sales-summary"),
    @SerialName("task-planner") TASK_PLANNER("task-planner"),
    @SerialName("by-route-geo-capture") BY_ROUTE_GEO_CAPTURE("by-route-geo-capture"),
    @SerialName("free-sample") FREE_SAMPLE("free-sample"),
    @SerialName("target-allocation") TARGET_ALLOCATION("target-allocation"),
    @SerialName("route-qc") ROUTE_QC("route-qc"),
    @SerialName("geofence-calibration") GEOFENCE_CALIBRATION("geofence-calibration"),
    @SerialName("astha") ASTHA("astha"),
    @SerialName("astha-gift-choice") ASTHA_GIFT_CHOICE("astha-gift-choice"),
    @SerialName("campaign-gift-redemption") CAMPAIGN_GIFT_REDEMPTION("campaign-gift-redemption"),
    @SerialName("diamond-league") DIAMOND_LEAGUE("diamond-league"),
    @SerialName("superstar-campaign") SUPERSTAR_CAMPAIGN("superstar-campaign"),
    @SerialName("retailer-list") RETAILER_LIST("retailer-list"),
    @SerialName("sku-list") SKU_LIST("sku-list"),
}

/** Kind of visit; AMO calls never count in the SR strike rate (docs/24 s12.4). */
@Serializable
enum class VisitKind(val wire: String) {
    @SerialName("sr_call") SR_CALL("sr_call"),
    @SerialName("amo_control_call") AMO_CONTROL_CALL("amo_control_call"),
    @SerialName("amo_joint_call") AMO_JOINT_CALL("amo_joint_call"),
    @SerialName("tso_visit") TSO_VISIT("tso_visit"),
    @SerialName("web_entry") WEB_ENTRY("web_entry"),
}

/** How a visit ended (docs/24 s4.2, D24-78). */
@Serializable
enum class VisitOutcome(val wire: String) {
    @SerialName("sold") SOLD("sold"),
    @SerialName("zero_sale_stock_ok") ZERO_SALE_STOCK_OK("zero_sale_stock_ok"),
    @SerialName("closed") CLOSED("closed"),
    @SerialName("owner_absent") OWNER_ABSENT("owner_absent"),
    @SerialName("refused") REFUSED("refused"),
    @SerialName("competitor_exclusive") COMPETITOR_EXCLUSIVE("competitor_exclusive"),
    @SerialName("not_reached") NOT_REACHED("not_reached"),
    @SerialName("abandoned") ABANDONED("abandoned"),
}

/** Stock ledger movement kind (docs/24 s12.5). */
@Serializable
enum class StockMovementKind(val wire: String) {
    @SerialName("issue") ISSUE("issue"),
    @SerialName("return") RETURN("return"),
    @SerialName("adjustment") ADJUSTMENT("adjustment"),
    @SerialName("damaged") DAMAGED("damaged"),
    @SerialName("short") SHORT("short"),
    @SerialName("qc_return") QC_RETURN("qc_return"),
}

/** Outlet change request type (docs/24 s12.2). */
@Serializable
enum class OutletRequestType(val wire: String) {
    @SerialName("new") NEW("new"),
    @SerialName("close") CLOSE("close"),
    @SerialName("info") INFO("info"),
    @SerialName("cluster") CLUSTER("cluster"),
    @SerialName("location") LOCATION("location"),
    @SerialName("route_add") ROUTE_ADD("route_add"),
}

/** Business code lists (docs/24 s12.1). */
@Serializable
enum class CodeListKey(val wire: String) {
    @SerialName("force_reason") FORCE_REASON("force_reason"),
    @SerialName("edit_reason") EDIT_REASON("edit_reason"),
    @SerialName("void_reason") VOID_REASON("void_reason"),
    @SerialName("visit_outcome") VISIT_OUTCOME("visit_outcome"),
    @SerialName("skip_reason") SKIP_REASON("skip_reason"),
    @SerialName("day_exception_reason") DAY_EXCEPTION_REASON("day_exception_reason"),
    @SerialName("stock_variance_reason") STOCK_VARIANCE_REASON("stock_variance_reason"),
    @SerialName("task_type") TASK_TYPE("task_type"),
    @SerialName("leave_type") LEAVE_TYPE("leave_type"),
    @SerialName("feedback_category") FEEDBACK_CATEGORY("feedback_category"),
    @SerialName("qc_fault_type") QC_FAULT_TYPE("qc_fault_type"),
    @SerialName("payment_mode") PAYMENT_MODE("payment_mode"),
    @SerialName("outlet_close_reason") OUTLET_CLOSE_REASON("outlet_close_reason"),
    @SerialName("submit_void_reason") SUBMIT_VOID_REASON("submit_void_reason"),
    @SerialName("channel") CHANNEL("channel"),
    @SerialName("sub_channel") SUB_CHANNEL("sub_channel"),
    @SerialName("geo_class") GEO_CLASS("geo_class"),
}

/** Why a photo was taken (docs/24 s4.11). */
@Serializable
enum class MediaPurpose(val wire: String) {
    @SerialName("force_sale") FORCE_SALE("force_sale"),
    @SerialName("outlet_capture") OUTLET_CAPTURE("outlet_capture"),
    @SerialName("outlet_verification") OUTLET_VERIFICATION("outlet_verification"),
    @SerialName("survey") SURVEY("survey"),
    @SerialName("feedback") FEEDBACK("feedback"),
    @SerialName("support") SUPPORT("support"),
    @SerialName("gift_photo") GIFT_PHOTO("gift_photo"),
}

/** Bundle sections that may be paged or delta-updated (docs/24 s4.10). */
@Serializable
enum class BundleSectionName(val wire: String) {
    @SerialName("outlets") OUTLETS("outlets"),
    @SerialName("open_memos") OPEN_MEMOS("open_memos"),
    @SerialName("prices") PRICES("prices"),
    @SerialName("offers") OFFERS("offers"),
    @SerialName("tasks") TASKS("tasks"),
    @SerialName("team") TEAM("team"),
    @SerialName("pending_outlet_requests") PENDING_OUTLET_REQUESTS("pending_outlet_requests"),
    @SerialName("programmes") PROGRAMMES("programmes"),
    @SerialName("content") CONTENT("content"),
}

/** Programme kind (docs/24 s4.14). */
@Serializable
enum class ProgrammeKind(val wire: String) {
    @SerialName("diamond_league") DIAMOND_LEAGUE("diamond_league"),
    @SerialName("astha") ASTHA("astha"),
    @SerialName("campaign") CAMPAIGN("campaign"),
    @SerialName("superstar") SUPERSTAR("superstar"),
}

/** Marketing content kind shown during a call (docs/24 s4.14). */
@Serializable
enum class ContentKind(val wire: String) {
    @SerialName("av") AV("av"),
    @SerialName("kv") KV("kv"),
}

/** Config scope level with its resolution precedence; the highest precedence wins (docs/24 s9.2). */
@Serializable
enum class ConfigScopeType(val wire: String, val precedence: Int) {
    @SerialName("global") GLOBAL("global", 0),
    @SerialName("role") ROLE("role", 10),
    @SerialName("wing") WING("wing", 30),
    @SerialName("division") DIVISION("division", 40),
    @SerialName("territory") TERRITORY("territory", 50),
    @SerialName("geo_class") GEO_CLASS("geo_class", 70),
    @SerialName("zone") ZONE("zone", 80),
    @SerialName("route") ROUTE("route", 90),
    @SerialName("outlet") OUTLET("outlet", 100),
    @SerialName("user") USER("user", 110),
    @SerialName("device") DEVICE("device", 120),
}

/** Outcome code of a rejected or quarantined record with its ack status and retryability (docs/24 s4.5); retryable is null for quarantined records. */
@Serializable
enum class RecordOutcomeCode(val wire: String, val status: AckStatus, val retryable: Boolean?) {
    @SerialName("scope_stale") SCOPE_STALE("scope_stale", AckStatus.REJECTED, true),
    @SerialName("config_version_unknown") CONFIG_VERSION_UNKNOWN("config_version_unknown", AckStatus.REJECTED, true),
    @SerialName("parent_missing") PARENT_MISSING("parent_missing", AckStatus.REJECTED, true),
    @SerialName("outlet_pending_approval") OUTLET_PENDING_APPROVAL("outlet_pending_approval", AckStatus.REJECTED, true),
    @SerialName("price_list_unknown") PRICE_LIST_UNKNOWN("price_list_unknown", AckStatus.REJECTED, true),
    @SerialName("schema_invalid") SCHEMA_INVALID("schema_invalid", AckStatus.REJECTED, false),
    @SerialName("unknown_record_type") UNKNOWN_RECORD_TYPE("unknown_record_type", AckStatus.REJECTED, false),
    @SerialName("unknown_sku") UNKNOWN_SKU("unknown_sku", AckStatus.REJECTED, false),
    @SerialName("unknown_outlet") UNKNOWN_OUTLET("unknown_outlet", AckStatus.REJECTED, false),
    @SerialName("unknown_route") UNKNOWN_ROUTE("unknown_route", AckStatus.REJECTED, false),
    @SerialName("arithmetic_mismatch") ARITHMETIC_MISMATCH("arithmetic_mismatch", AckStatus.QUARANTINED, null),
    @SerialName("memo_no_invalid") MEMO_NO_INVALID("memo_no_invalid", AckStatus.REJECTED, false),
    @SerialName("memo_no_duplicate") MEMO_NO_DUPLICATE("memo_no_duplicate", AckStatus.QUARANTINED, null),
    @SerialName("edit_not_allowed") EDIT_NOT_ALLOWED("edit_not_allowed", AckStatus.REJECTED, false),
    @SerialName("chain_too_deep") CHAIN_TOO_DEEP("chain_too_deep", AckStatus.REJECTED, false),
    @SerialName("voided_by_admin") VOIDED_BY_ADMIN("voided_by_admin", AckStatus.REJECTED, false),
    @SerialName("content_duplicate") CONTENT_DUPLICATE("content_duplicate", AckStatus.QUARANTINED, null),
    @SerialName("lines_exceed_max") LINES_EXCEED_MAX("lines_exceed_max", AckStatus.REJECTED, false),
    @SerialName("qty_invalid") QTY_INVALID("qty_invalid", AckStatus.REJECTED, false),
    @SerialName("attendance_duplicate") ATTENDANCE_DUPLICATE("attendance_duplicate", AckStatus.REJECTED, false),
    @SerialName("checkout_too_early") CHECKOUT_TOO_EARLY("checkout_too_early", AckStatus.QUARANTINED, null),
    @SerialName("server_error") SERVER_ERROR("server_error", AckStatus.REJECTED, true),
    @SerialName("payload_conflict") PAYLOAD_CONFLICT("payload_conflict", AckStatus.QUARANTINED, null),
    @SerialName("business_date_out_of_window") BUSINESS_DATE_OUT_OF_WINDOW("business_date_out_of_window", AckStatus.QUARANTINED, null),
    @SerialName("scope_out_of_reach") SCOPE_OUT_OF_REACH("scope_out_of_reach", AckStatus.QUARANTINED, null),
    @SerialName("no_assignment_on_date") NO_ASSIGNMENT_ON_DATE("no_assignment_on_date", AckStatus.QUARANTINED, null),
    @SerialName("device_integrity_failed") DEVICE_INTEGRITY_FAILED("device_integrity_failed", AckStatus.QUARANTINED, null),
    @SerialName("device_not_enrolled") DEVICE_NOT_ENROLLED("device_not_enrolled", AckStatus.QUARANTINED, null),
    @SerialName("user_disabled") USER_DISABLED("user_disabled", AckStatus.QUARANTINED, null),
    @SerialName("device_revoked") DEVICE_REVOKED("device_revoked", AckStatus.QUARANTINED, null),
    @SerialName("app_version_blocked") APP_VERSION_BLOCKED("app_version_blocked", AckStatus.QUARANTINED, null),
    @SerialName("after_month_close") AFTER_MONTH_CLOSE("after_month_close", AckStatus.QUARANTINED, null),
    @SerialName("unknown_gift") UNKNOWN_GIFT("unknown_gift", AckStatus.REJECTED, false),
    @SerialName("insufficient_points") INSUFFICIENT_POINTS("insufficient_points", AckStatus.REJECTED, false),
    @SerialName("gift_photo_exists") GIFT_PHOTO_EXISTS("gift_photo_exists", AckStatus.REJECTED, false),
    @SerialName("programme_inactive") PROGRAMME_INACTIVE("programme_inactive", AckStatus.QUARANTINED, null),
}

/** Stable RFC 9457 problem code with its HTTP status (docs/24 s3.4). */
@Serializable
enum class ProblemCode(val wire: String, val httpStatus: Int) {
    @SerialName("ERR_VALIDATION") ERR_VALIDATION("ERR_VALIDATION", 400),
    @SerialName("ERR_MALFORMED_JSON") ERR_MALFORMED_JSON("ERR_MALFORMED_JSON", 400),
    @SerialName("ERR_UNSUPPORTED_SCHEMA_VERSION") ERR_UNSUPPORTED_SCHEMA_VERSION("ERR_UNSUPPORTED_SCHEMA_VERSION", 400),
    @SerialName("ERR_UNAUTHENTICATED") ERR_UNAUTHENTICATED("ERR_UNAUTHENTICATED", 401),
    @SerialName("ERR_TOKEN_EXPIRED") ERR_TOKEN_EXPIRED("ERR_TOKEN_EXPIRED", 401),
    @SerialName("ERR_SCOPE_CHANGED") ERR_SCOPE_CHANGED("ERR_SCOPE_CHANGED", 401),
    @SerialName("ERR_PASSWORD_CHANGED") ERR_PASSWORD_CHANGED("ERR_PASSWORD_CHANGED", 401),
    @SerialName("ERR_FORBIDDEN") ERR_FORBIDDEN("ERR_FORBIDDEN", 403),
    @SerialName("ERR_OUT_OF_SCOPE") ERR_OUT_OF_SCOPE("ERR_OUT_OF_SCOPE", 403),
    @SerialName("ERR_NOT_FOUND") ERR_NOT_FOUND("ERR_NOT_FOUND", 404),
    @SerialName("ERR_CONFLICT") ERR_CONFLICT("ERR_CONFLICT", 409),
    @SerialName("ERR_PRECONDITION_FAILED") ERR_PRECONDITION_FAILED("ERR_PRECONDITION_FAILED", 412),
    @SerialName("ERR_PAYLOAD_TOO_LARGE") ERR_PAYLOAD_TOO_LARGE("ERR_PAYLOAD_TOO_LARGE", 413),
    @SerialName("ERR_UNSUPPORTED_MEDIA_TYPE") ERR_UNSUPPORTED_MEDIA_TYPE("ERR_UNSUPPORTED_MEDIA_TYPE", 415),
    @SerialName("ERR_RATE_LIMITED") ERR_RATE_LIMITED("ERR_RATE_LIMITED", 429),
    @SerialName("ERR_INTERNAL") ERR_INTERNAL("ERR_INTERNAL", 500),
    @SerialName("ERR_SERVICE_UNAVAILABLE") ERR_SERVICE_UNAVAILABLE("ERR_SERVICE_UNAVAILABLE", 503),
    @SerialName("ERR_READ_ONLY_MODE") ERR_READ_ONLY_MODE("ERR_READ_ONLY_MODE", 503),
    @SerialName("ERR_AUTH_INVALID_CREDENTIALS") ERR_AUTH_INVALID_CREDENTIALS("ERR_AUTH_INVALID_CREDENTIALS", 401),
    @SerialName("ERR_AUTH_ACCOUNT_LOCKED") ERR_AUTH_ACCOUNT_LOCKED("ERR_AUTH_ACCOUNT_LOCKED", 403),
    @SerialName("ERR_AUTH_USER_DISABLED") ERR_AUTH_USER_DISABLED("ERR_AUTH_USER_DISABLED", 403),
    @SerialName("ERR_AUTH_PASSWORD_POLICY") ERR_AUTH_PASSWORD_POLICY("ERR_AUTH_PASSWORD_POLICY", 400),
    @SerialName("ERR_AUTH_PASSWORD_CHANGE_REQUIRED") ERR_AUTH_PASSWORD_CHANGE_REQUIRED("ERR_AUTH_PASSWORD_CHANGE_REQUIRED", 403),
    @SerialName("ERR_AUTH_REFRESH_INVALID") ERR_AUTH_REFRESH_INVALID("ERR_AUTH_REFRESH_INVALID", 401),
    @SerialName("ERR_AUTH_REFRESH_REUSED") ERR_AUTH_REFRESH_REUSED("ERR_AUTH_REFRESH_REUSED", 401),
    @SerialName("ERR_AUTH_MFA_INVALID") ERR_AUTH_MFA_INVALID("ERR_AUTH_MFA_INVALID", 401),
    @SerialName("ERR_AUTH_OTP_INVALID") ERR_AUTH_OTP_INVALID("ERR_AUTH_OTP_INVALID", 401),
    @SerialName("ERR_AUTH_OTP_EXPIRED") ERR_AUTH_OTP_EXPIRED("ERR_AUTH_OTP_EXPIRED", 401),
    @SerialName("ERR_AUTH_OTP_ATTEMPTS_EXCEEDED") ERR_AUTH_OTP_ATTEMPTS_EXCEEDED("ERR_AUTH_OTP_ATTEMPTS_EXCEEDED", 403),
    @SerialName("ERR_AUTH_BIND_LOCKED") ERR_AUTH_BIND_LOCKED("ERR_AUTH_BIND_LOCKED", 403),
    @SerialName("ERR_DEVICE_NOT_ENROLLED") ERR_DEVICE_NOT_ENROLLED("ERR_DEVICE_NOT_ENROLLED", 403),
    @SerialName("ERR_DEVICE_UNBOUND") ERR_DEVICE_UNBOUND("ERR_DEVICE_UNBOUND", 403),
    @SerialName("ERR_DEVICE_SUSPENDED") ERR_DEVICE_SUSPENDED("ERR_DEVICE_SUSPENDED", 403),
    @SerialName("ERR_DEVICE_REVOKED") ERR_DEVICE_REVOKED("ERR_DEVICE_REVOKED", 403),
    @SerialName("ERR_DEVICE_INTEGRITY_FAILED") ERR_DEVICE_INTEGRITY_FAILED("ERR_DEVICE_INTEGRITY_FAILED", 403),
    @SerialName("ERR_DEVICE_PROOF_INVALID") ERR_DEVICE_PROOF_INVALID("ERR_DEVICE_PROOF_INVALID", 401),
    @SerialName("ERR_DEVICE_LIMIT_REACHED") ERR_DEVICE_LIMIT_REACHED("ERR_DEVICE_LIMIT_REACHED", 409),
    @SerialName("ERR_ENROLMENT_TOKEN_INVALID") ERR_ENROLMENT_TOKEN_INVALID("ERR_ENROLMENT_TOKEN_INVALID", 403),
    @SerialName("ERR_ENROLMENT_TOKEN_EXPIRED") ERR_ENROLMENT_TOKEN_EXPIRED("ERR_ENROLMENT_TOKEN_EXPIRED", 403),
    @SerialName("ERR_ENROLMENT_TOKEN_EXHAUSTED") ERR_ENROLMENT_TOKEN_EXHAUSTED("ERR_ENROLMENT_TOKEN_EXHAUSTED", 403),
    @SerialName("ERR_ENROLMENT_ATTESTATION_FAILED") ERR_ENROLMENT_ATTESTATION_FAILED("ERR_ENROLMENT_ATTESTATION_FAILED", 403),
    @SerialName("ERR_NONCE_INVALID") ERR_NONCE_INVALID("ERR_NONCE_INVALID", 400),
    @SerialName("ERR_APP_VERSION_UNSUPPORTED") ERR_APP_VERSION_UNSUPPORTED("ERR_APP_VERSION_UNSUPPORTED", 426),
    @SerialName("ERR_SYNC_BATCH_UUID_REUSED") ERR_SYNC_BATCH_UUID_REUSED("ERR_SYNC_BATCH_UUID_REUSED", 409),
    @SerialName("ERR_SYNC_BATCH_TOO_LARGE") ERR_SYNC_BATCH_TOO_LARGE("ERR_SYNC_BATCH_TOO_LARGE", 413),
    @SerialName("ERR_SYNC_DECOMPRESSION_LIMIT") ERR_SYNC_DECOMPRESSION_LIMIT("ERR_SYNC_DECOMPRESSION_LIMIT", 413),
    @SerialName("ERR_SYNC_HOLD") ERR_SYNC_HOLD("ERR_SYNC_HOLD", 429),
    @SerialName("ERR_BUNDLE_NEW_BUSINESS_DATE") ERR_BUNDLE_NEW_BUSINESS_DATE("ERR_BUNDLE_NEW_BUSINESS_DATE", 409),
    @SerialName("ERR_BUNDLE_CURSOR_EXPIRED") ERR_BUNDLE_CURSOR_EXPIRED("ERR_BUNDLE_CURSOR_EXPIRED", 410),
    @SerialName("ERR_BUNDLE_NOT_READY") ERR_BUNDLE_NOT_READY("ERR_BUNDLE_NOT_READY", 503),
    @SerialName("ERR_DAY_ALREADY_FINAL_SUBMITTED") ERR_DAY_ALREADY_FINAL_SUBMITTED("ERR_DAY_ALREADY_FINAL_SUBMITTED", 409),
    @SerialName("ERR_DAY_NOT_FINAL_SUBMITTED") ERR_DAY_NOT_FINAL_SUBMITTED("ERR_DAY_NOT_FINAL_SUBMITTED", 409),
    @SerialName("ERR_DAY_SUBMIT_VOID_NOT_ALLOWED") ERR_DAY_SUBMIT_VOID_NOT_ALLOWED("ERR_DAY_SUBMIT_VOID_NOT_ALLOWED", 409),
    @SerialName("ERR_DAY_DATA_VOID_NOT_ALLOWED") ERR_DAY_DATA_VOID_NOT_ALLOWED("ERR_DAY_DATA_VOID_NOT_ALLOWED", 409),
    @SerialName("ERR_CFG_UNKNOWN_KEY") ERR_CFG_UNKNOWN_KEY("ERR_CFG_UNKNOWN_KEY", 400),
    @SerialName("ERR_CFG_OUT_OF_BOUNDS") ERR_CFG_OUT_OF_BOUNDS("ERR_CFG_OUT_OF_BOUNDS", 400),
    @SerialName("ERR_CFG_SCOPE_NOT_ALLOWED") ERR_CFG_SCOPE_NOT_ALLOWED("ERR_CFG_SCOPE_NOT_ALLOWED", 400),
    @SerialName("ERR_CFG_DEPENDENCY") ERR_CFG_DEPENDENCY("ERR_CFG_DEPENDENCY", 400),
    @SerialName("ERR_CFG_SELF_APPROVAL") ERR_CFG_SELF_APPROVAL("ERR_CFG_SELF_APPROVAL", 409),
    @SerialName("ERR_CFG_REASON_REQUIRED") ERR_CFG_REASON_REQUIRED("ERR_CFG_REASON_REQUIRED", 400),
    @SerialName("ERR_CFG_FREEZE_WINDOW") ERR_CFG_FREEZE_WINDOW("ERR_CFG_FREEZE_WINDOW", 409),
    @SerialName("ERR_MASTER_DUPLICATE_CODE") ERR_MASTER_DUPLICATE_CODE("ERR_MASTER_DUPLICATE_CODE", 409),
    @SerialName("ERR_MASTER_EFFECTIVE_DATE_PAST") ERR_MASTER_EFFECTIVE_DATE_PAST("ERR_MASTER_EFFECTIVE_DATE_PAST", 400),
    @SerialName("ERR_MASTER_OVERLAP") ERR_MASTER_OVERLAP("ERR_MASTER_OVERLAP", 409),
    @SerialName("ERR_MASTER_IN_USE") ERR_MASTER_IN_USE("ERR_MASTER_IN_USE", 409),
    @SerialName("ERR_REQUEST_STATE") ERR_REQUEST_STATE("ERR_REQUEST_STATE", 409),
    @SerialName("ERR_SEPARATION_OF_DUTIES") ERR_SEPARATION_OF_DUTIES("ERR_SEPARATION_OF_DUTIES", 409),
    @SerialName("ERR_REPORT_INVALID_QUERY") ERR_REPORT_INVALID_QUERY("ERR_REPORT_INVALID_QUERY", 400),
    @SerialName("ERR_REPORT_TOO_LARGE") ERR_REPORT_TOO_LARGE("ERR_REPORT_TOO_LARGE", 413),
    @SerialName("ERR_PUSH_DISABLED") ERR_PUSH_DISABLED("ERR_PUSH_DISABLED", 409),
    @SerialName("ERR_ENTRY_WINDOW_CLOSED") ERR_ENTRY_WINDOW_CLOSED("ERR_ENTRY_WINDOW_CLOSED", 409),
    @SerialName("ERR_GIFT_CHOICE_LOCKED") ERR_GIFT_CHOICE_LOCKED("ERR_GIFT_CHOICE_LOCKED", 409),
}
