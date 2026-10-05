package com.aktcl.aron.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Reference data of the day bundle (docs/24 s4.10), replaced as a whole in one transaction when a bundle is applied.
// Server ids are the keys; nothing here is ever uploaded.

/** A route assigned to the user for the bundle's business date (contract `RouteSnapshot` + `Route`). */
@Entity(tableName = "route", indices = [Index("business_date")])
data class RouteEntity(
    @PrimaryKey @ColumnInfo(name = "route_id") val routeId: Long,
    val code: String,
    val name: String,
    @ColumnInfo(name = "display_label") val displayLabel: String?,
    @ColumnInfo(name = "zone_id") val zoneId: Long,
    val kind: String,
    @ColumnInfo(name = "visit_kind") val visitKind: String?,
    @ColumnInfo(name = "visit_days_mask") val visitDaysMask: Int,
    @ColumnInfo(name = "sequence_no") val sequenceNo: Int?,
    val status: String,
    @ColumnInfo(name = "assignment_kind") val assignmentKind: String,
    @ColumnInfo(name = "acting_for_user_id") val actingForUserId: Long?,
    @ColumnInfo(name = "planned_today") val plannedToday: Boolean,
    @ColumnInfo(name = "target_outlets") val targetOutlets: Int,
    @ColumnInfo(name = "route_snapshot_version") val routeSnapshotVersion: Int,
    /** The business date of the bundle this row came from. */
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "bundle_version") val bundleVersion: String,
)

/** An outlet with its resolved geofence radius and accuracy (contract `BundleOutlet`). */
@Entity(tableName = "outlet", indices = [Index("route_id"), Index("code")])
data class OutletEntity(
    @PrimaryKey @ColumnInfo(name = "outlet_id") val outletId: Long,
    @ColumnInfo(name = "route_id") val routeId: Long,
    val code: String,
    val name: String,
    @ColumnInfo(name = "name_bn") val nameBn: String?,
    @ColumnInfo(name = "name_sort_key") val nameSortKey: String,
    @ColumnInfo(name = "owner_name") val ownerName: String,
    @ColumnInfo(name = "contact_number") val contactNumber: String?,
    val lat: Double?,
    val lng: Double?,
    @ColumnInfo(name = "location_confirmed") val locationConfirmed: Boolean,
    @ColumnInfo(name = "provisional_lat") val provisionalLat: Double?,
    @ColumnInfo(name = "provisional_lng") val provisionalLng: Double?,
    @ColumnInfo(name = "cluster_id") val clusterId: Long,
    @ColumnInfo(name = "cluster_name") val clusterName: String,
    val channel: String,
    @ColumnInfo(name = "sub_channel_id") val subChannelId: Long?,
    @ColumnInfo(name = "geo_class") val geoClass: String?,
    val status: String,
    @ColumnInfo(name = "price_type") val priceType: String,
    @ColumnInfo(name = "outlet_kind") val outletKind: String,
    @ColumnInfo(name = "radius_m") val radiusM: Int,
    @ColumnInfo(name = "max_accuracy_m") val maxAccuracyM: Int,
    @ColumnInfo(name = "visit_sequence") val visitSequence: Int?,
    @ColumnInfo(name = "open_due_mtk") val openDueMtk: Long,
    @ColumnInfo(name = "open_due_as_of") val openDueAsOf: String?,
    /** JSON array of programme badge codes. */
    @ColumnInfo(name = "programme_flags_json") val programmeFlagsJson: String,
    @ColumnInfo(name = "pending_request") val pendingRequest: Boolean,
)

/** A sellable SKU with its base unit and pack factor (contract `Sku`). */
@Entity(tableName = "sku", indices = [Index(value = ["code"], unique = true)])
data class SkuEntity(
    @PrimaryKey @ColumnInfo(name = "sku_id") val skuId: Long,
    val code: String,
    @ColumnInfo(name = "variant_id") val variantId: Long,
    @ColumnInfo(name = "category_code") val categoryCode: String,
    val name: String,
    @ColumnInfo(name = "short_name") val shortName: String,
    @ColumnInfo(name = "name_bn") val nameBn: String?,
    @ColumnInfo(name = "base_unit") val baseUnit: String,
    @ColumnInfo(name = "base_per_pack") val basePerPack: Int,
    @ColumnInfo(name = "entry_unit_default") val entryUnitDefault: String,
    @ColumnInfo(name = "report_unit") val reportUnit: String?,
    /** Decimal3 as text (never a float). */
    @ColumnInfo(name = "report_factor") val reportFactor: String,
    val sort: Int,
    val status: String,
    val version: Int?,
)
