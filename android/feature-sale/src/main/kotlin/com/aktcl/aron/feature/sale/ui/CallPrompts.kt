package com.aktcl.aron.feature.sale.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.aron.AronSolidButton
import com.aktcl.aron.core.ui.aron.SolidTone
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
fun EditReasonScreen(denied: EditDenied?, onReason: (EditReason) -> Unit, modifier: Modifier = Modifier, reasons: List<EditReason> = EditReason.legacy) {
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
            reasons.forEach { r ->
                AronListRow(
                    stringResource(
                        when (r) {
                            EditReason.WRONG_SKU -> R.string.edit_reason_wrong_sku
                            EditReason.WRONG_QUANTITY -> R.string.edit_reason_wrong_quantity
                            EditReason.WRONG_PRICE_TYPE -> R.string.edit_reason_wrong_price_type
                            EditReason.WRONG_SKU_QTY -> R.string.edit_reason_wrong_sku_qty
                            EditReason.PROMOTION_ISSUE -> R.string.edit_reason_promotion_issue
                        },
                    ),
                    onClick = { onReason(r) },
                )
            }
        }
    }
}

/**
 * SRM-B-16 (shot 37): the reason step as a modal over the memo: the title, a dropdown of the manual's reasons, "বাতিল" (amber) and
 * "জমা দিন" (gradient). Submit hands the chosen reason to the host, which starts the edit; Cancel leaves without any change.
 * Nothing is chosen at first: Submit stays off until a reason is picked, so a reason is never made up.
 */
@Composable
fun EditReasonDialog(onSubmit: (EditReason) -> Unit, onCancel: () -> Unit, reasons: List<EditReason> = EditReason.manual) {
    var picked by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val chosen = reasons.firstOrNull { it.wire == picked }
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.edit_reason_dialog_title), style = MaterialTheme.typography.titleMedium, color = Color(0xFF1565C0))
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).border(1.dp, Color(0xFF9E9E9E), RoundedCornerShape(8.dp))
                        .clickable(role = Role.DropdownList) { open = true }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        chosen?.let { stringResource(reasonLabel(it)) } ?: stringResource(R.string.edit_reason_pick),
                        style = MaterialTheme.typography.bodyLarge, color = if (chosen == null) Color(0xFF616161) else Color(0xFF212121), modifier = Modifier.weight(1f),
                    )
                    Text(stringResource(R.string.edit_reason_glyph_dropdown), color = Color(0xFF616161))
                }
                DropdownMenu(open, { open = false }) {
                    reasons.forEach { r -> DropdownMenuItem(text = { Text(stringResource(reasonLabel(r))) }, onClick = { picked = r.wire; open = false }) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AronSolidButton(stringResource(R.string.edit_reason_cancel), onCancel, Modifier.weight(1f), tone = SolidTone.Amber)
                AronPrimaryButton(stringResource(R.string.edit_reason_submit), { chosen?.let(onSubmit) }, Modifier.weight(1f), enabled = chosen != null)
            }
        }
    }
}

private fun reasonLabel(r: EditReason): Int = when (r) {
    EditReason.WRONG_SKU -> R.string.edit_reason_wrong_sku
    EditReason.WRONG_QUANTITY -> R.string.edit_reason_wrong_quantity
    EditReason.WRONG_PRICE_TYPE -> R.string.edit_reason_wrong_price_type
    EditReason.WRONG_SKU_QTY -> R.string.edit_reason_wrong_sku_qty
    EditReason.PROMOTION_ISSUE -> R.string.edit_reason_promotion_issue
}
