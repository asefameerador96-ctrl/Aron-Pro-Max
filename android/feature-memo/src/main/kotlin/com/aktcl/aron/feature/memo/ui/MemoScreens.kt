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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.memo.R
import com.aktcl.aron.feature.memo.domain.DaySummary
import com.aktcl.aron.feature.memo.domain.MemoDetail
import com.aktcl.aron.feature.memo.domain.MemoMenuRow
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale

@Composable
private fun money(mtk: Long): String = Formats.money(mtk, if (LocalAppLanguage.current == AppLanguage.BN) UiLocale.BN else UiLocale.EN)

/** The Memo menu: today's live memos with the total beside each; selecting one shows [MemoDetailView] (F-SR-030). */
@Composable
fun MemoMenuScreen(rows: List<MemoMenuRow>, outletName: (Long) -> String, onOpen: (MemoMenuRow) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.memo_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        AronBanner(stringResource(R.string.memo_banner))
        if (rows.isEmpty()) AronEmptyState(stringResource(R.string.memo_empty))
        LazyColumn {
            items(rows, key = { it.memoUuid }) { r ->
                AronListRow(outletName(r.outletId), subtitle = localizedDigits(r.memoNo), trailing = money(r.totalMtk), onClick = { onOpen(r) })
            }
        }
    }
}

/**
 * Items, discount table and the three totals; Print, Edit and Mark paid. [canMarkPaid] must come from
 * `DueLedger.markPaid(...) != null` (not the stored due), so the button disappears after the collection (F-SR-032).
 */
@Composable
fun MemoDetailView(
    detail: MemoDetail,
    canMarkPaid: Boolean,
    dueMtk: Long,
    skuName: (Long) -> String,
    onPrint: () -> Unit,
    onEdit: () -> Unit,
    onMarkPaid: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        detail.items.forEach { i -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${skuName(i.skuId)}  ${localizedNumber(i.qtyBase)}"); Text(money(i.grossMtk)) } }
        detail.discountTable.forEach { d -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${d.skuId?.let(skuName).orEmpty()}  ${localizedNumber(d.qtyBase)}"); Text(money(d.valueMtk)) } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.memo_total_discount)); Text("− " + money(detail.totalDiscountMtk)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.memo_total_qc)); Text("− " + money(detail.totalQcMtk)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.memo_grand_total), style = MaterialTheme.typography.titleMedium); Text(money(detail.grandTotalMtk), style = MaterialTheme.typography.titleMedium) }
        if (dueMtk > 0) AronBanner(stringResource(R.string.memo_due, money(dueMtk)), kind = com.aktcl.aron.core.ui.BannerKind.Error)
        if (detail.reprintIsDuplicate) AronBanner(stringResource(R.string.memo_duplicate))
        AronPrimaryButton(stringResource(R.string.memo_print), onPrint, Modifier.fillMaxWidth())
        AronSecondaryButton(stringResource(R.string.memo_edit), onEdit, Modifier.fillMaxWidth())
        if (canMarkPaid) AronSecondaryButton(stringResource(R.string.memo_mark_paid), { confirm = true }, Modifier.fillMaxWidth())
    }
    if (confirm) {
        AronConfirmDialog(
            stringResource(R.string.memo_mark_paid), stringResource(R.string.memo_mark_paid_confirm), stringResource(R.string.memo_yes), stringResource(R.string.memo_no),
            onConfirm = { confirm = false; onMarkPaid() }, onDismiss = { confirm = false },
        )
    }
}

/** Sales summary: per SKU, per category, the "discount and others" line and the grand total (F-SR-036). */
@Composable
fun SummaryScreen(summary: DaySummary, skuName: (Long) -> String, onPrint: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.summary_title), style = MaterialTheme.typography.titleLarge)
        summary.skus.forEach { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${skuName(r.skuId)}  ${localizedNumber(r.memoCount.toLong())}  ${localizedNumber(r.qtyBase)}")
                Text("${money(r.valueMtk)}  ${money(r.discountMtk)}  ${money(r.discountedValueMtk)}  ${localizedNumber(r.returnQtyBase)}")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.summary_discount_and_others)); Text("− " + money(summary.discountAndOthersMtk)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.summary_grand_total), style = MaterialTheme.typography.titleMedium); Text(money(summary.grandTotalMtk), style = MaterialTheme.typography.titleMedium) }
        AronPrimaryButton(stringResource(R.string.summary_print), onPrint, Modifier.fillMaxWidth())
    }
}
