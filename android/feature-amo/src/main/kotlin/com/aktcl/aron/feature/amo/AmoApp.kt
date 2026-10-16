package com.aktcl.aron.feature.amo

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronScreenTransition
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.DeviceHealth
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.R
import com.aktcl.aron.feature.home.RoleBanner
import com.aktcl.aron.feature.home.RoleHero
import com.aktcl.aron.feature.home.RoleHomeContent
import com.aktcl.aron.feature.home.RoleHomes
import com.aktcl.aron.feature.home.RoleSectionPlaceholder
import com.aktcl.aron.feature.home.RoleSettingsContent
import com.aktcl.aron.feature.home.RoleTile

/** The AMO screens; every tile screen names its tile so the placeholder frame can show its label and icon. */
enum class AmoScreen(val tileId: String?) {
    HOME(null), SETTINGS(null),
    ATTENDANCE(RoleHomes.AmoTile.ATTENDANCE), CONTROL_CALL(RoleHomes.AmoTile.CONTROL_CALL), JOINT_CALL(RoleHomes.AmoTile.JOINT_CALL), SALE(RoleHomes.AmoTile.SALE),
    TEAM(RoleHomes.AmoTile.TEAM), OUTLET(RoleHomes.AmoTile.OUTLET), SALES_SUBMIT(RoleHomes.AmoTile.SALES_SUBMIT), REPORT(RoleHomes.AmoTile.REPORT);

    companion object { fun of(tile: RoleTile): AmoScreen = entries.firstOrNull { it.tileId == tile.id } ?: HOME }
}

/**
 * The AMO day host (docs/07; the frame of SrApp): Home, Settings and one screen per tile. Screen state is a saved enum
 * with the system back returning to Home, and screens change through [AronScreenTransition]. A section whose lane has
 * not landed shows [RoleSectionPlaceholder]: never plain text. Everything is local state; nothing waits for the network.
 */
@Composable
fun AmoApp(
    user: HomeUser,
    businessDate: String,
    bundle: BundleStatus,
    versionText: String,
    onLanguageSelect: (AppLanguage) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    sunlight: Boolean = false,
    onSunlight: (Boolean) -> Unit = {},
    hero: RoleHero? = null,
    health: DeviceHealth? = null,
    badges: Map<String, Int> = emptyMap(),
    banners: List<RoleBanner> = emptyList(),
    /** Settings > App update (F-SYS-020); null in previews and tests (row hidden). */
    onUpdate: (() -> Unit)? = null,
    updateAvailable: Boolean = false,
    /** Last successful sync as Dhaka `HH:mm`, for the chip and the placeholder captions; null when never. */
    syncedAt: String? = null,
    /** Opens the sync detail sheet from the Home chip; null until the AMO sheet lands (the chip is then not tappable). */
    onSync: (() -> Unit)? = null,
) {
    var screen by rememberSaveable { mutableStateOf(AmoScreen.HOME) }
    BackHandler(enabled = screen != AmoScreen.HOME) { screen = AmoScreen.HOME }
    val tiles = remember(badges) { RoleHomes.amoTiles(badges) }
    val sync = RoleHomes.syncState(bundle, user.offline).let { if (it == SyncChipState.Synced && syncedAt != null) SyncChipState.SyncedAt(syncedAt) else it }
    val header = remember(user, businessDate) { RoleHomes.header(RoleHomes.AMO_PREFIX, user, businessDate) }
    AronScreenTransition(screen, forward = { _, to -> to != AmoScreen.HOME }, modifier) { s ->
        when (s) {
            AmoScreen.HOME -> RoleHomeContent(
                header, user, bundle, tiles, sync,
                onTile = { screen = AmoScreen.of(it) }, onSettings = { screen = AmoScreen.SETTINGS },
                hero = hero, health = health, sunlight = sunlight, onSunlight = onSunlight, banners = banners, onSync = onSync,
            )
            AmoScreen.SETTINGS -> RoleSettingsContent(
                versionText, stringResource(R.string.set_logout_message), onLanguageSelect, onLogout, onBack = { screen = AmoScreen.HOME },
                sunlight = sunlight, onSunlight = onSunlight, onUpdate = onUpdate, updateAvailable = updateAvailable,
            )
            else -> {
                val tile = tiles.first { it.id == s.tileId }
                RoleSectionPlaceholder(stringResource(tile.label), tile.icon, onBack = { screen = AmoScreen.HOME }, syncedAt = syncedAt)
            }
        }
    }
}
