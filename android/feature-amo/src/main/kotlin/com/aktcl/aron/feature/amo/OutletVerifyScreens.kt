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
import androidx.compose.ui.platform.testTag
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

/** One pending request of an SR as the vendor's list shows it (47, 50, 52): five labelled values; null = not known on this phone yet. */
data class VerifyListRow(val request: PendingRequest, val route: String?, val cluster: String?, val shop: String?, val owner: String?, val contact: String?)

/**
 * F-AMO-022/023/024 list (screenshots 47, 50, 52): one white card per pending request of the group, oldest first, with route, cluster,
 * retailer name, owner name and contact (the full number with its leading 0, R3). [detailsOffline] adds the one-line notice that the owner
 * and contact load when the phone is online.
 */
@Composable
fun VerifyListContent(rows: List<VerifyListRow>, onOpen: (PendingRequest) -> Unit, modifier: Modifier = Modifier, detailsOffline: Boolean = false) {
    if (rows.isEmpty()) {
        AmoEmptyState(stringResource(R.string.amo_verify_empty), modifier.fillMaxSize(), hint = stringResource(R.string.amo_verify_empty_hint))
        return
    }
    val dash = "—"
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        if (detailsOffline) AmoBanner(stringResource(R.string.amo_out_list_no_detail), kind = BannerKind.Info)
        rows.forEach { r ->
            AmoFactsCard(
                listOf(
                    stringResource(R.string.amo_out_route) to (r.route ?: dash), stringResource(R.string.amo_out_cluster) to (r.cluster ?: dash),
                    stringResource(R.string.amo_out_retailer_name) to (r.shop ?: r.request.outletName ?: dash),
                    stringResource(R.string.amo_out_owner_name) to (r.owner ?: dash), stringResource(R.string.amo_out_contact) to (r.contact ?: dash),
                ),
                Modifier.testTag("vl_${r.request.uuid}"), onClick = { onOpen(r.request) },
            )
        }
    }
}

/**
 * The verification form (47, 48, 50, 52): the request's facts and flags over the [items] the host builds for its type, then Save (approve) and
 * Cancel (reject, after the "আপনি কি নিশ্চিত?" হ্যাঁ/না question: D-AMO-MAN-12). Leaving with Back queues nothing.
 */
@Composable
fun VerifyFormContent(
    request: PendingRequest,
    detail: VerifyDetailState,
    changes: List<FieldChange>,
    /** Read-only facts of the outlet shown above the details (a closure shows its open balance), as label to value. */
    facts: List<Pair<String, String>>,
    items: List<AmoFormItem>,
    /** False when the server says the request is no longer waiting: Save and Cancel are off. */
    decidable: Boolean,
    noFix: Boolean,
    saveFailed: Boolean,
    busy: Boolean,
    onSave: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReject by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (confirmReject) {
        com.aktcl.aron.core.ui.AronConfirmDialog(
            stringResource(com.aktcl.aron.feature.outlet.R.string.out_req_confirm_title), stringResource(R.string.amo_out_reject_message),
            stringResource(com.aktcl.aron.feature.outlet.R.string.out_req_yes), stringResource(com.aktcl.aron.feature.outlet.R.string.out_req_no),
            onConfirm = { confirmReject = false; onReject() }, onDismiss = { confirmReject = false },
        )
    }
    val extra = buildList<AmoFormItem> {
        if (!decidable) add(AmoFormItem.Notice(stringResource(R.string.amo_verify_not_pending), BannerKind.Warning))
        when (detail) {
            VerifyDetailState.Unavailable -> add(AmoFormItem.Notice(stringResource(R.string.amo_verify_details_offline), BannerKind.Warning))
            is VerifyDetailState.Loaded -> {
                if (!detail.fresh) add(AmoFormItem.Notice(stringResource(R.string.amo_verify_details_kept), BannerKind.Info))
                detail.detail.flags.forEach { add(AmoFormItem.Notice(flagLabel(it), BannerKind.Warning)) }
            }
            else -> Unit
        }
        if (noFix) add(AmoFormItem.Notice(stringResource(R.string.amo_verify_no_fix), BannerKind.Error))
        if (saveFailed) add(AmoFormItem.Notice(stringResource(R.string.amo_task_failed), BannerKind.Error))
    }
    AmoOutletFormBody(
        items = extra + items, onSave = onSave, onCancel = { confirmReject = true }, saveEnabled = decidable, busy = busy, modifier = modifier.fillMaxSize(),
        header = {
            if (detail == VerifyDetailState.Loading) Box(Modifier.fillMaxWidth().padding(AronTokens.Space.L), Alignment.Center) { com.aktcl.aron.core.ui.aron.AronLoadingCard(stringResource(R.string.amo_verify_loading)) }
            if (facts.isNotEmpty() || changes.isNotEmpty()) {
                AmoFactsCard(
                    facts + changes.map { c -> fieldLabel(c.field) to (c.new + (c.old?.let { " · " + stringResource(R.string.amo_verify_was, it) } ?: "")) },
                )
            }
        },
    )
}
