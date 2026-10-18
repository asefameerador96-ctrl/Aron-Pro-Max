package com.aktcl.aron.feature.memo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronSectionLabel
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.MoneyText
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.feature.memo.R
import com.aktcl.aron.feature.memo.domain.SaleHistory

/**
 * An outlet's previous sales (F-SR-054, F-AMO-013; manual SR-S-20 "সেল ডাটা"): pick one date, then the per-SKU table (SKU,
 * quantity, value) and the exact total of the rows shown. Dates are the live memos' business dates, newest first, with the
 * newest picked. [skuName] and [skuUnit] name each SKU and its unit; [offlineBanner] marks the server fallback.
 */
@Composable
fun SaleHistoryScreen(
    h: SaleHistory, offlineBanner: Boolean, modifier: Modifier = Modifier,
    skuName: (Long) -> String = { it.toString() }, skuUnit: (Long) -> String = { "" },
) {
    val c = LocalAronColors.current
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val day = h.days.firstOrNull { it.businessDate == picked } ?: h.days.firstOrNull()
    ScrollColumn(modifier, contentPadding = PaddingValues(horizontal = AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        if (offlineBanner || h.fromServer) AronBanner(stringResource(R.string.history_offline), kind = BannerKind.Offline)
        if (h.days.isEmpty()) AronEmptyState(stringResource(R.string.history_empty), icon = AronIcons.Calendar)
        if (h.days.isNotEmpty()) {
            AronSectionLabel(stringResource(R.string.history_pick_date))
            Column(Modifier.fillMaxWidth()) {
                h.days.forEachIndexed { i, d ->
                    AronListRow(
                        localizedDigits(d.businessDate), Modifier.testTag(HistoryTags.date(d.businessDate)), onClick = { picked = d.businessDate },
                        trailing = if (d.businessDate == day?.businessDate) "\u2713" else null, flat = true, position = rowPosition(i, h.days.size), divider = i < h.days.lastIndex,
                    )
                }
            }
        }
        if (day != null) {
            AronCard(Modifier.fillMaxWidth().testTag(HistoryTags.TABLE)) {
                Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    Pill(c.accentContainer, c.accentOnContainer, AronIcons.Calendar, localizedDigits(day.businessDate))
                    CardDivider()
                    TableHeader(stringResource(R.string.memo_col_sku), stringResource(R.string.memo_col_qty), stringResource(R.string.memo_col_price))
                    day.skuRows.forEach { r ->
                        TableRow(skuName(r.skuId), qtyText(r.qtyBase, skuUnit(r.skuId)), amount(r.valueMtk))
                    }
                    CardDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.history_footer), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                        MoneyText(money(day.skuTotalMtk), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.End, modifier = Modifier.testTag(HistoryTags.TOTAL))
                    }
                }
            }
        }
        Spacer(Modifier.height(AronTokens.Space.L))
    }
}

object HistoryTags {
    const val TABLE = "memo_history_table"
    const val TOTAL = "memo_history_total"
    fun date(iso: String) = "memo_history_date_$iso"
}
