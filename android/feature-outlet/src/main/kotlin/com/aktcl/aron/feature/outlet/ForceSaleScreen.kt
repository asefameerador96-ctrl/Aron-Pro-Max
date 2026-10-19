package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
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
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronLook
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
    val look = LocalAronLook.current
    FormCard(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                Box(
                    Modifier.size(AronTokens.Size.Well).clip(CircleShape).background(look.purple),
                    contentAlignment = Alignment.Center,
                ) { Icon(if (state.photo != null) AronIcons.CheckCircle else AronIcons.Camera, contentDescription = null, tint = Color.White, modifier = Modifier.size(AronTokens.Size.Icon)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                    Text(
                        state.accuracyMeters?.let { stringResource(R.string.out_geo_captured, localizedNumber(it.toLong())) } ?: stringResource(R.string.out_geo_missing),
                        Modifier.testTag(ForceSaleTags.GEO), style = MaterialTheme.typography.titleSmall, color = look.ink,
                    )
                    if (state.photo != null) Text(stringResource(R.string.out_photo_attached), style = MaterialTheme.typography.bodySmall, color = look.purple)
                    if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = look.inkMuted)
                }
            }
            if (state.geoMocked) FormNotice(stringResource(R.string.out_mocked_warn), kind = BannerKind.Error)
            val canShoot = !state.busy && (state.photo == null || state.retakesLeft > 0)
            AronPrimaryButton(
                stringResource(if (state.photo == null) R.string.out_take_photo else R.string.out_retake_photo), onShutter,
                Modifier.fillMaxWidth().testTag(ForceSaleTags.PHOTO), enabled = canShoot,
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
    val look = LocalAronLook.current
    Column(modifier.fillMaxSize().background(look.page)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                Icon(AronIcons.Warning, contentDescription = null, tint = look.red, modifier = Modifier.size(AronTokens.Size.Icon))
                Text(stringResource(R.string.out_force_title), style = MaterialTheme.typography.titleLarge, color = look.purple)
            }
            if (!noOutletLocation) {
                Text(stringResource(R.string.out_force_reason), style = MaterialTheme.typography.bodyLarge, color = look.ink)
                listOf(ForceReason.INTERNET_PROBLEM to R.string.out_reason_internet_problem, ForceReason.LOCATION_CHANGE to R.string.out_reason_location_change).forEach { (r, label) ->
                    FormRadio(stringResource(label), selected == r, { onReason(r) }, Modifier.testTag(ForceSaleTags.reason(r)))
                }
            } else {
                FormNotice(stringResource(R.string.out_no_outlet_location), kind = BannerKind.Warning)
            }
            GeoPhotoBlock(capture, onShutter, note = stringResource(R.string.out_force_note))
            if (ForceSaleMissing.REASON in missing) FormError(stringResource(R.string.out_err_reason))
            if (ForceSaleMissing.PHOTO in missing) FormError(stringResource(R.string.out_err_photo))
            if (ForceSaleMissing.LOCATION_PERMISSION in missing) FormError(stringResource(R.string.out_err_permission))
        }
        FormBar(BarAction(stringResource(R.string.out_force_confirm), onConfirm, ForceSaleTags.CONFIRM, icon = AronIcons.ArrowForward))
    }
}
