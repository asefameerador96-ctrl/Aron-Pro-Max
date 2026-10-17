package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.feature.outlet.VisitCheckTexts

object AmoCallTags { const val ROUTE_LIST = "amo_cc_routes"; const val EMPTY = "amo_cc_empty"; const val MENU_SALE = "amo_cc_sale"; const val MENU_ASSESS = "amo_cc_assess"; const val MENU_SURVEY = "amo_cc_survey"; const val MENU_PRICE = "amo_cc_price"; const val OVERRIDE_LIMIT = "amo_cc_limit"; const val DAY_SUBMITTED = "amo_cc_submitted" }

/** Route step of a Control or Joint Call (F-AMO-004): the zone's routes from the local bundle; empty state when none are downloaded. */
@Composable
fun AmoRoutePick(rows: List<AmoRouteRow>, onPick: (AmoRouteRow) -> Unit, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) {
        AronEmptyState(stringResource(R.string.amo_cc_routes_empty), modifier.fillMaxSize().testTag(AmoCallTags.EMPTY), hint = stringResource(R.string.amo_cc_routes_empty_hint), icon = AronIcons.Place)
        return
    }
    Column(modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.amo_cc_pick_route), style = MaterialTheme.typography.titleMedium, color = LocalAronColors.current.textPrimary,
            modifier = Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
        )
        LazyColumn(Modifier.fillMaxWidth().testTag(AmoCallTags.ROUTE_LIST).padding(horizontal = AronTokens.Space.Screen)) {
            itemsIndexed(rows, key = { _, r -> r.routeId }) { i, r ->
                val pos = when { rows.size == 1 -> RowPosition.Only; i == 0 -> RowPosition.First; i == rows.lastIndex -> RowPosition.Last; else -> RowPosition.Middle }
                AronListRow(
                    r.title, subtitle = stringResource(R.string.amo_cc_route_outlets, r.outletCount.toString()), onClick = { onPick(r) },
                    position = pos, divider = i != rows.lastIndex, flat = true,
                )
            }
        }
    }
}

/** Wording the AMO puts over the shared visit check: its own out-of-range line and "Manual Override" for Force Sale. */
@Composable
fun amoVisitCheckTexts(): VisitCheckTexts = VisitCheckTexts(stringResource(R.string.amo_cc_out_of_range), stringResource(R.string.amo_cc_manual_override))

/** Shown instead of the override form once the day's limit is used (F-AMO-005). */
@Composable
fun ManualOverrideLimit(max: Int, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        Text(stringResource(R.string.amo_cc_override_limit, max.toString()), style = MaterialTheme.typography.bodyLarge, color = LocalAronColors.current.textPrimary, modifier = Modifier.testTag(AmoCallTags.OVERRIDE_LIMIT))
        AronSecondaryButton(stringResource(R.string.amo_cc_back_to_picker), onBack, Modifier.fillMaxWidth())
    }
}

/** The per-outlet menu of an open Control Call: Sale, SR Perf. Assessment, Survey (docs/07), and Price compliance (F-AMO-035). An entry is hidden when its callback is null. */
@Composable
fun ControlCallMenu(onSale: () -> Unit, onAssessment: () -> Unit, onSurvey: (() -> Unit)?, onDone: () -> Unit, modifier: Modifier = Modifier, onPrice: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Text(stringResource(R.string.amo_cc_menu_title), style = MaterialTheme.typography.titleMedium, color = LocalAronColors.current.textPrimary)
        AronPrimaryButton(stringResource(R.string.amo_cc_menu_sale), onSale, Modifier.fillMaxWidth().testTag(AmoCallTags.MENU_SALE))
        AronPrimaryButton(stringResource(R.string.amo_cc_menu_assessment), onAssessment, Modifier.fillMaxWidth().testTag(AmoCallTags.MENU_ASSESS))
        if (onSurvey != null) AronPrimaryButton(stringResource(R.string.amo_cc_menu_survey), onSurvey, Modifier.fillMaxWidth().testTag(AmoCallTags.MENU_SURVEY))
        if (onPrice != null) AronPrimaryButton(stringResource(R.string.amo_cc_menu_price), onPrice, Modifier.fillMaxWidth().testTag(AmoCallTags.MENU_PRICE))
        AronSecondaryButton(stringResource(R.string.amo_cc_back_to_picker), onDone, Modifier.fillMaxWidth())
    }
}

/** Shown instead of the route list once the day is submitted (F-AMO-030): nothing more is captured until the server reopens the day. */
@Composable
fun DaySubmittedNotice(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        Text(stringResource(R.string.amo_day_submitted), style = MaterialTheme.typography.bodyLarge, color = LocalAronColors.current.textPrimary, modifier = Modifier.testTag(AmoCallTags.DAY_SUBMITTED))
        AronSecondaryButton(stringResource(R.string.amo_sale_done_back), onBack, Modifier.fillMaxWidth())
    }
}
