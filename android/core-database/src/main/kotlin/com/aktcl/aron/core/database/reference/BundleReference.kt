package com.aktcl.aron.core.database.reference

import com.aktcl.aron.contract.PagedSection
import com.aktcl.aron.contract.ResolvedConfigValue
import com.aktcl.aron.contract.RouteSnapshot
import com.aktcl.aron.contract.Sku
import com.aktcl.aron.contract.SkuPrice
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

// The reference view of the day bundle: Bundle.routes[] (RouteSnapshot with Route and BundleOutlet), Bundle.products.skus[]
// (Sku) and Bundle.prices[] (SkuPrice) are shared:contract's generated DTOs (N-002). `BundleReference` itself is a
// projection of the contract `Bundle`, and `ResolvedConfig` and `ConfigDelta` are not generated yet, so those stay here.
// Sections without a table of their own are kept raw (bundle_section).

@Serializable
data class BundleReference(
    val meta: Meta,
    val products: Products,
    val routes: List<RouteSnapshot>,
    val prices: List<SkuPrice> = emptyList(),
    val config: ConfigSection? = null,
) {
    @Serializable
    data class Meta(
        @SerialName("bundle_version") val bundleVersion: String,
        @SerialName("valid_for_business_date") val validForBusinessDate: String,
        @SerialName("server_time") val serverTime: String? = null,
        @SerialName("config_version") val configVersion: Long? = null,
        val cursor: String? = null,
        @SerialName("is_prefetch") val isPrefetch: Boolean = false,
        @SerialName("paged_sections") val pagedSections: List<PagedSection> = emptyList(),
    )

    @Serializable
    data class Products(val skus: List<Sku>)

    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun parse(text: String): BundleReference = json.decodeFromString(serializer(), text)
    }
}

/** `ResolvedConfig` (contract): values in force plus scheduled values within the horizon. Not generated in shared:contract. */
@Serializable
data class ConfigSection(
    @SerialName("config_version") val configVersion: Long,
    val values: List<ResolvedConfigValue> = emptyList(),
    val scheduled: List<ResolvedConfigValue> = emptyList(),
)

/** `ConfigDelta` (contract): resolved values that changed for the caller since a config version. */
@Serializable
data class ConfigDeltaWire(
    @SerialName("from_version") val fromVersion: Long,
    @SerialName("to_version") val toVersion: Long,
    val values: List<ResolvedConfigValue> = emptyList(),
    val scheduled: List<ResolvedConfigValue> = emptyList(),
    @SerialName("removed_keys") val removedKeys: List<String> = emptyList(),
    @SerialName("calendar_changes") val calendarChanges: JsonArray = JsonArray(emptyList()),
    @SerialName("outlet_radius_changes") val outletRadiusChanges: List<RadiusChange> = emptyList(),
    @SerialName("policy_changed") val policyChanged: Boolean = false,
) {
    @Serializable
    data class RadiusChange(
        @SerialName("outlet_id") val outletId: Long,
        @SerialName("radius_m") val radiusM: Int,
        @SerialName("max_accuracy_m") val maxAccuracyM: Int,
    )
}
