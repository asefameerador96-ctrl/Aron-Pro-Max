package com.aktcl.aron.feature.tso

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.core.ui.aron.AronCard
import com.aktcl.aron.core.ui.aron.AronHomeHeader
import com.aktcl.aron.core.ui.aron.AronScaffold
import com.aktcl.aron.core.ui.aron.AronTile
import com.aktcl.aron.core.ui.aron.AronTileGrid
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.core.ui.R as CoreR
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.DeviceHealth
import com.aktcl.aron.feature.home.HomeHeader
import com.aktcl.aron.feature.home.HomeTags
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.R
import com.aktcl.aron.feature.home.RoleBanner
import com.aktcl.aron.feature.home.RoleHero
import com.aktcl.aron.feature.home.RoleHomeTags
import com.aktcl.aron.feature.home.RoleTile

/**
 * The TSO Home in the existing-Aron look (PARITY-SPEC s2.3 and s2.4, sr/home-1-tiles.webp): the gradient header with the
 * `TSO - Name (login)` line and the gear, then the tile grid of the TSO catalogue on the white page, with the quiet status
 * lines (offline, bundle, sync, banners), the zones card and the device line below the tiles. Everything is local state: nothing
 * here waits for the network. The sun switch, language and logout live in Settings. Replaces the Calm Glass Home and the
 * drawer (every drawer entry is a tile, the same destinations).
 */
@Composable
fun TsoHomeContent(
    header: HomeHeader,
    user: HomeUser,
    bundle: BundleStatus,
    tiles: List<RoleTile>,
    sync: SyncChipState,
    onTile: (RoleTile) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    hero: RoleHero? = null,
    health: DeviceHealth? = null,
    banners: List<RoleBanner> = emptyList(),
    onSync: (() -> Unit)? = null,
) {
    val look = LocalAronLook.current
    AronScaffold(
        modifier = modifier,
        home = true,
        topBar = {
            AronHomeHeader(
                title = header.title, subtitle = localizedDigits(header.subtitle), onSettings = onSettings,
                settingsLabel = stringResource(R.string.set_open),
                modifier = Modifier.testTag(HomeTags.HEADER),
            )
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AronTileGrid(tiles) { t, m ->
                AronTile(
                    stringResource(t.label), tsoIcon(t.id), onClick = { onTile(t) }, modifier = m.testTag(RoleHomeTags.tile(t.id)), badge = t.badge,
                    badgeDescription = if (t.badge > 0) stringResource(CoreR.string.core_ui_tile_badge, localizedNumber(t.badge.toLong())) else null,
                )
            }
            if (user.offline) TsoNote(stringResource(CoreR.string.core_ui_offline_mode), Modifier.testTag(HomeTags.MODE))
            if (user.reauthRequired) TsoNote(stringResource(R.string.home_reauth_required))
            if (user.updateRequired) TsoNote(stringResource(R.string.home_update_required))
            banners.forEach { TsoNote(it.text) }
            hero?.let { TsoZonesCard(it) }
            // the bundle check line is shown only when it adds something to the offline note
            if (!(user.offline && bundle == BundleStatus.Offline)) {
                Text(bundleLine(bundle), style = MaterialTheme.typography.bodySmall, color = look.inkMuted, modifier = Modifier.testTag(HomeTags.BUNDLE))
            }
            SyncLine(sync, onSync)
            health?.let { DeviceLine(it) }
        }
    }
}

@Composable
private fun SyncLine(sync: SyncChipState, onSync: (() -> Unit)?) {
    val look = LocalAronLook.current
    val text = when (sync) {
        SyncChipState.Offline -> stringResource(CoreR.string.core_ui_chip_offline)
        SyncChipState.Syncing -> stringResource(CoreR.string.core_ui_chip_syncing)
        SyncChipState.Synced -> stringResource(CoreR.string.core_ui_chip_synced)
        is SyncChipState.SyncedAt -> stringResource(CoreR.string.core_ui_chip_synced_at, sync.timeText)
        is SyncChipState.Waiting -> stringResource(CoreR.string.core_ui_chip_waiting, localizedNumber(sync.count.toLong()))
    }
    val open = stringResource(CoreR.string.core_ui_chip_open_sync)
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = look.inkMuted,
        modifier = Modifier.fillMaxWidth().testTag(RoleHomeTags.SYNC).heightIn(min = if (onSync != null) 48.dp else 0.dp)
            .then(if (onSync != null) Modifier.clickable(role = Role.Button, onClickLabel = open, onClick = onSync) else Modifier),
    )
}

@Composable
private fun TsoZonesCard(hero: RoleHero) {
    val look = LocalAronLook.current
    if (hero !is RoleHero.Tso) return
    AronCard(Modifier.testTag(RoleHomeTags.HERO)) {
        Text(stringResource(R.string.rh_hero_tso_zones), style = MaterialTheme.typography.titleSmall, color = look.purple)
        Text(localizedNumber(hero.zonesSubmitted.toLong()), style = MaterialTheme.typography.headlineMedium, color = look.ink)
        Text(stringResource(R.string.rh_hero_tso_zones_of, localizedNumber(hero.zonesTotal.toLong())), style = MaterialTheme.typography.bodySmall, color = look.inkMuted)
    }
}

/** Device-health line (F-SR-064): caption text; a note under it when any figure is low. */
@Composable
private fun DeviceLine(h: DeviceHealth) {
    val look = LocalAronLook.current
    Column(Modifier.testTag(RoleHomeTags.DEVICE), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            listOf(
                stringResource(R.string.hm_health_battery, localizedNumber(h.batteryPct.toLong())),
                stringResource(R.string.hm_health_storage, localizedNumber(h.freeStorageMb)),
                stringResource(R.string.hm_health_pending, localizedNumber(h.pendingRows.toLong())),
                h.lastSyncAgeMin?.let { stringResource(R.string.hm_health_sync, localizedNumber(it)) } ?: stringResource(R.string.hm_health_never_synced),
            ).joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall, color = look.inkMuted,
        )
        if (h.anyWarning) TsoNote(stringResource(R.string.hm_health_warning))
    }
}

@Composable
private fun bundleLine(status: BundleStatus): String = when (status) {
    BundleStatus.Checking -> stringResource(R.string.home_bundle_checking)
    // only the date takes Bengali digits; the bundle version and codes are identifiers and stay as sent
    is BundleStatus.Ready -> stringResource(R.string.home_bundle_ready, localizedDigits(status.validFor), status.bundleVersion)
    BundleStatus.Unchanged -> stringResource(R.string.home_bundle_unchanged)
    BundleStatus.Offline -> stringResource(R.string.home_bundle_offline)
    is BundleStatus.Refused -> stringResource(R.string.home_bundle_refused, status.httpStatus.toString(), status.code ?: "-")
}
