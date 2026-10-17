package com.aktcl.aron.feature.tso

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronIconButton
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronColors
import kotlinx.coroutines.launch

object TsoChromeTags {
    const val BAR = "tso_chrome_bar"
    const val MENU = "tso_chrome_menu"
    const val GREETING = "tso_chrome_greeting"
    const val LOGOUT = "tso_chrome_logout"
    const val HOME_BUTTON = "tso_chrome_home"
    const val DRAWER_HEADER = "tso_drawer_header"
    fun entry(screen: TsoScreen) = "tso_drawer_entry_${screen.name.lowercase()}"
    fun subtitle(screen: TsoScreen) = "tso_drawer_subtitle_${screen.name.lowercase()}"
}

private val MenuIcon: ImageVector by lazy {
    ImageVector.Builder("menu", 24.dp, 24.dp, 24f, 24f).path(fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)) {
        moveTo(3f, 18f); horizontalLineToRelative(18f); verticalLineToRelative(-2f); horizontalLineTo(3f); close()
        moveTo(3f, 13f); horizontalLineToRelative(18f); verticalLineToRelative(-2f); horizontalLineTo(3f); close()
        moveTo(3f, 6f); verticalLineToRelative(2f); horizontalLineToRelative(18f); verticalLineTo(6f); close()
    }.build()
}

private val HomeIcon: ImageVector by lazy {
    ImageVector.Builder("home", 24.dp, 24.dp, 24f, 24f).path(fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)) {
        moveTo(10f, 20f); verticalLineToRelative(-6f); horizontalLineToRelative(4f); verticalLineToRelative(6f); horizontalLineToRelative(5f); verticalLineToRelative(-8f)
        horizontalLineToRelative(3f); lineTo(12f, 3f); lineTo(2f, 12f); horizontalLineToRelative(3f); verticalLineToRelative(8f); close()
    }.build()
}

/** One drawer entry: the screen it opens, its title and the one-line subtitle of the manual ("Manage your leaves"). */
data class TsoDrawerEntry(val screen: TsoScreen, val title: Int, val subtitle: Int, val icon: ImageVector)

/**
 * The seven drawer entries (docs/08 and delta-tso G-27/G-24). Target Status is deferred (docs/27) and the bind-code page is gone
 * for the TSO (D-AUTH-TSO-NOBIND), so Settings (language, version, update, support file, change password) is the seventh.
 */
fun tsoDrawerEntries(): List<TsoDrawerEntry> = listOf(
    TsoDrawerEntry(TsoScreen.DASHBOARD, R.string.tso_drawer_dashboard, R.string.tso_drawer_dashboard_sub, AronIcons.Dashboard),
    TsoDrawerEntry(TsoScreen.LEAVE, R.string.tso_drawer_leave, R.string.tso_drawer_leave_sub, AronIcons.Leave),
    TsoDrawerEntry(TsoScreen.FINAL_SUBMIT, R.string.tso_drawer_final_submit, R.string.tso_drawer_final_submit_sub, AronIcons.Verified),
    TsoDrawerEntry(TsoScreen.PERIPHERY, R.string.tso_drawer_periphery, R.string.tso_drawer_periphery_sub, AronIcons.Explore),
    TsoDrawerEntry(TsoScreen.MY_CALL, R.string.tso_drawer_my_call, R.string.tso_drawer_my_call_sub, AronIcons.Place),
    TsoDrawerEntry(TsoScreen.FEEDBACK, R.string.tso_drawer_feedback, R.string.tso_drawer_feedback_sub, AronIcons.Feedback),
    TsoDrawerEntry(TsoScreen.SETTINGS, R.string.tso_drawer_settings, R.string.tso_drawer_settings_sub, AronIcons.Settings),
)

/** Hamburger (top left), "Good day, name", logout icon (top right): the header of the manual (G-27). */
@Composable
fun TsoChromeBar(name: String, onMenu: () -> Unit, onLogout: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    Row(
        modifier.fillMaxWidth().testTag(TsoChromeTags.BAR).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AronIconButton(MenuIcon, stringResource(R.string.tso_chrome_menu), onMenu, Modifier.testTag(TsoChromeTags.MENU))
        Text(
            stringResource(R.string.tso_greeting, name), Modifier.weight(1f).padding(horizontal = AronTokens.Space.S).testTag(TsoChromeTags.GREETING),
            style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 2,
        )
        AronIconButton(AronIcons.Logout, stringResource(R.string.tso_chrome_logout), onLogout, Modifier.testTag(TsoChromeTags.LOGOUT))
    }
}

/** The blue Home button (bottom centre) of every screen but Home: returns to Home. */
@Composable
fun TsoHomeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    val label = stringResource(R.string.tso_chrome_home)
    Box(
        modifier.heightIn(min = AronTokens.Touch.Primary).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }.testTag(TsoChromeTags.HOME_BUTTON),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(horizontal = 20.dp).heightIn(min = AronTokens.Touch.Primary),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
        ) {
            androidx.compose.material3.Icon(HomeIcon, contentDescription = null, tint = c.textOnAccent)
            Text(label, style = MaterialTheme.typography.labelLarge, color = c.textOnAccent)
        }
    }
}

/** The slide-in menu: header "TSO" plus the name, then the seven entries with their subtitles. */
@Composable
fun TsoDrawer(name: String, entries: List<TsoDrawerEntry>, onEntry: (TsoScreen) -> Unit, content: @Composable (open: () -> Unit) -> Unit) {
    val state = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val c = LocalAronColors.current
    ModalNavigationDrawer(
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = c.surfaceSolid) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))) {
                    Column(Modifier.fillMaxWidth().testTag(TsoChromeTags.DRAWER_HEADER).padding(AronTokens.Space.L)) {
                        Text(stringResource(R.string.tso_drawer_role), style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
                        Text(name, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    }
                    entries.forEach { e ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(TsoChromeTags.entry(e.screen))
                                .clickable(role = Role.Button) { scope.launch { state.close() }; onEntry(e.screen) }.padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.S),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.L),
                        ) {
                            androidx.compose.material3.Icon(e.icon, contentDescription = null, tint = c.accentText)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(e.title), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                                Text(stringResource(e.subtitle), Modifier.testTag(TsoChromeTags.subtitle(e.screen)), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                            }
                        }
                    }
                }
            }
        },
        drawerState = state,
        gesturesEnabled = state.isOpen,
    ) { content { scope.launch { state.open() } } }
}
