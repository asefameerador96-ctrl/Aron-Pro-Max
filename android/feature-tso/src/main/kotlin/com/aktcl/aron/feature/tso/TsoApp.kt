package com.aktcl.aron.feature.tso

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronScreenTransition
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.DeviceHealth
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.R
import com.aktcl.aron.feature.home.RoleBanner
import com.aktcl.aron.feature.home.RoleHero
import com.aktcl.aron.feature.home.RoleHomes
import com.aktcl.aron.feature.home.RoleSectionPlaceholder
import com.aktcl.aron.feature.home.RoleTile

/**
 * The legacy tile Home's destinations (a1-stubs keeps the tile Home working; A1's shell step retires it for the drawer,
 * D-TSO-MAN-14). A hub tile opens the screen that hosts its hub today: Periphery -> MY_TEAM, My Call -> MY_VISIT_PLAN,
 * Feedback -> FEEDBACK_LIST.
 */
internal object TsoTiles {
    val screenOf: Map<String, TsoScreen> = mapOf(
        RoleHomes.TsoTile.DASHBOARD to TsoScreen.DASHBOARD,
        RoleHomes.TsoTile.LEAVE to TsoScreen.LEAVE,
        RoleHomes.TsoTile.FINAL_SUBMIT to TsoScreen.FINAL_SUBMIT,
        RoleHomes.TsoTile.PERIPHERY to TsoScreen.MY_TEAM,
        RoleHomes.TsoTile.MY_CALL to TsoScreen.MY_VISIT_PLAN,
        RoleHomes.TsoTile.FEEDBACK to TsoScreen.FEEDBACK_LIST,
    )

    fun tileOf(screen: TsoScreen): String? = screenOf.entries.firstOrNull { it.value == screen }?.key
}

/**
 * The TSO day host (docs/08): the root (the tile Home until A1's shell step), Settings with Change Password and PDA to
 * Support, and every other screen from the host map [screens] (A1 dashboard, A2 tickets, A3 areas), keyed by [TsoScreen].
 * Navigation is a saved back stack behind [TsoNav]; the system back pops it, and screens change through
 * [AronScreenTransition]. A screen with no entry shows [RoleSectionPlaceholder]: never plain text. Logout wipes this user's
 * data (F-SYS-022), so Settings says so before the confirm.
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
    /**
     * The web dashboard origin of the Device OTP page. The TSO app offers no Device OTP screen (D-AUTH-TSO-NOBIND; the
     * tile was already hidden), so nothing reads it; kept so callers compile until A1's shell step trims the signature.
     */
    @Suppress("UNUSED_PARAMETER") webOrigin: String? = null,
    /** F-TSO-024: change password (online) and the PDA to Support screen; null hides the row. */
    changePassword: com.aktcl.aron.feature.tso.password.ChangePasswordApi? = null,
    support: (@Composable (onBack: () -> Unit) -> Unit)? = null,
    /** Real screens by destination, supplied by the app module's hosts; a screen not listed shows the placeholder frame. */
    screens: Map<TsoScreen, TsoScreenContent> = emptyMap(),
) {
    val nav = rememberSaveable(saver = TsoNavState.Saver) { TsoNavState() }
    BackHandler(enabled = nav.stack.isNotEmpty()) { nav.back() }
    // the TSO app has no bind OTP (D-AUTH-TSO-NOBIND), so the Device OTP tile of the shared catalogue has nothing to show and is not offered
    val tiles = remember(badges) { RoleHomes.tsoTiles(badges).filter { it.id != RoleHomes.TsoTile.DEVICE_OTP } }
    val sync = RoleHomes.syncState(bundle, user.offline).let { if (it == SyncChipState.Synced && syncedAt != null) SyncChipState.SyncedAt(syncedAt) else it }
    val header = remember(user, businessDate) { RoleHomes.header(RoleHomes.TSO_PREFIX, user, businessDate) }
    // Every screen but Home carries its own gradient top bar with the back chevron (PARITY-SPEC s2.1), so there is no Home strip below it.
    Box(modifier) {
        AronScreenTransition(nav.stack, forward = { from, to -> to.size > from.size }, Modifier) { stack ->
            val route = stack.lastOrNull()
            val content = route?.let { screens[it.screen] }
            when {
                route == null -> TsoHomeContent(
                    header, user, bundle, tiles, sync, onTile = { t -> TsoTiles.screenOf[t.id]?.let { nav.open(it) } }, onSettings = { nav.open(TsoScreen.SETTINGS) },
                    hero = hero, health = health, banners = banners, onSync = onSync,
                )
                content != null -> content(nav, route.arg)
                route.screen == TsoScreen.SETTINGS -> TsoSettingsContent(
                    versionText, stringResource(R.string.rh_set_logout_wipe), onLanguageSelect, onLogout, onBack = nav::back,
                    sunlight = sunlight, onSunlight = onSunlight, onUpdate = onUpdate, updateAvailable = updateAvailable,
                    onSupport = if (support != null) ({ nav.open(TsoScreen.SUPPORT_UPLOAD) }) else null,
                    onChangePassword = if (changePassword != null) ({ nav.open(TsoScreen.CHANGE_PASSWORD) }) else null,
                )
                route.screen == TsoScreen.CHANGE_PASSWORD && changePassword != null -> {
                    // a fresh form (and no typed password kept) every time the screen is opened
                    val cpEpoch = rememberRetained("tso-${user.username}-cp-epoch") { RetainedCounter() }
                    val st = rememberRetained("tso-${user.username}-cp-${cpEpoch.n}") { com.aktcl.aron.feature.tso.password.ChangePasswordState(changePassword) }
                    com.aktcl.aron.feature.tso.password.ChangePasswordScreen(st, onBack = { nav.back(); cpEpoch.n++ })
                }
                route.screen == TsoScreen.SUPPORT_UPLOAD && support != null -> support { nav.back() }
                else -> TsoPlaceholderFor(route.screen, tiles, syncedAt, nav::back)
            }
        }
    }
}

/** A destination whose lane has not landed: the designed placeholder with the screen's label (a Home tile's label and icon when it has one). */
@Composable
private fun TsoPlaceholderFor(s: TsoScreen, tiles: List<RoleTile>, syncedAt: String?, onBack: () -> Unit) {
    val tile = TsoTiles.tileOf(s)?.let { id -> tiles.firstOrNull { it.id == id } }
    if (tile != null) {
        RoleSectionPlaceholder(stringResource(tile.label), tile.icon, onBack = onBack, syncedAt = syncedAt)
    } else {
        RoleSectionPlaceholder(stringResource(placeholderLabel(s)), AronIcons.Dashboard, onBack = onBack, syncedAt = syncedAt)
    }
}

private fun placeholderLabel(s: TsoScreen): Int = when (s) {
    TsoScreen.DASHBOARD -> com.aktcl.aron.feature.tso.R.string.tso_dash_title
    TsoScreen.LOGIN_SUBMIT_STATUS -> com.aktcl.aron.feature.tso.R.string.tso_dash_login_status
    TsoScreen.NOTIFICATIONS -> com.aktcl.aron.feature.tso.R.string.tso_chrome_notifications
    TsoScreen.SETTINGS -> com.aktcl.aron.feature.tso.R.string.tso_drawer_settings
    TsoScreen.CHANGE_PASSWORD -> com.aktcl.aron.feature.tso.R.string.tso_cp_title
    TsoScreen.SUPPORT_UPLOAD -> R.string.set_support
    TsoScreen.TICKET_DASHBOARD, TsoScreen.TICKET_LIST, TsoScreen.TICKET_QUICK, TsoScreen.TICKET_CREATE, TsoScreen.TICKET_DETAIL ->
        com.aktcl.aron.feature.tso.R.string.tso_drawer_tickets
    TsoScreen.LEAVE -> com.aktcl.aron.feature.tso.R.string.tso_leave_title
    TsoScreen.FINAL_SUBMIT -> com.aktcl.aron.feature.tso.R.string.tso_fs_title
    TsoScreen.MY_TEAM -> com.aktcl.aron.feature.tso.R.string.tso_pe_team
    TsoScreen.RETAILER -> com.aktcl.aron.feature.tso.R.string.tso_pe_retailer
    TsoScreen.MY_VISIT_PLAN -> com.aktcl.aron.feature.tso.R.string.tso_mc_visit_plan
    TsoScreen.SET_PLAN -> com.aktcl.aron.feature.tso.R.string.tso_mc_set_plan
    TsoScreen.TARGET_STATUS, TsoScreen.TARGET_DETAILS -> com.aktcl.aron.feature.tso.R.string.tso_drawer_target_status
    TsoScreen.FEEDBACK_NEW -> com.aktcl.aron.feature.tso.R.string.tso_fb_new
    TsoScreen.FEEDBACK_LIST -> com.aktcl.aron.feature.tso.R.string.tso_fb_title
}

object TsoSettingsTags { const val CHANGE_PASSWORD = "tso_settings_change_password" }
