package com.aktcl.aron.core.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.delay

object OutletLiteMapTags {
    const val MAP = "outlet_lite_map"
}

/** Whether the inline outlet map may be drawn at all: online, a key in the build and a drawable provider (D-MAP-02). */
fun outletMapLive(online: Boolean, settings: MapSettings, keyPresent: Boolean): Boolean = online && keyPresent && settings.drawable

/**
 * The frame of the inline outlet map: centred on the outlet and wide enough for the radius circle and, when there is a
 * fix, the phone. Lite mode has no gestures, so the first frame must hold everything the rep needs to see.
 */
fun outletMapFrame(outletLat: Double, outletLng: Double, radiusM: Int, phoneLat: Double?, phoneLng: Double?): Pair<LatLng, Float> {
    val dLat = Math.toDegrees(radiusM / EARTH_RADIUS_M)
    val dLng = Math.toDegrees(radiusM / (EARTH_RADIUS_M * Math.cos(Math.toRadians(outletLat))))
    val pts = mutableListOf(
        MapPoint("n", "", outletLat + dLat, outletLng), MapPoint("s", "", outletLat - dLat, outletLng),
        MapPoint("e", "", outletLat, outletLng + dLng), MapPoint("w", "", outletLat, outletLng - dLng),
    )
    if (phoneLat != null && phoneLng != null) pts += MapPoint("p", "", phoneLat, phoneLng)
    val f = MapFrame.of(pts)!!
    // keep the pin at the middle of the picture so the radius circle is centred on it
    val centre = if (phoneLat != null && phoneLng != null) {
        val spanLat = Math.abs(phoneLat - outletLat); val spanLng = Math.abs(phoneLng - outletLng)
        if (spanLat > dLat || spanLng > dLng) LatLng(f.lat, f.lng) else LatLng(outletLat, outletLng)
    } else LatLng(outletLat, outletLng)
    return centre to f.zoom
}

/**
 * D-MAP-02: the inline map on the Sale outlet-confirm step. Google Maps lite mode: one static image, 2D, no gestures, no
 * toolbar, no continuous location. The outlet pin, its radius circle and the phone's fix (the one the visit already
 * took). It draws nothing unless [live]; it stays transparent until the image has loaded, and gives up after
 * [GIVE_UP_MS], so the panel under it (distance and bearing arrow) is what the rep sees without tiles. It never blocks
 * the sale. The SDK's own tile cache is left on.
 */
@Composable
fun OutletLiteMap(
    name: String,
    outletLat: Double,
    outletLng: Double,
    radiusM: Int,
    phoneLat: Double?,
    phoneLng: Double?,
    live: Boolean,
    ring: Color,
    phoneLabel: String,
    modifier: Modifier = Modifier,
) {
    if (!live) return
    var loaded by remember { mutableStateOf(false) }
    var gaveUp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(GIVE_UP_MS); if (!loaded) gaveUp = true }
    if (gaveUp) return
    val (centre, zoom) = remember(outletLat, outletLng, radiusM, phoneLat, phoneLng) { outletMapFrame(outletLat, outletLng, radiusM, phoneLat, phoneLng) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(centre, zoom) }
    LaunchedEffect(centre, zoom) { camera.move(CameraUpdateFactory.newLatLngZoom(centre, zoom)) }
    GoogleMap(
        modifier = modifier.alpha(if (loaded) 1f else 0f).testTag(OutletLiteMapTags.MAP),
        cameraPositionState = camera,
        googleMapOptionsFactory = { GoogleMapOptions().liteMode(true).mapToolbarEnabled(false) },
        uiSettings = MapUiSettings(
            mapToolbarEnabled = false, zoomControlsEnabled = false, myLocationButtonEnabled = false,
            tiltGesturesEnabled = false, scrollGesturesEnabled = false, zoomGesturesEnabled = false, rotationGesturesEnabled = false,
        ),
        onMapLoaded = { loaded = true },
    ) {
        val outlet = LatLng(outletLat, outletLng)
        Marker(state = rememberUpdatedMarkerState(position = outlet), title = name)
        Circle(center = outlet, radius = radiusM.toDouble(), fillColor = ring.copy(alpha = 0.16f), strokeColor = ring, strokeWidth = 3f)
        if (phoneLat != null && phoneLng != null) {
            Marker(
                state = rememberUpdatedMarkerState(position = LatLng(phoneLat, phoneLng)), title = phoneLabel,
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
            )
        }
    }
}

private const val EARTH_RADIUS_M = 6_371_000.0
const val GIVE_UP_MS = 10_000L
