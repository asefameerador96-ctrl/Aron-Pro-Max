package com.aktcl.aron.feature.amo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber

/** F-AMO-017: Monthly and Till Date tabs and one bar per category (green from 80, amber from 40, red below; the number is capped at 100). */
@Composable
fun TeamPerformanceContent(bars: List<PerfBar>, tillDate: Boolean, onTab: (Boolean) -> Unit, stale: Boolean, failed: Boolean, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(AronTokens.Space.S)) {
        if (failed) AmoBanner(stringResource(R.string.amo_perf_offline), kind = BannerKind.Warning) else if (stale) AmoBanner(stringResource(R.string.amo_task_list_kept), kind = BannerKind.Info)
        AmoChips(listOf(stringResource(R.string.amo_perf_monthly), stringResource(R.string.amo_perf_till_date)), if (tillDate) 1 else 0, { onTab(it == 1) })
        if (bars.isEmpty()) AmoEmptyState(stringResource(R.string.amo_perf_empty), Modifier.fillMaxWidth(), hint = stringResource(R.string.amo_perf_empty_hint))
        bars.forEach { b ->
            val band = b.pct?.let { TeamPerformance.band(it) }
            val color = when (band) { PerfBand.GREEN -> c.success; PerfBand.AMBER -> c.warning; PerfBand.RED -> c.danger; null -> c.textSecondary }
            Text(b.name + (band?.let { stringResource(R.string.amo_verify_sep) + stringResource(when (it) { PerfBand.GREEN -> R.string.amo_perf_good; PerfBand.AMBER -> R.string.amo_perf_watch; PerfBand.RED -> R.string.amo_perf_low }) } ?: ""), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Box(Modifier.fillMaxWidth().height(12.dp).background(c.surfaceSolid, RoundedCornerShape(6.dp))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(((b.pct?.let { TeamPerformance.printed(it) } ?: 0.0) / 100.0).toFloat()).background(color, RoundedCornerShape(6.dp)))
            }
            Text(
                (b.pct?.let { localizedDigits(String.format(java.util.Locale.ROOT, "%.0f", TeamPerformance.printed(it))) + " %" } ?: stringResource(R.string.amo_live_dash)) + stringResource(R.string.amo_verify_sep) +
                    stringResource(R.string.amo_perf_line, localizedDigits(TeamPerformance.qty(b.target)), localizedDigits(TeamPerformance.qty(b.achieved)), localizedDigits(TeamPerformance.qty(b.remaining))),
                style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
            )
        }
    }
}
