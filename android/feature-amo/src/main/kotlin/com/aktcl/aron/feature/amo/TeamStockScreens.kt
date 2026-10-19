package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.localizedNumber

@Composable
private fun unitLabel(unit: String) = stringResource(when (unit) { "stick" -> R.string.amo_unit_stick; "dozen" -> R.string.amo_unit_dozen; else -> R.string.amo_unit_piece })

@Composable
private fun qty(n: Long, unit: String) = localizedNumber(n) + " " + unitLabel(unit)

/** F-AMO-021: the SRs with their masked phones. */
@Composable
fun TeamStockListContent(srs: List<SrStock>, phones: Map<Long, String>, stale: Boolean, failed: Boolean, onOpen: (SrStock) -> Unit, modifier: Modifier = Modifier) {
    AmoPage(modifier) {
        if (failed) AmoNotice(stringResource(R.string.amo_task_list_offline), warning = true)
        else if (stale) AmoNotice(stringResource(R.string.amo_task_list_kept))
        if (srs.isEmpty()) AmoEmpty(stringResource(R.string.amo_stock_empty), hint = stringResource(R.string.amo_stock_empty_hint))
        srs.forEach { s -> AmoRowCard(s.name, subtitle = phones[s.userId], onClick = { onOpen(s) }) }
    }
}

/** One SR's lifted stock by SKU for the day (pack rows with the packshot), then the per-category totals on the purple panel. */
@Composable
fun TeamStockDetailContent(sr: SrStock, skus: Map<Long, SkuInfo>, totals: List<CategoryTotal>, bangla: Boolean, modifier: Modifier = Modifier) {
    AmoPage(modifier) {
        AmoRowCard(sr.name)
        if (sr.lines.isEmpty()) AmoEmpty(stringResource(R.string.amo_stock_none_today))
        else AmoHeading(stringResource(R.string.amo_stock_by_sku))
        sr.lines.forEach { l ->
            val sku = skus[l.skuId]
            AmoPackRow(
                l.skuId.toString(), sku?.let { if (bangla) it.nameBn ?: it.name else it.name } ?: l.skuId.toString(),
                subtitle = stringResource(R.string.amo_stock_line, qty(l.sold, l.unit), qty(l.current, l.unit)), figure = qty(l.lifted, l.unit),
            )
        }
        if (totals.isNotEmpty()) AmoTotalsBlock(
            listOf(stringResource(R.string.amo_stock_totals), stringResource(R.string.amo_stock_by_sku)),
            totals.map { t -> listOf(t.categoryCode.ifBlank { stringResource(R.string.amo_stock_other) }, qty(t.lifted, t.unit)) },
        )
    }
}
