package com.aktcl.aron.feature.tso

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.aron.NightButton
import com.aktcl.aron.core.ui.aron.NightButtonStyle
import com.aktcl.aron.core.ui.aron.nightRoles
import com.aktcl.aron.core.ui.aron.nightText
import com.aktcl.aron.core.ui.localizedNumber

/** Test tags of the TSO shell (top bar, drawer, logout dialog). */
object TsoShellTags {
    const val TOP_BAR = "tso_shell_top_bar"
    const val MENU = "tso_shell_menu"
    const val GREETING = "tso_shell_greeting"
    const val BELL = "tso_shell_bell"
    const val BADGE = "tso_shell_badge"
    const val DRAWER = "tso_shell_drawer"
    const val GEAR = "tso_shell_gear"
    const val PLACEHOLDER = "tso_shell_placeholder"
    const val LOGOUT_DIALOG = "tso_logout_dialog"
    const val LOGOUT_CANCEL = "tso_logout_cancel"
    const val LOGOUT_CONFIRM = "tso_logout_confirm"
    fun row(id: String) = "tso_drawer_row_$id"
}

/**
 * One row of the TSO drawer (D-TSO-MAN-14; screenshots 9, 10, 27, 30, 44): its id, title and subtitle (from the screenshots), icon,
 * and either the [screen] it opens or the [children] it expands to. [LOGOUT_ID] opens the logout dialog.
 */
data class TsoDrawerEntry(
    val id: String,
    @StringRes val title: Int,
    @StringRes val subtitle: Int?,
    val icon: ImageVector,
    val screen: TsoScreen? = null,
    val children: List<TsoDrawerEntry> = emptyList(),
) {
    companion object { const val LOGOUT_ID = "logout" }
}

/** The drawer rows, exactly in the order of D-TSO-MAN-14 (Dashboard first, Log Out last). */
object TsoDrawer {
    val entries: List<TsoDrawerEntry> = listOf(
        TsoDrawerEntry("dashboard", R.string.tso_drawer_dashboard, R.string.tso_drawer_dashboard_sub, AronIcons.GridView, TsoScreen.DASHBOARD),
        TsoDrawerEntry(
            "tickets", R.string.tso_drawer_tickets, R.string.tso_drawer_tickets_sub, AronIcons.Tasks,
            children = listOf(
                TsoDrawerEntry("ticket_dashboard", R.string.tso_drawer_ticket_dashboard, R.string.tso_drawer_ticket_dashboard_sub, AronIcons.PieChart, TsoScreen.TICKET_DASHBOARD),
                TsoDrawerEntry("all_tickets", R.string.tso_drawer_all_tickets, R.string.tso_drawer_all_tickets_sub, AronIcons.Bolt, TsoScreen.TICKET_LIST),
                TsoDrawerEntry("quick_ticket", R.string.tso_drawer_quick_ticket, R.string.tso_drawer_quick_ticket_sub, AronIcons.Bolt, TsoScreen.TICKET_QUICK),
                TsoDrawerEntry("create_ticket", R.string.tso_drawer_create_ticket, R.string.tso_drawer_create_ticket_sub, AronIcons.AddTask, TsoScreen.TICKET_CREATE),
            ),
        ),
        TsoDrawerEntry("leave", R.string.tso_drawer_leave, R.string.tso_drawer_leave_sub, AronIcons.Journey, TsoScreen.LEAVE),
        TsoDrawerEntry("final_submit", R.string.tso_drawer_final_submit, R.string.tso_drawer_final_submit_sub, AronIcons.UploadFile, TsoScreen.FINAL_SUBMIT),
        TsoDrawerEntry(
            "periphery", R.string.tso_drawer_periphery, R.string.tso_drawer_periphery_sub, AronIcons.Scatter,
            children = listOf(
                TsoDrawerEntry("my_team", R.string.tso_drawer_my_team, R.string.tso_drawer_periphery_child_sub, AronIcons.Scatter, TsoScreen.MY_TEAM),
                TsoDrawerEntry("retailer", R.string.tso_drawer_retailer, R.string.tso_drawer_periphery_child_sub, AronIcons.Store, TsoScreen.RETAILER),
            ),
        ),
        TsoDrawerEntry(
            "my_call", R.string.tso_drawer_my_call, R.string.tso_drawer_my_call_sub, AronIcons.Verified,
            children = listOf(
                TsoDrawerEntry("visit_plan", R.string.tso_drawer_visit_plan, R.string.tso_drawer_visit_plan_sub, AronIcons.AddBusiness, TsoScreen.MY_VISIT_PLAN),
                TsoDrawerEntry("set_plan", R.string.tso_drawer_set_plan, R.string.tso_drawer_set_plan_sub, AronIcons.Outlet, TsoScreen.SET_PLAN),
            ),
        ),
        TsoDrawerEntry("target_status", R.string.tso_drawer_target_status, R.string.tso_drawer_target_status_sub, AronIcons.Target, TsoScreen.TARGET_STATUS),
        TsoDrawerEntry(
            "feedback", R.string.tso_drawer_feedback, R.string.tso_drawer_feedback_sub, AronIcons.Forum,
            children = listOf(
                TsoDrawerEntry("feedback_new", R.string.tso_drawer_feedback_new, null, AronIcons.Forum, TsoScreen.FEEDBACK_NEW),
                TsoDrawerEntry("feedback_list", R.string.tso_drawer_feedback_list, null, AronIcons.Forum, TsoScreen.FEEDBACK_LIST),
            ),
        ),
        TsoDrawerEntry(TsoDrawerEntry.LOGOUT_ID, R.string.tso_drawer_logout, R.string.tso_drawer_logout_sub, AronIcons.Logout),
    )

    /** Every screen the drawer opens, in drawer order (groups flattened). */
    val screens: List<TsoScreen> get() = entries.flatMap { e -> if (e.children.isEmpty()) listOfNotNull(e.screen) else e.children.mapNotNull { it.screen } }
}

/**
 * The Home button column of build contract s8 (FAB = Y per screenshot): Dashboard (6, 7), Login & Bikroy Joma Status (8), My Team
 * (28), Retailer (29), Set Plan (31 to 33), My Visit Plan and its outlets, query and task (34 to 38), Add new feedback (43), and My
 * feedback with it (an improvement with no screenshot; it belongs to the same group). Every other screen has none: login (5),
 * tickets (11 to 18), leave (19 to 22), Final Submit (23 to 26), Target Status (39 to 42), Settings, Change Password, PDA to Support
 * and the inbox (no screenshot: none).
 */
object TsoFab {
    val screens: Set<TsoScreen> = setOf(
        TsoScreen.DASHBOARD, TsoScreen.LOGIN_SUBMIT_STATUS, TsoScreen.MY_TEAM, TsoScreen.RETAILER, TsoScreen.SET_PLAN,
        TsoScreen.MY_VISIT_PLAN, TsoScreen.FEEDBACK_NEW, TsoScreen.FEEDBACK_LIST,
    )

    /** Pass to [TsoFrame]'s `homeButton`: `TsoFrame(title, nav, homeButton = TsoFab.of(TsoScreen.MY_TEAM))`. */
    fun of(screen: TsoScreen): Boolean = screen in screens
}

/**
 * The Dashboard's top bar (screenshot 6): the menu glyph, "Good day, <full_name>", and at the end the bell with the unread badge
 * (hidden at 0; "99+" above 99). The bell opens the inbox. No back button: the Dashboard is the root.
 */
@Composable
fun TsoRootTopBar(fullName: String, unread: Int, onMenu: () -> Unit, onBell: () -> Unit, modifier: Modifier = Modifier) {
    val n = nightRoles()
    Row(
        modifier
            .fillMaxWidth()
            .background(n.page)
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp)
            .testTag(TsoShellTags.TOP_BAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val menu = stringResource(R.string.tso_chrome_menu)
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onMenu).semantics { contentDescription = menu }.testTag(TsoShellTags.MENU),
            contentAlignment = Alignment.Center,
        ) { Icon(AronIcons.Menu, contentDescription = null, tint = n.ink, modifier = Modifier.size(28.dp)) }
        Text(
            stringResource(R.string.tso_chrome_greeting, fullName), Modifier.weight(1f).padding(start = 4.dp).testTag(TsoShellTags.GREETING),
            style = nightText(17.sp, n.ink), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        val bellLabel = if (unread > 0) pluralStringResource(R.plurals.tso_chrome_bell_unread, unread, localizedNumber(unread.toLong())) else stringResource(R.string.tso_chrome_notifications)
        Box(
            Modifier.size(52.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBell).semantics { contentDescription = bellLabel }.testTag(TsoShellTags.BELL),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AronIcons.Notifications, contentDescription = null, tint = n.inkMuted, modifier = Modifier.size(28.dp))
            if (unread > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = 6.dp).sizeIn(minWidth = 20.dp, minHeight = 20.dp).clip(CircleShape).background(n.badge)
                        .padding(horizontal = 4.dp).testTag(TsoShellTags.BADGE),
                    contentAlignment = Alignment.Center,
                ) { Text(if (unread > 99) "99+" else localizedNumber(unread.toLong()), style = nightText(11.sp, n.onBadge, bold = true)) }
            }
        }
    }
}

/**
 * The drawer sheet (screenshots 9, 10, 27, 30, 44): "TSO" over the bold [fullName] with the Settings gear at the end (improvement),
 * a rule, then the rows of [TsoDrawer.entries]: icon, title, subtitle, and a chevron (">" opens, "˅" expands a group and turns to
 * "˄"; an open group sits between two rules, its children indented). [onOpen] gets the chosen screen, [onLogout] the Log Out row.
 * One group is open at a time, as on the screenshots; which one survives recreation.
 */
@Composable
fun TsoDrawerContent(
    fullName: String,
    onOpen: (TsoScreen) -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    /** The group open when the drawer is first drawn (goldens of screenshots 10, 27, 30); null: all closed. */
    initiallyExpanded: String? = null,
) {
    val n = nightRoles()
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(
        modifier
            .background(n.drawer)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .testTag(TsoShellTags.DRAWER),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 40.dp, bottom = 56.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tso_drawer_role), style = nightText(28.sp, n.ink))
                Text(fullName, style = nightText(32.sp, n.ink, bold = true), maxLines = 2, modifier = Modifier.semantics { heading() })
            }
            val gear = stringResource(R.string.tso_drawer_settings)
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onSettings).semantics { contentDescription = gear }.testTag(TsoShellTags.GEAR),
                contentAlignment = Alignment.Center,
            ) { Icon(AronIcons.Settings, contentDescription = null, tint = n.ink, modifier = Modifier.size(26.dp)) }
        }
        Rule()
        TsoDrawer.entries.forEach { e ->
            val open = expanded == e.id
            if (open) Rule()
            DrawerRow(
                e, indent = false,
                trailing = when { e.children.isEmpty() -> Trailing.Open; open -> Trailing.Collapse; else -> Trailing.Expand },
                onClick = {
                    when {
                        e.children.isNotEmpty() -> expanded = if (open) null else e.id
                        e.id == TsoDrawerEntry.LOGOUT_ID -> onLogout()
                        else -> e.screen?.let(onOpen)
                    }
                },
            )
            if (open) {
                e.children.forEach { c -> DrawerRow(c, indent = true, trailing = Trailing.Open, onClick = { c.screen?.let(onOpen) }) }
                Rule()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private enum class Trailing { Open, Expand, Collapse }

@Composable
private fun Rule() {
    val n = nightRoles()
    Box(Modifier.fillMaxWidth().height(1.dp).background(n.divider))
}

@Composable
private fun DrawerRow(e: TsoDrawerEntry, indent: Boolean, trailing: Trailing, onClick: () -> Unit) {
    val n = nightRoles()
    val title = stringResource(e.title)
    val sub = e.subtitle?.let { stringResource(it) }
    val state = when (trailing) {
        Trailing.Expand -> stringResource(R.string.tso_drawer_collapsed)
        Trailing.Collapse -> stringResource(R.string.tso_drawer_expanded)
        Trailing.Open -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (indent) 68.dp else 80.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { if (state != null) stateDescription = state }
            .testTag(TsoShellTags.row(e.id))
            .padding(start = if (indent) 56.dp else 24.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(e.icon, contentDescription = null, tint = n.ink, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = nightText(19.sp, n.ink, semibold = true))
            if (sub != null) Text(sub, style = nightText(14.sp, n.inkMuted))
        }
        Icon(
            when (trailing) { Trailing.Open -> AronIcons.ChevronRight; Trailing.Expand -> AronIcons.ExpandMore; Trailing.Collapse -> AronIcons.ExpandLess },
            contentDescription = null, tint = if (trailing == Trailing.Open) n.chevron else n.ink, modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * The logout dialog (screenshot 44, D-TSO-MAN-37): "Log Out!", "All app data on this phone will be removed." (grammar fix of the
 * screenshot's text, s7.1 Q6), the green Cancel and the orange Log Out. Confirming calls [onConfirm]; the host still refuses while
 * items are unsent (G-man-tso-01) and then wipes this user's data.
 */
@Composable
fun TsoLogoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    // the words are resolved in the caller's composition (its locale), then drawn in the dialog window
    val words = TsoLogoutWords(
        stringResource(R.string.tso_chrome_logout), stringResource(R.string.tso_chrome_logout_body),
        stringResource(R.string.tso_chrome_cancel), stringResource(R.string.tso_drawer_logout),
    )
    Dialog(onDismissRequest = onDismiss) { TsoLogoutDialogContent(onConfirm, onDismiss, words = words) }
}

/** The four texts of the logout dialog. */
data class TsoLogoutWords(val title: String, val body: String, val cancel: String, val confirm: String)

/** The body of [TsoLogoutDialog] (also drawn inline by the goldens, which capture one window). */
@Composable
fun TsoLogoutDialogContent(onConfirm: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier, words: TsoLogoutWords? = null) {
    val n = nightRoles()
    val w = words ?: TsoLogoutWords(
        stringResource(R.string.tso_chrome_logout), stringResource(R.string.tso_chrome_logout_body),
        stringResource(R.string.tso_chrome_cancel), stringResource(R.string.tso_drawer_logout),
    )
    run {
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(if (n.solid) SolidColor(n.dialogTop) else Brush.verticalGradient(listOf(n.dialogTop, n.dialogBottom)))
                .then(if (n.solid) Modifier.border(2.dp, n.outlineEdge, RoundedCornerShape(24.dp)) else Modifier)
                .padding(24.dp)
                .testTag(TsoShellTags.LOGOUT_DIALOG),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(w.title, style = nightText(26.sp, n.ink, bold = true, align = TextAlign.Center), modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(36.dp))
            Text(w.body, style = nightText(16.sp, n.inkMuted, align = TextAlign.Center))
            Spacer(Modifier.height(36.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                NightButton(w.cancel, onDismiss, Modifier.testTag(TsoShellTags.LOGOUT_CANCEL), style = NightButtonStyle.Cancel, fill = false)
                NightButton(w.confirm, onConfirm, Modifier.testTag(TsoShellTags.LOGOUT_CONFIRM), style = NightButtonStyle.LogOut, fill = false)
            }
        }
    }
}
