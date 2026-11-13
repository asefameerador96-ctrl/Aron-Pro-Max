package com.aktcl.aron.feature.amo

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronScreenTransition
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.DeviceHealth
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.R as HomeR
import com.aktcl.aron.feature.home.RoleBanner
import com.aktcl.aron.feature.home.RoleHero
import com.aktcl.aron.feature.home.RoleHomeContent
import com.aktcl.aron.feature.home.RoleHomes
import com.aktcl.aron.feature.home.RoleSectionPlaceholder
import com.aktcl.aron.feature.home.RoleSettingsContent
import com.aktcl.aron.feature.home.RoleTile

/**
 * The AMO screens (D-AMO-MAN-02): one per Home tile of the manual, so a later lane only fills a screen, never adds an entry. Every tile
 * screen names its tile id (the [AmoLeaf] name in lower case) so the placeholder frame can show its label and icon; SUPPORT, PRINTER and
 * EXCEPTIONS have no tile (Settings rows).
 */
enum class AmoScreen(val tileId: String?) {
    HOME(null), SETTINGS("settings"), SUPPORT(null), PRINTER(null), EXCEPTIONS(null),
    ATTENDANCE("attendance"), JOINT_CALL("joint_call"), CONTROL_CALL("control_call"),
    TEAM_LOCATION("team_location"), TEAM_PERFORMANCE("team_performance"), TASK_DELEGATION("task_delegation"), LIVE_DASHBOARD("live_dashboard"), SR_STOCK("sr_stock"),
    OUTLET("outlet"), STOCK("stock"), SALE("sale"), MEMO("memo"), SUMMARY("summary"), SALES_SUBMIT("sales_submit"), REPORT("report"), SALES_JOURNEY("sales_journey"),
    /** Deferred programme hooks (docs/27): a tile in the old app's place that opens the "coming later" state. */
    ASTHA("astha"), CAMPAIGN_ENROLLMENT("campaign_enrollment");

    /** The destination this screen is for, when it is a tile. */
    val leaf: AmoLeaf? get() = tileId?.let { id -> AmoLeaf.entries.firstOrNull { AmoMenu.tileId(it) == id } }

    companion object { fun of(tile: RoleTile): AmoScreen = entries.firstOrNull { it.tileId == tile.id } ?: HOME }
}

/** Screens opened from Settings: Back returns there (Support, Printer diagnostics). */
private val UNDER_SETTINGS = setOf(AmoScreen.SUPPORT, AmoScreen.PRINTER, AmoScreen.EXCEPTIONS)

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
    /** What the bundle's supervisor section says (F-AMO-001): drives the Outlet badge; from [AmoReach.parse]. */
    reach: AmoReach = AmoReach.NONE,
    banners: List<RoleBanner> = emptyList(),
    /** Settings > App update (F-SYS-020); null in previews and tests (row hidden). */
    onUpdate: (() -> Unit)? = null,
    updateAvailable: Boolean = false,
    /** Last successful sync as Dhaka `HH:mm`, for the chip and the placeholder captions; null when never. */
    syncedAt: String? = null,
    /** Opens the sync detail sheet from the Home chip; null until the AMO sheet lands (the chip is then not tappable). */
    onSync: (() -> Unit)? = null,
    /** Real screens by destination, supplied by the app module (it owns the day wiring); a screen not listed shows the placeholder frame. */
    screens: Map<AmoScreen, @Composable (onBack: () -> Unit) -> Unit> = emptyMap(),
    /** The four target cards of Home (screenshot 9); null until the server answers (every card then prints "—"). */
    targets: CallTargets? = null,
    targetsStatus: CallTargetsStatus = CallTargetsStatus.Fresh,
    /** The zone name for the header line "<zone> AMO, <date>" (D-AMO-MAN-02); null when the bundle has none yet. */
    zoneName: String? = null,
) {
    var screen by rememberSaveable { mutableStateOf(AmoScreen.HOME) }
    BackHandler(enabled = screen != AmoScreen.HOME) { screen = if (screen in UNDER_SETTINGS) AmoScreen.SETTINGS else AmoScreen.HOME }
    val tiles = remember(badges, reach) { AmoMenu.tiles(reach, AmoMenu.badges(reach) + badges) }
    val sync = RoleHomes.syncState(bundle, user.offline).let { if (it == SyncChipState.Synced && syncedAt != null) SyncChipState.SyncedAt(syncedAt) else it }
    val zoneFormat = stringResource(R.string.amo_shell_header_zone); val plainFormat = stringResource(R.string.amo_shell_header_plain)
    val header = remember(user, businessDate, zoneName, reach, zoneFormat, plainFormat) { AmoHomeHeader.of(user, businessDate, zoneName ?: reach.zoneName, zoneFormat, plainFormat) }
    val navigate: (AmoScreen) -> Unit = remember { { screen = it } }
    androidx.compose.runtime.CompositionLocalProvider(LocalAmoNavigate provides navigate) {
    AronScreenTransition(screen, forward = { _, to -> to != AmoScreen.HOME }, modifier) { s ->
        when (s) {
            AmoScreen.HOME -> AmoHomeContent(
                header, user, bundle, tiles,
                onTile = { screen = AmoScreen.of(it) }, onSettings = { screen = AmoScreen.SETTINGS },
                hero = hero, health = health, banners = banners, sync = sync, onSync = onSync, targets = targets, targetsStatus = targetsStatus,
            )
            AmoScreen.SETTINGS -> AmoSettingsContent(
                versionText, stringResource(HomeR.string.set_logout_message), onLanguageSelect, onLogout, onBack = { screen = AmoScreen.HOME },
                sunlight = sunlight, onSunlight = onSunlight, onUpdate = onUpdate, updateAvailable = updateAvailable,
                onSupport = screens[AmoScreen.SUPPORT]?.let { { screen = AmoScreen.SUPPORT } },
                onExceptions = screens[AmoScreen.EXCEPTIONS]?.let { { screen = AmoScreen.EXCEPTIONS } },
                onPrinter = screens[AmoScreen.PRINTER]?.let { { screen = AmoScreen.PRINTER } },
            )
            else -> if (screens[s] != null) screens.getValue(s)({ screen = if (s in UNDER_SETTINGS) AmoScreen.SETTINGS else AmoScreen.HOME }) else if (s == AmoScreen.SALES_JOURNEY) {
                // Lane A3 builds the journey; until then the tile opens an honest empty state (D-AMO-MAN-02 (c)).
                AmoFrame(stringResource(HomeR.string.hm_tile_sales_journey), { screen = AmoScreen.HOME }, icon = com.aktcl.aron.core.ui.R.drawable.aron_art_sales_journey) {
                    com.aktcl.aron.core.ui.AronEmptyState(stringResource(R.string.amo_shell_no_data), Modifier.testTag("amo_journey_empty"))
                }
            } else if (AmoMenu.isHook(s.tileId.orEmpty())) {
                val tile = tiles.first { it.id == s.tileId }
                AmoFrame(stringResource(tile.label), { screen = AmoScreen.HOME }, icon = amoTileIcon(tile.id)) {
                    com.aktcl.aron.feature.home.ComingSoonContent(onBack = { screen = AmoScreen.HOME })
                }
            } else {
                // A screen without a tile (Support) whose host is not ready after a recreation goes back to Settings instead of crashing.
                val tile = tiles.firstOrNull { it.id == s.tileId }
                if (tile == null) { LaunchedEffect(s) { screen = AmoScreen.SETTINGS }; return@AronScreenTransition }
                RoleSectionPlaceholder(stringResource(tile.label), tile.icon, onBack = { screen = AmoScreen.HOME }, syncedAt = syncedAt)
            }
        }
    }
    }
}

/** D-NIGHT-CHECKOUT-01: lets a screen supplied by the app module open another AMO screen (Attendance to Sales Submit). */
val LocalAmoNavigate = androidx.compose.runtime.staticCompositionLocalOf<(AmoScreen) -> Unit> { {} }
