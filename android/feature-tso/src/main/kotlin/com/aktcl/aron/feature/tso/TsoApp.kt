package com.aktcl.aron.feature.tso

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
import com.aktcl.aron.feature.home.RoleDeviceOtpContent
import com.aktcl.aron.feature.home.RoleHero
import com.aktcl.aron.feature.home.RoleHomeContent
import com.aktcl.aron.feature.home.RoleHomes
import com.aktcl.aron.feature.home.RoleSectionPlaceholder
import com.aktcl.aron.feature.home.RoleSettingsContent
import com.aktcl.aron.feature.home.RoleTile

/** The TSO screens; every tile screen names its tile so the placeholder frame can show its label and icon. */
enum class TsoScreen(val tileId: String?) {
    HOME(null), SETTINGS(null),
    DASHBOARD(RoleHomes.TsoTile.DASHBOARD), LEAVE(RoleHomes.TsoTile.LEAVE), FINAL_SUBMIT(RoleHomes.TsoTile.FINAL_SUBMIT), PERIPHERY(RoleHomes.TsoTile.PERIPHERY),
    MY_CALL(RoleHomes.TsoTile.MY_CALL), FEEDBACK(RoleHomes.TsoTile.FEEDBACK), DEVICE_OTP(RoleHomes.TsoTile.DEVICE_OTP);

    companion object { fun of(tile: RoleTile): TsoScreen = entries.firstOrNull { it.tileId == tile.id } ?: HOME }
}

/**
 * The TSO day host (docs/08; the frame of SrApp): Home, Settings, the Device OTP page and one screen per tile. Screen
 * state is a saved enum with the system back returning to Home, and screens change through [AronScreenTransition].
 * A section whose lane has not landed shows [RoleSectionPlaceholder]: never plain text. Logout wipes this user's data
 * (F-SYS-022), so Settings says so before the confirm.
 */
@Composable
fun TsoApp(
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
    /** Opens the sync detail sheet from the Home chip; null until the TSO sheet lands (the chip is then not tappable). */
    onSync: (() -> Unit)? = null,
    /** The web dashboard origin (scheme and host, no path) for the Device OTP page; null shows the path alone. */
    webOrigin: String? = null,
) {
    var screen by rememberSaveable { mutableStateOf(TsoScreen.HOME) }
    BackHandler(enabled = screen != TsoScreen.HOME) { screen = TsoScreen.HOME }
    val tiles = remember(badges) { RoleHomes.tsoTiles(badges) }
    val sync = RoleHomes.syncState(bundle, user.offline).let { if (it == SyncChipState.Synced && syncedAt != null) SyncChipState.SyncedAt(syncedAt) else it }
    val header = remember(user, businessDate) { RoleHomes.header(RoleHomes.TSO_PREFIX, user, businessDate) }
    AronScreenTransition(screen, forward = { _, to -> to != TsoScreen.HOME }, modifier) { s ->
        when (s) {
            TsoScreen.HOME -> RoleHomeContent(
                header, user, bundle, tiles, sync,
                onTile = { screen = TsoScreen.of(it) }, onSettings = { screen = TsoScreen.SETTINGS },
                hero = hero, health = health, sunlight = sunlight, onSunlight = onSunlight, banners = banners, onSync = onSync,
            )
            TsoScreen.SETTINGS -> RoleSettingsContent(
                versionText, stringResource(R.string.rh_set_logout_wipe), onLanguageSelect, onLogout, onBack = { screen = TsoScreen.HOME },
                sunlight = sunlight, onSunlight = onSunlight, onUpdate = onUpdate, updateAvailable = updateAvailable,
            )
            TsoScreen.DEVICE_OTP -> RoleDeviceOtpContent(onBack = { screen = TsoScreen.HOME }, webOrigin = webOrigin)
            else -> {
                val tile = tiles.first { it.id == s.tileId }
                RoleSectionPlaceholder(stringResource(tile.label), tile.icon, onBack = { screen = TsoScreen.HOME }, syncedAt = syncedAt)
            }
        }
    }
}
