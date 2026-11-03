package com.aktcl.aron.backend.analytics

import kotlinx.serialization.Serializable

/** Wire types of the national dashboard (contract 1.4.0, the national dashboard operations). Names are the contract's. */

@Serializable
data class NationalDashboardFilter(
    val wing_ids: List<Long> = emptyList(), val division_ids: List<Long> = emptyList(), val territory_ids: List<Long> = emptyList(),
    val house_ids: List<Long> = emptyList(), val zone_ids: List<Long> = emptyList(),
    val category_codes: List<String> = emptyList(), val segment_codes: List<String> = emptyList(),
    val brand_codes: List<String> = emptyList(), val variant_codes: List<String> = emptyList(),
    val date: String? = null,
)

@Serializable
data class NationalBreakdownRequest(val filter: NationalDashboardFilter = NationalDashboardFilter(), val measure: String, val dimension: String, val category_code: String? = null)

@Serializable
data class FfGeoPinsRequest(val filter: NationalDashboardFilter = NationalDashboardFilter(), val colors: List<String> = emptyList())

@Serializable
data class NationalSalesCard(
    val category_code: String, val base_unit: String, val total_sales: Long, val daily_target: String?, val sales_so_far: Long,
    val remaining: String?, val achievement_pct_bp: Long?, val target_routes: Int,
    /** Contract 1.10.0: the sum of the route monthly targets in scope (base units); null when no target is loaded (D-TSO-MAN-08). */
    val monthly_target_qty: Long? = null,
)

@Serializable
data class NationalSales(
    val date: String, val till_time: Boolean, val as_of: String, val last_updated_at: String?, val working_days: Int, val cards: List<NationalSalesCard>,
)

@Serializable
data class NationalCpr(
    val date: String, val till_time: Boolean, val as_of: String, val last_updated_at: String?,
    val target_outlets: Int, val successful_calls: Int, val cpr_pct_bp: Int?,
)

@Serializable
data class ChannelShare(val channel: String, val value: Long, val share_pct_bp: Int?)

@Serializable
data class NationalChannelCalls(
    val date: String, val till_time: Boolean, val as_of: String, val last_updated_at: String?,
    val channels: List<ChannelShare>, val total: Int, val unclassified: Int,
)

@Serializable
data class NationalChannelStd(
    val date: String, val till_time: Boolean, val as_of: String, val last_updated_at: String?,
    val channels: List<ChannelShare>, val total: Long, val unit: String = "stick",
)

@Serializable
data class NationalFinalSubmit(
    val date: String, val till_time: Boolean, val as_of: String, val last_updated_at: String?,
    val total_zones: Int, val service_zones: Int, val final_submitted: Int, val remaining: Int, val submit_pct_bp: Int?,
)

@Serializable
data class NationalLoginSubmit(
    val date: String, val till_time: Boolean, val as_of: String, val last_updated_at: String?,
    val target_routes: Int, val successful_login: Int, val login_pct_bp: Int?, val submitted: Int, val submit_pct_bp: Int?,
    /** Contract 1.10.0: submitted / target_routes, the TSO app's Bikroy Joma % (D-TSO-MAN-07). */
    val submit_of_target_pct_bp: Int? = null,
)

@Serializable
data class BreakdownItem(val code: String, val name: String? = null, val value: Long)

@Serializable
data class NationalBreakdown(
    val date: String, val till_time: Boolean, val as_of: String, val last_updated_at: String?,
    val measure: String, val dimension: String, val category_code: String? = null, val unit: String? = null, val items: List<BreakdownItem>,
)

@Serializable
data class NationalGeoFencing(
    val date: String, val till_time: Boolean, val as_of: String, val last_updated_at: String?,
    val total_calls: Int, val geo_validated: Int, val geo_pct_bp: Int?,
)

@Serializable
data class FfGeoPin(val route_id: Long, val lat: Double, val lng: Double, val color: String, val geo_pct_bp: Int?, val position_at: String)

@Serializable
data class ColorCounts(val red: Int, val amber: Int, val green: Int)

@Serializable
data class FfGeoPins(
    val date: String, val till_time: Boolean, val as_of: String, val last_updated_at: String?,
    val pins: List<FfGeoPin>, val counts: ColorCounts, val routes_without_position: Int,
)

@Serializable
data class PlaceRef(val id: Long, val code: String, val name: String? = null)

@Serializable
data class FfGeoRoutePopup(
    val date: String, val till_time: Boolean, val as_of: String, val last_updated_at: String?,
    val route: PlaceRef, val sr_name: String? = null, val contact: String? = null, val territory: PlaceRef, val zone: PlaceRef,
    val position_at: String? = null, val miss_pct_bp: Int? = null,
)

@Serializable
data class CategoryQty(val category_code: String, val base_unit: String, val qty: Long)

@Serializable
data class MapPosition(val lat: Double, val lng: Double, val at: String)

@Serializable
data class RouteMapOutlet(
    val outlet_id: Long, val code: String, val name: String? = null, val contact: String? = null, val cluster_name: String? = null,
    val lat: Double, val lng: Double, val state: String, val call_time: String? = null, val sales: List<CategoryQty>,
)

@Serializable
data class TargetVsAchievement(val category_code: String, val base_unit: String, val daily_target: String?, val sales: Long, val achievement_pct_bp: Long?)

@Serializable
data class RouteMapCpr(val target_outlets: Int, val successful_calls: Int, val geo_validated: Int, val cpr_pct_bp: Int?)

@Serializable
data class RouteMapTimeCard(
    val first_sale_time: String? = null, val last_sale_time: String? = null, val total_sale_seconds: Long? = null,
    val average_sale_seconds: Long? = null, val distance_covered_m: Double,
)

@Serializable
data class RouteMap(
    val date: String, val till_time: Boolean, val as_of: String, val last_updated_at: String?,
    val route: PlaceRef, val sr_name: String? = null, val territory: PlaceRef, val zone: PlaceRef,
    val sale_start: MapPosition? = null, val sr_last_position: MapPosition? = null,
    val outlets: List<RouteMapOutlet>, val path: List<MapPosition>, val target_vs_achievement: List<TargetVsAchievement>,
    val last_sale_place: String? = null, val cpr: RouteMapCpr, val time_card: RouteMapTimeCard,
)

@Serializable
data class SalePositionBase(val lat: Double, val lng: Double)

@Serializable
data class SalePositionSale(
    val memo_uuid: String, val kind: String, val lat: Double, val lng: Double, val at: String, val distance_m: Double,
    val within_fence: Boolean, val status: String? = null, val photo_media_uuid: String? = null,
)

@Serializable
data class SalePosition(
    val date: String, val till_time: Boolean, val as_of: String, val last_updated_at: String?,
    val zone: PlaceRef, val route: PlaceRef, val outlet_code: String, val radius_m: Int, val base: SalePositionBase,
    val header_distance_m: Double? = null, val sales: List<SalePositionSale>,
)

@Serializable
data class OptionNode(
    val id: Long, val code: String, val name: String? = null, val wing_id: Long? = null, val division_id: Long? = null,
    val territory_id: Long? = null, val house_id: Long? = null,
)

@Serializable
data class OptionCode(val code: String, val name: String? = null, val category_code: String? = null, val segment_code: String? = null, val brand_code: String? = null)

@Serializable
data class NationalFilterOptions(
    val date: String, val till_time: Boolean, val as_of: String, val last_updated_at: String?,
    val wings: List<OptionNode>, val divisions: List<OptionNode>, val territories: List<OptionNode>, val houses: List<OptionNode>, val zones: List<OptionNode>,
    val categories: List<OptionCode>, val segments: List<OptionCode>, val brands: List<OptionCode>, val variants: List<OptionCode>,
)

/** Brand CPR card (contract 1.10.0 `NationalBrandCpr`, D-TSO-MAN-06). */
@Serializable
data class BrandCprRow(
    val brand_code: String, val brand_name: String, val outlets_bought: Int, val cpr_bp: Long?, val cpr_of_successful_bp: Long?, val cpr_of_target_bp: Long?,
)

@Serializable
data class NationalBrandCpr(
    val date: String, val till_time: Boolean, val as_of: String, val last_updated_at: String?,
    val target_outlets: Int, val successful_calls: Int, val denominator: String, val brands: List<BrandCprRow>,
)

/** By Segment Value Contribution card (contract 1.10.0 `NationalSegmentShare`, D-TSO-MAN-11). */
@Serializable
data class SegmentFamily(val base_unit: String, val total_qty_base: Long)

@Serializable
data class SegmentShareRow(
    val segment_code: String, val segment_name: String, val category_code: String, val base_unit: String, val value_mtk: Long, val value_share_bp: Int?,
    val qty_base: Long, val volume_share_bp: Int?,
)

@Serializable
data class NationalSegmentShare(
    val date: String, val till_time: Boolean, val as_of: String, val last_updated_at: String?,
    val total_value_mtk: Long, val families: List<SegmentFamily>, val segments: List<SegmentShareRow>,
)

/** One (route, user) row of the Login / Submit Status screen (contract 1.10.0 `LoginSubmitUserRow`, D-TSO-MAN-12). */
@Serializable
data class LoginSubmitUserRow(
    val route_id: Long, val route_code: String, val route_name: String, val zone_id: Long, val dep_name: String,
    val user_id: Long?, val username: String?, val display_name: String?, val role: String,
)

@Serializable
data class NationalLoginSubmitUsers(
    val date: String, val till_time: Boolean, val as_of: String, val last_updated_at: String?,
    val tab: String, val route_count: Int, val truncated: Boolean, val items: List<LoginSubmitUserRow>,
)

@Serializable
data class FreshnessTable(val table: String, val last_updated_at: String?)

@Serializable
data class NationalFreshness(
    val date: String, val till_time: Boolean, val as_of: String, val last_updated_at: String?, val tables: List<FreshnessTable>,
)
