package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.feature.outlet.KitTextField

object PriceTags {
    const val EMPTY = "amo_pc_empty"; const val SAVE = "amo_pc_save"; const val SAVED = "amo_pc_saved"
    fun field(skuId: Long) = "amo_pc_field_$skuId"; fun verdict(skuId: Long) = "amo_pc_verdict_$skuId"
}

/** F-AMO-035: SKUs with the outlet list price, a field for the observed shelf price (taka), the verdict beside it. Save writes the typed ones. */
@Composable
fun PriceComplianceContent(
    rows: List<PriceRow>, typed: Map<Long, String>, toleranceBp: Int, saved: Boolean, saving: Boolean,
    onType: (Long, String) -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) {
        AronEmptyState(stringResource(R.string.amo_pc_empty), modifier.fillMaxSize().testTag(PriceTags.EMPTY), hint = stringResource(R.string.amo_pc_empty_hint))
        return
    }
    val c = LocalAronColors.current
    val editable = !saved && !saving
    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            items(rows, key = { it.skuId }) { r ->
                val text = typed[r.skuId].orEmpty()
                val observed = PriceCompliance.parseTakaToMtk(text)
                Column(verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text(stringResource(R.string.amo_pc_list, PriceCompliance.formatTaka(r.referenceMtk), r.packLabel), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    KitTextField(
                        text, { if (editable) onType(r.skuId, it) }, stringResource(R.string.amo_pc_field), isError = text.isNotBlank() && observed == null,
                        keyboardType = KeyboardType.Decimal, fieldModifier = Modifier.testTag(PriceTags.field(r.skuId)),
                    )
                    val verdict = when {
                        text.isBlank() -> null
                        observed == null -> stringResource(R.string.amo_pc_invalid)
                        PriceCompliance.compliant(r.referenceMtk, observed, toleranceBp) -> stringResource(R.string.amo_pc_ok)
                        else -> stringResource(R.string.amo_pc_off, PriceCompliance.formatTaka(observed - r.referenceMtk).let { if (observed > r.referenceMtk) "+$it" else it })
                    }
                    if (verdict != null) Text(verdict, style = MaterialTheme.typography.labelLarge, color = if (observed != null && PriceCompliance.compliant(r.referenceMtk, observed, toleranceBp)) c.textPrimary else c.danger, modifier = Modifier.testTag(PriceTags.verdict(r.skuId)))
                }
            }
        }
        if (saved) Text(stringResource(R.string.amo_pa_saved), style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.padding(AronTokens.Space.Screen).testTag(PriceTags.SAVED))
        AronPrimaryButton(stringResource(R.string.amo_pa_save), onSave, Modifier.fillMaxWidth().padding(AronTokens.Space.Screen).testTag(PriceTags.SAVE), enabled = editable && PriceCompliance.canSave(rows, typed), busy = saving)
    }
}
