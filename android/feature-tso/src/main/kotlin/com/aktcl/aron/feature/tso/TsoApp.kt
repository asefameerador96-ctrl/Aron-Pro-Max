package com.aktcl.aron.feature.tso

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronScreenTransition
import com.aktcl.aron.core.ui.aron.AronHomeFab
import com.aktcl.aron.core.ui.aron.nightPage
import com.aktcl.aron.core.ui.aron.nightRoles
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.R
import kotlinx.coroutines.launch

/**
 * The TSO app after login (D-TSO-MAN-14, ruling 7; docs/08): the Dashboard is the root, under the top bar (menu, "Good day,
 * <full_name>", the bell with [unread]) and with the Home button. The menu opens the drawer ([TsoDrawerContent]); a drawer row opens
 * its screen ON the Dashboard (the back stack is [screen]), so back from any drawer screen returns to the Dashboard, and a screen's own
 * sub-screens push on top. The drawer's gear opens Settings (language, Sunlight, version, update, PDA to Support, Change Password);
 * Log Out (last row) asks first ([TsoLogoutDialog]) and then calls [onLogout], which the host refuses while items are unsent.
 *
 * Screens come from the host maps [screens] (A1 dashboard, Login & Bikroy Joma status, targets and inbox; A2 tickets; A3 areas); a
 * screen with no entry shows the designed placeholder frame. Settings, Change Password and PDA to Support are built in. Navigation is a
 * saved back stack behind [TsoNav]; the system back pops it (closing the drawer first). No timer anywhere: the Dashboard reads when it
 * is shown and on pull-to-refresh.
 */
@Composable
fun TsoApp(
    user: HomeUser,
    businessDate: String,
    @Suppress("UNUSED_PARAMETER") bundle: BundleStatus,
    versionText: String,
    onLanguageSelect: (AppLanguage) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    sunlight: Boolean = false,
    onSunlight: (Boolean) -> Unit = {},
    /** Settings > App update (F-SYS-020); null in previews and tests (row hidden). */
    onUpdate: (() -> Unit)? = null,
    updateAvailable: Boolean = false,
    /** F-TSO-024: change password (online) and the PDA to Support screen; null hides the row. */
    changePassword: com.aktcl.aron.feature.tso.password.ChangePasswordApi? = null,
    support: (@Composable (onBack: () -> Unit) -> Unit)? = null,
    /** Real screens by destination, supplied by the app module's hosts; a screen not listed shows the placeholder frame. */
    screens: Map<TsoScreen, TsoScreenContent> = emptyMap(),
    /** Unread inbox items for the bell's badge (0 hides it); the host reads it on Dashboard open and pull-to-refresh, never on a timer. */
    unread: Int = 0,
) {
    val nav = rememberSaveable(saver = TsoNavState.Saver) { TsoNavState() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var askLogout by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && nav.stack.isNotEmpty()) { nav.back() }
    fun fromDrawer(action: () -> Unit) { scope.launch { drawer.close() }; action() }
    val n = nightRoles()
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = nav.stack.isEmpty(),
        modifier = modifier,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = n.drawer, drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                modifier = Modifier.fillMaxWidth(0.85f).fillMaxHeight(),
            ) {
                TsoDrawerContent(
                    user.fullName,
                    onOpen = { s -> fromDrawer { nav.openFromDrawer(s) } },
                    onSettings = { fromDrawer { nav.openFromDrawer(TsoScreen.SETTINGS) } },
                    onLogout = { fromDrawer { askLogout = true } },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize().nightPage(n)) {
            AronScreenTransition(nav.stack, forward = { from, to -> to.size > from.size }, Modifier) { stack ->
                val route = stack.lastOrNull()
                if (route == null) {
                    TsoRoot(user, unread, nav, screens[TsoScreen.DASHBOARD], onMenu = { scope.launch { drawer.open() } })
                } else {
                    TsoRouteContent(route, nav, screens, user, versionText, onLanguageSelect, sunlight, onSunlight, onUpdate, updateAvailable, changePassword, support)
                }
            }
        }
    }
    if (askLogout) TsoLogoutDialog(onConfirm = { askLogout = false; onLogout() }, onDismiss = { askLogout = false })
}

/** The root: the Dashboard under the top bar, with the Home button (s8 row 6: Y). */
@Composable
private fun TsoRoot(user: HomeUser, unread: Int, nav: TsoNavState, dashboard: TsoScreenContent?, onMenu: () -> Unit) {
    val n = nightRoles()
    Box(Modifier.fillMaxSize().nightPage(n)) {
        Column(Modifier.fillMaxSize()) {
            TsoRootTopBar(user.fullName, unread, onMenu = onMenu, onBell = { nav.open(TsoScreen.NOTIFICATIONS) })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (dashboard != null) dashboard(nav, null) else TsoSoon(stringResource(com.aktcl.aron.feature.tso.R.string.tso_dash_title), Modifier)
            }
        }
        AronHomeFab({ nav.home() }, stringResource(com.aktcl.aron.feature.tso.R.string.tso_chrome_home), Modifier.align(Alignment.BottomCenter), buttonModifier = Modifier.testTag(TsoKitTags.HOME))
    }
}

@Composable
private fun TsoRouteContent(
    route: TsoRoute, nav: TsoNavState, screens: Map<TsoScreen, TsoScreenContent>, user: HomeUser, versionText: String,
    onLanguageSelect: (AppLanguage) -> Unit, sunlight: Boolean, onSunlight: (Boolean) -> Unit, onUpdate: (() -> Unit)?, updateAvailable: Boolean,
    changePassword: com.aktcl.aron.feature.tso.password.ChangePasswordApi?, support: (@Composable (onBack: () -> Unit) -> Unit)?,
) {
    val content = screens[route.screen]
    when {
        content != null -> content(nav, route.arg)
        route.screen == TsoScreen.SETTINGS -> TsoSettingsContent(
            versionText, stringResource(R.string.rh_set_logout_wipe), onLanguageSelect, onLogout = {}, onBack = nav::back,
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
        else -> TsoFrame(stringResource(placeholderLabel(route.screen)), nav, homeButton = TsoFab.of(route.screen)) {
            TsoSoon(stringResource(placeholderLabel(route.screen)), Modifier.align(Alignment.Center))
        }
    }
}

/** A destination whose lane has not landed: the designed empty card (never plain text), tagged for the shell tests. */
@Composable
private fun TsoSoon(label: String, modifier: Modifier) {
    TsoEmpty(stringResource(com.aktcl.aron.feature.tso.R.string.tso_chrome_soon, label), modifier.testTag(TsoShellTags.PLACEHOLDER), hint = stringResource(com.aktcl.aron.feature.tso.R.string.tso_chrome_soon_hint))
}

private fun placeholderLabel(s: TsoScreen): Int = when (s) {
    TsoScreen.DASHBOARD -> com.aktcl.aron.feature.tso.R.string.tso_dash_title
    TsoScreen.LOGIN_SUBMIT_STATUS -> com.aktcl.aron.feature.tso.R.string.tso_dash_login_submit
    TsoScreen.NOTIFICATIONS -> com.aktcl.aron.feature.tso.R.string.tso_chrome_notifications
    TsoScreen.SETTINGS -> com.aktcl.aron.feature.tso.R.string.tso_drawer_settings
    TsoScreen.CHANGE_PASSWORD -> com.aktcl.aron.feature.tso.R.string.tso_cp_title
    TsoScreen.SUPPORT_UPLOAD -> R.string.set_support
    TsoScreen.TICKET_DASHBOARD -> com.aktcl.aron.feature.tso.R.string.tso_drawer_ticket_dashboard
    TsoScreen.TICKET_LIST, TsoScreen.TICKET_DETAIL -> com.aktcl.aron.feature.tso.R.string.tso_drawer_all_tickets
    TsoScreen.TICKET_QUICK -> com.aktcl.aron.feature.tso.R.string.tso_drawer_quick_ticket
    TsoScreen.TICKET_CREATE -> com.aktcl.aron.feature.tso.R.string.tso_drawer_create_ticket
    TsoScreen.LEAVE -> com.aktcl.aron.feature.tso.R.string.tso_drawer_leave
    TsoScreen.FINAL_SUBMIT -> com.aktcl.aron.feature.tso.R.string.tso_drawer_final_submit
    TsoScreen.MY_TEAM -> com.aktcl.aron.feature.tso.R.string.tso_drawer_my_team
    TsoScreen.RETAILER -> com.aktcl.aron.feature.tso.R.string.tso_drawer_retailer
    TsoScreen.MY_VISIT_PLAN -> com.aktcl.aron.feature.tso.R.string.tso_drawer_visit_plan
    TsoScreen.SET_PLAN -> com.aktcl.aron.feature.tso.R.string.tso_drawer_set_plan
    TsoScreen.TARGET_STATUS, TsoScreen.TARGET_DETAILS -> com.aktcl.aron.feature.tso.R.string.tso_drawer_target_status
    TsoScreen.FEEDBACK_NEW -> com.aktcl.aron.feature.tso.R.string.tso_drawer_feedback_new
    TsoScreen.FEEDBACK_LIST -> com.aktcl.aron.feature.tso.R.string.tso_drawer_feedback_list
}

object TsoSettingsTags { const val CHANGE_PASSWORD = "tso_settings_change_password" }
