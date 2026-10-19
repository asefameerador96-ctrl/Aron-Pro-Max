package com.aktcl.aron.feature.amo

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronFonts
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.core.ui.aron.AronHomeHeader
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.aron.AronScaffold
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
    com.aktcl.aron.feature.home.RoleHomes.AmoTile.ATTENDANCE -> CoreR.drawable.aron_art_attendance
    com.aktcl.aron.feature.home.RoleHomes.AmoTile.SALE -> CoreR.drawable.aron_art_sale
    com.aktcl.aron.feature.home.RoleHomes.AmoTile.OUTLET -> CoreR.drawable.aron_art_outlet
    com.aktcl.aron.feature.home.RoleHomes.AmoTile.SALES_SUBMIT -> CoreR.drawable.aron_art_sales_submit
    com.aktcl.aron.feature.home.RoleHomes.AmoTile.TEAM -> CoreR.drawable.aron_ic_team
    com.aktcl.aron.feature.home.RoleHomes.AmoTile.REPORT -> CoreR.drawable.aron_ic_report
    com.aktcl.aron.feature.home.RoleHomes.AmoTile.JOINT_CALL -> CoreR.drawable.aron_ic_joint_call
    com.aktcl.aron.feature.home.RoleHomes.AmoTile.CONTROL_CALL -> CoreR.drawable.aron_ic_control_call
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
    /** The day card's money text (milli-taka in the user's digits), formatted by the host. */
    money: @Composable (Long) -> String = { com.aktcl.aron.rules.Formats.money(it, if (LocalAppLanguage.current == AppLanguage.BN) com.aktcl.aron.rules.UiLocale.BN else com.aktcl.aron.rules.UiLocale.EN) },
) {
    val look = LocalAronLook.current
    val dateSpoken = stringResource(HomeR.string.home_business_date, localizedDigits(header.subtitle))
    AronScaffold(
        topBar = {
            AronHomeHeader(
                header.title, localizedDigits(header.subtitle), onSettings, stringResource(HomeR.string.set_open),
                Modifier.testTag(HomeTags.HEADER).semantics { contentDescription = header.title + ", " + dateSpoken },
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
            AronTileGrid(tiles) { t, m ->
                AronTile(
                    stringResource(t.label), tilePainter(t.id), onClick = { onTile(t) }, modifier = m.testTag(RoleHomeTags.tile(t.id)),
                    badge = t.badge, badgeDescription = if (t.badge > 0) stringResource(CoreR.string.core_ui_tile_badge, localizedNumber(t.badge.toLong())) else null,
                )
            }
            (hero as? RoleHero.Amo)?.let { h -> AmoDayCard(h, money(h.teamSalesMtk), Modifier.padding(horizontal = 4.dp)) }
            // the quiet status lines: the sync state, what today's data check found, and the device figures (F-SR-064)
            AmoStatusLine(sync, onSync, Modifier.padding(horizontal = 4.dp).testTag(RoleHomeTags.SYNC))
            if (!(user.offline && bundle == BundleStatus.Offline)) {
                Text(amoBundleLine(bundle), style = inkStyle(12, color = look.inkMuted), modifier = Modifier.padding(horizontal = 8.dp).testTag(HomeTags.BUNDLE))
            }
            health?.let { h ->
                Column(Modifier.padding(horizontal = 8.dp).testTag(RoleHomeTags.DEVICE), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(
                        stringResource(HomeR.string.hm_health_battery, localizedNumber(h.batteryPct.toLong())),
                        stringResource(HomeR.string.hm_health_storage, localizedNumber(h.freeStorageMb)),
                        stringResource(HomeR.string.hm_health_pending, localizedNumber(h.pendingRows.toLong())),
                        h.lastSyncAgeMin?.let { stringResource(HomeR.string.hm_health_sync, localizedNumber(it)) } ?: stringResource(HomeR.string.hm_health_never_synced),
                    ).forEach { Text(it, style = inkStyle(12, color = look.inkMuted)) }
                }
                if (h.anyWarning) AmoNotice(stringResource(HomeR.string.hm_health_warning), Modifier.padding(horizontal = 4.dp), warning = true)
            }
        }
    }
}

/** The sync state as one quiet line in token colours only: a purple check and grey ink (a warning state uses the red token); taps open the sync sheet when the host has one. */
@Composable
private fun AmoStatusLine(state: SyncChipState, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val text = when (state) {
        SyncChipState.Offline -> stringResource(CoreR.string.core_ui_chip_offline)
        SyncChipState.Syncing -> stringResource(CoreR.string.core_ui_chip_syncing)
        SyncChipState.Synced -> stringResource(CoreR.string.core_ui_chip_synced)
        is SyncChipState.SyncedAt -> stringResource(CoreR.string.core_ui_chip_synced_at, state.timeText)
        is SyncChipState.Waiting -> stringResource(CoreR.string.core_ui_chip_waiting, localizedNumber(state.count.toLong()))
    }
    val bad = state is SyncChipState.Offline || state is SyncChipState.Waiting
    val glyph = when (state) { SyncChipState.Offline -> "!"; is SyncChipState.Waiting -> "!"; SyncChipState.Syncing -> "…"; else -> "✓" }
    Row(
        modifier.heightIn(min = 48.dp).let { if (onClick != null) it.clickable(role = Role.Button, onClickLabel = stringResource(CoreR.string.core_ui_chip_open_sync), onClick = onClick) else it },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(glyph, style = inkStyle(16, bold = true, color = if (bad) look.red else look.purple), modifier = Modifier.clearAndSetSemantics { })
        Text(text, style = inkStyle(14, bold = bad, color = if (bad) look.red else look.inkMuted))
    }
}

@Composable
private fun amoBundleLine(status: BundleStatus): String = when (status) {
    BundleStatus.Checking -> stringResource(HomeR.string.home_bundle_checking)
    is BundleStatus.Ready -> stringResource(HomeR.string.home_bundle_ready, localizedDigits(status.validFor), status.bundleVersion)
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
            SectionTitle(stringResource(HomeR.string.set_language))
            val tick: @Composable (Boolean) -> Unit = { on -> if (on) Text("✓", style = inkStyle(20, bold = true, color = look.purple), modifier = Modifier.clearAndSetSemantics { }) }
            SettingRow(stringResource(CoreR.string.core_ui_language_bangla), Modifier.testTag(RoleHomeTags.SET_LANG_BN).semantics { selected = language == AppLanguage.BN }, onClick = { onLanguageSelect(AppLanguage.BN) }) { tick(language == AppLanguage.BN) }
            SettingRow(stringResource(CoreR.string.core_ui_language_english), Modifier.testTag(RoleHomeTags.SET_LANG_EN).semantics { selected = language == AppLanguage.EN }, onClick = { onLanguageSelect(AppLanguage.EN) }) { tick(language == AppLanguage.EN) }
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
            if (onSupport != null) {
                SettingRow(stringResource(HomeR.string.set_support), Modifier.testTag(RoleHomeTags.SET_SUPPORT), onClick = onSupport) {
                    Text("›", style = inkStyle(24, color = look.inkMuted), modifier = Modifier.clearAndSetSemantics { })
                }
            }
            AronPrimaryButton(stringResource(HomeR.string.set_logout), { confirm = true }, Modifier.fillMaxWidth().padding(top = 12.dp).testTag(HomeTags.LOGOUT))
        }
    }
    if (confirm) {
        AronConfirmDialog(
            stringResource(HomeR.string.set_logout_title), logoutMessage,
            stringResource(HomeR.string.set_logout_confirm), stringResource(HomeR.string.set_cancel),
            onConfirm = { confirm = false; onLogout() }, onDismiss = { confirm = false },
        )
    }
}
