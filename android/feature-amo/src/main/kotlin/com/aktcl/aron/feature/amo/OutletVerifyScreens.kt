package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.RowPosition

/** What the form knows about the request's details: loading, the server's answer (fresh or kept), or nothing (offline, never seen). */
sealed interface VerifyDetailState {
    data object Loading : VerifyDetailState
    data class Loaded(val detail: VerifyDetail, val fresh: Boolean) : VerifyDetailState
    data object Unavailable : VerifyDetailState
    /** The server refuses the request or no longer has it (403/404). */
    data object Gone : VerifyDetailState
}

@Composable
private fun typeLabel(type: String): String = stringResource(
    when (type) {
        "new" -> R.string.amo_verify_type_new; "close" -> R.string.amo_verify_type_close; "info" -> R.string.amo_verify_type_info
        "cluster" -> R.string.amo_verify_type_cluster; "location" -> R.string.amo_verify_type_location; else -> R.string.amo_verify_type_route_add
    },
)

@Composable
private fun fieldLabel(field: String): String = stringResource(
    when (field) {
        "name" -> R.string.amo_verify_f_name; "name_bn" -> R.string.amo_verify_f_name_bn; "owner_name" -> R.string.amo_verify_f_owner
        "contact_number" -> R.string.amo_verify_f_mobile; "address" -> R.string.amo_verify_f_address; "close_reason_code" -> R.string.amo_verify_f_reason
        "sub_channel_id" -> R.string.amo_verify_f_sub_channel; "geo_class" -> R.string.amo_verify_geo_class; "cluster_id" -> R.string.amo_verify_f_cluster
        else -> return field // an unknown member is shown by its key, never under a wrong label
    },
)

@Composable
private fun flagLabel(flag: String): String = stringResource(
    when (flag) {
        "remote_verification" -> R.string.amo_verify_flag_remote; "moved_to_home" -> R.string.amo_verify_flag_home
        "low_accuracy" -> R.string.amo_verify_flag_accuracy; else -> R.string.amo_verify_flag_mock
    },
)

/** F-AMO-022/023/024: the SRs' pending requests of the zone, oldest first. */
@Composable
fun VerifyListContent(rows: List<PendingRequest>, names: Map<Long, String>, onOpen: (PendingRequest) -> Unit, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) {
        AmoEmptyState(stringResource(R.string.amo_verify_empty), modifier.fillMaxSize(), hint = stringResource(R.string.amo_verify_empty_hint))
        return
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen)) {
        rows.forEachIndexed { i, r ->
            AmoListRow(
                r.outletName ?: typeLabel(r.type), subtitle = listOfNotNull(typeLabel(r.type), names[r.requestedBy]).joinToString(stringResource(R.string.amo_verify_sep)),
                onClick = { onOpen(r) },
                position = if (rows.size == 1) RowPosition.Only else if (i == 0) RowPosition.First else if (i == rows.lastIndex) RowPosition.Last else RowPosition.Middle,
                divider = i < rows.lastIndex, flat = true,
            )
        }
    }
}

/**
 * The verification form. A `new` outlet needs the sub-channel and the geo class (route and cluster are shown, the verification record has
 * no member for them: D-AMO-VERIFY-01); close shows the dues and points the server sent; info shows old against new. Save verifies, Cancel
 * goes back without any record, and "Reject request" (press and hold) queues a `discarded` decision.
 */
@Composable
fun VerifyFormContent(
    request: PendingRequest,
    detail: VerifyDetailState,
    changes: List<FieldChange>,
    /** Read-only facts of the outlet shown above the details (a closure shows its open balance), as label to value. */
    facts: List<Pair<String, String>>,
    /** Only a new outlet asks the AMO for the sub-channel and geo class. */
    askClassification: Boolean,
    /** False when the server says the request is no longer waiting: Save and Reject are off. */
    decidable: Boolean,
    subChannels: List<SubChannel>,
    bangla: Boolean,
    subChannelId: Long?,
    geoClass: String?,
    note: String,
    errors: Set<VerifyError>,
    noFix: Boolean,
    saveFailed: Boolean,
    busy: Boolean,
    onSubChannel: (Long) -> Unit,
    onGeoClass: (String) -> Unit,
    onNote: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReject by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (confirmReject) {
        com.aktcl.aron.core.ui.AronConfirmDialog(
            stringResource(R.string.amo_verify_reject_title), stringResource(R.string.amo_verify_reject_message),
            stringResource(R.string.amo_verify_reject_yes), stringResource(R.string.amo_base_confirm_no),
            onConfirm = { confirmReject = false; onReject() }, onDismiss = { confirmReject = false },
        )
    }
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            AmoListRow(request.outletName ?: typeLabel(request.type), subtitle = typeLabel(request.type), flat = true)
            facts.forEach { (k, v) -> AmoListRow(v, subtitle = k, flat = true) }
            if (!decidable) AmoBanner(stringResource(R.string.amo_verify_not_pending), kind = BannerKind.Warning)
            when (detail) {
                VerifyDetailState.Loading -> Box(Modifier.fillMaxWidth().padding(AronTokens.Space.L), Alignment.Center) { com.aktcl.aron.core.ui.aron.AronLoadingCard(stringResource(R.string.amo_verify_loading)) }
                VerifyDetailState.Unavailable -> AmoBanner(stringResource(R.string.amo_verify_details_offline), kind = BannerKind.Warning)
                VerifyDetailState.Gone -> Unit // the not-pending banner above says it
                is VerifyDetailState.Loaded -> {
                    if (!detail.fresh) AmoBanner(stringResource(R.string.amo_verify_details_kept), kind = BannerKind.Info)
                    detail.detail.flags.forEach { AmoBanner(flagLabel(it), kind = BannerKind.Warning) }
                    val shown = if (request.type == "info") changes else detail.detail.proposed.map { (k, v) -> FieldChange(k, null, v) }
                    shown.forEach { c ->
                        AmoListRow(
                            c.new, subtitle = fieldLabel(c.field) + (c.old?.let { " · " + stringResource(R.string.amo_verify_was, it) } ?: ""), flat = true,
                        )
                    }
                }
            }
            if (askClassification) {
                AmoSection(stringResource(R.string.amo_verify_sub_channel))
                if (subChannels.isEmpty()) {
                    AmoBanner(stringResource(R.string.amo_verify_no_sub_channels), kind = BannerKind.Info)
                } else {
                    subChannels.forEachIndexed { i, s ->
                        AmoListRow(
                            if (bangla) s.labelBn ?: s.labelEn else s.labelEn, onClick = { onSubChannel(s.id) },
                            trailing = if (s.id == subChannelId) stringResource(R.string.amo_verify_selected) else null,
                            position = if (subChannels.size == 1) RowPosition.Only else if (i == 0) RowPosition.First else if (i == subChannels.lastIndex) RowPosition.Last else RowPosition.Middle,
                            divider = i < subChannels.lastIndex, flat = true,
                        )
                    }
                }
                if (VerifyError.SUB_CHANNEL_REQUIRED in errors) AmoBanner(stringResource(R.string.amo_verify_sub_channel_required), kind = BannerKind.Error)
                AmoSection(stringResource(R.string.amo_verify_geo_class))
                val classes = OutletVerify.GEO_CLASSES
                AmoChips(
                    listOf(R.string.amo_verify_geo_hill, R.string.amo_verify_geo_urban, R.string.amo_verify_geo_semiurban, R.string.amo_verify_geo_rural).map { stringResource(it) },
                    classes.indexOf(geoClass), { onGeoClass(classes[it]) },
                )
                if (VerifyError.GEO_CLASS_REQUIRED in errors) AmoBanner(stringResource(R.string.amo_verify_geo_required), kind = BannerKind.Error)
            }
            AmoField(note, onNote, stringResource(R.string.amo_verify_note), singleLine = false)
            if (noFix) AmoBanner(stringResource(R.string.amo_verify_no_fix), kind = BannerKind.Error)
            if (saveFailed) AmoBanner(stringResource(R.string.amo_task_failed), kind = BannerKind.Error)
        }
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            AmoPrimary(stringResource(R.string.amo_verify_save), onSave, Modifier.fillMaxWidth(), enabled = !busy && decidable, busy = busy)
            // docs/07: Cancel rejects the request (a `discarded` decision), after a confirmation; leaving with Back queues nothing.
            AmoSecondary(stringResource(R.string.amo_verify_cancel), { confirmReject = true }, Modifier.fillMaxWidth(), enabled = !busy && decidable)
        }
    }
}
