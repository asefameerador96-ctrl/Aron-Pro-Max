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
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
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
 *
 * [interactive] (tso-manual A3, D-TSO-MAN-34; the TSO's My Team and Retailer only, the AMO stays on lite mode): the live
 * map is a full map that fills the screen, with zoom and pan, tap a pin for its bubble (name, last seen, source and
 * [MapPoint.note]), tilt and rotation off and no my-location layer; old fixes are faded, pins are coloured by
 * [MapPoint.kind], and [circle] is drawn and framed. It is still composed only while its screen is open, so no tile is
 * fetched in the background. Offline or without a map, the list shows exactly as in lite mode.
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
    interactive: Boolean = false,
    circle: MapCircle? = null,
) {
    val mode = mapModeOf(online, settings, keyPresent, points)
    if (mode == MapMode.LIVE && interactive) {
        InteractiveMap(points, nowMs, staleAfterMin, circle, cache, cacheKey, modifier.fillMaxSize())
        return
    }
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

/** The words under a pin's name: "Last seen 09:10 (15 min ago) · Visit", then the note on its own line; null when nothing. */
@Composable
private fun pointLine(p: MapPoint, nowMs: Long, staleAfterMin: Int): String? {
    val age = FixAge.of(p.fixAtMs, nowMs, staleAfterMin)
    val first = when {
        p.kind != MapPointKind.PERSON && age == null -> null
        age == null -> stringResource(R.string.map_no_fix)
        else -> stringResource(R.string.map_last_seen, localizedDigits(age.clock), localizedNumber(age.minutesAgo))
    }
    val source = p.source?.takeIf { it.isNotBlank() }?.let { s -> MapSources.labelRes(s)?.let { stringResource(it) } ?: s }
    val line = when {
        first == null -> source
        source == null -> first
        else -> stringResource(R.string.map_line_with_source, first, source)
    }
    return listOfNotNull(line, p.note?.takeIf { it.isNotBlank() }).joinToString("\n").ifEmpty { null }
}

/**
 * The full interactive map of [LiteMapScreen] (D-TSO-MAN-34): zoom and pan, tap for the bubble, tilt and rotation off, no
 * my-location layer, no toolbar. The first frame holds every located pin and the circle; a new answer moves it.
 */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
private fun InteractiveMap(points: List<MapPoint>, nowMs: Long, staleAfterMin: Int, circle: MapCircle?, cache: MapTileCache?, cacheKey: String, modifier: Modifier) {
    val shown = remember(points) { points.filter { it.located }.take(MAX_INTERACTIVE_MARKERS) }
    val frame = remember(shown, circle) { MapFrame.of(shown, circle) } ?: return
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(frame.lat, frame.lng), frame.zoom) }
    LaunchedEffect(frame) { camera.move(CameraUpdateFactory.newLatLngZoom(LatLng(frame.lat, frame.lng), frame.zoom)) }
    val scope = rememberCoroutineScope()
    val lines = shown.associate { it.id to pointLine(it, nowMs, staleAfterMin) }
    val ring = com.aktcl.aron.core.ui.LocalAronLook.current.purple
    GoogleMap(
        modifier = modifier.testTag(LiteMapTags.MAP),
        cameraPositionState = camera,
        googleMapOptionsFactory = { GoogleMapOptions().liteMode(false).mapToolbarEnabled(false) },
        properties = MapProperties(isMyLocationEnabled = false, isBuildingEnabled = false, isIndoorEnabled = false),
        uiSettings = MapUiSettings(
            mapToolbarEnabled = false, zoomControlsEnabled = true, myLocationButtonEnabled = false, compassEnabled = false,
            tiltGesturesEnabled = false, rotationGesturesEnabled = false, scrollGesturesEnabled = true, zoomGesturesEnabled = true,
            indoorLevelPickerEnabled = false,
        ),
    ) {
        if (circle != null) Circle(center = LatLng(circle.lat, circle.lng), radius = circle.radiusM.toDouble(), fillColor = ring.copy(alpha = 0.14f), strokeColor = ring, strokeWidth = 3f)
        shown.forEach { p ->
            key(p.id) {
                val stale = p.kind == MapPointKind.PERSON && FixAge.of(p.fixAtMs, nowMs, staleAfterMin).let { it == null || it.stale }
                Marker(
                    state = rememberUpdatedMarkerState(position = LatLng(p.lat!!, p.lng!!)), title = p.label, snippet = lines[p.id],
                    alpha = if (stale) STALE_ALPHA else 1f, icon = BitmapDescriptorFactory.defaultMarker(hueOf(p.kind)),
                )
            }
        }
        if (cache != null) MapEffect(cacheKey) { map ->
            map.setOnMapLoadedCallback {
                map.snapshot { bmp -> if (bmp != null) scope.launch(Dispatchers.IO) { runCatching { cache.put(cacheKey, encode(bmp)) } } }
            }
        }
    }
}

/** Pin colours: people azure, outlets red (the map default), the TSO's own fix green. */
internal fun hueOf(kind: MapPointKind): Float = when (kind) {
    MapPointKind.PERSON -> BitmapDescriptorFactory.HUE_AZURE
    MapPointKind.OUTLET -> BitmapDescriptorFactory.HUE_RED
    MapPointKind.SELF -> BitmapDescriptorFactory.HUE_GREEN
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
            // a person without a fix or with an old one is greyed; an outlet has no fix age and never is
            val faded = p.kind == MapPointKind.PERSON && (age == null || age.stale)
            Column(
                Modifier.fillMaxWidth().heightIn(min = AronTokens.Touch.Min).alpha(if (faded) STALE_ALPHA else 1f)
                    .padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.S).testTag(LiteMapTags.ROW + p.id),
            ) {
                Text(p.label, style = MaterialTheme.typography.titleMedium)
                pointLine(p, nowMs, staleAfterMin)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
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

/** The interactive map draws pins one by one and can zoom in, so it holds a whole Retailer answer (server cap 300). */
const val MAX_INTERACTIVE_MARKERS = 300
private val MAP_HEIGHT = 280.dp
private const val STALE_ALPHA = 0.5f
