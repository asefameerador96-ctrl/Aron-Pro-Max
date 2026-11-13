package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronDialog

object SrRequestTags {
    const val CLUSTER = "sr_cluster"
    const val RETAILER = "sr_retailer"
    const val OUTLET = "sr_outlet"
    const val DETAILS = "sr_details"
    const val SAVED_DIALOG = "sr_saved_dialog"
    const val ALPHA = "sr_alpha"
    fun letter(l: String) = "sr_letter_$l"
}

/** The title and art of the request form's top bar follow the kind (SRM-B-34). */
fun srRequestTitle(kind: OutletRequestKind): Int = when (kind) {
    OutletRequestKind.NEW -> R.string.out_req_new
    OutletRequestKind.CLOSE -> R.string.out_req_close
    OutletRequestKind.INFO, OutletRequestKind.LOCATION -> R.string.out_req_info
    OutletRequestKind.ROUTE_ADD -> R.string.out_req_route_add
    OutletRequestKind.CLUSTER -> R.string.out_menu_clusters
}

/**
 * The SR's outlet request form in the order of the manual (screenshots 42 to 44; SRM-B-32, 37, 39, 40):
 *  - **new shop**: the cluster card first (drop-down with a search dialog), then name, owner, mobile, then GEO and photo and save;
 *  - **permanently closed**: the cluster drop-down narrows the retailer drop-down, a read-only details card shows the chosen shop, then save;
 *  - **change information**: the outlet drop-down with the alphabet bar on top, the prefilled cluster, name, owner and mobile, GEO and photo;
 *  - **add to my route**: the cluster the shop joins and the reason.
 * The form's own title is the top bar's (the host passes [srRequestTitle]); it is not repeated in the body. The save button is disabled
 * while a save runs ([saving]) so a double tap cannot file two requests, and a finished save shows a success dialog before [onDone].
 */
@Composable
fun SrOutletRequestContent(
    form: OutletRequestForm,
    errors: Set<RequestError>,
    clusters: List<ClusterOption>,
    outlets: List<OutletEntity>,
    capture: GeoPhotoState,
    saving: Boolean,
    savedDialog: Boolean,
    onChange: (OutletRequestForm) -> Unit,
    onPickOutlet: (OutletEntity) -> Unit,
    onShutter: () -> Unit,
    onSave: () -> Unit,
    onSavedOk: () -> Unit,
    modifier: Modifier = Modifier,
    blockingReason: String? = null,
    onAddToRoute: (() -> Unit)? = null,
) {
    val look = LocalAronLook.current
    var confirm by rememberSaveable { mutableStateOf(false) }
    val kind = form.kind
    val fieldErrors = errors.filter { it != RequestError.CONFIRMATION_REQUIRED }
    val clusterItems = remember(clusters) { clusters.map { PickItem(it.clusterId.toString(), it.name) } }
    val clusterTitle = stringResource(R.string.out_pick_cluster)
    val searchHint = stringResource(R.string.out_pick_search)
    val rows = remember(outlets) { OutletPicker.rows(outlets) }
    Column(modifier.fillMaxSize().background(look.page)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            // The manual's Permanent close page carries no standing banner (screenshot 43): the sentence belongs to the confirm
            // dialog, which asks it on Save. A warning that something is WRONG still shows, and is never silent.
            if (kind == OutletRequestKind.CLOSE && form.openDueMtk > 0) FormNotice(stringResource(R.string.out_req_dues_warning), kind = BannerKind.Warning)
            when (kind) {
                OutletRequestKind.NEW -> {
                    // manual 42: card 1 the labelled cluster box, card 2 the three labelled boxes
                    FormCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L)) {
                            ClusterPicker(clusterTitle, searchHint, clusterItems, form.clusterId, { onChange(form.copy(clusterId = it)) }, RequestError.CLUSTER_REQUIRED in errors)
                        }
                    }
                    DetailFields(form, errors, onChange)
                }
                OutletRequestKind.CLOSE -> {
                    var clusterFilter by rememberSaveable { mutableStateOf<Long?>(null) }
                    val inCluster = remember(rows, clusterFilter) { rows.filter { clusterFilter == null || it.outlet.clusterId == clusterFilter } }
                    FormCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L)) {
                            ClusterPicker(clusterTitle, searchHint, clusterItems, clusterFilter, { clusterFilter = it }, false)
                        }
                    }
                    val retailerTitle = stringResource(R.string.out_pick_retailer)
                    val pick = rows.firstOrNull { it.outlet.outletId == form.outletId }?.let { PickItem(it.outlet.outletId.toString(), it.label) }
                    FormCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L)) {
                            VendorPicker(
                                retailerTitle, retailerTitle, searchHint, inCluster.map { PickItem(it.outlet.outletId.toString(), it.label) }, pick,
                                { p -> outlets.firstOrNull { it.outletId.toString() == p.key }?.let(onPickOutlet) }, SrRequestTags.RETAILER,
                                isError = RequestError.OUTLET_REQUIRED in errors, dot = VendorOutletDot,
                            )
                        }
                    }
                    val chosen = outlets.firstOrNull { it.outletId == form.outletId }
                    // manual 43 left: with nothing chosen the page says, centred, what to do next
                    if (chosen == null) {
                        Text(
                            retailerTitle, Modifier.fillMaxWidth().padding(top = AronTokens.Space.M),
                            style = MaterialTheme.typography.bodyLarge, color = look.ink, textAlign = TextAlign.Center,
                        )
                    } else {
                        DetailsCard(chosen)
                    }
                }
                OutletRequestKind.INFO, OutletRequestKind.LOCATION -> {
                    OutletSwitcher(rows, form.outletId, onPickOutlet, searchHint)
                    if (form.outletId != null) {
                        // manual 44: the cluster box and the three field boxes share one card
                        FormCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                                ClusterPicker(clusterTitle, searchHint, clusterItems, form.clusterId, { onChange(form.copy(clusterId = it)) }, false)
                                DetailBoxes(form, errors, onChange)
                            }
                        }
                    }
                }
                OutletRequestKind.ROUTE_ADD, OutletRequestKind.CLUSTER -> {
                    FormCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                            ClusterPicker(clusterTitle, searchHint, clusterItems, form.clusterId, { onChange(form.copy(clusterId = it)) }, RequestError.CLUSTER_REQUIRED in errors)
                            VendorField(form.note.orEmpty(), { onChange(form.copy(note = it)) }, stringResource(R.string.out_req_note), fieldModifier = Modifier.testTag(RequestTags.NOTE), isError = RequestError.REASON_REQUIRED in errors, singleLine = false)
                        }
                    }
                }
            }
            if (kind.needsGeoPhoto && (kind != OutletRequestKind.INFO || form.outletId != null)) {
                GeoPhotoBlock(capture, onShutter, note = stringResource(R.string.out_req_photo_note), takeLabel = R.string.out_req_capture_geo_photo, vendor = true)
            }
            fieldErrors.forEach { FormError(errorText(it)) }
            if (kind == OutletRequestKind.INFO && onAddToRoute != null && form.outletId != null) {
                FormLink(stringResource(R.string.out_req_route_add), onAddToRoute, Modifier.fillMaxWidth().testTag(RequestTags.ROUTE_ADD))
            }
        }
        FormBar(
            BarAction(
                stringResource(R.string.out_req_save), { if (kind.needsConfirmation && !form.confirmed) confirm = true else onSave() }, RequestTags.SAVE,
                enabled = blockingReason == null && !saving, glyph = com.aktcl.aron.core.ui.aron.ButtonGlyph.Save,
            ),
            reason = blockingReason,
        )
    }
    if (confirm) {
        AronConfirmDialog(
            stringResource(R.string.out_req_confirm_title), stringResource(if (kind == OutletRequestKind.CLOSE) R.string.out_req_confirm_close else R.string.out_req_confirm_message),
            stringResource(R.string.out_req_yes), stringResource(R.string.out_req_no),
            onConfirm = { confirm = false; onChange(form.copy(confirmed = true)); onSave() }, onDismiss = { confirm = false },
        )
    }
    // SRM-B-35: the rep sees that the request was saved and sent to the AMO before the form closes, so nobody files it twice
    if (savedDialog) {
        AronDialog(
            stringResource(R.string.out_req_saved_title), stringResource(R.string.out_req_saved), stringResource(R.string.out_req_saved_ok),
            onConfirm = onSavedOk, onDismiss = onSavedOk,
        )
    }
}

@Composable
private fun errorText(e: RequestError): String = stringResource(
    when (e) {
        RequestError.NAME_INVALID -> R.string.out_req_err_name
        RequestError.OWNER_INVALID -> R.string.out_req_err_owner
        RequestError.MOBILE_INVALID -> R.string.out_req_err_mobile
        RequestError.CLUSTER_REQUIRED, RequestError.CLUSTER_UNCHANGED -> R.string.out_req_err_cluster
        RequestError.OUTLET_REQUIRED -> R.string.out_pick_retailer
        RequestError.GEO_REQUIRED -> R.string.out_req_err_geo
        RequestError.PHOTO_REQUIRED -> R.string.out_req_err_photo
        RequestError.DUES_BLOCK_CLOSE -> R.string.out_req_err_dues
        RequestError.POINT_TOO_FAR -> R.string.out_req_err_point_far
        RequestError.MOCK_FIX -> R.string.out_req_err_mock
        RequestError.MOVE_TOO_FAR -> R.string.out_req_err_move_far
        RequestError.SAVE_FAILED -> R.string.out_req_err_save_failed
        else -> R.string.out_req_err_reason
    },
)

/** The labelled cluster box the manual repeats on every outlet form (screenshots 42 to 44). */
@Composable
private fun ClusterPicker(title: String, searchHint: String, items: List<PickItem>, selectedId: Long?, onPick: (Long?) -> Unit, isError: Boolean) {
    VendorPicker(title, title, searchHint, items, items.firstOrNull { it.key == selectedId?.toString() }, { onPick(it.key.toLongOrNull()) }, SrRequestTags.CLUSTER, isError = isError)
}

/** The three labelled boxes of the manual's forms: shop name, owner's name, mobile number, in that order. */
@Composable
private fun DetailBoxes(form: OutletRequestForm, errors: Set<RequestError>, onChange: (OutletRequestForm) -> Unit) {
    VendorField(form.name.orEmpty(), { onChange(form.copy(name = it)) }, stringResource(R.string.out_req_name), fieldModifier = Modifier.testTag(RequestTags.NAME), isError = RequestError.NAME_INVALID in errors)
    VendorField(form.ownerName.orEmpty(), { onChange(form.copy(ownerName = it)) }, stringResource(R.string.out_req_owner), fieldModifier = Modifier.testTag(RequestTags.OWNER), isError = RequestError.OWNER_INVALID in errors)
    VendorField(
        form.mobile.orEmpty(), { onChange(form.copy(mobile = it)) }, stringResource(R.string.out_req_mobile), fieldModifier = Modifier.testTag(RequestTags.MOBILE),
        isError = RequestError.MOBILE_INVALID in errors, keyboardType = KeyboardType.Phone,
    )
}

@Composable
private fun DetailFields(form: OutletRequestForm, errors: Set<RequestError>, onChange: (OutletRequestForm) -> Unit) {
    FormCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            DetailBoxes(form, errors, onChange)
        }
    }
}

/**
 * The read-only card under the retailer drop-down (SRM-B-37, screenshot 43 right): the shop's name, owner and mobile, the
 * label in ink and the value beside it. The manual greys the value; here it keeps full-strength ink, because this card is
 * the only confirmation the rep gets of WHICH shop is about to be closed for good (rule 14a: legibility wins).
 */
@Composable
private fun DetailsCard(o: OutletEntity) {
    FormCard(Modifier.fillMaxWidth().testTag(SrRequestTags.DETAILS)) {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            DetailLine(stringResource(R.string.out_detail_name), OutletPicker.label(o))
            DetailLine(stringResource(R.string.out_detail_owner), o.ownerName)
            DetailLine(stringResource(R.string.out_detail_mobile), o.contactNumber.orEmpty())
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    val look = LocalAronLook.current
    Row(horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = look.ink)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = look.secondaryText)
    }
}

/**
 * The outlet drop-down with the first-letter bar of the change-information form (SRM-B-39, screenshot 44): a plain white row
 * with the status dot and the outlet's label, then the dark grey panel whose letters narrow the drop-down's list.
 */
@Composable
private fun OutletSwitcher(rows: List<PickerRow>, selectedId: Long?, onPick: (OutletEntity) -> Unit, searchHint: String) {
    var letter by rememberSaveable { mutableStateOf(OutletPicker.ALL_CHIP) }
    val shown = remember(rows, letter) { if (letter == OutletPicker.ALL_CHIP) rows else rows.filter { it.chip == letter } }
    val chosen = rows.firstOrNull { it.outlet.outletId == selectedId }?.let { PickItem(it.outlet.outletId.toString(), it.label) }
    SearchPickerField(
        stringResource(R.string.out_pick_retailer), searchHint, shown.map { PickItem(it.outlet.outletId.toString(), it.label) }, chosen,
        { p -> rows.firstOrNull { it.outlet.outletId.toString() == p.key }?.let { onPick(it.outlet) } }, SrRequestTags.OUTLET,
        leadingDot = VendorOutletDot,
    )
    VendorLetterBar(
        OutletPicker.chips(rows), letter, stringResource(R.string.out_pick_all), { letter = it },
        Modifier.testTag(SrRequestTags.ALPHA), tagOf = { SrRequestTags.letter(it) },
    )
}
