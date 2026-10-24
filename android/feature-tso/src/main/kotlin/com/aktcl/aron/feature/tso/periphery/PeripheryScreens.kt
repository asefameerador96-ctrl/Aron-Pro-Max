package com.aktcl.aron.feature.tso.periphery

import android.Manifest
import com.aktcl.aron.feature.tso.zoneName
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.map.LiteMapScreen
import com.aktcl.aron.core.map.MapSettings
import com.aktcl.aron.core.map.MapTileCache
import com.aktcl.aron.core.map.MapsKey
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.tso.R
import java.io.File
import kotlinx.coroutines.launch
import com.aktcl.aron.core.ui.aron.AronCard
import com.aktcl.aron.core.ui.aron.AronListRow
import com.aktcl.aron.feature.tso.TsoButton
import com.aktcl.aron.core.ui.aron.AronTabs
import com.aktcl.aron.feature.tso.TsoAction
import com.aktcl.aron.feature.tso.TsoBanner
import com.aktcl.aron.feature.tso.TsoBannerKind
import com.aktcl.aron.feature.tso.TsoBottomBar
import com.aktcl.aron.feature.tso.TsoEmpty
import com.aktcl.aron.feature.tso.TsoError
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoSectionTitle
import com.aktcl.aron.feature.tso.TsoStepper

object PeripheryTags {
    const val TEAM = "tso_pe_team"
    const val RETAILER = "tso_pe_retailer"
    const val FIND = "tso_pe_find"
    const val ALLOW = "tso_pe_allow"
    const val STATUS = "tso_pe_status"
    const val LEGEND = "tso_pe_legend"
    const val ASOF = "tso_pe_asof"
    const val CACHED = "tso_pe_cached"
    const val TRUNCATED = "tso_pe_truncated"
    const val MOCK = "tso_pe_mock"
    const val SETTINGS = "tso_pe_settings"
    fun zone(id: Long) = "tso_pe_zone_$id"
    fun radius(m: Int) = "tso_pe_radius_$m"
}

@Composable
fun PeripheryHub(onTeam: () -> Unit, onRetailer: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    TsoFrame(stringResource(R.string.tso_drawer_periphery), onBack, modifier, tile = com.aktcl.aron.feature.home.RoleHomes.TsoTile.PERIPHERY) {
        Column(Modifier.fillMaxSize().padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            com.aktcl.aron.feature.tso.TsoMenuGrid(
                listOf(
                    com.aktcl.aron.feature.tso.TsoMenuTile(stringResource(R.string.tso_pe_team), "team", PeripheryTags.TEAM, onTeam),
                    com.aktcl.aron.feature.tso.TsoMenuTile(stringResource(R.string.tso_pe_retailer), "retailer", PeripheryTags.RETAILER, onRetailer),
                ),
            )
        }
    }
}

@Composable
private fun ZonePicker(zones: List<Long>, zoneId: Long?, onPick: (Long) -> Unit) {
    zones.forEach { z -> AronListRow(zoneName(z), Modifier.testTag(PeripheryTags.zone(z)), onClick = { onPick(z) }, trailing = if (z == zoneId) "✓" else null) }
}

/** F-TSO-011 My Team: the SRs' last synced fixes on a lite map, grouped by route with a legend; offline the last list and image stay. */
@Composable
fun TeamScreen(state: TeamState, online: () -> Boolean, nowMs: () -> Long, userKey: String, onBack: () -> Unit, modifier: Modifier = Modifier, settings: MapSettings = MapSettings()) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(state) { state.load() }
    val cache = androidx.compose.runtime.remember(userKey) { MapTileCache(File(context.cacheDir, "map_tso_$userKey")) { settings.tileCacheBytes } }
    TsoFrame(stringResource(R.string.tso_pe_team), onBack, modifier, tile = "team") {
        Column(Modifier.fillMaxSize()) {
            if (ui.zones.size > 1) Column(Modifier.padding(horizontal = AronTokens.Space.Screen)) { ZonePicker(ui.zones, ui.zoneId) { state.pickZone(it); scope.launch { state.refresh() } } }
            when (val p = ui.phase) {
                TeamUi.Loading -> com.aktcl.aron.feature.tso.TsoLoading()
                // the words already say "connect once and try again": the kit error state carries the retry that does it
                TeamUi.Unavailable -> com.aktcl.aron.feature.tso.TsoError(
                    stringResource(R.string.tso_mc_unavailable), stringResource(R.string.tso_retry), { scope.launch { state.refresh() } },
                    Modifier.weight(1f).testTag(PeripheryTags.STATUS),
                )
                is TeamUi.Shown -> {
                    if (p.result.fromCache) TsoBanner(stringResource(R.string.tso_offline_cached), Modifier.testTag(PeripheryTags.CACHED), kind = TsoBannerKind.Offline)
                    val lg = legend(p.result.value, p.codes)
                    if (lg.isNotEmpty()) Text(lg.joinToString("   ") { "${it.first}: ${it.second}" }.let { localizedDigits(it) }, Modifier.padding(horizontal = AronTokens.Space.Screen).testTag(PeripheryTags.LEGEND), style = MaterialTheme.typography.bodySmall)
                    Text(localizedDigits(stringResource(R.string.tso_as_of, p.result.asOf)), Modifier.padding(horizontal = AronTokens.Space.Screen).testTag(PeripheryTags.ASOF), style = MaterialTheme.typography.bodySmall)
                    LiteMapScreen(teamPoints(p.result.value, p.codes), settings, online(), MapsKey.present(context), nowMs(), cache, "team_${userKey}_${ui.zoneId}", Modifier.weight(1f))
                }
            }
        }
    }
}

/** F-TSO-012 Retailer: zone and radius (50, 100 or 300 m) around one fix taken now; capped markers; phone numbers only as the server sends them. */
@Composable
fun RetailerScreen(state: RetailerState, online: () -> Boolean, nowMs: () -> Long, userKey: String, onBack: () -> Unit, modifier: Modifier = Modifier, settings: MapSettings = MapSettings()) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(state) { state.loadZones() }
    val cache = androidx.compose.runtime.remember(userKey) { MapTileCache(File(context.cacheDir, "map_tso_$userKey")) { settings.tileCacheBytes } }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) scope.launch { state.find() } else state.permissionRefused() }
    val phase = ui.phase
    val resources = LocalResources.current
    val language = com.aktcl.aron.core.ui.LocalAppLanguage.current
    TsoFrame(stringResource(R.string.tso_pe_retailer), onBack, modifier, tile = com.aktcl.aron.feature.home.RoleHomes.TsoTile.PERIPHERY) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = AronTokens.Space.Screen).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                TsoSectionTitle(stringResource(R.string.tso_mc_zone_label))
                ZonePicker(ui.zones, ui.zoneId) { state.pickZone(it) }
                TsoSectionTitle(stringResource(R.string.tso_pe_radius))
                AronTabs(RADIUS_OPTIONS_M.map { stringResource(R.string.tso_unit_m, localizedNumber(it.toLong())) }, RADIUS_OPTIONS_M.indexOf(ui.radiusM), { state.pickRadius(RADIUS_OPTIONS_M[it]) }, Modifier.testTag("tso_pe_radius"))
                TsoButton(stringResource(R.string.tso_pe_find), { scope.launch { state.find() } }, Modifier.testTag(PeripheryTags.FIND))
                when (phase) {
                    RetailerUi.Locating -> TsoBanner(stringResource(R.string.tso_pe_locating), Modifier.testTag(PeripheryTags.STATUS), kind = TsoBannerKind.Info)
                    RetailerUi.Loading -> TsoBanner(stringResource(R.string.tso_pe_loading), Modifier.testTag(PeripheryTags.STATUS), kind = TsoBannerKind.Info)
                    RetailerUi.NeedsPermission -> {
                        TsoBanner(stringResource(R.string.tso_pe_need_permission), Modifier.testTag(PeripheryTags.STATUS), kind = TsoBannerKind.Warning)
                        TsoButton(stringResource(R.string.tso_pe_allow), { askPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }, Modifier.testTag(PeripheryTags.ALLOW))
                        if (ui.permissionDenied) TsoButton(stringResource(R.string.tso_pe_settings), {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }, Modifier.testTag(PeripheryTags.SETTINGS))
                    }
                    RetailerUi.LocationOff -> TsoBanner(stringResource(R.string.tso_pe_location_off), Modifier.testTag(PeripheryTags.STATUS), kind = TsoBannerKind.Warning)
                    RetailerUi.OutOfArea -> TsoBanner(stringResource(R.string.tso_pe_out_of_area), Modifier.testTag(PeripheryTags.STATUS), kind = TsoBannerKind.Warning)
                    RetailerUi.NoFix -> TsoBanner(stringResource(R.string.tso_pe_no_fix), Modifier.testTag(PeripheryTags.STATUS), kind = TsoBannerKind.Warning)
                    RetailerUi.Unavailable -> TsoBanner(stringResource(R.string.tso_mc_unavailable), Modifier.testTag(PeripheryTags.STATUS), kind = TsoBannerKind.Offline)
                    else -> Unit
                }
                if (ui.mockWarning) TsoBanner(stringResource(R.string.tso_pe_mock), Modifier.testTag(PeripheryTags.MOCK), kind = TsoBannerKind.Warning)
            }
            if (phase is RetailerUi.Shown) {
                val n = phase.result.value
                if (phase.result.fromCache) TsoBanner(stringResource(R.string.tso_offline_cached), Modifier.testTag(PeripheryTags.CACHED), kind = TsoBannerKind.Offline)
                if (n.truncated) TsoBanner(stringResource(R.string.tso_pe_truncated, MAX_SHOWN), Modifier.testTag(PeripheryTags.TRUNCATED), kind = TsoBannerKind.Info)
                Text(localizedDigits(stringResource(R.string.tso_as_of, phase.result.asOf)), Modifier.padding(horizontal = AronTokens.Space.Screen).testTag(PeripheryTags.ASOF), style = MaterialTheme.typography.bodySmall)
                LiteMapScreen(nearbyPoints(n) { d -> resources.getString(R.string.tso_unit_m, com.aktcl.aron.core.common.LocaleDigits.localize(d.toString(), language)) }, settings, online(), MapsKey.present(context), nowMs(), cache, "near_${userKey}_${phase.zoneId}_${phase.radiusM}_${positionKey(phase.lat, phase.lng)}", Modifier.weight(1f))
            }
        }
    }
}
