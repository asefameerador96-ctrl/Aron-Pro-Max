package com.aktcl.aron.feature.sale.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.feature.sale.R
import com.aktcl.aron.feature.sale.domain.ZeroSaleReason

object ZeroReasonTags {
    const val DIALOG = "zero_reason_dialog"
    fun row(r: ZeroSaleReason) = "zero_reason_" + r.code
    const val OK = "zero_reason_ok"
}

@Composable
internal fun zeroReasonLabel(r: ZeroSaleReason): String = stringResource(
    when (r) {
        ZeroSaleReason.STOCK_AVAILABLE -> R.string.zero_reason_stock_available
        ZeroSaleReason.OUTLET_CLOSED -> R.string.zero_reason_outlet_closed
        ZeroSaleReason.OWNER_ABSENT -> R.string.zero_reason_owner_absent
        ZeroSaleReason.INSUFFICIENT_CASH -> R.string.zero_reason_insufficient_cash
    },
)

/**
 * Zero sale, step 2 (SR manual s10 / screenshot 32): after "yes" the SR names why nothing was sold, one of four reasons. Nothing is
 * pre-selected and the confirm button stays off until one is chosen, so a reason is never guessed. Cancel returns to the entry screen.
 */
@Composable
fun ZeroSaleReasonDialog(onChosen: (ZeroSaleReason) -> Unit, onCancel: () -> Unit) {
    val c = LocalAronColors.current
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(ZeroReasonTags.DIALOG),
        containerColor = c.surfaceSolid, titleContentColor = c.textPrimary, textContentColor = c.textPrimary,
        title = { Text(stringResource(R.string.zero_reason_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                ZeroSaleReason.entries.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = AronTokens.Touch.Min).testTag(ZeroReasonTags.row(r))
                            .selectable(selected = picked == r.code, role = Role.RadioButton, onClick = { picked = r.code }),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
                    ) {
                        RadioButton(selected = picked == r.code, onClick = null)
                        Text(zeroReasonLabel(r), modifier = Modifier.padding(vertical = AronTokens.Space.Xs))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { ZeroSaleReason.entries.firstOrNull { it.code == picked }?.let(onChosen) },
                enabled = picked != null, modifier = Modifier.heightIn(min = AronTokens.Touch.Min).testTag(ZeroReasonTags.OK),
            ) { Text(stringResource(R.string.sale_yes_ok)) }
        },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = AronTokens.Touch.Min)) { Text(stringResource(R.string.sale_no)) } },
    )
}
