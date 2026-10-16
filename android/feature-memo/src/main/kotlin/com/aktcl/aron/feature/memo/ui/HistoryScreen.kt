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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.MoneyText
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.feature.memo.R
import com.aktcl.aron.feature.memo.domain.SaleHistory

/**
 * An outlet's sales by date (F-SR-054; screens-sr s9 "sale history": date chips and a table). One card per business
 * date: the date chip and the day's subtotal in the head, the memos (number and time, total) as the table; the footer
 * card is the exact sum of the rows. [offlineBanner] marks the server fallback.
 */
@Composable
fun SaleHistoryScreen(h: SaleHistory, offlineBanner: Boolean, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    ScrollColumn(modifier, contentPadding = PaddingValues(horizontal = AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        if (offlineBanner || h.fromServer) AronBanner(stringResource(R.string.history_offline), kind = BannerKind.Offline)
        if (h.days.isEmpty()) AronEmptyState(stringResource(R.string.history_empty), icon = AronIcons.Calendar)
        h.days.forEach { day ->
            AronCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Pill(c.accentContainer, c.accentOnContainer, AronIcons.Calendar, localizedDigits(day.businessDate))
                        Spacer(Modifier.weight(1f))
                        MoneyText(money(day.subtotalMtk), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End)
                    }
                    CardDivider()
                    TableHeader(stringResource(R.string.history_col_memo), "", stringResource(R.string.memo_col_price))
                    day.memos.forEach { r ->
                        TableRow(stringResource(R.string.memo_row_meta, localizedDigits(shortMemoNo(r.memoNo)), dhakaTime(r.committedAtIso)), "", amount(r.totalMtk))
                    }
                }
            }
        }
        if (h.days.isNotEmpty()) {
            AronCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.history_footer), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                    MoneyText(money(h.footerMtk), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.End)
                }
            }
        }
        Spacer(Modifier.height(AronTokens.Space.L))
    }
}
