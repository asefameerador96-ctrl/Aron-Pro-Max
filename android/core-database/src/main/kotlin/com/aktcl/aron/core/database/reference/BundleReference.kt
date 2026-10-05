package com.aktcl.aron.core.database.reference

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

// The reference sections of the day bundle as the contract defines them (Bundle.routes[] RouteSnapshot with Route and
// BundleOutlet; Bundle.products.skus[] Sku). REQUEST: docs/requests/android-core-contract-dtos.md. The full bundle
// apply (prices, offers, config, templates, tasks) is F-SYS-006 (Day 2).

@Serializable
data class BundleReference(
    val meta: Meta,
    val products: Products,
    val routes: List<RouteSnapshot>,
) {
    @Serializable
    data class Meta(
        @SerialName("bundle_version") val bundleVersion: String,
        @SerialName("valid_for_business_date") val validForBusinessDate: String,
    )

    @Serializable
    data class Products(val skus: List<Sku>)

    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun parse(text: String): BundleReference = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class RouteSnapshot(
    @SerialName("route_id") val routeId: Long,
    @SerialName("route_snapshot_version") val routeSnapshotVersion: Int,
    val route: Route,
    @SerialName("assignment_kind") val assignmentKind: String,
    @SerialName("acting_for_user_id") val actingForUserId: Long? = null,
    @SerialName("planned_today") val plannedToday: Boolean,
    @SerialName("target_outlets") val targetOutlets: Int,
    val outlets: List<BundleOutlet>,
    // Sections applied by the bundle row (F-SYS-006, Day 2); kept raw here so the shape stays the contract's.
    @SerialName("open_memos") val openMemos: JsonArray = JsonArray(emptyList()),
    @SerialName("sales_plan_sku_ids") val salesPlanSkuIds: List<Long> = emptyList(),
    val targets: JsonArray = JsonArray(emptyList()),
    @SerialName("achievement_mtd") val achievementMtd: JsonArray = JsonArray(emptyList()),
    @SerialName("day_state") val dayState: JsonElement = JsonNull,
)

@Serializable
data class Route(
    val id: Long,
    val code: String,
    val name: String,
    @SerialName("display_label") val displayLabel: String? = null,
    @SerialName("zone_id") val zoneId: Long,
    val kind: String,
    @SerialName("visit_kind") val visitKind: String? = null,
    @SerialName("visit_days_mask") val visitDaysMask: Int,
    @SerialName("sequence_no") val sequenceNo: Int? = null,
    val status: String,
)

@Serializable
data class BundleOutlet(
    @SerialName("outlet_id") val outletId: Long,
    @SerialName("route_id") val routeId: Long,
    val code: String,
    val name: String,
    @SerialName("name_bn") val nameBn: String? = null,
    @SerialName("name_sort_key") val nameSortKey: String,
    @SerialName("owner_name") val ownerName: String,
    @SerialName("contact_number") val contactNumber: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("location_confirmed") val locationConfirmed: Boolean,
    @SerialName("provisional_lat") val provisionalLat: Double? = null,
    @SerialName("provisional_lng") val provisionalLng: Double? = null,
    @SerialName("cluster_id") val clusterId: Long,
    @SerialName("cluster_name") val clusterName: String,
    val channel: String,
    @SerialName("sub_channel_id") val subChannelId: Long? = null,
    @SerialName("geo_class") val geoClass: String? = null,
    val status: String,
    @SerialName("price_type") val priceType: String,
    @SerialName("outlet_kind") val outletKind: String,
    @SerialName("radius_m") val radiusM: Int,
    @SerialName("max_accuracy_m") val maxAccuracyM: Int,
    @SerialName("visit_sequence") val visitSequence: Int? = null,
    @SerialName("open_due_mtk") val openDueMtk: Long,
    @SerialName("open_due_as_of") val openDueAsOf: String? = null,
    @SerialName("programme_flags") val programmeFlags: List<String>,
    @SerialName("pending_request") val pendingRequest: Boolean,
    /** Suggested-quantity hook, empty until the formula is confirmed (cfg.sale.suggested_qty_enabled); not stored yet. */
    @SerialName("suggested_qty") val suggestedQty: JsonArray = JsonArray(emptyList()),
)

@Serializable
data class Sku(
    val id: Long,
    val code: String,
    @SerialName("variant_id") val variantId: Long,
    @SerialName("category_code") val categoryCode: String,
    val name: String,
    @SerialName("short_name") val shortName: String,
    @SerialName("name_bn") val nameBn: String? = null,
    @SerialName("base_unit") val baseUnit: String,
    @SerialName("base_per_pack") val basePerPack: Int,
    @SerialName("entry_unit_default") val entryUnitDefault: String,
    @SerialName("report_unit") val reportUnit: String? = null,
    @SerialName("report_factor") val reportFactor: String,
    val sort: Int,
    val status: String,
    val version: Int? = null,
)
