package com.aktcl.aron.amo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.BannerKind
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.feature.amo.R as AmoR
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
    var kept by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val sources = mapOf(
        "check_in" to stringResource(AmoR.string.amo_loc_source_check_in), "check_out" to stringResource(AmoR.string.amo_loc_source_check_out),
        "visit" to stringResource(AmoR.string.amo_loc_source_visit), "breadcrumb" to stringResource(AmoR.string.amo_loc_source_sync),
    )
    var settings by remember { mutableStateOf(MapSettings()) }
    val cache = remember(day.userId) { MapTileCache(File(context.cacheDir, "map_amo_" + day.userId)) { settings.tileCacheBytes } }
    LaunchedEffect(day) {
        settings = day.mapSettings()
        val fetched = day.api.teamLocations(day.businessDate())
        val json = fetched.jsonOrNull()
        kept = fetched is AmoFetch.Cached; failed = fetched.isMissing()
        points = TeamLocations.parse(json).map { MapPoint(it.userId.toString(), it.name, it.lat, it.lng, it.atMs, it.source?.let { s -> sources[s] ?: s }) }
    }
    val list = points ?: run { Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    Column(modifier.fillMaxSize()) {
        if (failed) AronBanner(stringResource(AmoR.string.amo_task_list_offline), kind = BannerKind.Warning)
        else if (kept) AronBanner(stringResource(AmoR.string.amo_task_list_kept), kind = BannerKind.Info)
        LiteMapScreen(list, settings, day.online(), MapsKey.present(context), day.currentMs(), cache, "team_" + day.userId + "_" + day.businessDate(), Modifier.weight(1f))
    }
}
