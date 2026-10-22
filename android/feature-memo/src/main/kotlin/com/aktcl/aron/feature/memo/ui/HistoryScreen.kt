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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronInfoCard
import com.aktcl.aron.core.ui.aron.AronSectionHeader
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
    /** Category code of a SKU: with the stored unit it picks the word (packet, box, dozen). */
    skuCategory: (Long) -> String? = { null },
) {
    val look = LocalAronLook.current
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val day = h.days.firstOrNull { it.businessDate == picked } ?: h.days.firstOrNull()
    ScrollColumn(modifier.background(look.page), contentPadding = PaddingValues(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        if (offlineBanner || h.fromServer) AronInfoCard(stringResource(R.string.history_offline))
        if (h.days.isEmpty()) {
            Text(
                stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyLarge, color = look.inkMuted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = AronTokens.Space.Xl),
            )
        }
        if (h.days.isNotEmpty()) {
            AronSectionHeader(stringResource(R.string.history_pick_date))
            WhiteCard {
                h.days.forEachIndexed { i, d ->
                    if (i > 0) CardDivider()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = AronLook.Touch).testTag(HistoryTags.date(d.businessDate))
                            .clickable(role = Role.Button) { picked = d.businessDate }.padding(AronTokens.Space.L),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(localizedDigits(d.businessDate), style = MaterialTheme.typography.titleSmall, color = look.purple, modifier = Modifier.weight(1f))
                        if (d.businessDate == day?.businessDate) Text("\u2713", style = MaterialTheme.typography.titleSmall, color = look.purple)
                    }
                }
            }
        }
        if (day != null) {
            WhiteCard(Modifier.testTag(HistoryTags.TABLE)) {
                Column(Modifier.fillMaxWidth().padding(AronTokens.Space.M), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    Text(localizedDigits(day.businessDate), style = MaterialTheme.typography.titleSmall, color = look.purple, modifier = Modifier.padding(horizontal = AronTokens.Space.M))
                    TableHeader(stringResource(R.string.memo_col_sku), stringResource(R.string.memo_col_qty), stringResource(R.string.memo_col_price))
                    day.skuRows.forEach { r ->
                        TableRow(skuName(r.skuId), lineQtyText(r.qtyEntered, r.unitEntered, skuCategory(r.skuId), r.qtyBase, skuUnit(r.skuId)), amount(r.valueMtk))
                    }
                    CardDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.M), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.history_footer), style = MaterialTheme.typography.titleMedium, color = look.purple, modifier = Modifier.weight(1f))
                        MoneyText(money(day.skuTotalMtk), style = MaterialTheme.typography.headlineMedium, color = look.purple, textAlign = TextAlign.End, modifier = Modifier.testTag(HistoryTags.TOTAL))
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
