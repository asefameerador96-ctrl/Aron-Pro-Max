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
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.feature.outlet.KitTextField

/** One SKU with the stock the app believes is in the bag, in the SKU's base unit (sticks, pieces, dozens). */
data class CorrectionRow(val skuId: Long, val name: String, val unit: String, val currentBase: Long)

/** Reason codes of a stock correction (the adjustment movement's `reason_code`). */
enum class CorrectionReason(val code: String) { COUNT_ERROR("count_error"), DAMAGED("damaged"), LOST("lost"), OTHER("other") }

object CorrectionTags {
    const val EMPTY = "amo_sc_empty"; const val TOTAL = "amo_sc_total"; const val SAVE = "amo_sc_save"; const val SAVED = "amo_sc_saved"; const val INVALID = "amo_sc_invalid"
    fun row(skuId: Long) = "amo_sc_row_$skuId"; fun reason(r: CorrectionReason) = "amo_sc_reason_${r.code}"
}

object StockCorrection {
    /** Whole base units, 0 or more, at most 10,000,000 (the adjustment's range); anything else is null. */
    fun parseTotal(text: String): Long? {
        val t = text.trim().map { if (it in '০'..'৯') '0' + (it - '০') else it }.joinToString("")
        return t.takeIf { Regex("^\\d{1,8}$").matches(it) }?.toLong()?.takeIf { it <= 10_000_000L }
    }

    /** The signed difference the adjustment will post, or null when the text is not a total or nothing differs. */
    fun delta(current: Long, text: String): Long? = parseTotal(text)?.let { it - current }?.takeIf { it != 0L }
}

/** F-AMO-049: pick a SKU, state the right total and a reason; Save posts a signed adjustment, never an overwrite. */
@Composable
fun StockCorrectionContent(
    rows: List<CorrectionRow>, selected: Long?, text: String, reason: CorrectionReason?, saved: Boolean, saving: Boolean,
    onSelect: (Long) -> Unit, onText: (String) -> Unit, onReason: (CorrectionReason) -> Unit, onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) {
        AronEmptyState(stringResource(R.string.amo_sc_empty), modifier.fillMaxSize().testTag(CorrectionTags.EMPTY), hint = stringResource(R.string.amo_sc_empty_hint))
        return
    }
    val c = LocalAronColors.current
    val sel = rows.firstOrNull { it.skuId == selected }
    val delta = sel?.let { StockCorrection.delta(it.currentBase, text) }
    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            items(rows, key = { it.skuId }) { r ->
                val chosen = r.skuId == selected
                val rowModifier = Modifier.fillMaxWidth().testTag(CorrectionTags.row(r.skuId))
                val label = r.name + "  " + localizedNumber(r.currentBase) + " " + unitWord(r.unit)
                if (chosen) AronPrimaryButton(label, { onSelect(r.skuId) }, rowModifier) else AronSecondaryButton(label, { onSelect(r.skuId) }, rowModifier, enabled = !saving)
            }
        }
        if (sel != null) {
            Column(Modifier.padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                KitTextField(
                    text, { if (!saving && !saved) onText(it) }, stringResource(R.string.amo_sc_total, unitWord(sel.unit)),
                    isError = text.isNotBlank() && StockCorrection.parseTotal(text) == null, keyboardType = KeyboardType.Number,
                    fieldModifier = Modifier.testTag(CorrectionTags.TOTAL),
                )
                if (delta != null) Text(stringResource(R.string.amo_sc_delta, (if (delta > 0) "+" else "") + localizedNumber(delta), unitWord(sel.unit)), style = MaterialTheme.typography.labelLarge, color = c.textPrimary)
                else if (text.isNotBlank() && StockCorrection.parseTotal(text) != null) Text(stringResource(R.string.amo_sc_same), style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
                CorrectionReason.entries.forEach { r ->
                    val label = stringResource(when (r) { CorrectionReason.COUNT_ERROR -> R.string.amo_sc_r_count; CorrectionReason.DAMAGED -> R.string.amo_sc_r_damaged; CorrectionReason.LOST -> R.string.amo_sc_r_lost; CorrectionReason.OTHER -> R.string.amo_sc_r_other })
                    val m = Modifier.fillMaxWidth().testTag(CorrectionTags.reason(r))
                    if (reason == r) AronPrimaryButton(label, { onReason(r) }, m) else AronSecondaryButton(label, { onReason(r) }, m, enabled = !saving && !saved)
                }
                if (saved) Text(stringResource(R.string.amo_pa_saved), style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.testTag(CorrectionTags.SAVED))
                AronPrimaryButton(
                    stringResource(R.string.amo_pa_save), onSave, Modifier.fillMaxWidth().testTag(CorrectionTags.SAVE),
                    enabled = !saving && !saved && delta != null && reason != null, busy = saving,
                )
            }
        }
    }
}

/** The unit as a word in the user's language (sticks, pieces, dozens); an unknown unit is shown as sent. */
@Composable
internal fun unitWord(unit: String): String = when (unit) {
    "stick" -> stringResource(R.string.amo_unit_stick); "piece" -> stringResource(R.string.amo_unit_piece); "dozen" -> stringResource(R.string.amo_unit_dozen); else -> unit
}
