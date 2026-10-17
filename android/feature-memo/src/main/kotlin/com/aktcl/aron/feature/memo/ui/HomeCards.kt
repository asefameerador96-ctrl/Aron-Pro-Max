package com.aktcl.aron.feature.memo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronMeter
import com.aktcl.aron.core.ui.AronSectionLabel
import com.aktcl.aron.core.ui.AronStatCard
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.MoneyText
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.memo.R
import com.aktcl.aron.feature.memo.domain.HomeMoney
import com.aktcl.aron.feature.memo.domain.Journey
import com.aktcl.aron.feature.memo.domain.JourneyStatus
import com.aktcl.aron.feature.memo.domain.KpiStrip

/** 100 percent in hundredths: the strike-rate meter's full scale. */
private const val STRIKE_RATE_MAX = 10_000

/** Percent from hundredths with two decimals ("10.00"), digits per locale; blank when nothing is planned. */
fun percentText(hundredths: Int?): String =
    hundredths?.let { (it / 100).toString() + "." + (it % 100).toString().padStart(2, '0') }.orEmpty()

/**
 * KPI (F-SR-010; screens-sr s18): stat cards of the day from the local aggregate: outlets visited (with a meter), the
 * strike rate (with its own meter out of 100 percent), non-visit, no sale; then issue and current stock per category in
 * the category's own unit.
 */
@Composable
fun KpiStripView(strip: KpiStrip, categoryLabel: (String) -> String, unitLabel: (String) -> String, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    Column(modifier.fillMaxWidth().padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        AronCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                Text(stringResource(R.string.kpi_visited), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                MoneyText(stringResource(R.string.kpi_ratio, localizedNumber(strip.visitedOutlets.toLong()), localizedNumber(strip.plannedOutlets.toLong())), style = MaterialTheme.typography.displayLarge)
                AronMeter(strip.visitedOutlets, strip.plannedOutlets)
            }
        }
        // the strike rate with its meter (screens-sr s18): hundredths of a percent out of 10,000
        AronCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                Text(stringResource(R.string.kpi_strike), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                MoneyText(strip.strikeRateHundredths?.let { localizedDigits(stringResource(R.string.kpi_percent, percentText(it))) } ?: stringResource(R.string.kpi_none))
                AronMeter(strip.strikeRateHundredths ?: 0, STRIKE_RATE_MAX, Modifier.padding(top = AronTokens.Space.Xs))
            }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            AronStatCard(stringResource(R.string.kpi_non_visit), localizedNumber(strip.nonVisitOutlets.toLong()), Modifier.weight(1f).fillMaxHeight())
            AronStatCard(stringResource(R.string.kpi_no_sale), localizedNumber(strip.noSaleOutlets.toLong()), Modifier.weight(1f).fillMaxHeight())
        }
        if (strip.categories.isNotEmpty()) {
            AronCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(vertical = AronTokens.Space.S)) {
                    Text(
                        stringResource(R.string.kpi_stock_title), style = MaterialTheme.typography.labelMedium, color = c.textSecondary,
                        modifier = Modifier.padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.S),
                    )
                    strip.categories.forEachIndexed { i, cat ->
                        AronListRow(
                            categoryLabel(cat.categoryCode),
                            subtitle = stringResource(R.string.kpi_stock_line, localizedNumber(cat.issueBase), localizedNumber(cat.currentStockBase), unitLabel(cat.unit)),
                            divider = i < strip.categories.lastIndex,
                        )
                    }
                }
            }
        }
    }
}

/** Sales by category and the money totals (F-SR-069; screens-sr s18): net per category, then gross, the deductions with a true minus, and the grand total. */
@Composable
fun MoneyCardsView(m: HomeMoney, categoryLabel: (String) -> String, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    AronCard(modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S)) {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            Text(stringResource(R.string.money_by_category), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
            m.categories.forEach { cat -> TotalLine(categoryLabel(cat.categoryCode), money(cat.netMtk)) }
            CardDivider()
            TotalLine(stringResource(R.string.money_gross), money(m.grossMtk))
            if (m.totalDiscountMtk != 0L) DeductionLine(stringResource(R.string.money_discount), minusMoney(m.totalDiscountMtk))
            if (m.drpDiscountMtk != 0L) DeductionLine(stringResource(R.string.money_drp), minusMoney(m.drpDiscountMtk))
            if (m.totalQcMtk != 0L) DeductionLine(stringResource(R.string.money_qc), minusMoney(m.totalQcMtk))
            CardDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.money_grand), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                MoneyText(money(m.grandTotalMtk), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.End)
            }
        }
    }
}

/**
 * Sales Journey (F-SR-067; screens-sr s17): the counts in three stat cards (visited, sold, not visited, with the plan as
 * the caption), then today's planned outlets as flat rows, each with a status chip (word plus icon, never colour alone).
 * All local, offline.
 */
@Composable
fun JourneyScreen(j: Journey, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    ScrollColumn(modifier) {
        Text(
            stringResource(R.string.journey_planned, localizedNumber(j.planned.toLong())), style = MaterialTheme.typography.labelMedium, color = c.textSecondary,
            modifier = Modifier.padding(start = AronTokens.Space.Screen, end = AronTokens.Space.Screen, top = AronTokens.Space.M),
        )
        // the three counts share the tallest card's height (a wrapped Bangla label never makes one card shorter)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.M).height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            AronStatCard(stringResource(R.string.journey_visited), localizedNumber(j.visited.toLong()), Modifier.weight(1f).fillMaxHeight())
            AronStatCard(stringResource(R.string.journey_sold), localizedNumber(j.sold.toLong()), Modifier.weight(1f).fillMaxHeight())
            AronStatCard(stringResource(R.string.journey_not_visited), localizedNumber(j.notVisited.toLong()), Modifier.weight(1f).fillMaxHeight())
        }
        if (j.rows.isEmpty()) {
            AronEmptyState(stringResource(R.string.journey_empty), icon = AronIcons.Journey)
        } else {
            AronSectionLabel(stringResource(R.string.journey_route))
            Column(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.Screen)) {
                j.rows.forEachIndexed { i, r ->
                    AronListRow(
                        r.outlet.name,
                        subtitle = stringResource(R.string.journey_sequence, localizedNumber(r.outlet.sequence.toLong())),
                        flat = true, position = rowPosition(i, j.rows.size), divider = i < j.rows.lastIndex,
                        trailingContent = {
                            when (r.status) {
                                JourneyStatus.Sold -> Pill(c.successContainer, c.successOnContainer, AronIcons.CheckCircle, stringResource(R.string.journey_sold))
                                JourneyStatus.Visited -> Pill(c.accentContainer, c.accentOnContainer, AronIcons.Check, stringResource(R.string.journey_visited))
                                JourneyStatus.NotVisited -> Pill(null, c.textSecondary, AronIcons.Clock, stringResource(R.string.journey_not_visited))
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(AronTokens.Space.L))
        }
    }
}
