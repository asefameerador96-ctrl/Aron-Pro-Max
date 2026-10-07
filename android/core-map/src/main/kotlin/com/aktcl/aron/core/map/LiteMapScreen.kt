package com.aktcl.aron.core.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object LiteMapTags {
    const val MAP = "map_lite"
    const val SNAPSHOT = "map_snapshot"
    const val LIST = "map_list"
    const val ROW = "map_row_"
    const val BANNER = "map_banner"
}

/** Why the screen shows no live map (the list is always there). */
enum class MapMode { LIVE, OFFLINE, UNAVAILABLE }

/**
 * Decides once per composition; the Maps SDK is touched only for [MapMode.LIVE], so with the network off, no key, a
 * MapLibre setting (not bundled) or nothing located, no map class loads and no tile is requested.
 */
fun mapModeOf(online: Boolean, settings: MapSettings, keyPresent: Boolean, points: List<MapPoint>): MapMode = when {
    !online -> MapMode.OFFLINE
    !settings.drawable || !keyPresent || points.none { it.located } -> MapMode.UNAVAILABLE
    else -> MapMode.LIVE
}

/**
 * N-053: the shared map screen body for the AMO and TSO apps (Team Location, My Team, Retailer) and any later map. The
 * host opens it only from its tile (an Activity or a destination composed on demand); there is no map anywhere else.
 * Live: Google Maps lite mode (one static image, 2D, no gestures, no continuous location), capped markers, and the
 * rendered image saved to [cache] when it has loaded. Offline: the image as last seen, if any, and the list with the
 * age of each fix ("last seen HH:MM (n min ago)", greyed after [staleAfterMin]). The list shows in every mode.
 *
 * [cacheKey] names the view (screen plus scope) so the image of one zone never shows for another. [nowMs] is trusted time.
 */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun LiteMapScreen(
    points: List<MapPoint>,
    settings: MapSettings,
    online: Boolean,
    keyPresent: Boolean,
    nowMs: Long,
    cache: MapTileCache?,
    cacheKey: String,
    modifier: Modifier = Modifier,
    staleAfterMin: Int = FixAge.DEFAULT_STALE_AFTER_MIN,
) {
    val mode = mapModeOf(online, settings, keyPresent, points)
    Column(modifier.fillMaxSize()) {
        when (mode) {
            MapMode.LIVE -> {
                val shown = remember(points) { points.filter { it.located }.take(MAX_MARKERS) }
                val frame = remember(shown) { MapFrame.of(shown) }!!
                val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(frame.lat, frame.lng), frame.zoom) }
                // A refreshed list moves the frame too: lite mode has no gestures, so a point outside it would be invisible.
                LaunchedEffect(frame) { camera.move(CameraUpdateFactory.newLatLngZoom(LatLng(frame.lat, frame.lng), frame.zoom)) }
                val scope = rememberCoroutineScope()
                GoogleMap(
                    modifier = Modifier.fillMaxWidth().height(MAP_HEIGHT).testTag(LiteMapTags.MAP),
                    cameraPositionState = camera,
                    googleMapOptionsFactory = { GoogleMapOptions().liteMode(true).mapToolbarEnabled(false) },
                    uiSettings = MapUiSettings(mapToolbarEnabled = false, zoomControlsEnabled = false, myLocationButtonEnabled = false, tiltGesturesEnabled = false),
                ) {
                    shown.forEach { p -> key(p.id) { Marker(state = rememberUpdatedMarkerState(position = LatLng(p.lat!!, p.lng!!)), title = p.label) } }
                    if (cache != null) MapEffect(cacheKey) { map ->
                        map.setOnMapLoadedCallback {
                            map.snapshot { bmp -> if (bmp != null) scope.launch(Dispatchers.IO) { runCatching { cache.put(cacheKey, encode(bmp)) } } }
                        }
                    }
                }
                if (points.size > shown.size) AronBanner(stringResource(R.string.map_capped, localizedNumber(shown.size.toLong())), Modifier.testTag(LiteMapTags.BANNER))
            }
            MapMode.OFFLINE -> {
                // Disk read and decode off the main thread; the list shows at once and the image follows.
                val image by produceState<Bitmap?>(null, cacheKey, cache) {
                    value = withContext(Dispatchers.IO) { cache?.get(cacheKey)?.let { f -> runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() } }
                }
                image?.let { Image(it.asImageBitmap(), stringResource(R.string.map_last_seen_image), Modifier.fillMaxWidth().height(MAP_HEIGHT).testTag(LiteMapTags.SNAPSHOT), contentScale = ContentScale.Crop) }
                AronBanner(stringResource(R.string.map_offline), Modifier.testTag(LiteMapTags.BANNER), BannerKind.Warning)
            }
            MapMode.UNAVAILABLE -> AronBanner(stringResource(R.string.map_unavailable), Modifier.testTag(LiteMapTags.BANNER), BannerKind.Info)
        }
        PointList(points, nowMs, staleAfterMin, Modifier.weight(1f))
    }
}

@Composable
private fun PointList(points: List<MapPoint>, nowMs: Long, staleAfterMin: Int, modifier: Modifier) {
    if (points.isEmpty()) {
        Text(stringResource(R.string.map_list_empty), modifier.padding(AronTokens.Space.L).testTag(LiteMapTags.LIST))
        return
    }
    LazyColumn(modifier.testTag(LiteMapTags.LIST)) {
        items(points, key = { it.id }) { p ->
            val age = FixAge.of(p.fixAtMs, nowMs, staleAfterMin)
            Column(
                Modifier.fillMaxWidth().heightIn(min = AronTokens.Touch.Min).alpha(if (age == null || age.stale) STALE_ALPHA else 1f)
                    .padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.S).testTag(LiteMapTags.ROW + p.id),
            ) {
                Text(p.label, style = MaterialTheme.typography.titleMedium)
                val line = when {
                    age == null -> stringResource(R.string.map_no_fix)
                    else -> stringResource(R.string.map_last_seen, localizedDigits(age.clock), localizedNumber(age.minutesAgo))
                }
                Text(if (p.source.isNullOrBlank()) line else stringResource(R.string.map_line_with_source, line, p.source), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun encode(bmp: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
    @Suppress("DEPRECATION")
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    bmp.compress(format, 70, out)
    out.toByteArray()
}

/** Lite mode draws every marker into one image; more than this is clutter on a 360 dp screen and costs memory. */
const val MAX_MARKERS = 100
private val MAP_HEIGHT = 280.dp
private const val STALE_ALPHA = 0.5f
