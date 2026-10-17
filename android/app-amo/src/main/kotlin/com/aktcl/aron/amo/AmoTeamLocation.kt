package com.aktcl.aron.amo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aktcl.aron.core.map.LiteMapScreen
import com.aktcl.aron.core.map.MapPoint
import com.aktcl.aron.core.map.MapSettings
import com.aktcl.aron.core.map.MapTileCache
import com.aktcl.aron.core.map.MapsKey
import com.aktcl.aron.feature.amo.TeamLocations
import java.io.File

/**
 * F-AMO-016 Team Location (N-053 component): the SRs of the zone with their last synced fix, "last seen HH:MM (n min ago)" and its
 * source, greyed after 120 minutes. The map loads only here, in lite mode; offline the last image and the list stay. The answer is
 * kept per user for the offline list.
 */
@Composable
fun AmoTeamLocation(day: AmoDay, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var points by remember { mutableStateOf<List<MapPoint>?>(null) }
    var settings by remember { mutableStateOf(MapSettings()) }
    val cache = remember(day.userId) { MapTileCache(File(context.cacheDir, "map_amo_" + day.userId)) { settings.tileCacheBytes } }
    LaunchedEffect(day) {
        settings = day.mapSettings()
        val fetched = day.api.teamLocations(day.businessDate())
        val json = when (fetched) { is AmoFetch.Fresh -> fetched.json; is AmoFetch.Cached -> fetched.json; AmoFetch.Unavailable -> null }
        points = TeamLocations.parse(json).map { MapPoint(it.userId.toString(), it.name, it.lat, it.lng, it.atMs, it.source) }
    }
    val list = points ?: run { Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    LiteMapScreen(list, settings, day.online(), MapsKey.present(context), day.currentMs(), cache, "team_" + day.userId, modifier)
}
