package com.aktcl.aron.feature.sale.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.sale.R
import com.aktcl.aron.feature.sale.domain.OutletTarget
import com.aktcl.aron.feature.sale.domain.categoryTotals

object TargetTags {
    const val POPUP = "target_popup"
    const val OKAY = "target_okay"
    const val EMPTY = "target_empty"
    const val PAGE = "target_page"
}

/**
 * "আজকের টার্গেট" (SR manual s8 / 21, left): the outlet, the campaign and month, then Target / Achievement / Remaining STD and
 * Target / Achievement / Remaining Memo, and an Okay button. [target] null (no target set or the bundle has none yet) says so plainly.
 */
@Composable
fun OutletTargetPopup(outletLabel: String, target: OutletTarget?, onOkay: () -> Unit) {
    val c = LocalAronColors.current
    AlertDialog(
        onDismissRequest = onOkay, modifier = Modifier.testTag(TargetTags.POPUP),
        containerColor = c.surfaceSolid, titleContentColor = c.textPrimary, textContentColor = c.textPrimary,
        title = { Text(outletLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            if (target == null) Text(stringResource(R.string.target_none), Modifier.testTag(TargetTags.EMPTY))
            else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                val head = listOfNotNull(target.campaign, "(" + target.monthLabel + ")").joinToString(" ")
                Text(head, color = c.accent, fontWeight = FontWeight.Medium)
                TargetFigure(stringResource(R.string.target_std), target.targetStd)
                TargetFigure(stringResource(R.string.target_ach_std), target.achievementStd)
                TargetFigure(stringResource(R.string.target_rem_std), target.remainingStd)
                TargetFigure(stringResource(R.string.target_memo), target.targetMemo)
                TargetFigure(stringResource(R.string.target_ach_memo), target.achievementMemo)
                TargetFigure(stringResource(R.string.target_rem_memo), target.remainingMemo)
            }
        },
        confirmButton = { TextButton(onClick = onOkay, modifier = Modifier.heightIn(min = AronTokens.Touch.Min).testTag(TargetTags.OKAY)) { Text(stringResource(R.string.target_okay)) } },
    )
}

@Composable
private fun TargetFigure(label: String, value: Long) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(localizedNumber(value), fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
    }
}

/**
 * The "টার্গেট" page (SR manual s8 / 21, right): the outlet, route and cluster lines, then SKU / target / sales / % rows and the category
 * totals. Percent is whole-number integer math. Reached from the popup once the bundle carries per-SKU rows (TRAIN 2).
 */
@Composable
fun OutletTargetPage(outletLabel: String, routeLine: String?, clusterLine: String?, target: OutletTarget?, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    Column(modifier.testTag(TargetTags.PAGE).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(outletLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        routeLine?.let { Text(it, color = c.textSecondary) }
        clusterLine?.let { Text(it, color = c.textSecondary) }
        if (target == null || target.rows.isEmpty()) {
            AronEmptyState(stringResource(R.string.target_none), icon = AronIcons.Target)
        } else {
            TargetRowView(stringResource(R.string.target_col_sku), stringResource(R.string.target_col_target), stringResource(R.string.target_col_sales), stringResource(R.string.target_col_pct), header = true)
            target.rows.forEach { TargetRowView(it.skuCode, localizedNumber(it.target), localizedNumber(it.sales), localizedNumber(it.percent)) }
            target.categoryTotals().forEach { TargetRowView(categoryLabel(it.categoryCode), localizedNumber(it.target), localizedNumber(it.sales), localizedNumber(it.percent), header = true) }
        }
    }
}

@Composable
private fun TargetRowView(a: String, b: String, c: String, d: String, header: Boolean = false) {
    val w = if (header) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(a, Modifier.weight(1.4f), fontWeight = w)
        Text(b, Modifier.weight(1f), fontWeight = w)
        Text(c, Modifier.weight(1f), fontWeight = w)
        Text(d, Modifier.weight(0.7f), fontWeight = w)
    }
}
