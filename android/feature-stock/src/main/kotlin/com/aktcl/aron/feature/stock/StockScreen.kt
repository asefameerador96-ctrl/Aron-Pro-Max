package com.aktcl.aron.feature.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronStepper
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.localizedNumber

object StockTags {
    const val SAVE = "stk_save"
    const val MESSAGE = "stk_message"
    const val TOTALS = "stk_totals"
    const val PRINT = "stk_print"
    fun row(skuId: Long) = "stk_row_$skuId"
}

/** What the last Save said, shown under the list. */
enum class StockMessage { SAVED, REFUSED_SAME_VALUES, NOTHING_ENTERED, SAVE_FAILED }

@Composable
private fun categoryLabel(code: String): String = when (code) {
    "cigarette" -> stringResource(R.string.stk_cat_cigarette)
    "bidi" -> stringResource(R.string.stk_cat_bidi)
    "lighter" -> stringResource(R.string.stk_cat_lighter)
    "match" -> stringResource(R.string.stk_cat_match)
    else -> code
}

/**
 * The Stock screen (F-SR-014): one row per SKU with a stepped Issue and today's loaded total (read-only), the category
 * totals, and Save. Save never waits for the printer or the network (Q-UI-03): the slip is flagged not printed.
 */
@Composable
fun StockContent(
    rows: List<StockRow>,
    totals: List<CategoryTotal>,
    message: StockMessage?,
    slipNotPrinted: Boolean,
    onIssue: (skuId: Long, qty: Int) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    softCeiling: Long = StockLoad.DEFAULT_SOFT_CEILING,
    onPrint: (() -> Unit)? = null,
    printEnabled: Boolean = true,
) {
    Column(modifier.fillMaxSize().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        Text(stringResource(R.string.stk_title), style = MaterialTheme.typography.headlineSmall)
        Row {
            Text(stringResource(R.string.stk_col_sku), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.stk_col_issue), Modifier.weight(1.4f), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.stk_col_loaded), Modifier.weight(0.6f), style = MaterialTheme.typography.labelLarge)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.sku.skuId }) { r ->
                Row(Modifier.testTag(StockTags.row(r.sku.skuId)), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.sku.shortName, Modifier.weight(1f))
                    AronStepper(
                        value = r.entered.toInt(), onValueChange = { onIssue(r.sku.skuId, it) },
                        minusLabel = stringResource(R.string.stk_less), plusLabel = stringResource(R.string.stk_more),
                        modifier = Modifier.weight(1.4f),
                    )
                    Text(localizedNumber(r.loadedBase), Modifier.weight(0.6f))
                }
                if (r.entered > softCeiling) AronBanner(stringResource(R.string.stk_high), kind = BannerKind.Warning)
            }
        }
        Column(Modifier.testTag(StockTags.TOTALS)) {
            Text(stringResource(R.string.stk_totals), style = MaterialTheme.typography.labelLarge)
            totals.forEach { Text(stringResource(R.string.stk_total_row, categoryLabel(it.categoryCode), localizedNumber(it.issuedBase))) }
        }
        when (message) {
            StockMessage.SAVED -> AronBanner(stringResource(R.string.stk_saved), Modifier.testTag(StockTags.MESSAGE), BannerKind.Info)
            StockMessage.REFUSED_SAME_VALUES -> AronBanner(stringResource(R.string.stk_refused), Modifier.testTag(StockTags.MESSAGE), BannerKind.Warning)
            StockMessage.NOTHING_ENTERED -> AronBanner(stringResource(R.string.stk_nothing), Modifier.testTag(StockTags.MESSAGE), BannerKind.Warning)
            StockMessage.SAVE_FAILED -> AronBanner(stringResource(R.string.stk_save_failed), Modifier.testTag(StockTags.MESSAGE), BannerKind.Error)
            null -> Unit
        }
        if (slipNotPrinted) AronBanner(stringResource(R.string.stk_slip_not_printed), kind = BannerKind.Warning)
        AronPrimaryButton(stringResource(R.string.stk_save), onSave, Modifier.testTag(StockTags.SAVE))
        if (onPrint != null) AronSecondaryButton(stringResource(R.string.stk_print), onPrint, Modifier.testTag(StockTags.PRINT), enabled = printEnabled)
    }
}
