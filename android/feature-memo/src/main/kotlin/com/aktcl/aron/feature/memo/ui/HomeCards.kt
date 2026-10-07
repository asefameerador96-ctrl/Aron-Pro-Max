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
import com.aktcl.aron.core.ui.AronCard
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.memo.R
import com.aktcl.aron.feature.memo.domain.HomeMoney
import com.aktcl.aron.feature.memo.domain.Journey
import com.aktcl.aron.feature.memo.domain.JourneyStatus
import com.aktcl.aron.feature.memo.domain.KpiStrip

/** Percent from hundredths with two decimals ("10.00"), digits per locale; blank when nothing is planned. */
fun percentText(hundredths: Int?): String =
    hundredths?.let { (it / 100).toString() + "." + (it % 100).toString().padStart(2, '0') }.orEmpty()

/** Home KPI strip: visited x/y, strike rate, non-visit, no-sale, and issue and current stock per category with units (F-SR-010). */
@Composable
fun KpiStripView(strip: KpiStrip, categoryLabel: (String) -> String, unitLabel: (String) -> String, modifier: Modifier = Modifier) {
    AronCard(modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(localizedDigits(stringResource(R.string.kpi_visited, strip.visitedOutlets.toString(), strip.plannedOutlets.toString())), style = MaterialTheme.typography.titleMedium)
            strip.strikeRateHundredths?.let { Text(localizedDigits(stringResource(R.string.kpi_strike, percentText(it)))) }
            Text(stringResource(R.string.kpi_non_visit, localizedNumber(strip.nonVisitOutlets.toLong())))
            Text(stringResource(R.string.kpi_no_sale, localizedNumber(strip.noSaleOutlets.toLong())))
            strip.categories.forEach { c ->
                Text(stringResource(R.string.kpi_stock_line, categoryLabel(c.categoryCode), localizedNumber(c.issueBase), localizedNumber(c.currentStockBase), unitLabel(c.unit)))
            }
        }
    }
}

/** The two Home money cards (F-SR-069): per-category value and net, then discount, slide, QC and the grand total. */
@Composable
fun MoneyCardsView(m: HomeMoney, categoryLabel: (String) -> String, modifier: Modifier = Modifier) {
    AronCard(modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            m.categories.forEach { c ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(categoryLabel(c.categoryCode)); Text(money(c.netMtk)) }
            }
            Line(R.string.money_gross, m.grossMtk)
            Line(R.string.money_discount, -m.totalDiscountMtk)
            if (m.drpDiscountMtk != 0L) Line(R.string.money_drp, -m.drpDiscountMtk)
            Line(R.string.money_qc, -m.totalQcMtk)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.money_grand), style = MaterialTheme.typography.titleMedium); Text(money(m.grandTotalMtk), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Line(label: Int, mtk: Long) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(label)); Text(money(mtk)) }

/** Sales Journey: today's planned outlets with visited, not-visited and sold status, and the counts (F-SR-067). */
@Composable
fun JourneyScreen(j: Journey, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.journey_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        AronCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.journey_counts, localizedNumber(j.planned.toLong()), localizedNumber(j.visited.toLong()), localizedNumber(j.sold.toLong()), localizedNumber(j.notVisited.toLong())),
                modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium,
            )
        }
        LazyColumn {
            items(j.rows, key = { it.outlet.outletId }) { r ->
                AronListRow(
                    r.outlet.name,
                    trailing = stringResource(when (r.status) { JourneyStatus.Sold -> R.string.journey_sold; JourneyStatus.Visited -> R.string.journey_visited; JourneyStatus.NotVisited -> R.string.journey_not_visited }),
                )
            }
        }
    }
}
