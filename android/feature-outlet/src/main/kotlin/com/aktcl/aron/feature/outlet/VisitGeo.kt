package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.rules.Geo
import com.aktcl.aron.rules.GeoVerdict
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object GeoPanelTags {
    const val PANEL = "geo_panel"
    const val DISTANCE = "geo_distance"
    const val ARROW = "geo_arrow"
    const val MAP_SLOT = "geo_map_slot"
}

/**
 * What the Sale outlet-confirm step shows about the fix the visit already took (D-MAP-02): the distance, the radius and
 * the verdict, from data on the phone. Built from the visit's own fix; no extra location sample.
 */
data class VisitGeoInfo(
    val outletName: String,
    val outletLat: Double,
    val outletLng: Double,
    val radiusM: Int,
    val phoneLat: Double?,
    val phoneLng: Double?,
    val distanceM: Double?,
    val verdict: GeoVerdict,
) {
    val hasFix: Boolean get() = phoneLat != null && phoneLng != null
    val inRange: Boolean get() = verdict == GeoVerdict.IN_RANGE

    /** Initial bearing from the phone to the outlet in degrees clockwise from north, or null without a fix. */
    val bearingDeg: Double?
        get() = if (phoneLat != null && phoneLng != null) bearing(phoneLat, phoneLng, outletLat, outletLng) else null

    companion object {
        fun of(outlet: VisitOutlet, verdict: GeoVerdict, distanceM: Double?, phoneLat: Double?, phoneLng: Double?): VisitGeoInfo? {
            val lat = outlet.lat; val lng = outlet.lng
            if (lat == null || lng == null) return null
            val d = distanceM ?: if (phoneLat != null && phoneLng != null) Geo.haversineM(phoneLat, phoneLng, lat, lng) else null
            return VisitGeoInfo(outlet.name, lat, lng, outlet.radiusM, phoneLat, phoneLng, d, verdict)
        }

        fun bearing(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Double {
            val p1 = Math.toRadians(fromLat); val p2 = Math.toRadians(toLat); val dl = Math.toRadians(toLng - fromLng)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }
    }
}

/** The eight-wind compass sector of [deg]: 0 north, 1 north-east, ... 7 north-west. */
fun compassSector(deg: Double): Int = (((deg % 360.0 + 360.0) % 360.0 + 22.5) / 45.0).toInt() % 8

@Composable
private fun compassWord(deg: Double): String = stringResource(
    when (compassSector(deg)) {
        0 -> R.string.out_compass_n; 1 -> R.string.out_compass_ne; 2 -> R.string.out_compass_e; 3 -> R.string.out_compass_se
        4 -> R.string.out_compass_s; 5 -> R.string.out_compass_sw; 6 -> R.string.out_compass_w; else -> R.string.out_compass_nw
    },
)

/** The slot a host fills with the lite map: given the info, draws into [Modifier]; fully transparent until the image has loaded. */
typealias GeoMapSlot = @Composable (info: VisitGeoInfo, modifier: Modifier) -> Unit

/**
 * The geo panel of the confirm step (D-MAP-02), shown without a tap: the distance and limit line, the offline
 * [RangeView] picture (always drawn first, so it is what shows with no network or no tiles), the host's [mapSlot] laid
 * over it when the map is live, the bearing arrow with its compass word, and "Open in Maps" as the secondary link.
 * Nothing here waits for the map, starts a sensor or touches the network.
 */
@Composable
fun VisitGeoPanel(info: VisitGeoInfo, modifier: Modifier = Modifier, mapSlot: GeoMapSlot? = null, onOpenMaps: (() -> Unit)? = null) {
    val c = LocalAronColors.current
    Column(modifier.fillMaxWidth().testTag(GeoPanelTags.PANEL), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        info.distanceM?.let { d ->
            Text(
                stringResource(if (info.inRange) R.string.out_geo_inside else R.string.out_geo_outside, localizedNumber(Math.round(d)), localizedNumber(info.radiusM.toLong())),
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag(GeoPanelTags.DISTANCE),
            )
        }
        if (info.hasFix) {
            info.bearingDeg?.let { b ->
                val word = compassWord(b)
                val cd = stringResource(R.string.out_geo_arrow_cd, word)
                Row(
                    Modifier.testTag(GeoPanelTags.ARROW).semantics(mergeDescendants = true) { contentDescription = cd },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
                ) {
                    // ArrowForward points east (90 degrees), so it turns by bearing minus 90
                    Icon(AronIcons.ArrowForward, contentDescription = null, tint = c.textPrimary, modifier = Modifier.size(AronTokens.Size.Icon).rotate((b - 90.0).toFloat()))
                    Text(stringResource(R.string.out_geo_direction, word), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                }
            }
            Box(Modifier.fillMaxWidth()) {
                RangeView(info.outletLat, info.outletLng, info.radiusM, info.phoneLat!!, info.phoneLng!!, inRange = info.inRange, showScale = mapSlot == null) // the live map has its own scale
                if (mapSlot != null) {
                    val cd = stringResource(R.string.out_geo_map_cd)
                    Box(Modifier.fillMaxWidth().height(RangeViewHeight).testTag(GeoPanelTags.MAP_SLOT).semantics { contentDescription = cd }) {
                        mapSlot(info, Modifier.matchParentSize())
                    }
                }
            }
        }
        // the Maps app loads only on tap, is secondary, and never blocks Force Sale (N-041)
        if (onOpenMaps != null) AronTextButton(stringResource(R.string.out_open_maps), onOpenMaps, Modifier.testTag(OutletTags.MAP), icon = AronIcons.Map, color = c.textPrimary)
    }
}

