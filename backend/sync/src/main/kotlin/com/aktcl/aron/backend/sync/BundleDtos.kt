package com.aktcl.aron.backend.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Wire shapes of GET /v1/sync/bundle (contract `Bundle` and its members, docs/24 s4.10). Member names are the
 * contract's snake_case names. Optional contract members that this build does not fill yet are not declared at all
 * (rather than sent as null where the schema forbids null): `device_policy_version` and `my_outlet_requests`.
 */

@Serializable
data class Bundle(
    val meta: BundleMeta,
    val user: BundleUser,
    val config: ResolvedConfig,
    val code_lists: List<CodeList>,
    val products: BundleProducts,
    val prices: List<SkuPrice>,
    /** Deferred (docs/27): always empty, the member stays. */
    val offers: List<JsonObject>,
    val calendar: CalendarSection,
    val templates: List<PrintTemplate>,
    val routes: List<RouteSnapshot>,
    val tasks: List<TaskDto>,
    val surveys: List<JsonObject>,
    val rubrics: List<JsonObject>,
    /** AMO and TSO team section (zone-wide data, F-AMO-044); null in this build. */
    val supervisor: JsonObject?,
    val reason_texts: Map<String, ReasonText>,
    /** Deferred programmes (docs/27): null. */
    val programmes: JsonObject?,
    val content: List<JsonObject>,
    val tutorials: List<JsonObject>,
)

@Serializable
data class PagedSection(val section: String, val pages: Int, val rows: Int)

@Serializable
data class BundleMeta(
    val bundle_version: String,
    val valid_for_business_date: String,
    val generated_at: String,
    val server_time: String,
    val user_id: Long,
    val role: String,
    val config_version: Long,
    val schema_version: Int,
    val cursor: String,
    val is_prefetch: Boolean,
    val paged_sections: List<PagedSection>,
)

@Serializable
data class BundleUser(
    val user_id: Long,
    val username: String,
    val full_name: String,
    val role: String,
    val designation: String?,
    val locale: String,
    val bind_ordinal: Int,
    val memo_seq_block_size: Int,
    val zone_id: Long?,
    val territory_id: Long?,
    /** Policy versions the user accepted (F-SYS-075): the phone seeds its consent flag and does not ask again. */
    val consents: List<BundleConsent> = emptyList(),
)

@Serializable
data class BundleConsent(val policy_key: String, val policy_version: Int, val accepted_at: String)

@Serializable
data class ResolvedConfigValue(
    val key: String,
    val value: JsonElement,
    val scope_type: String,
    val scope_id: Long?,
    val effective_from: String?,
    val effective_to: String?,
    val config_version: Long?,
    val requires_ack: Boolean,
    val bounds: JsonObject?,
)

@Serializable
data class ResolvedConfig(val config_version: Long, val values: List<ResolvedConfigValue>, val scheduled: List<ResolvedConfigValue>)

@Serializable
data class CodeItem(
    val code: String,
    val label_bn: String?,
    val label_en: String,
    val sort: Int,
    val attrs: JsonObject,
    val valid_from: String,
    val valid_to: String?,
)

@Serializable
data class CodeList(val list_key: String, val items: List<CodeItem>)

@Serializable
data class BundleProductNode(val id: Long, val level: String, val parent_id: Long?, val code: String?, val name: String, val name_bn: String?, val sort: Int)

@Serializable
data class SkuDto(
    val id: Long,
    val code: String,
    val variant_id: Long,
    val category_code: String,
    val name: String,
    val short_name: String,
    val name_bn: String?,
    val base_unit: String,
    val base_per_pack: Int,
    val entry_unit_default: String,
    val report_unit: String?,
    /** Decimal3 on the wire: a decimal string with up to three fraction digits. */
    val report_factor: String,
    val sort: Int,
    val status: String,
    val thumbnail_media_uuid: String?,
    val updated_at: String,
    val version: Int,
)

@Serializable
data class BundleProducts(val nodes: List<BundleProductNode>, val skus: List<SkuDto>)

@Serializable
data class SkuPrice(
    val id: Long,
    val sku_id: Long,
    val price_type: String,
    val amount_mtk: Long,
    val per_base_qty: Int,
    val valid_from: String,
    val valid_to: String?,
)

@Serializable
data class Holiday(
    val id: Long,
    val date: String,
    val scope_type: String,
    val scope_id: Long,
    val kind: String,
    val selling_day: Boolean,
    val name_en: String,
    val name_bn: String?,
)

@Serializable
data class CalendarSection(val weekend_days: List<Int>, val entries: List<Holiday>)

@Serializable
data class PrintTemplate(val kind: String, val version: Int, val font_columns: Int, val template_json: String)

@Serializable
data class RouteDto(
    val id: Long,
    val code: String,
    val name: String,
    val display_label: String?,
    val zone_id: Long,
    val kind: String,
    val visit_kind: String?,
    val visit_days_mask: Int,
    val sequence_no: Int?,
    val status: String,
    val created_at: String,
    val updated_at: String,
    val version: Int,
)

@Serializable
data class BundleOutlet(
    val outlet_id: Long,
    val route_id: Long,
    val code: String,
    val name: String,
    val name_bn: String?,
    val name_sort_key: String,
    val owner_name: String,
    val contact_number: String?,
    val lat: Double?,
    val lng: Double?,
    val location_confirmed: Boolean,
    val provisional_lat: Double?,
    val provisional_lng: Double?,
    val cluster_id: Long,
    val cluster_name: String,
    val channel: String,
    val sub_channel_id: Long?,
    val geo_class: String?,
    val status: String,
    val price_type: String,
    val outlet_kind: String,
    val radius_m: Int,
    val max_accuracy_m: Int,
    val visit_sequence: Int?,
    val open_due_mtk: Long,
    val open_due_as_of: String?,
    /** Eligibility dots of the deferred programmes (docs/27): empty. */
    val programme_flags: List<String>,
    val pending_request: Boolean,
    /** Suggested-order hook (F-SYS-035): empty while cfg.sale.suggested_qty_enabled is false. */
    val suggested_qty: List<JsonObject>,
)

@Serializable
data class OpenMemo(
    val memo_client_uuid: String,
    val memo_no: String,
    val business_date: String,
    val outlet_id: Long,
    val net_mtk: Long,
    val due_mtk: Long,
)

@Serializable
data class RouteDayStateDto(
    val route_id: Long,
    val business_date: String,
    val state: String,
    val planned: Boolean,
    val submit_cycle: Int,
    val submit_voided: Boolean,
    val submit_count_mismatch: Boolean?,
    val logged_in_at: String?,
    val sales_submitted_at: String?,
    val final_submitted_at: String?,
)

@Serializable
data class RouteSnapshot(
    val route_id: Long,
    val route_snapshot_version: Int,
    val route: RouteDto,
    val assignment_kind: String,
    val acting_for_user_id: Long?,
    val planned_today: Boolean,
    val target_outlets: Int,
    val outlets: List<BundleOutlet>,
    val open_memos: List<OpenMemo>,
    val sales_plan_sku_ids: List<Long>,
    /** Deferred (docs/27): empty. */
    val targets: List<JsonObject>,
    /** Achievement against deferred targets (docs/27): empty. */
    val achievement_mtd: List<JsonObject>,
    val day_state: RouteDayStateDto,
)

@Serializable
data class TaskDto(
    val task_uuid: String,
    val task_type_code: String,
    val title: String,
    val description: String?,
    val assignee_user_id: Long,
    val assigned_by_user_id: Long,
    val route_id: Long?,
    val outlet_id: Long?,
    val due_date: String?,
    val status: String,
    val created_at: String,
    val resolved_at: String?,
    val resolution_note: String?,
    val source: String,
)

@Serializable
data class ReasonText(val bn: String, val en: String)
