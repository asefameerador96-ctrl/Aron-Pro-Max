package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.feature.outlet.ForceSaleMissing
import com.aktcl.aron.feature.outlet.ForceSaleTags
import com.aktcl.aron.feature.outlet.GeoPhotoBlock
import com.aktcl.aron.feature.outlet.GeoPhotoState
import com.aktcl.aron.feature.outlet.R as OutR

/**
 * Manual Override (manual: the camera comes straight away, one photo, then the menu). Unlike the SR's Force Sale there is no reason question:
 * the AMO overrides because the outlet's position is wrong, and the photo's fix goes for approval as a location correction (F-AMO-005).
 */
@Composable
fun ManualOverrideContent(
    capture: GeoPhotoState,
    missing: Set<ForceSaleMissing>,
    onShutter: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            Text(stringResource(R.string.amo_cc_manual_override), style = MaterialTheme.typography.titleLarge, color = LocalAronColors.current.textPrimary)
            GeoPhotoBlock(capture, onShutter, note = stringResource(R.string.amo_mo_note))
            if (ForceSaleMissing.PHOTO in missing) AmoBanner(stringResource(OutR.string.out_err_photo), kind = BannerKind.Error)
            if (ForceSaleMissing.LOCATION_PERMISSION in missing) AmoBanner(stringResource(OutR.string.out_err_permission), kind = BannerKind.Error)
        }
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.Screen)) {
            AmoPrimary(stringResource(R.string.amo_mo_continue), onConfirm, Modifier.fillMaxWidth())
        }
    }
}
