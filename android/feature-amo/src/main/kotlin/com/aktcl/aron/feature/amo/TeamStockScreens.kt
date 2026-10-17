package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronSectionLabel
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.core.ui.localizedNumber

private fun pos(i: Int, n: Int) = if (n == 1) RowPosition.Only else if (i == 0) RowPosition.First else if (i == n - 1) RowPosition.Last else RowPosition.Middle

@Composable
private fun unitLabel(unit: String) = stringResource(when (unit) { "stick" -> R.string.amo_unit_stick; "dozen" -> R.string.amo_unit_dozen; else -> R.string.amo_unit_piece })

@Composable
private fun qty(n: Long, unit: String) = localizedNumber(n) + " " + unitLabel(unit)

/** F-AMO-021: the SRs with their masked phones. */
@Composable
fun TeamStockListContent(srs: List<SrStock>, phones: Map<Long, String>, stale: Boolean, failed: Boolean, onOpen: (SrStock) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen)) {
        if (failed) AronBanner(stringResource(R.string.amo_task_list_offline), kind = BannerKind.Warning)
        else if (stale) AronBanner(stringResource(R.string.amo_task_list_kept), kind = BannerKind.Info)
        if (srs.isEmpty()) AronEmptyState(stringResource(R.string.amo_stock_empty), Modifier.fillMaxSize(), hint = stringResource(R.string.amo_stock_empty_hint))
        srs.forEachIndexed { i, s -> AronListRow(s.name, subtitle = phones[s.userId], onClick = { onOpen(s) }, position = pos(i, srs.size), divider = i < srs.lastIndex, flat = true) }
    }
}

/** One SR's lifted stock by SKU for the day, then the per-category totals. */
@Composable
fun TeamStockDetailContent(sr: SrStock, skus: Map<Long, SkuInfo>, totals: List<CategoryTotal>, bangla: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen)) {
        if (sr.lines.isEmpty()) AronEmptyState(stringResource(R.string.amo_stock_none_today), Modifier.fillMaxSize())
        AronSectionLabel(stringResource(R.string.amo_stock_by_sku))
        sr.lines.forEachIndexed { i, l ->
            val sku = skus[l.skuId]
            AronListRow(
                sku?.let { if (bangla) it.nameBn ?: it.name else it.name } ?: l.skuId.toString(),
                subtitle = stringResource(R.string.amo_stock_line, qty(l.sold, l.unit), qty(l.current, l.unit)), trailing = qty(l.lifted, l.unit),
                position = pos(i, sr.lines.size), divider = i < sr.lines.lastIndex, flat = true,
            )
        }
        if (totals.isNotEmpty()) AronSectionLabel(stringResource(R.string.amo_stock_totals))
        totals.forEachIndexed { i, t -> AronListRow(t.categoryCode.ifBlank { stringResource(R.string.amo_stock_other) }, trailing = qty(t.lifted, t.unit), position = pos(i, totals.size), divider = i < totals.lastIndex, flat = true) }
    }
}
