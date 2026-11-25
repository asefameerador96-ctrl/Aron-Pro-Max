package com.aktcl.aron.feature.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronMeter
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronInfoCard
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.memo.R
import com.aktcl.aron.feature.memo.domain.KpiStrip

object KpiDashboardTags {
    const val SCREEN = "kpi_dashboard"
    const val REAL = "kpi_dash_real"
    fun seeAll(section: String) = "kpi_see_all_$section"
}

/** The month line of the dashboard's purple card: the month as the rep reads it, the day of the month and the days it has. */
data class KpiMonth(val label: String, val dayOfMonth: Int, val daysInMonth: Int)

/**
 * The KPI Dashboard of the owner's manual (screenshot 60), reached from the Home KPI tile (lead ruling 2026-11-13).
 *
 * It keeps the vendor's page: the absence warning, the purple month card, then the Volume, Memo, BLM and CPR sections each with
 * its coloured header and its red "See all brands" button. What it does NOT do is invent a figure. Two kinds of line appear:
 *
 *  - the figures this phone really computes from today's own records — outlets visited against the plan, the strike rate, non-visit
 *    and no-sale counts, and issue and current stock per category in that category's own unit — are shown for real, under the labels
 *    the KPI screen has always used, inside the section each belongs to (stock under Volume, calls under CPR);
 *  - every programme figure (target, achievement, allocated and achieved KPI, CPR target) says [R.string.hook_later] and shows
 *    nothing else, because the programmes are deferred (docs/27) and a number here would be a guess.
 *
 * Reads nothing but the [strip] it is handed, so it works in airplane mode exactly as the KPI strip it replaces does.
 */
@Composable
fun KpiDashboardContent(
    strip: KpiStrip,
    month: KpiMonth?,
    categoryLabel: (String) -> String,
    unitLabel: (String) -> String,
    onSeeAllBrands: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = LocalAronLook.current
    ScrollColumn(
        modifier.background(look.page).testTag(KpiDashboardTags.SCREEN), contentPadding = PaddingValues(AronTokens.Space.Screen),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        AronInfoCard(stringResource(R.string.kpi_dash_warning))
        MonthCard(month)
        // Volume: the vendor's totals and brand rows wait for the programme; the issue and current stock this phone counts do not.
        KpiSection(stringResource(R.string.hook_kpi_volume), SectionVolume, onSeeAllBrands, "volume") {
            LaterLine(stringResource(R.string.kpi_dash_total_target))
            LaterLine(stringResource(R.string.kpi_dash_total_achievement))
            if (strip.categories.isNotEmpty()) {
                RealBlock {
                    Text(stringResource(R.string.kpi_stock_title), style = MaterialTheme.typography.labelMedium, color = look.secondaryText)
                    strip.categories.forEach { cat ->
                        // the issue-and-stock line is long and carries its own unit word, so it sits under its category rather than beside it
                        StackedFigure(
                            categoryLabel(cat.categoryCode),
                            stringResource(R.string.kpi_stock_line, kpiFigure(cat.issueBase, cat.issueRemBase, cat.categoryCode, cat.unit, unitLabel), kpiFigure(cat.currentStockBase, cat.currentRemBase, cat.categoryCode, cat.unit, unitLabel)),
                        )
                    }
                }
            }
        }
        KpiSection(stringResource(R.string.hook_kpi_memo), SectionMemo, onSeeAllBrands, "memo") {
            LaterLine(stringResource(R.string.kpi_dash_total_target))
            LaterLine(stringResource(R.string.kpi_dash_total_achievement))
        }
        KpiSection(stringResource(R.string.hook_kpi_blm), SectionBlm, onSeeAllBrands, "blm") {
            LaterLine(stringResource(R.string.kpi_dash_total_target))
            LaterLine(stringResource(R.string.kpi_dash_total_achievement))
        }
        // CPR: the vendor's call target and its taka lines wait; the calls this phone has actually made do not.
        KpiSection(stringResource(R.string.hook_kpi_cpr), SectionCpr, null, "cpr") {
            LaterLine(stringResource(R.string.kpi_dash_target_cpr))
            LaterLine(stringResource(R.string.kpi_dash_achieved_cpr))
            LaterLine(stringResource(R.string.kpi_dash_allocated))
            LaterLine(stringResource(R.string.kpi_dash_kpi_today))
            RealBlock {
                Figure(stringResource(R.string.kpi_visited), stringResource(R.string.kpi_ratio, localizedNumber(strip.visitedOutlets.toLong()), localizedNumber(strip.plannedOutlets.toLong())))
                AronMeter(strip.visitedOutlets, strip.plannedOutlets)
                Figure(
                    stringResource(R.string.kpi_strike),
                    strip.strikeRateHundredths?.let { localizedDigits(stringResource(R.string.kpi_percent, percentText(it))) } ?: stringResource(R.string.kpi_none),
                )
                Figure(stringResource(R.string.kpi_non_visit), localizedNumber(strip.nonVisitOutlets.toLong()))
                Figure(stringResource(R.string.kpi_no_sale), localizedNumber(strip.noSaleOutlets.toLong()))
            }
        }
    }
}

// The manual's four section tints (screenshot 60): pink, cream, pale green, pale lilac, each with a bar of its own colour.
private val SectionVolume = Color(0xFFD5484A) to Color(0xFFFBEAEA)
private val SectionMemo = Color(0xFFE08A00) to Color(0xFFFDF4E3)
private val SectionBlm = Color(0xFF2E9E44) to Color(0xFFE9F6EC)
private val SectionCpr = Color(0xFF4B1C97) to Color(0xFFEFEAF8)

@Composable
private fun KpiSection(
    title: String,
    tint: Pair<Color, Color>,
    onSeeAllBrands: (() -> Unit)?,
    key: String,
    body: @Composable () -> Unit,
) {
    val look = LocalAronLook.current
    WhiteCard {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().background(tint.second).padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.M),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(4.dp).heightIn(min = 20.dp).clip(RoundedCornerShape(2.dp)).background(tint.first))
                Text(title, Modifier.padding(start = AronTokens.Space.M), style = MaterialTheme.typography.titleSmall, color = look.ink)
            }
            Column(
                Modifier.fillMaxWidth().padding(AronTokens.Space.L),
                verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
            ) { body() }
            if (onSeeAllBrands != null) {
                // The manual's button is flat red with a white label, which is about 3.9:1 — below the body bar of docs/32.
                // Rule 14a: legibility wins, so this is the kit's own reviewed primary (the same ramp, ending deep purple).
                Box(Modifier.fillMaxWidth().padding(AronTokens.Space.L)) {
                    com.aktcl.aron.core.ui.aron.AronPrimaryButton(
                        stringResource(R.string.kpi_dash_see_all), onSeeAllBrands,
                        Modifier.fillMaxWidth().testTag(KpiDashboardTags.seeAll(key)), glyph = com.aktcl.aron.core.ui.aron.ButtonGlyph.Arrow,
                    )
                }
            }
        }
    }
}

/** A programme line: the vendor's own label with "later" where its number would be, and no number at all. */
@Composable
private fun LaterLine(label: String) {
    val look = LocalAronLook.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = look.ink)
        Text(stringResource(R.string.hook_later), style = MaterialTheme.typography.labelLarge, color = look.inkMuted, textAlign = TextAlign.End)
    }
}

/** A figure this phone really counted: the label and its value, told apart from the programme lines by the note above the block. */
@Composable
private fun Figure(label: String, value: String) {
    val look = LocalAronLook.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = look.ink)
        Text(value, style = MaterialTheme.typography.titleSmall, color = look.ink, textAlign = TextAlign.End)
    }
}

/** A figure whose value is too long to sit beside its label (the issue-and-stock line, which carries its own unit word). */
@Composable
private fun StackedFigure(label: String, value: String) {
    val look = LocalAronLook.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = look.ink)
        Text(value, style = MaterialTheme.typography.titleSmall, color = look.ink)
    }
}

/** The block that separates what the phone counted from what the programme still owes, in words and not by colour alone. */
@Composable
private fun RealBlock(content: @Composable () -> Unit) {
    val look = LocalAronLook.current
    Column(
        Modifier.fillMaxWidth().padding(top = AronTokens.Space.S).clip(RoundedCornerShape(10.dp))
            .border(1.dp, look.infoBorder, RoundedCornerShape(10.dp)).padding(AronTokens.Space.M).testTag(KpiDashboardTags.REAL),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
    ) {
        Text(stringResource(R.string.kpi_dash_real), style = MaterialTheme.typography.labelMedium, color = look.purple)
        content()
        Text(stringResource(R.string.kpi_dash_real_note), style = MaterialTheme.typography.bodySmall, color = look.inkMuted)
    }
}

/** The purple month card (screenshot 60): the month and the day count are real; the three KPI tiles wait for the programme. */
@Composable
private fun MonthCard(month: KpiMonth?) {
    val look = LocalAronLook.current
    val fill = Brush.horizontalGradient(listOf(Color(0xFF4A1FA8), Color(0xFF7B3FE4)))
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(fill).padding(AronTokens.Space.L)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            if (month != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(month.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(
                        stringResource(R.string.kpi_dash_days, localizedNumber(month.dayOfMonth.toLong()), localizedNumber(month.daysInMonth.toLong())),
                        style = MaterialTheme.typography.titleMedium, color = Color.White,
                    )
                }
            }
            // the three tiles keep their values on one line: the label block is two lines tall whether or not its own label wraps
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S), verticalAlignment = Alignment.Top) {
                listOf(R.string.kpi_dash_allocated, R.string.kpi_dash_achieved, R.string.kpi_dash_progress).forEach { label ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                        Text(
                            stringResource(label), Modifier.heightIn(min = 34.dp), style = MaterialTheme.typography.bodySmall,
                            color = look.onPanelMuted, minLines = 2,
                        )
                        Text(stringResource(R.string.hook_later), style = MaterialTheme.typography.titleSmall, color = Color.White)
                    }
                }
            }
        }
    }
}
