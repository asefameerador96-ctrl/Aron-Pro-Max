package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.rules.GeoVerdict

object OutletTags {
    const val CHIPS = "out_chips"
    const val LIST = "out_list"
    const val REFRESH = "out_refresh"
    const val FORCE = "out_force"
    const val MAP = "out_map"
    const val RETRY = "out_retry"
    const val MESSAGE = "out_message"
}

/** The Sale picker (F-SR-016, F-SR-074): chips, then rows `name (code-phone-cluster)`; works from the local bundle only. */
@Composable
fun OutletPickerContent(
    rows: List<PickerRow>,
    chips: List<String>,
    selectedChip: String,
    onChip: (String) -> Unit,
    onPick: (PickerRow) -> Unit,
    modifier: Modifier = Modifier,
    showDistance: Boolean = false,
) {
    Column(modifier.fillMaxSize()) {
        Text(stringResource(R.string.out_picker_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(AronTokens.Space.L))
        LazyRow(Modifier.testTag(OutletTags.CHIPS), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AronTokens.Space.L)) {
            item { FilterChip(selected = selectedChip == OutletPicker.ALL_CHIP, onClick = { onChip(OutletPicker.ALL_CHIP) }, label = { Text(stringResource(R.string.out_chip_all)) }) }
            items(chips) { c -> FilterChip(selected = selectedChip == c, onClick = { onChip(c) }, label = { Text(c) }) }
        }
        if (rows.isEmpty()) {
            AronEmptyState(stringResource(R.string.out_picker_none))
        } else {
            LazyColumn(Modifier.testTag(OutletTags.LIST)) {
                items(rows, key = { it.outlet.outletId }) { r ->
                    AronListRow(
                        title = r.label,
                        trailing = if (showDistance) r.distanceM?.let { stringResource(R.string.out_distance_m, localizedNumber(Math.round(it))) } else null,
                        onClick = { onPick(r) },
                    )
                }
            }
        }
    }
}

/** The check screen shown after an outlet is picked (F-SR-017, F-SR-019, F-SR-018 entry, N-041 entry). */
@Composable
fun VisitCheckContent(
    state: VisitUiState,
    onRefresh: () -> Unit,
    onForceSale: () -> Unit,
    onRetry: () -> Unit,
    onMap: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        when (state) {
            VisitUiState.Idle -> Unit
            VisitUiState.ReadingFix -> Row(horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.out_reading_fix), Modifier.testTag(OutletTags.MESSAGE))
            }
            is VisitUiState.NeedsDecision -> {
                val msg = when (state.result.verdict) {
                    GeoVerdict.OUT_OF_RANGE -> R.string.out_out_of_range
                    GeoVerdict.NO_FIX -> R.string.out_no_fix
                    GeoVerdict.ACCURACY_TOO_LOW -> R.string.out_accuracy_low
                    GeoVerdict.NO_OUTLET_LOCATION -> R.string.out_no_outlet_location
                    GeoVerdict.MOCKED -> R.string.out_mocked
                    GeoVerdict.IN_RANGE -> R.string.out_out_of_range
                }
                AronBanner(stringResource(msg), Modifier.testTag(OutletTags.MESSAGE), BannerKind.Warning)
                if (state.mockWarning) AronBanner(stringResource(R.string.out_mocked_warn), kind = BannerKind.Error)
                state.result.distanceM?.let {
                    Text(stringResource(R.string.out_distance_info, localizedNumber(Math.round(it)), localizedNumber(state.outlet.radiusM.toLong())))
                }
                if (state.refreshLeft) {
                    AronPrimaryButton(stringResource(R.string.out_refresh), onRefresh, Modifier.testTag(OutletTags.REFRESH))
                    Text(stringResource(R.string.out_refresh_left, localizedNumber((state.refreshMax - state.refreshCount).toLong())), style = MaterialTheme.typography.bodySmall)
                }
                if (state.forceSaleAvailable) AronSecondaryButton(stringResource(R.string.out_force_sale), onForceSale, Modifier.testTag(OutletTags.FORCE))
                // The map loads only on tap and never blocks Force Sale (N-041).
                if (onMap != null) AronSecondaryButton(stringResource(R.string.out_map), onMap, Modifier.testTag(OutletTags.MAP))
            }
            is VisitUiState.Blocked -> AronBanner(stringResource(R.string.out_blocked_mock), Modifier.testTag(OutletTags.MESSAGE), BannerKind.Error)
            is VisitUiState.LocationBlocked -> AronBanner(
                stringResource(if (state.reason == "location_off") R.string.out_location_off else R.string.out_location_denied),
                Modifier.testTag(OutletTags.MESSAGE), BannerKind.Error,
            )
            is VisitUiState.CommitFailed -> {
                AronBanner(stringResource(R.string.out_commit_failed), Modifier.testTag(OutletTags.MESSAGE), BannerKind.Error)
                AronPrimaryButton(stringResource(R.string.out_retry), onRetry, Modifier.testTag(OutletTags.RETRY))
            }
            is VisitUiState.Open -> Unit // the sale screens take over (android-sr-b)
        }
    }
}
