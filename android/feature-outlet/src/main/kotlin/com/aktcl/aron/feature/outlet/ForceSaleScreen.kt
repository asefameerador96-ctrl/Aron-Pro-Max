package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.localizedNumber

object ForceSaleTags {
    const val CONFIRM = "fs_confirm"
    const val PHOTO = "fs_photo"
    const val GEO = "fs_geo"
    fun reason(r: ForceReason) = "fs_reason_${r.code}"
}

/**
 * The GEO and photo block shared by Force Sale and the outlet-request forms (F-SR-079): a card with the camera well, the
 * "GEO captured ± n m" line, the photo line, the mock warning and the shutter (secondary; one retake).
 */
@Composable
fun GeoPhotoBlock(state: GeoPhotoState, onShutter: () -> Unit, modifier: Modifier = Modifier, note: String? = null) {
    val c = LocalAronColors.current
    val well = if (state.photo != null) c.successContainer else c.accentContainer
    val glyph = if (state.photo != null) c.successOnContainer else c.accentOnContainer
    AronCard(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                Box(
                    Modifier.size(AronTokens.Size.Well).drawBehind { drawRoundRect(well, cornerRadius = CornerRadius(AronTokens.Radius.Well.toPx())) },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (state.photo != null) AronIcons.CheckCircle else AronIcons.Camera, contentDescription = null, tint = glyph, modifier = Modifier.size(AronTokens.Size.Icon)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                    Text(
                        state.accuracyMeters?.let { stringResource(R.string.out_geo_captured, localizedNumber(it.toLong())) } ?: stringResource(R.string.out_geo_missing),
                        Modifier.testTag(ForceSaleTags.GEO), style = MaterialTheme.typography.titleSmall, color = c.textPrimary,
                    )
                    if (state.photo != null) Text(stringResource(R.string.out_photo_attached), style = MaterialTheme.typography.bodySmall, color = c.successOnContainer)
                    if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }
            if (state.geoMocked) AronBanner(stringResource(R.string.out_mocked_warn), kind = BannerKind.Error)
            val canShoot = !state.busy && (state.photo == null || state.retakesLeft > 0)
            AronSecondaryButton(
                stringResource(if (state.photo == null) R.string.out_take_photo else R.string.out_retake_photo), onShutter,
                Modifier.testTag(ForceSaleTags.PHOTO), enabled = canShoot, icon = AronIcons.Camera, busy = state.busy,
            )
        }
    }
}

/**
 * Force Sale (F-SR-018, screens-sr s6): the question, the two reason radio rows (one required), the note, the shop photo
 * with its fix, the validation lines, and the sticky primary in the bar. Renders its own bar.
 */
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
    val c = LocalAronColors.current
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                Icon(AronIcons.Warning, contentDescription = null, tint = c.warning, modifier = Modifier.size(AronTokens.Size.Icon))
                Text(stringResource(R.string.out_force_title), style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
            }
            if (!noOutletLocation) {
                Text(stringResource(R.string.out_force_reason), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                listOf(ForceReason.INTERNET_PROBLEM to R.string.out_reason_internet_problem, ForceReason.LOCATION_CHANGE to R.string.out_reason_location_change).forEach { (r, label) ->
                    RadioRow(stringResource(label), selected == r, { onReason(r) }, Modifier.testTag(ForceSaleTags.reason(r)))
                }
            } else {
                AronBanner(stringResource(R.string.out_no_outlet_location), kind = BannerKind.Warning)
            }
            GeoPhotoBlock(capture, onShutter, note = stringResource(R.string.out_force_note))
            if (ForceSaleMissing.REASON in missing) FieldError(stringResource(R.string.out_err_reason))
            if (ForceSaleMissing.PHOTO in missing) FieldError(stringResource(R.string.out_err_photo))
            if (ForceSaleMissing.LOCATION_PERMISSION in missing) FieldError(stringResource(R.string.out_err_permission))
        }
        OutletBar(BarAction(stringResource(R.string.out_force_confirm), onConfirm, ForceSaleTags.CONFIRM, icon = AronIcons.ArrowForward))
    }
}
