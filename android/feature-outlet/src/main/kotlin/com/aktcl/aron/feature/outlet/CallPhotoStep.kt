package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronPrimaryButton

object CallPhotoTags {
    const val CONTINUE = "call_photo_continue"
    const val SKIP = "call_photo_skip"
}

/** The one code a reasoned skip carries until the contract defines the list (owner Q8: "yes, with a reasoned skip"; D-SR-MAN-S4-07). */
const val CALL_PHOTO_SKIP_CAMERA = "camera_unavailable"

/**
 * The outlet photo at call start (SR manual s8 / 20, D-SR-MAN-S4-07): shown after হ্যাঁ, inside the geofence. The shutter takes the photo and
 * the fix together ([GeoPhotoBlock], one retake); the call continues once both exist. The camera never blocks the sale: [onSkip] lets the
 * rep go on without a photo. TRAIN 1 records NOTHING about the skip or the photo against the visit (no contract field yet): the step is off
 * in production (`SrSaleKit.callPhotoRequired = false`). TRAIN 2 must persist the photo UUID once per visit, record the skip with a mandatory
 * reason ([CALL_PHOTO_SKIP_CAMERA] is the first code), and link the media record to the visit.
 */
@Composable
fun CallPhotoStep(state: GeoPhotoState, onShutter: () -> Unit, onContinue: () -> Unit, onSkip: (() -> Unit)?, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Column(
        modifier.fillMaxSize().background(look.page).verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Text(stringResource(R.string.out_call_photo_title), style = MaterialTheme.typography.titleLarge, color = look.purple)
        GeoPhotoBlock(state, onShutter, note = stringResource(R.string.out_call_photo_note))
        AronPrimaryButton(stringResource(R.string.out_call_photo_continue), onContinue, Modifier.fillMaxWidth().testTag(CallPhotoTags.CONTINUE), enabled = state.complete)
        if (onSkip != null) AronTextButton(stringResource(R.string.out_call_photo_skip), onSkip, Modifier.testTag(CallPhotoTags.SKIP))
    }
}
