package com.aktcl.aron.feature.tso.periphery

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import com.aktcl.aron.feature.tso.TsoIcons
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.common.LocaleDigits
import com.aktcl.aron.core.map.LiteMapScreen
import com.aktcl.aron.core.map.MapSettings
import com.aktcl.aron.core.map.MapTileCache
import com.aktcl.aron.core.map.MapsKey
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.tso.R
import com.aktcl.aron.feature.tso.TsoBanner
import com.aktcl.aron.feature.tso.TsoBannerKind
import com.aktcl.aron.feature.tso.TsoButton
import com.aktcl.aron.feature.tso.TsoDropdownField
import com.aktcl.aron.feature.tso.TsoError
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoLoading
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.leave.backOnly
import com.aktcl.aron.feature.tso.zoneName
import java.io.File
import kotlinx.coroutines.launch

object PeripheryTags {
    /** The refresh icon of Retailer: one new fix and one new search. */
    const val FIND = "tso_pe_find"
    const val ALLOW = "tso_pe_allow"
    const val STATUS = "tso_pe_status"
    const val LEGEND = "tso_pe_legend"
    const val ASOF = "tso_pe_asof"
    const val CACHED = "tso_pe_cached"
    const val COMPUTED = "tso_pe_computed"
    const val TRUNCATED = "tso_pe_truncated"
    const val MOCK = "tso_pe_mock"
    const val SETTINGS = "tso_pe_settings"
    const val ZONE = "tso_pe_zone"
    const val RADIUS = "tso_pe_radius"
    /** The Refresh action of My Team: one new read of the last synced fixes (never live tracking). */
    const val REFRESH = "tso_pe_refresh"
    const val FULL = "tso_pe_map_full"
    const val RECENTRE = "tso_pe_map_recentre"
}

/** The zone names of [zones] (the scope tree's names, else "Zone <id>"), read once per composition. */
@Composable
private fun zoneLabels(zones: List<Long>): Map<Long, String> = zones.associateWith { zoneName(it) }

/**
 * My Team (screenshot 28, D-TSO-MAN-34): the zone dropdown on top and the interactive map of the SRs' LAST SYNCED fixes
 * (never live tracking, no locate ping, D-170), grouped by route (legend), "last seen HH:mm (n min ago)" in each bubble,
 * greyed after `cfg.tso.team_location_max_age_min`, and the mock-fix chip of the day. Read on open and on Refresh only.
 * Offline the last answer and the map image as last seen stay, with the list.
 */
@Composable
fun TeamScreen(
    state: TeamState, online: () -> Boolean, nowMs: () -> Long, userKey: String, onBack: () -> Unit, modifier: Modifier = Modifier,
    settings: MapSettings = MapSettings(), nav: TsoNav = backOnly(onBack),
) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val language = LocalAppLanguage.current
    LaunchedEffect(state) { state.load() }
    val cache = remember(userKey) { MapTileCache(File(context.cacheDir, "map_tso_$userKey")) { settings.tileCacheBytes } }
    val names = zoneLabels(ui.zones)
    val live = online() && MapsKey.present(context)
    var full by rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = full) { full = false }
    TsoFrame(stringResource(R.string.tso_pe_my_teams), nav, homeButton = true, modifier = modifier, onBack = onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = if (full) 0.dp else AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            if (ui.zones.isNotEmpty() && !full) TsoDropdownField(
                ui.zones, ui.zoneId, { z -> state.pickZone(z); scope.launch { state.refresh() } }, { names[it] ?: it.toString() },
                Modifier.testTag(PeripheryTags.ZONE), placeholder = stringResource(R.string.tso_fs_select_zone),
            )
            when (val p = ui.phase) {
                TeamUi.Loading -> TsoLoading(Modifier.weight(1f))
                // the words already say "connect once and try again": the kit error state carries the retry that does it
                TeamUi.Unavailable -> TsoError(
                    stringResource(R.string.tso_mc_unavailable), stringResource(R.string.tso_retry), { scope.launch { state.refresh() } },
                    Modifier.weight(1f).testTag(PeripheryTags.STATUS),
                )
                is TeamUi.Shown -> {
                    if (p.result.fromCache) TsoBanner(stringResource(R.string.tso_offline_cached), Modifier.testTag(PeripheryTags.CACHED), kind = TsoBannerKind.Offline)
                    // D-PAR-TSO-07: the online map stands alone as in screenshot 28; the legend, "as of" and Refresh belong to the no-network list
                    if (!live) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val lg = legend(p.result.value, p.codes)
                            if (lg.isNotEmpty()) Text(localizedDigits(lg.joinToString("   ") { "${it.first}: ${it.second}" }), Modifier.testTag(PeripheryTags.LEGEND), style = MaterialTheme.typography.bodySmall, color = LocalAronLook.current.inkMuted)
                            Text(localizedDigits(stringResource(R.string.tso_as_of, p.result.asOf)), Modifier.testTag(PeripheryTags.ASOF), style = MaterialTheme.typography.bodySmall, color = LocalAronLook.current.inkMuted)
                        }
                        RefreshButton(stringResource(R.string.tso_pe_refresh), PeripheryTags.REFRESH, withText = true) { scope.launch { state.refresh() } }
                    }
                    val points = teamPoints(p.result.value, p.codes) { n -> resources.getString(R.string.tso_pe_mock_today, LocaleDigits.localize(n.toString(), language)) }
                    MapCard(live, full, { full = !full }, Modifier.weight(1f).fillMaxWidth()) {
                        LiteMapScreen(
                            points, settings, online(), MapsKey.present(context), nowMs(), cache, "team_${userKey}_${ui.zoneId}", Modifier,
                            staleAfterMin = ui.config.staleAfterMin, interactive = true,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The map card of My Team and Retailer outlets (screenshots 28, 29, D-PAR-TSO-06): the online street map fills the rounded card with
 * the fullscreen and recentre buttons on it. Recentre puts the camera back on the frame of the points (the map is drawn again; no
 * location layer is turned on, so nothing listens to the GPS). Fullscreen asks the screen to give the map the whole page.
 */
@Composable
private fun MapCard(live: Boolean, full: Boolean, onFull: () -> Unit, modifier: Modifier = Modifier, map: @Composable () -> Unit) {
    var recentred by remember { mutableIntStateOf(0) }
    val look = LocalAronLook.current
    Box(modifier.clip(RoundedCornerShape(if (full) 0.dp else 20.dp))) {
        androidx.compose.runtime.key(recentred) { map() }
        if (live) Column(Modifier.align(Alignment.TopEnd).padding(AronTokens.Space.S), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            MapButton(if (full) TsoIcons.FullscreenExit else TsoIcons.Fullscreen, stringResource(if (full) R.string.tso_pe_map_exit_full else R.string.tso_pe_map_full), PeripheryTags.FULL, look.card, look.ink, onFull)
            MapButton(TsoIcons.Recentre, stringResource(R.string.tso_pe_map_recentre), PeripheryTags.RECENTRE, look.card, look.ink) { recentred++ }
        }
    }
}

@Composable
private fun MapButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String, bg: androidx.compose.ui.graphics.Color, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(bg).clickable(role = Role.Button, onClickLabel = label, onClick = onClick).semantics { contentDescription = label }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp)) }
}

/** A round refresh action (48 dp target); [withText] adds the word beside the icon. */
@Composable
private fun RefreshButton(label: String, tag: String, withText: Boolean = false, onClick: () -> Unit) {
    val look = LocalAronLook.current
    Row(
        Modifier.heightIn(min = 48.dp).clip(CircleShape).background(look.card).clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }.testTag(tag).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AronIcons.Refresh, contentDescription = null, tint = look.ink, modifier = Modifier.size(24.dp))
        if (withText) Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = look.ink)
    }
}

/**
 * Retailer (screenshot 29, D-TSO-MAN-34): "Zone" and "Radius" dropdowns side by side, then the interactive map with the
 * outlets inside the radius circle around the TSO's own fix (green pin). One balanced fix is taken when the screen opens
 * and when the rep picks a zone or a radius, plus the refresh icon; never a stream. Offline the phone works the answer out
 * from the bundle with the server's rule (it says so). The radii come from the config (50, 100, 300 by default).
 */
@Composable
fun RetailerScreen(
    state: RetailerState, online: () -> Boolean, nowMs: () -> Long, userKey: String, onBack: () -> Unit, modifier: Modifier = Modifier,
    settings: MapSettings = MapSettings(), nav: TsoNav = backOnly(onBack), findOnOpen: Boolean = false,
) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(state) {
        state.loadZones()
        if (findOnOpen && state.ui.value.phase == RetailerUi.Pick) state.find()
    }
    val cache = remember(userKey) { MapTileCache(File(context.cacheDir, "map_tso_$userKey")) { settings.tileCacheBytes } }
    // the permission dialog needs the activity's result registry; without one (a preview, a test host) Allow offers the settings
    val askPermission = if (androidx.activity.compose.LocalActivityResultRegistryOwner.current != null) {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) scope.launch { state.find() } else state.permissionRefused() }
    } else null
    val phase = ui.phase
    val resources = LocalResources.current
    val language = LocalAppLanguage.current
    val names = zoneLabels(ui.zones)
    val look = LocalAronLook.current
    val live = online() && MapsKey.present(context)
    var full by rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = full) { full = false }
    TsoFrame(stringResource(R.string.tso_pe_outlets_title), nav, homeButton = true, modifier = modifier, onBack = onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = if (full) 0.dp else AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            if (!full) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                Box(Modifier.weight(1.7f)) {
                    TsoDropdownField(
                        ui.zones, ui.zoneId, { z -> state.pickZone(z); scope.launch { state.find() } }, { names[it] ?: it.toString() },
                        Modifier.testTag(PeripheryTags.ZONE), label = stringResource(R.string.tso_mc_zone_label), placeholder = stringResource(R.string.tso_fs_select_zone),
                    )
                }
                Box(Modifier.weight(1f)) {
                    TsoDropdownField(
                        ui.radii, ui.radiusM, { r -> state.pickRadius(r); scope.launch { state.find() } }, { LocaleDigits.localize(it.toString(), language) },
                        Modifier.testTag(PeripheryTags.RADIUS), label = stringResource(R.string.tso_pe_radius),
                    )
                }
            }
            when (phase) {
                RetailerUi.Locating -> TsoBanner(stringResource(R.string.tso_pe_locating), Modifier.testTag(PeripheryTags.STATUS), kind = TsoBannerKind.Info)
                RetailerUi.Loading -> TsoBanner(stringResource(R.string.tso_pe_loading), Modifier.testTag(PeripheryTags.STATUS), kind = TsoBannerKind.Info)
                RetailerUi.NeedsPermission -> {
                    TsoBanner(stringResource(R.string.tso_pe_need_permission), Modifier.testTag(PeripheryTags.STATUS), kind = TsoBannerKind.Warning)
                    TsoButton(stringResource(R.string.tso_pe_allow), { askPermission?.launch(Manifest.permission.ACCESS_FINE_LOCATION) ?: state.permissionRefused() }, Modifier.testTag(PeripheryTags.ALLOW))
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
            val shown = phase as? RetailerUi.Shown
            // D-PAR-TSO-07: on the online map the info card, the count caption and the refresh button are gone (screenshot 29); they stay for the no-network list
            if (!live && shown?.result?.computedOnPhone == true) TsoBanner(stringResource(R.string.tso_pe_computed), Modifier.testTag(PeripheryTags.COMPUTED), kind = TsoBannerKind.Offline)
            else if (!live && shown?.result?.fromCache == true) TsoBanner(stringResource(R.string.tso_offline_cached), Modifier.testTag(PeripheryTags.CACHED), kind = TsoBannerKind.Offline)
            if (shown?.result?.value?.truncated == true) TsoBanner(stringResource(R.string.tso_pe_truncated, shown.result.value.items.size), Modifier.testTag(PeripheryTags.TRUNCATED), kind = TsoBannerKind.Info)
            if (!live || shown == null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val line = if (shown == null) stringResource(R.string.tso_pe_pick_hint)
                else stringResource(R.string.tso_pe_found, localizedNumber(shown.result.value.items.size.toLong()), localizedNumber(shown.radiusM.toLong())) + "  ·  " + stringResource(R.string.tso_as_of, shown.result.asOf)
                Text(localizedDigits(line), Modifier.weight(1f).testTag(PeripheryTags.ASOF), style = MaterialTheme.typography.bodySmall, color = look.inkMuted)
                RefreshButton(stringResource(R.string.tso_pe_find), PeripheryTags.FIND) { scope.launch { state.find() } }
            }
            if (shown != null) {
                val metres: (Int) -> String = { d -> resources.getString(R.string.tso_unit_m, LocaleDigits.localize(d.toString(), language)) }
                MapCard(live, full, { full = !full }, Modifier.weight(1f).fillMaxWidth()) {
                    LiteMapScreen(
                        nearbyPoints(shown.result.value, metres, shown.lat to shown.lng, resources.getString(R.string.tso_pe_me)), settings, online(), MapsKey.present(context), nowMs(), cache,
                        "near_${userKey}_${shown.zoneId}_${shown.radiusM}_${positionKey(shown.lat, shown.lng)}", Modifier, interactive = true, circle = nearbyCircle(shown),
                    )
                }
            }
        }
    }
}
