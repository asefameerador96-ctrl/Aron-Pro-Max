package com.aktcl.aron.feature.memo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.feature.memo.R
import com.aktcl.aron.feature.memo.domain.SaleHistory

/** An outlet's sales by date (F-SR-054). The footer is the exact sum of the rows; [offlineBanner] marks the server fallback. */
@Composable
fun SaleHistoryScreen(h: SaleHistory, offlineBanner: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        if (offlineBanner || h.fromServer) AronBanner(stringResource(R.string.history_offline), kind = BannerKind.Warning)
        if (h.days.isEmpty()) AronEmptyState(stringResource(R.string.history_empty))
        LazyColumn(Modifier.weight(1f, fill = false)) {
            h.days.forEach { day ->
                item(key = "d-" + day.businessDate) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(localizedDigits(day.businessDate), style = MaterialTheme.typography.titleMedium); Text(money(day.subtotalMtk), style = MaterialTheme.typography.titleMedium)
                    }
                }
                items(day.memos, key = { it.memoUuid }) { r -> AronListRow(localizedDigits(r.memoNo), trailing = money(r.totalMtk)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.history_footer), style = MaterialTheme.typography.titleMedium); Text(money(h.footerMtk), style = MaterialTheme.typography.titleMedium)
        }
    }
}
