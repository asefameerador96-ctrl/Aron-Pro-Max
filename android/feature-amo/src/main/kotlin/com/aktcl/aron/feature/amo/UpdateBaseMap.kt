package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.localizedNumber
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState

object UpdateBaseMapTags { const val MAP = "ub_map"; const val CONFIRM = "ub_confirm"; const val DISTANCE = "ub_distance" }

/**
 * "অবস্থান নিশ্চিত করুন" (64): the interactive map with the AMO's fix (blue) and the draggable pin (red) inside the circle of
 * `cfg.geo.update_base_max_distance_m`; a drop or a tap outside it lands on the circle's edge ([UpdateBasePoint.clamp]). The distance of the pin
 * from the AMO and the cap are written under the map; নিশ্চিত করুন saves. When the map is not drawable (offline, no key) [live] is false and the
 * steps of [UpdateBasePointContent] move the pin instead, so the save is never blocked by the map.
 */
@Composable
fun UpdateBaseMapContent(
    fixLat: Double, fixLng: Double, pin: Pair<Double, Double>, maxM: Int, live: Boolean, onPin: (Pair<Double, Double>) -> Unit,
    onConfirm: () -> Unit, confirmEnabled: Boolean, modifier: Modifier = Modifier, fallback: (@Composable () -> Unit)? = null,
) {
    val look = LocalAronLook.current
    val away = com.aktcl.aron.rules.Geo.haversineM(fixLat, fixLng, pin.first, pin.second).toLong()
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (live) {
                val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(fixLat, fixLng), 18f) }
                val pinState = rememberMarkerState(position = LatLng(pin.first, pin.second))
                LaunchedEffect(pin) {
                    pinState.position = LatLng(pin.first, pin.second)
                    camera.move(CameraUpdateFactory.newLatLng(LatLng(pin.first, pin.second)))
                }
                // a drag ends where it was dropped: clamp it to the circle and tell the host
                LaunchedEffect(pinState.dragState) {
                    if (pinState.dragState == com.google.maps.android.compose.DragState.END) onPin(UpdateBasePoint.clamp(fixLat, fixLng, pinState.position.latitude, pinState.position.longitude, maxM))
                }
                GoogleMap(
                    modifier = Modifier.fillMaxSize().testTag(UpdateBaseMapTags.MAP), cameraPositionState = camera,
                    properties = MapProperties(isMyLocationEnabled = false),
                    uiSettings = MapUiSettings(zoomControlsEnabled = true, myLocationButtonEnabled = false, compassEnabled = false, mapToolbarEnabled = false),
                    onMapClick = { onPin(UpdateBasePoint.clamp(fixLat, fixLng, it.latitude, it.longitude, maxM)) },
                ) {
                    Circle(center = LatLng(fixLat, fixLng), radius = maxM.toDouble(), fillColor = look.purple.copy(alpha = 0.12f), strokeColor = look.purple, strokeWidth = 3f)
                    Marker(state = remember { com.google.maps.android.compose.MarkerState(LatLng(fixLat, fixLng)) }, title = stringResource(R.string.amo_out_map_title), icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                    Marker(state = pinState, draggable = true, icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                }
            } else {
                Column(Modifier.fillMaxSize().padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                    AmoBanner(stringResource(R.string.amo_out_map_offline))
                    fallback?.invoke()
                }
            }
        }
        Text(
            stringResource(R.string.amo_out_map_distance, localizedNumber(away), localizedNumber(maxM.toLong())),
            Modifier.padding(horizontal = AronTokens.Space.Screen).testTag(UpdateBaseMapTags.DISTANCE), style = amoText(15, color = look.ink),
        )
        if (live) Text(stringResource(R.string.amo_out_map_hint), Modifier.padding(horizontal = AronTokens.Space.Screen), style = amoText(13, color = look.inkMuted))
        AmoPrimary(stringResource(R.string.amo_out_map_confirm), onConfirm, Modifier.fillMaxWidth().padding(AronTokens.Space.Screen).height(56.dp).testTag(UpdateBaseMapTags.CONFIRM), enabled = confirmEnabled)
    }
}
