package com.aktcl.aron.feature.sale.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.feature.sale.R
import com.aktcl.aron.feature.sale.domain.EditDenied
import com.aktcl.aron.feature.sale.domain.EditReason

/** After the geo gate: "start the call?". No returns to the list without counting a visit; Yes starts the call (F-SR-060). */
@Composable
fun StartCallPrompt(outletName: String, onYes: () -> Unit, onNo: () -> Unit) {
    AronConfirmDialog(
        stringResource(R.string.call_start_title), stringResource(R.string.call_start_message, outletName),
        stringResource(R.string.sale_yes), stringResource(R.string.sale_no), onConfirm = onYes, onDismiss = onNo,
    )
}

/** Edit: one of the three reasons, or the reason it is denied (F-SR-033). */
@Composable
fun EditReasonScreen(denied: EditDenied?, onReason: (EditReason) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.edit_reason_title), style = MaterialTheme.typography.titleLarge)
        if (denied != null) {
            AronBanner(
                stringResource(
                    when (denied) {
                        EditDenied.OutsideGeofence -> R.string.edit_denied_outside
                        EditDenied.QcDone -> R.string.edit_denied_qc
                        EditDenied.AlreadySuperseded, EditDenied.NoLiveMemo -> R.string.edit_denied_superseded
                    },
                ),
                kind = BannerKind.Warning,
            )
        } else {
            EditReason.entries.forEach { r ->
                AronListRow(
                    stringResource(
                        when (r) {
                            EditReason.WRONG_SKU -> R.string.edit_reason_wrong_sku
                            EditReason.WRONG_QUANTITY -> R.string.edit_reason_wrong_quantity
                            EditReason.WRONG_PRICE_TYPE -> R.string.edit_reason_wrong_price_type
                        },
                    ),
                    onClick = { onReason(r) },
                )
            }
        }
    }
}
