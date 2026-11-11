package com.aktcl.aron.feature.amo

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronFonts
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.core.ui.aron.AronHomeHeader
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.aron.AronScaffold
import com.aktcl.aron.core.ui.aron.AronSyncFooter
import com.aktcl.aron.core.ui.aron.AronTile
import com.aktcl.aron.core.ui.aron.AronTileGrid
import com.aktcl.aron.core.ui.aron.AronTopBar
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.DeviceHealth
import com.aktcl.aron.feature.home.HomeHeader
import com.aktcl.aron.feature.home.HomeTags
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.RoleBanner
import com.aktcl.aron.feature.home.RoleHero
import com.aktcl.aron.feature.home.RoleHomeTags
import com.aktcl.aron.feature.home.RoleTile
import com.aktcl.aron.feature.home.R as HomeR
import com.aktcl.aron.core.ui.R as CoreR

/**
 * The tile icons in the existing-Aron line-art style (PARITY-SPEC s2.11). A destination the kit has not drawn yet returns null and
 * the tile shows the kit's gradient placeholder square until ui-kit lands the drawable.
 */
@DrawableRes
internal fun amoTileIcon(tileId: String): Int? = when (tileId) {
    "attendance" -> CoreR.drawable.aron_art_attendance
    "joint_call" -> CoreR.drawable.aron_ic_joint_call
    "control_call" -> CoreR.drawable.aron_ic_control_call
    "team_location" -> CoreR.drawable.aron_ic_team_location
    "team_performance" -> CoreR.drawable.aron_ic_team_performance
    "task_delegation" -> CoreR.drawable.aron_art_task_delegation
    "live_dashboard" -> CoreR.drawable.aron_ic_live_dashboard
    "sr_stock" -> CoreR.drawable.aron_ic_sr_stock
    "outlet" -> CoreR.drawable.aron_art_outlet
    "stock" -> CoreR.drawable.aron_art_stock
    "sale" -> CoreR.drawable.aron_art_sale
    "memo" -> CoreR.drawable.aron_art_memo
    "summary" -> CoreR.drawable.aron_art_summary
    "sales_submit" -> CoreR.drawable.aron_art_sales_submit
    "report" -> CoreR.drawable.aron_ic_report
    "sales_journey" -> CoreR.drawable.aron_art_sales_journey
    "settings" -> CoreR.drawable.aron_art_settings
    else -> null
}

@Composable
private fun tilePainter(tileId: String): Painter? = amoTileIcon(tileId)?.let { painterResource(it) }

@Composable
private fun inkStyle(size: Int, bold: Boolean = false, color: Color = LocalAronLook.current.ink) =
    TextStyle(fontFamily = AronFonts.forLanguage(LocalAppLanguage.current), fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = color)

/** A notice on the grey or white page: a white card with a red or purple edge. Replaces the glass banners on the parity screens. */
@Composable
fun AmoNotice(text: String, modifier: Modifier = Modifier, warning: Boolean = false) {
    val look = LocalAronLook.current
    Row(modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(look.card), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 6.dp, height = 48.dp).background(if (warning) look.red else look.purple))
        Text(text, style = inkStyle(14), modifier = Modifier.padding(12.dp))
    }
}

/** The "Target and achievement" card of the Home (sr/home-2-kpis.webp), here the team's day so far: sales and visits; no target percentage (docs/27). */
@Composable
private fun AmoDayCard(hero: RoleHero.Amo, money: String, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(look.targetTop, look.targetBottom))).padding(16.dp).testTag(RoleHomeTags.HERO),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(HomeR.string.rh_hero_amo_sales), style = inkStyle(14, color = look.onBar))
        Text(money, style = inkStyle(28, bold = true, color = look.onBar))
        Text(
            stringResource(HomeR.string.rh_hero_amo_visits) + ": " + localizedNumber(hero.visits.toLong()) +
                (hero.visitTarget?.let { " (" + stringResource(HomeR.string.rh_hero_amo_visits_of, localizedNumber(it.toLong())) + ")" } ?: ""),
            style = inkStyle(14, color = look.onBar),
        )
    }
}

/** The AMO Home header lines (screenshots 8, 9; R3): "<name> (<username>)" over "<zone name> AMO, <business date>". */
object AmoHomeHeader {
    /** [zoneFormat] = `amo_shell_header_zone` ("%1$s AMO, %2$s"), [plainFormat] = `amo_shell_header_plain` ("AMO, %1$s"): the words live in the string layer. */
    fun of(user: HomeUser, businessDate: String, zoneName: String?, zoneFormat: String, plainFormat: String): HomeHeader {
        val zone = zoneName?.trim().orEmpty()
        return HomeHeader(
            title = "${user.fullName.trim()} (${user.username})",
            subtitle = if (zone.isEmpty()) plainFormat.format(businessDate) else zoneFormat.format(zone, businessDate),
            routeKind = null, routeName = null, isoDate = businessDate,
        )
    }
}

private data class Card(val key: String, @androidx.annotation.StringRes val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector, val target: CallTarget?)

/** The four target cards (screenshot 9): sky-blue rounded cards, a white icon, the white label and the white bold "done/target" or "—". */
@Composable
private fun AmoTargetCards(targets: CallTargets?, modifier: Modifier = Modifier, status: CallTargetsStatus = CallTargetsStatus.Fresh) {
    val look = LocalAronLook.current
    val dash = stringResource(R.string.amo_shell_no_target)
    val cards = listOf(
        Card("today", R.string.amo_shell_target_today, AronIcons.Place, targets?.today),
        Card("total", R.string.amo_shell_target_total, AronIcons.Phone, targets?.month),
        Card("control", R.string.amo_shell_target_control, AronIcons.Sale, targets?.control),
        Card("joint", R.string.amo_shell_target_joint, AronIcons.Team, targets?.joint),
    )
    Column(
        modifier.fillMaxWidth().testTag(AmoHomeTags.TARGETS).clip(RoundedCornerShape(12.dp)).background(look.card).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        cards.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (key, label, icon, t) ->
                    val figure = localizedDigits(CallTargetMath.figure(t, dash))
                    Column(
                        Modifier.weight(1f).testTag(AmoHomeTags.target(key)).clip(RoundedCornerShape(12.dp)).background(look.targetCard ?: look.targetTop).padding(12.dp)
                            .semantics(mergeDescendants = true) { },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        androidx.compose.material3.Icon(icon, contentDescription = null, tint = look.onPanel, modifier = Modifier.size(24.dp))
                        Text(stringResource(label), style = inkStyle(14, color = look.onPanel))
                        Text(figure, style = inkStyle(22, bold = true, color = look.onPanel))
                    }
                }
            }
        }
        // the state line: shown only when the numbers are the kept copy (stale) or could not be read at all
        val note = when (status) {
            CallTargetsStatus.Stale -> R.string.amo_shell_targets_stale
            CallTargetsStatus.Failed -> R.string.amo_shell_targets_failed
            CallTargetsStatus.Fresh -> null
        }
        if (note != null) Text(stringResource(note), style = MaterialTheme.typography.bodySmall.copy(color = look.ink), modifier = Modifier.testTag(AmoHomeTags.TARGETS_NOTE))
    }
}

object AmoHomeTags {
    const val LANGUAGE = "amo_set_language"
    const val TARGETS = "amo_targets"
    const val COLLAPSE = "amo_collapse"
    const val LOGOUT_DIALOG = "amo_logout_dialog"
    const val TARGETS_NOTE = "amo_targets_note"
    const val LOGOUT_YES = "amo_logout_yes"
    const val LOGOUT_NO = "amo_logout_no"
    /** Stable names: today, total, control, joint. */
    fun target(key: String) = "amo_target_$key"
}

/**
 * The AMO Home in the existing-Aron look (sr/home-1-tiles.webp): gradient header, white page, the tile grid, the day card, then the quiet
 * status lines (offline, bundle, device) that the offline-first rules keep. Stand-alone from feature-home's RoleHomeContent (lane ui-sr-a owns that).
 */
@Composable
fun AmoHomeContent(
    header: HomeHeader,
    user: HomeUser,
    bundle: BundleStatus,
    tiles: List<RoleTile>,
    onTile: (RoleTile) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    hero: RoleHero? = null,
    health: DeviceHealth? = null,
    banners: List<RoleBanner> = emptyList(),
    /** The quiet sync state under the tiles; tapping opens the sync sheet when the host has one (null: not tappable). */
    sync: SyncChipState = SyncChipState.Synced,
    onSync: (() -> Unit)? = null,
    /** The four target cards; null prints "—" on each (the server endpoint has not answered or is not built yet). */
    targets: CallTargets? = null,
    targetsStatus: CallTargetsStatus = CallTargetsStatus.Fresh,
    /** The day card's money text (milli-taka in the user's digits), formatted by the host. */
    money: @Composable (Long) -> String = { com.aktcl.aron.rules.Formats.money(it, if (LocalAppLanguage.current == AppLanguage.BN) com.aktcl.aron.rules.UiLocale.BN else com.aktcl.aron.rules.UiLocale.EN) },
) {
    val look = LocalAronLook.current
    val dateSpoken = stringResource(HomeR.string.home_business_date, localizedDigits(header.isoDate.orEmpty()))
    AronScaffold(
        topBar = {
            AronHomeHeader(
                header.title, localizedDigits(header.subtitle), onSettings, stringResource(HomeR.string.set_open),
                Modifier.testTag(HomeTags.HEADER).semantics { contentDescription = header.title + ", " + dateSpoken },
                showSettings = false,
            )
        },
        modifier = modifier, home = true,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).padding(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (user.offline) AmoNotice(stringResource(CoreR.string.core_ui_offline_mode), Modifier.testTag(HomeTags.MODE))
            if (user.reauthRequired) AmoNotice(stringResource(HomeR.string.home_reauth_required), warning = true)
            if (user.updateRequired) AmoNotice(stringResource(HomeR.string.home_update_required), warning = true)
            banners.forEach { AmoNotice(it.text) }
            // the vendor grid (D-AMO-MAN-02): three columns on a white card; the chevron below it folds the grid to show the target cards
            var gridOpen by rememberSaveable { mutableStateOf(true) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(look.card).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (gridOpen) AronTileGrid(tiles, columns = 3, gap = 8.dp) { t, m ->
                    AronTile(
                        stringResource(t.label), tilePainter(t.id), onClick = { onTile(t) }, modifier = m.testTag(RoleHomeTags.tile(t.id)),
                        badge = t.badge, badgeDescription = if (t.badge > 0) stringResource(CoreR.string.core_ui_tile_badge, localizedNumber(t.badge.toLong())) else null,
                        showZeroBadge = t.id == AmoMenu.tileId(AmoLeaf.OUTLET),
                    )
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag(AmoHomeTags.COLLAPSE)
                        .clickable(role = Role.Button, onClickLabel = stringResource(if (gridOpen) R.string.amo_shell_fold else R.string.amo_shell_unfold)) { gridOpen = !gridOpen },
                    contentAlignment = Alignment.Center,
                ) { androidx.compose.material3.Icon(if (gridOpen) AronIcons.ExpandLess else AronIcons.ExpandMore, contentDescription = null, tint = look.choiceFill ?: look.purple, modifier = Modifier.size(28.dp)) }
            }
            AmoTargetCards(targets, status = targetsStatus)
            (hero as? RoleHero.Amo)?.let { h -> AmoDayCard(h, money(h.teamSalesMtk), Modifier.padding(horizontal = 4.dp)) }
            // the shared sync-status footer of the AMO and TSO homes: the sync state, then what today's data check found and the device figures (F-SR-064)
            val status = amoSyncText(sync)
            val details = buildList {
                if (!(user.offline && bundle == BundleStatus.Offline)) add(amoBundleLine(bundle))
                health?.let { h ->
                    add(stringResource(HomeR.string.hm_health_battery, localizedNumber(h.batteryPct.toLong())))
                    add(stringResource(HomeR.string.hm_health_storage, localizedNumber(h.freeStorageMb)))
                    add(stringResource(HomeR.string.hm_health_pending, localizedNumber(h.pendingRows.toLong())))
                    add(h.lastSyncAgeMin?.let { stringResource(HomeR.string.hm_health_sync, localizedNumber(it)) } ?: stringResource(HomeR.string.hm_health_never_synced))
                }
            }
            val tap = if (onSync != null) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(CoreR.string.core_ui_chip_open_sync), onClick = onSync) else Modifier
            AronSyncFooter(
                status, details, Modifier.testTag(RoleHomeTags.SYNC).then(tap),
                synced = sync is SyncChipState.Synced || sync is SyncChipState.SyncedAt,
            )
            if (health?.anyWarning == true) AmoNotice(stringResource(HomeR.string.hm_health_warning), Modifier.padding(horizontal = 4.dp), warning = true)
        }
    }
}

@Composable
private fun amoSyncText(state: SyncChipState): String = when (state) {
    SyncChipState.Offline -> stringResource(CoreR.string.core_ui_chip_offline)
    SyncChipState.Syncing -> stringResource(CoreR.string.core_ui_chip_syncing)
    SyncChipState.Synced -> stringResource(CoreR.string.core_ui_chip_synced)
    is SyncChipState.SyncedAt -> stringResource(CoreR.string.core_ui_chip_synced_at, state.timeText)
    is SyncChipState.Waiting -> stringResource(CoreR.string.core_ui_chip_waiting, localizedNumber(state.count.toLong()))
}

/** A date-like bundle version ("2026-11-07:1") takes locale digits; an identifier with letters ("b-17") is shown as it is. */
@Composable
internal fun bundleVersionText(v: String): String = if (v.none { it.isLetter() }) localizedDigits(v) else v

@Composable
private fun amoBundleLine(status: BundleStatus): String = when (status) {
    BundleStatus.Checking -> stringResource(HomeR.string.home_bundle_checking)
    is BundleStatus.Ready -> stringResource(HomeR.string.home_bundle_ready, localizedDigits(status.validFor), bundleVersionText(status.bundleVersion))
    BundleStatus.Unchanged -> stringResource(HomeR.string.home_bundle_unchanged)
    BundleStatus.Offline -> stringResource(HomeR.string.home_bundle_offline)
    is BundleStatus.Refused -> stringResource(HomeR.string.home_bundle_refused, status.httpStatus.toString(), status.code ?: "-")
}

@Composable
private fun SettingRow(label: String, modifier: Modifier = Modifier, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    val look = LocalAronLook.current
    Row(
        modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(look.card)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = inkStyle(16, color = look.tileLabel))
            if (subtitle != null) Text(subtitle, style = inkStyle(12, color = look.inkMuted))
        }
        trailing?.invoke()
    }
}

/** One square tile of the Settings row (screenshot 75): light grey, thin border; [caption] under the content in bold purple, none for the language tile. */
@Composable
private fun SettingTile(modifier: Modifier, onClick: () -> Unit, caption: String?, row: Boolean = false, content: @Composable () -> Unit) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.heightIn(min = 112.dp).clip(shape).background(look.tileMenu).border(1.dp, look.tileHairline, shape).clickable(role = Role.Button, onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (row) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() } else content()
        if (caption != null) Text(caption, style = inkStyle(14, bold = true, color = look.tileLabel), textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun TileDisc(icon: androidx.compose.ui.graphics.vector.ImageVector, brush: Brush) {
    Box(Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape).background(brush), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

/** The small flag of the language tile and its menu rows (no flag icon in the kit): a pole and a waving cloth. */
@Composable
private fun FlagGlyph(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val w = size.width
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.2f, w * 0.08f), androidx.compose.ui.geometry.Offset(w * 0.2f, w * 0.95f), strokeWidth = w * 0.1f)
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.25f, w * 0.12f); cubicTo(w * 0.5f, w * 0.0f, w * 0.6f, w * 0.3f, w * 0.9f, w * 0.15f)
            lineTo(w * 0.9f, w * 0.6f); cubicTo(w * 0.6f, w * 0.75f, w * 0.5f, w * 0.45f, w * 0.25f, w * 0.58f); close()
        }
        drawPath(p, color)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = inkStyle(16, bold = true, color = LocalAronLook.current.purple), modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() })
}

/**
 * The AMO Settings in the existing-Aron look: language, display (Sunlight, the optional outdoor theme), about (version, update, PDA to
 * Support) and Log out with its confirmation. Same tags and callbacks as the shared Settings it replaces.
 */
@Composable
fun AmoSettingsContent(
    versionText: String,
    logoutMessage: String,
    onLanguageSelect: (AppLanguage) -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    sunlight: Boolean = false,
    onSunlight: ((Boolean) -> Unit)? = null,
    onUpdate: (() -> Unit)? = null,
    updateAvailable: Boolean = false,
    onSupport: (() -> Unit)? = null,
    /** "Printer diagnostics" (D-PRINT-02); shown when non-null. */
    onPrinter: (() -> Unit)? = null,
    /** Exceptions (risk signals): no Home tile in the vendor menu, so it is a Settings row (D-AMO-SHELL-03); shown when non-null. */
    onExceptions: (() -> Unit)? = null,
) {
    val look = LocalAronLook.current
    val language = LocalAppLanguage.current
    var confirm by rememberSaveable { mutableStateOf(false) }
    AronScaffold(
        topBar = { AronTopBar(stringResource(HomeR.string.set_title), icon = painterResource(CoreR.drawable.aron_ic_gear), onBack = onBack, backLabel = stringResource(CoreR.string.core_ui_back)) },
        modifier = modifier,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // screenshot 75 (R16): one row of three tiles: the language drop-down ("en" / "বাংলা"), PDA টু সাপোর্ট and লগ আউট
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                var menu by rememberSaveable { mutableStateOf(false) }
                Box(Modifier.weight(1f)) {
                    SettingTile(Modifier.testTag(AmoHomeTags.LANGUAGE), onClick = { menu = true }, caption = null, row = true) {
                        FlagGlyph(look.ink)
                        Text(
                            stringResource(if (language == AppLanguage.BN) CoreR.string.core_ui_language_bangla else R.string.amo_shell_lang_en),
                            style = inkStyle(16, color = look.purple),
                        )
                        androidx.compose.material3.Icon(AronIcons.ExpandMore, contentDescription = null, tint = look.purple, modifier = Modifier.size(20.dp))
                    }
                    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = look.card) {
                        listOf(AppLanguage.EN to RoleHomeTags.SET_LANG_EN, AppLanguage.BN to RoleHomeTags.SET_LANG_BN).forEach { (l, tag) ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(if (l == AppLanguage.BN) CoreR.string.core_ui_language_bangla else R.string.amo_shell_lang_en), style = inkStyle(16, color = look.purple)) },
                                leadingIcon = { FlagGlyph(look.ink) },
                                onClick = { menu = false; onLanguageSelect(l) },
                                modifier = Modifier.testTag(tag).semantics { selected = language == l },
                            )
                        }
                    }
                }
                if (onSupport != null) SettingTile(Modifier.weight(1f).testTag(RoleHomeTags.SET_SUPPORT), onClick = onSupport, caption = stringResource(HomeR.string.set_support)) {
                    TileDisc(AronIcons.Submit, Brush.verticalGradient(listOf(Color(0xFFE53570), Color(0xFF2F86F0))))
                } else Spacer(Modifier.weight(1f))
                SettingTile(Modifier.weight(1f).testTag(HomeTags.LOGOUT), onClick = { confirm = true }, caption = stringResource(HomeR.string.set_logout)) {
                    TileDisc(AronIcons.Logout, Brush.verticalGradient(listOf(Color(0xFF4B1C97), Color(0xFFC0338E))))
                }
            }
            if (onSunlight != null) {
                SectionTitle(stringResource(HomeR.string.rh_set_display))
                SettingRow(
                    stringResource(HomeR.string.rh_set_sunlight),
                    Modifier.testTag(RoleHomeTags.SET_SUNLIGHT).semantics { role = Role.Switch; toggleableState = ToggleableState(sunlight) },
                    subtitle = stringResource(HomeR.string.rh_set_sunlight_hint), onClick = { onSunlight(!sunlight) },
                ) {
                    Switch(
                        sunlight, onCheckedChange = onSunlight, modifier = Modifier.clearAndSetSemantics { },
                        colors = SwitchDefaults.colors(checkedTrackColor = look.purple, checkedThumbColor = Color.White, uncheckedTrackColor = look.disabledFill, uncheckedThumbColor = look.inkMuted),
                    )
                }
            }
            SectionTitle(stringResource(HomeR.string.rh_set_about))
            SettingRow(stringResource(HomeR.string.set_version, localizedDigits(versionText)), Modifier.testTag(RoleHomeTags.SET_VERSION))
            if (onUpdate != null) {
                SettingRow(
                    stringResource(if (updateAvailable) HomeR.string.set_update_available else HomeR.string.set_update), Modifier.testTag(RoleHomeTags.SET_UPDATE), onClick = onUpdate,
                ) { Text("›", style = inkStyle(24, color = look.inkMuted), modifier = Modifier.clearAndSetSemantics { }) }
            }
            if (onPrinter != null) {
                SettingRow(stringResource(HomeR.string.set_printer), Modifier.testTag(RoleHomeTags.SET_PRINTER), onClick = onPrinter) {
                    Text("›", style = inkStyle(24, color = look.inkMuted), modifier = Modifier.clearAndSetSemantics { })
                }
            }
            if (onExceptions != null) {
                SettingRow(stringResource(R.string.amo_exc_title), Modifier.testTag("amo_set_exceptions"), onClick = onExceptions) {
                    Text("›", style = inkStyle(24, color = look.inkMuted), modifier = Modifier.clearAndSetSemantics { })
                }
            }
        }
    }
    if (confirm) {
        AmoLogoutDialog(
            stringResource(HomeR.string.set_logout_title), logoutMessage, stringResource(HomeR.string.set_logout_confirm), stringResource(HomeR.string.set_cancel),
            onConfirm = { confirm = false; onLogout() }, onDismiss = { confirm = false },
        )
    }
}

/**
 * The log-out confirmation of screenshot 77: a white card with large round corners, the blue "i" disc, the question centred in blue, and
 * a green "yes" (left) and a red "no" (right) as small rounded rectangles, white bold labels. Fills are the deep AMO tokens (7:1).
 * The data-kept note of the role (F-SYS-022; not on the vendor dialog, a standing rule) stays as one small line under the question.
 */
@Composable
internal fun AmoLogoutDialog(question: String, note: String, yes: String, no: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val look = LocalAronLook.current
    val blue = look.choiceFill ?: look.purple
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().testTag(AmoHomeTags.LOGOUT_DIALOG).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            androidx.compose.material3.Icon(AronIcons.Info, contentDescription = null, tint = blue, modifier = Modifier.size(44.dp))
            Text(question, style = inkStyle(18, bold = true, color = blue), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(note, style = MaterialTheme.typography.bodySmall.copy(color = LocalAronLook.current.ink), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                AmoDialogButton(yes, look.primaryStart ?: look.purple, AmoHomeTags.LOGOUT_YES, onConfirm)
                AmoDialogButton(no, look.stopFill ?: look.red, AmoHomeTags.LOGOUT_NO, onDismiss)
            }
        }
    }
}

/** A small rounded-rectangle button of the AMO dialogs: white bold label on a deep [fill] (7:1), at least 48 dp tall. */
@Composable
private fun AmoDialogButton(label: String, fill: Color, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.testTag(tag).heightIn(min = 48.dp).widthIn(min = 72.dp).clip(RoundedCornerShape(8.dp)).background(fill)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = inkStyle(16, bold = true, color = Color.White)) }
}
