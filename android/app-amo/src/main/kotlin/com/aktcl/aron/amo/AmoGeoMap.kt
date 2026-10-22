package com.aktcl.aron.amo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.map.MapSettings
import com.aktcl.aron.core.map.MapsKey
import com.aktcl.aron.core.map.OutletLiteMap
import com.aktcl.aron.core.map.outletMapLive
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.feature.outlet.GeoMapSlot

/**
 * D-MAP-02: the lite map for the Control Call outlet-check step (the same rules as the SR app). Null (no map composed, no Maps class loaded, no tile
 * requested) when the phone is offline, the build has no Maps key or the provider is not drawable; the confirm step then
 * shows the offline range picture, the distance and the bearing arrow. Read once per composition of that step.
 */
@Composable
fun rememberAmoOutletMapSlot(online: Boolean): GeoMapSlot? {
    val context = LocalContext.current
    val live = remember(online) { outletMapLive(online, MapSettings(), MapsKey.present(context)) }
    if (!live) return null
    val ring = LocalAronColors.current.accent
    val you = stringResource(com.aktcl.aron.feature.outlet.R.string.out_geo_you)
    return remember(ring, you) {
        { info, modifier ->
            OutletLiteMap(info.outletName, info.outletLat, info.outletLng, info.radiusM, info.phoneLat, info.phoneLng, live = true, ring = ring, phoneLabel = you, modifier = modifier)
        }
    }
}
