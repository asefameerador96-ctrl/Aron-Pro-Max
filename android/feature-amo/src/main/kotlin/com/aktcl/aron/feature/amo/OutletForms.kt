package com.aktcl.aron.feature.amo

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.repo.RouteDay
import com.aktcl.aron.feature.outlet.GeoPhotoState
import com.aktcl.aron.feature.outlet.OutletPicker
import com.aktcl.aron.feature.outlet.RequestError
import com.aktcl.aron.rules.TextRules

/** The checks the AMO forms report under their fields (own requests and verification share them; D-AMO-OUTLET-01). */
enum class AmoFormError { ROUTE, CLUSTER, RETAILER, NAME, OWNER, MOBILE, SUB_CHANNEL, GEO_CLASS, GEO_PHOTO }

/** Data of the drop-downs and the shop lists, from the local bundle (works offline). */
object AmoOutletLists {
    /** Active routes, ordered by code; the vendor's label is the display label (name plus visit days) or the name. */
    fun routes(days: List<RouteDay>): List<AmoOption> =
        days.filter { it.route.status == "active" }.sortedWith(compareBy({ it.route.code }, { it.route.routeId }))
            .map { AmoOption(it.route.routeId, it.route.displayLabel ?: it.route.name) }.distinctBy { it.id }

    /** Distinct clusters of [outlets] by name; an outlet without a cluster adds none. */
    fun clusters(outlets: List<OutletEntity>): List<AmoOption> =
        outlets.mapNotNull { o -> o.clusterId?.let { AmoOption(it, o.clusterName ?: it.toString()) } }.distinctBy { it.id }.sortedBy { it.label.lowercase() }

    /** The retailers of a pick: active outlets of [cluster] (all when null) filtered by the alphabet chip, as `name (code-phone-cluster)`. */
    fun retailers(outlets: List<OutletEntity>, cluster: Long?, chip: String): List<AmoOption> =
        OutletPicker.rows(outlets.filter { cluster == null || it.clusterId == cluster }, chip).map { AmoOption(it.outlet.outletId, it.label) }

    /** The letters present in the retailers of [cluster]: case-insensitive, upper-case (R3). */
    fun letters(outlets: List<OutletEntity>, cluster: Long?): List<String> =
        OutletPicker.chips(OutletPicker.rows(outlets.filter { cluster == null || it.clusterId == cluster }))

    /** The number as the vendor never shows it: the full 11 digits with the leading 0 (R3); an unusable number is shown as stored. */
    fun phone(raw: String?): String? = raw?.takeIf { it.isNotBlank() }?.let { TextRules.normalisePhone(it)?.value ?: it }

    /** A [RequestError] of the shared validator, as the AMO form's field error. */
    fun fieldError(e: RequestError): AmoFormError? = when (e) {
        RequestError.NAME_INVALID -> AmoFormError.NAME; RequestError.OWNER_INVALID -> AmoFormError.OWNER; RequestError.MOBILE_INVALID -> AmoFormError.MOBILE
        RequestError.CLUSTER_REQUIRED -> AmoFormError.CLUSTER; RequestError.OUTLET_REQUIRED -> AmoFormError.RETAILER
        RequestError.GEO_REQUIRED, RequestError.PHOTO_REQUIRED, RequestError.MOCK_FIX -> AmoFormError.GEO_PHOTO
        else -> null
    }
}

@Composable
private fun errorText(e: AmoFormError?): String? = e?.let {
    stringResource(
        when (it) {
            AmoFormError.ROUTE -> R.string.amo_out_err_route; AmoFormError.CLUSTER -> R.string.amo_out_err_cluster; AmoFormError.RETAILER -> R.string.amo_out_err_retailer
            AmoFormError.NAME -> R.string.amo_out_err_name; AmoFormError.OWNER -> R.string.amo_out_err_owner; AmoFormError.MOBILE -> R.string.amo_out_err_mobile
            AmoFormError.SUB_CHANNEL -> R.string.amo_out_err_sub_channel; AmoFormError.GEO_CLASS -> R.string.amo_out_err_geo_class; AmoFormError.GEO_PHOTO -> R.string.amo_out_err_geo
        },
    )
}

/** A drop-down value: the id chosen, and what to do on a choice. */
data class Pick(val options: List<AmoOption>, val id: Long?, val onPick: (Long) -> Unit, val enabled: Boolean = true)

/** The text fields of a shop: what the AMO typed (or the SR proposed, read-only on the verification form). */
data class ShopText(val name: String, val owner: String, val mobile: String, val onName: (String) -> Unit, val onOwner: (String) -> Unit, val onMobile: (String) -> Unit)

@Composable
fun routeItem(p: Pick, errors: Set<AmoFormError>) = AmoFormItem.Select(
    "route", null, stringResource(R.string.amo_out_pick_route), p.options, p.id, p.onPick, p.enabled, errorText(AmoFormError.ROUTE.takeIf { it in errors }),
)

@Composable
fun clusterItem(p: Pick, errors: Set<AmoFormError>) = AmoFormItem.Select(
    "cluster", null, stringResource(R.string.amo_out_pick_cluster), p.options, p.id, p.onPick, p.enabled, errorText(AmoFormError.CLUSTER.takeIf { it in errors }),
)

@Composable
fun retailerItem(p: Pick, errors: Set<AmoFormError>) = AmoFormItem.Select(
    "retailer", null, stringResource(R.string.amo_out_pick_retailer), p.options, p.id, p.onPick, p.enabled, errorText(AmoFormError.RETAILER.takeIf { it in errors }),
)

@Composable
fun shopTextItems(t: ShopText, errors: Set<AmoFormError>): List<AmoFormItem> = listOf(
    AmoFormItem.Field("name", stringResource(R.string.amo_out_f_shop), t.name, t.onName, error = errorText(AmoFormError.NAME.takeIf { it in errors })),
    AmoFormItem.Field("owner", stringResource(R.string.amo_out_f_owner), t.owner, t.onOwner, error = errorText(AmoFormError.OWNER.takeIf { it in errors })),
    AmoFormItem.Field("mobile", stringResource(R.string.amo_out_f_mobile), t.mobile, t.onMobile, KeyboardType.Phone, errorText(AmoFormError.MOBILE.takeIf { it in errors })),
)

/** Sub-Channel and Geo Classification (47, 52, 55): both drop-downs, Geo Classification from the four reference classes. */
@Composable
fun classificationItems(sub: Pick, geoClass: String?, onGeoClass: (String) -> Unit, errors: Set<AmoFormError>, enabled: Boolean = true): List<AmoFormItem> {
    val classes = OutletVerify.GEO_CLASSES
    val labels = listOf(R.string.amo_verify_geo_hill, R.string.amo_verify_geo_urban, R.string.amo_verify_geo_semiurban, R.string.amo_verify_geo_rural).map { stringResource(it) }
    return listOf(
        AmoFormItem.Select("sub_channel", stringResource(R.string.amo_out_sub_channel), stringResource(R.string.amo_out_pick_sub_channel), sub.options, sub.id, sub.onPick, enabled && sub.enabled, errorText(AmoFormError.SUB_CHANNEL.takeIf { it in errors })),
        AmoFormItem.Select(
            "geo_class", stringResource(R.string.amo_out_geo_class), stringResource(R.string.amo_out_pick_geo_class), classes.indices.map { AmoOption(it.toLong(), labels[it]) },
            geoClass?.let { classes.indexOf(it).takeIf { i -> i >= 0 }?.toLong() }, { onGeoClass(classes[it.toInt()]) }, enabled, errorText(AmoFormError.GEO_CLASS.takeIf { it in errors }),
        ),
    )
}

@Composable
fun captureItem(state: GeoPhotoState, onShutter: () -> Unit, errors: Set<AmoFormError>) = AmoFormItem.Capture(state, onShutter, errorText(AmoFormError.GEO_PHOTO.takeIf { it in errors }))

/** Read-only name, owner and mobile of the chosen outlet (50, 57). */
@Composable
fun shopReadOnlyItems(name: String?, owner: String?, mobile: String?): List<AmoFormItem> = listOf(
    AmoFormItem.ReadOnly("name", stringResource(R.string.amo_out_f_shop), name.orEmpty()),
    AmoFormItem.ReadOnly("owner", stringResource(R.string.amo_out_f_owner), owner.orEmpty()),
    AmoFormItem.ReadOnly("mobile", stringResource(R.string.amo_out_f_mobile), AmoOutletLists.phone(mobile).orEmpty()),
)

/**
 * New shop (54, 55): route, then cluster; the form (name, owner, mobile, Sub-Channel, Geo Classification, GEO and photo) opens once a cluster is
 * chosen. The AMO's own request goes to the web approver, so the two classification drop-downs are optional here.
 */
@Composable
fun newShopItems(route: Pick, cluster: Pick, text: ShopText, sub: Pick, geoClass: String?, onGeoClass: (String) -> Unit, capture: GeoPhotoState, onShutter: () -> Unit, errors: Set<AmoFormError>): List<AmoFormItem> =
    listOf(routeItem(route, errors), clusterItem(cluster, errors)) +
        if (cluster.id == null) emptyList() else shopTextItems(text, errors) + classificationItems(sub, geoClass, onGeoClass, errors) + captureItem(capture, onShutter, errors)

/** Change information (59): route, retailer with the alphabet bar, the cluster once (read-only), name, owner, mobile, GEO and photo. */
@Composable
fun changeInfoItems(route: Pick, retailer: Pick, letters: AmoFormItem.Letters?, cluster: Pick, text: ShopText, capture: GeoPhotoState, onShutter: () -> Unit, errors: Set<AmoFormError>): List<AmoFormItem> =
    listOfNotNull(routeItem(route, errors), letters, retailerItem(retailer, errors)) +
        if (retailer.id == null) emptyList() else listOf(clusterItem(cluster, errors)) + shopTextItems(text, errors) + captureItem(capture, onShutter, errors)

/** Permanent close (57): cluster, retailer, then the read-only name, owner and mobile of that retailer. */
@Composable
fun closeItems(cluster: Pick, retailer: Pick, name: String?, owner: String?, mobile: String?, errors: Set<AmoFormError>): List<AmoFormItem> =
    listOf(clusterItem(cluster, errors), retailerItem(retailer, errors)) + if (retailer.id == null) emptyList() else shopReadOnlyItems(name, owner, mobile)
