package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronLook

data class ClusterOption(val clusterId: Long, val name: String)

object RequestTags {
    const val ROUTE_ADD = "rq_route_add"
    const val NAME = "rq_name"
    const val OWNER = "rq_owner"
    const val MOBILE = "rq_mobile"
    const val NOTE = "rq_note"
    const val SAVE = "rq_save"
    const val SAVED = "rq_saved"
    fun cluster(id: Long) = "rq_cluster_$id"
}

@Composable
private fun errorText(e: RequestError): String = stringResource(
    when (e) {
        RequestError.NAME_INVALID -> R.string.out_req_err_name
        RequestError.OWNER_INVALID -> R.string.out_req_err_owner
        RequestError.MOBILE_INVALID -> R.string.out_req_err_mobile
        RequestError.CLUSTER_REQUIRED, RequestError.CLUSTER_UNCHANGED -> R.string.out_req_err_cluster
        RequestError.REASON_REQUIRED -> R.string.out_req_err_reason
        RequestError.GEO_REQUIRED -> R.string.out_req_err_geo
        RequestError.PHOTO_REQUIRED -> R.string.out_req_err_photo
        RequestError.DUES_BLOCK_CLOSE -> R.string.out_req_err_dues
        RequestError.POINT_TOO_FAR -> R.string.out_req_err_point_far
        RequestError.MOCK_FIX -> R.string.out_req_err_mock
        RequestError.MOVE_TOO_FAR -> R.string.out_req_err_move_far
        else -> R.string.out_req_err_reason
    },
)

/**
 * One form for every outlet request (F-SR-037 new shop, F-SR-038 permanently closed, F-SR-039 information change,
 * F-SR-076 add to my route, N-040 cluster; screens-sr s15): kit fields in a card, cluster chips, the notices, the GEO and
 * photo block, the error lines, the saved banner and the submit primary in the bar. Fields shown depend on the kind.
 * Saving works offline; kinds that need confirmation ask first. Renders its own bar.
 */
@Composable
fun OutletRequestContent(
    form: OutletRequestForm,
    errors: Set<RequestError>,
    clusters: List<ClusterOption>,
    capture: GeoPhotoState,
    saved: Boolean,
    warnOpenDues: Boolean,
    onChange: (OutletRequestForm) -> Unit,
    onShutter: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Why the form cannot be submitted yet, from the host's validator (screens-sr s15: the primary is disabled with its
     * reason until the form is valid). Null keeps the older behaviour: Submit is enabled and the errors show after the tap.
     */
    blockingReason: String? = null,
    /** Change-information form only: switches the same outlet to an "add to my route" request (F-SR-076); null hides the link. */
    onAddToRoute: (() -> Unit)? = null,
) {
    val look = LocalAronLook.current
    var confirm by rememberSaveable { mutableStateOf(false) }
    val kind = form.kind
    val fieldErrors = errors.filter { it != RequestError.CONFIRMATION_REQUIRED }
    Column(modifier.fillMaxSize().background(look.page)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            Text(
                stringResource(when (kind) {
                    OutletRequestKind.NEW -> R.string.out_req_new
                    OutletRequestKind.CLOSE -> R.string.out_req_close
                    OutletRequestKind.INFO -> R.string.out_req_info
                    OutletRequestKind.CLUSTER -> R.string.out_req_cluster
                    OutletRequestKind.ROUTE_ADD -> R.string.out_req_route_add
                    OutletRequestKind.LOCATION -> R.string.out_req_info
                }),
                style = MaterialTheme.typography.titleLarge, color = look.purple,
            )
            if (saved) FormNotice(stringResource(R.string.out_req_saved), Modifier.testTag(RequestTags.SAVED), BannerKind.Success)
            if (kind == OutletRequestKind.CLOSE) {
                FormNotice(stringResource(R.string.out_req_close_notice), kind = BannerKind.Warning)
                if (warnOpenDues || form.openDueMtk > 0) FormNotice(stringResource(R.string.out_req_dues_warning), kind = BannerKind.Warning)
            }
            val hasFields = kind == OutletRequestKind.NEW || kind == OutletRequestKind.INFO || kind == OutletRequestKind.CLUSTER || kind == OutletRequestKind.ROUTE_ADD
            if (hasFields) {
                FormCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                        if (kind == OutletRequestKind.NEW || kind == OutletRequestKind.INFO) {
                            FormField(form.name.orEmpty(), { onChange(form.copy(name = it)) }, stringResource(R.string.out_req_name), fieldModifier = Modifier.testTag(RequestTags.NAME), isError = RequestError.NAME_INVALID in errors)
                            FormField(form.ownerName.orEmpty(), { onChange(form.copy(ownerName = it)) }, stringResource(R.string.out_req_owner), fieldModifier = Modifier.testTag(RequestTags.OWNER), isError = RequestError.OWNER_INVALID in errors)
                            FormField(
                                form.mobile.orEmpty(), { onChange(form.copy(mobile = it)) }, stringResource(R.string.out_req_mobile), fieldModifier = Modifier.testTag(RequestTags.MOBILE),
                                isError = RequestError.MOBILE_INVALID in errors, keyboardType = KeyboardType.Phone,
                            )
                        }
                        if (kind == OutletRequestKind.NEW || kind == OutletRequestKind.CLUSTER) {
                            Text(stringResource(R.string.out_req_cluster_pick), style = MaterialTheme.typography.bodySmall, color = look.inkMuted)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                                clusters.forEach { cl ->
                                    FormChip(cl.name, form.clusterId == cl.clusterId, { onChange(form.copy(clusterId = cl.clusterId)) }, Modifier.testTag(RequestTags.cluster(cl.clusterId)))
                                }
                            }
                        }
                        if (kind == OutletRequestKind.CLUSTER || kind == OutletRequestKind.ROUTE_ADD) {
                            FormField(
                                form.note.orEmpty(), { onChange(form.copy(note = it)) }, stringResource(R.string.out_req_note), fieldModifier = Modifier.testTag(RequestTags.NOTE),
                                isError = RequestError.REASON_REQUIRED in errors || RequestError.NOTE_TOO_LONG in errors, singleLine = false,
                            )
                        }
                    }
                }
            }
            if (kind.needsGeoPhoto) GeoPhotoBlock(capture, onShutter, note = stringResource(R.string.out_req_photo_note), takeLabel = R.string.out_req_capture_geo_photo)
            fieldErrors.forEach { FormError(errorText(it)) }
            if (kind == OutletRequestKind.INFO && onAddToRoute != null) {
                FormLink(stringResource(R.string.out_req_route_add), onAddToRoute, Modifier.fillMaxWidth().testTag(RequestTags.ROUTE_ADD))
            }
        }
        FormBar(
            BarAction(stringResource(R.string.out_req_save), { if (kind.needsConfirmation && !form.confirmed) confirm = true else onSave() }, RequestTags.SAVE, enabled = blockingReason == null, icon = AronIcons.Submit),
            reason = blockingReason,
        )
    }
    if (confirm) {
        AronConfirmDialog(
            stringResource(R.string.out_req_confirm_title), stringResource(R.string.out_req_confirm_message),
            stringResource(R.string.out_req_yes), stringResource(R.string.out_req_no),
            onConfirm = { confirm = false; onChange(form.copy(confirmed = true)); onSave() }, onDismiss = { confirm = false },
        )
    }
}
