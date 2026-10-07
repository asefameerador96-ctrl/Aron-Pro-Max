package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.localizedNumber

object ForceSaleTags {
    const val CONFIRM = "fs_confirm"
    const val PHOTO = "fs_photo"
    const val GEO = "fs_geo"
    fun reason(r: ForceReason) = "fs_reason_${r.code}"
}

/** The GEO and photo block shared by Force Sale and the outlet-request forms (F-SR-079): thumbnail line, accuracy, retake. */
@Composable
fun GeoPhotoBlock(state: GeoPhotoState, onShutter: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(
            state.accuracyMeters?.let { stringResource(R.string.out_geo_captured, localizedNumber(it.toLong())) } ?: stringResource(R.string.out_geo_missing),
            Modifier.testTag(ForceSaleTags.GEO), style = MaterialTheme.typography.bodyMedium,
        )
        if (state.photo != null) Text(stringResource(R.string.out_photo_attached), style = MaterialTheme.typography.bodyMedium)
        if (state.geoMocked) AronBanner(stringResource(R.string.out_mocked_warn), kind = BannerKind.Error)
        val canShoot = !state.busy && (state.photo == null || state.retakesLeft > 0)
        AronSecondaryButton(
            stringResource(if (state.photo == null) R.string.out_take_photo else R.string.out_retake_photo), onShutter,
            Modifier.testTag(ForceSaleTags.PHOTO), enabled = canShoot,
        )
    }
}

/** Force Sale screen (F-SR-018): one reason, the shop photo with its fix, then Continue. */
@Composable
fun ForceSaleContent(
    selected: ForceReason?,
    capture: GeoPhotoState,
    missing: Set<ForceSaleMissing>,
    noOutletLocation: Boolean,
    onReason: (ForceReason) -> Unit,
    onShutter: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        Text(stringResource(R.string.out_force_title), style = MaterialTheme.typography.headlineSmall)
        if (!noOutletLocation) {
            Text(stringResource(R.string.out_force_reason), style = MaterialTheme.typography.bodyLarge)
            listOf(ForceReason.INTERNET_PROBLEM to R.string.out_reason_internet_problem, ForceReason.LOCATION_CHANGE to R.string.out_reason_location_change).forEach { (r, label) ->
                Row(
                    Modifier.selectable(selected == r, onClick = { onReason(r) }, role = Role.RadioButton).testTag(ForceSaleTags.reason(r)),
                    verticalAlignment = Alignment.CenterVertically,
                ) { RadioButton(selected == r, onClick = null); Text(stringResource(label), Modifier.padding(start = AronTokens.Space.S)) }
            }
        } else {
            AronBanner(stringResource(R.string.out_no_outlet_location), kind = BannerKind.Warning)
        }
        GeoPhotoBlock(capture, onShutter)
        if (ForceSaleMissing.REASON in missing) Text(stringResource(R.string.out_err_reason), color = MaterialTheme.colorScheme.error)
        if (ForceSaleMissing.PHOTO in missing) Text(stringResource(R.string.out_err_photo), color = MaterialTheme.colorScheme.error)
        if (ForceSaleMissing.LOCATION_PERMISSION in missing) Text(stringResource(R.string.out_err_permission), color = MaterialTheme.colorScheme.error)
        AronPrimaryButton(stringResource(R.string.out_force_confirm), onConfirm, Modifier.testTag(ForceSaleTags.CONFIRM))
    }
}
