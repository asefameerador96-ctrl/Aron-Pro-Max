package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind

data class ClusterOption(val clusterId: Long, val name: String)

object RequestTags {
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
        else -> R.string.out_req_err_reason
    },
)

/**
 * One form for every outlet request (F-SR-037 new shop, F-SR-038 permanently closed, F-SR-039 information change,
 * F-SR-076 add to my route, N-040 cluster). Fields shown depend on the kind. Saving works offline and shows the success
 * line with the pending state; kinds that need confirmation ask first.
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
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val kind = form.kind
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        Text(
            stringResource(when (kind) {
                OutletRequestKind.NEW -> R.string.out_req_new
                OutletRequestKind.CLOSE -> R.string.out_req_close
                OutletRequestKind.INFO -> R.string.out_req_info
                OutletRequestKind.CLUSTER -> R.string.out_req_cluster
                OutletRequestKind.ROUTE_ADD -> R.string.out_req_route_add
                OutletRequestKind.LOCATION -> R.string.out_req_info
            }),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (kind == OutletRequestKind.NEW || kind == OutletRequestKind.INFO) {
            OutlinedTextField(form.name.orEmpty(), { onChange(form.copy(name = it)) }, Modifier.fillMaxWidth().testTag(RequestTags.NAME), label = { Text(stringResource(R.string.out_req_name)) }, singleLine = true)
            OutlinedTextField(form.ownerName.orEmpty(), { onChange(form.copy(ownerName = it)) }, Modifier.fillMaxWidth().testTag(RequestTags.OWNER), label = { Text(stringResource(R.string.out_req_owner)) }, singleLine = true)
            OutlinedTextField(
                form.mobile.orEmpty(), { onChange(form.copy(mobile = it)) }, Modifier.fillMaxWidth().testTag(RequestTags.MOBILE),
                label = { Text(stringResource(R.string.out_req_mobile)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
        }
        if (kind == OutletRequestKind.NEW || kind == OutletRequestKind.CLUSTER) {
            Text(stringResource(R.string.out_req_cluster_pick), style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                items(clusters, key = { it.clusterId }) { c ->
                    FilterChip(form.clusterId == c.clusterId, { onChange(form.copy(clusterId = c.clusterId)) }, { Text(c.name) }, Modifier.testTag(RequestTags.cluster(c.clusterId)))
                }
            }
        }
        if (kind == OutletRequestKind.CLOSE) {
            AronBanner(stringResource(R.string.out_req_close_notice), kind = BannerKind.Warning)
            if (warnOpenDues || form.openDueMtk > 0) AronBanner(stringResource(R.string.out_req_dues_warning), kind = BannerKind.Warning)
        }
        if (kind == OutletRequestKind.CLUSTER || kind == OutletRequestKind.ROUTE_ADD) {
            OutlinedTextField(form.note.orEmpty(), { onChange(form.copy(note = it)) }, Modifier.fillMaxWidth().testTag(RequestTags.NOTE), label = { Text(stringResource(R.string.out_req_note)) })
        }
        if (kind.needsGeoPhoto) GeoPhotoBlock(capture, onShutter)
        errors.filter { it != RequestError.CONFIRMATION_REQUIRED }.forEach { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
        if (saved) AronBanner(stringResource(R.string.out_req_saved), Modifier.testTag(RequestTags.SAVED), BannerKind.Info)
        AronPrimaryButton(stringResource(R.string.out_req_save), { if (kind.needsConfirmation && !form.confirmed) confirm = true else onSave() }, Modifier.testTag(RequestTags.SAVE))
    }
    if (confirm) {
        AronConfirmDialog(
            stringResource(R.string.out_req_confirm_title), stringResource(R.string.out_req_confirm_message),
            stringResource(R.string.out_req_yes), stringResource(R.string.out_req_no),
            onConfirm = { confirm = false; onChange(form.copy(confirmed = true)); onSave() }, onDismiss = { confirm = false },
        )
    }
}
