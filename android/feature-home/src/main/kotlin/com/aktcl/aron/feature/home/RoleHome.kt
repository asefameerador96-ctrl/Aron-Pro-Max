package com.aktcl.aron.feature.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIconButton
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronSectionLabel
import com.aktcl.aron.core.ui.AronStatCard
import com.aktcl.aron.core.ui.AronStatusChip
import com.aktcl.aron.core.ui.AronTile
import com.aktcl.aron.core.ui.AronTileGrid
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.ChipForm
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.core.ui.SunlightToggle
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale
import com.aktcl.aron.core.ui.R as CoreR

/**
 * Test tags of the AMO and TSO Home frame. The header, date, offline banner, bundle line and logout keep the Day-1
 * [HomeTags], so the earlier shell tests still find them.
 */
object RoleHomeTags {
    const val SETTINGS = "rh_settings"
    const val SUNLIGHT = "rh_sunlight"
    const val SYNC = "rh_sync"
    const val HERO = "rh_hero"
    const val DEVICE = "rh_device"
    const val EMPTY = "rh_empty"
    const val OTP_URL = "rh_otp_url"
    const val SET_VERSION = "rh_set_version"
    const val SET_LANG_BN = "rh_set_lang_bn"
    const val SET_LANG_EN = "rh_set_lang_en"
    const val SET_SUNLIGHT = "rh_set_sunlight"
    const val SET_UPDATE = "rh_set_update"
    fun tile(id: String) = "rh_tile_$id"
}

/** The labelled groups of the Home grid; a role's catalogue puts each tile in one, keeping the reference order. */
enum class RoleTileGroup(@StringRes val label: Int, val compact: Boolean) {
    Today(R.string.rh_section_today, compact = false),
    Others(R.string.rh_section_others, compact = true),
    DayClose(R.string.rh_section_day_close, compact = true),
}

/** One Home tile: a stable [id] the host maps to a screen, its label resource, icon, badge (hidden at zero) and group. */
@Immutable
data class RoleTile(val id: String, @StringRes val label: Int, val icon: ImageVector, val badge: Int = 0, val group: RoleTileGroup = RoleTileGroup.Today)

/** The optional hero row above the tiles; hidden when the host has nothing local to show yet. */
@Immutable
sealed interface RoleHero {
    /** AMO: the team's sales so far today (milli-taka) and the visits made, with the planned count when known. */
    data class Amo(val teamSalesMtk: Long, val visits: Int, val visitTarget: Int? = null) : RoleHero
    /** TSO: zones with a Final Submit today out of the zones of the territory. */
    data class Tso(val zonesSubmitted: Int, val zonesTotal: Int) : RoleHero
}

/** An extra banner the host wants on Home (for example "bundle downloading"). */
@Immutable
data class RoleBanner(val text: String, val kind: BannerKind = BannerKind.Info)

/** The tile catalogues of the two role apps and the small pure helpers the hosts share. */
object RoleHomes {
    const val AMO_PREFIX = "AMO"
    const val TSO_PREFIX = "TSO"

    object AmoTile {
        const val ATTENDANCE = "attendance"; const val CONTROL_CALL = "control_call"; const val JOINT_CALL = "joint_call"; const val SALE = "sale"
        const val TEAM = "team"; const val OUTLET = "outlet"; const val SALES_SUBMIT = "sales_submit"; const val REPORT = "report"
    }

    object TsoTile {
        const val DASHBOARD = "dashboard"; const val LEAVE = "leave"; const val FINAL_SUBMIT = "final_submit"; const val PERIPHERY = "periphery"
        const val MY_CALL = "my_call"; const val FEEDBACK = "feedback"; const val DEVICE_OTP = "device_otp"
    }

    /**
     * AMO tiles in docs/07 order: the six daily tiles (3 x 2), then Sales Submit and Report as the day-close group. Astha is
     * deferred (docs/27) and has no entry. [badges] by tile id (the Outlet tile carries pending verifications).
     */
    fun amoTiles(badges: Map<String, Int> = emptyMap()): List<RoleTile> = listOf(
        RoleTile(AmoTile.ATTENDANCE, R.string.rh_amo_attendance, AronIcons.Attendance, badges[AmoTile.ATTENDANCE] ?: 0, RoleTileGroup.Today),
        RoleTile(AmoTile.CONTROL_CALL, R.string.rh_amo_control_call, AronIcons.Place, badges[AmoTile.CONTROL_CALL] ?: 0, RoleTileGroup.Today),
        RoleTile(AmoTile.JOINT_CALL, R.string.rh_amo_joint_call, AronIcons.Team, badges[AmoTile.JOINT_CALL] ?: 0, RoleTileGroup.Today),
        RoleTile(AmoTile.SALE, R.string.rh_amo_sale, AronIcons.Sale, badges[AmoTile.SALE] ?: 0, RoleTileGroup.Today),
        RoleTile(AmoTile.TEAM, R.string.rh_amo_team, AronIcons.Team, badges[AmoTile.TEAM] ?: 0, RoleTileGroup.Today),
        RoleTile(AmoTile.OUTLET, R.string.rh_amo_outlet, AronIcons.Outlet, badges[AmoTile.OUTLET] ?: 0, RoleTileGroup.Today),
        RoleTile(AmoTile.SALES_SUBMIT, R.string.rh_amo_sales_submit, AronIcons.Submit, badges[AmoTile.SALES_SUBMIT] ?: 0, RoleTileGroup.DayClose),
        RoleTile(AmoTile.REPORT, R.string.rh_amo_report, AronIcons.Trend, badges[AmoTile.REPORT] ?: 0, RoleTileGroup.DayClose),
    )

    /** TSO tiles in docs/08 drawer order (the six drawer items, 3 x 2), then Device OTP, which opens the "read it on the web" page; Target Status is deferred (docs/27). */
    fun tsoTiles(badges: Map<String, Int> = emptyMap()): List<RoleTile> = listOf(
        RoleTile(TsoTile.DASHBOARD, R.string.rh_tso_dashboard, AronIcons.Dashboard, badges[TsoTile.DASHBOARD] ?: 0, RoleTileGroup.Today),
        RoleTile(TsoTile.LEAVE, R.string.rh_tso_leave, AronIcons.Leave, badges[TsoTile.LEAVE] ?: 0, RoleTileGroup.Today),
        RoleTile(TsoTile.FINAL_SUBMIT, R.string.rh_tso_final_submit, AronIcons.Verified, badges[TsoTile.FINAL_SUBMIT] ?: 0, RoleTileGroup.Today),
        RoleTile(TsoTile.PERIPHERY, R.string.rh_tso_periphery, AronIcons.Explore, badges[TsoTile.PERIPHERY] ?: 0, RoleTileGroup.Today),
        RoleTile(TsoTile.MY_CALL, R.string.rh_tso_my_call, AronIcons.Place, badges[TsoTile.MY_CALL] ?: 0, RoleTileGroup.Today),
        RoleTile(TsoTile.FEEDBACK, R.string.rh_tso_feedback, AronIcons.Feedback, badges[TsoTile.FEEDBACK] ?: 0, RoleTileGroup.Today),
        RoleTile(TsoTile.DEVICE_OTP, R.string.rh_tso_device_otp, AronIcons.Lock, badges[TsoTile.DEVICE_OTP] ?: 0, RoleTileGroup.Others),
    )

    /** `AMO - name (username)` over the ISO business date (F-SR-008 wording, no route for these roles). */
    fun header(rolePrefix: String, user: HomeUser, businessDate: String): HomeHeader =
        HomeModel.header(rolePrefix, user.fullName, user.username, null, null, businessDate)

    /** The status chip from what the bundle check said; an offline unlock is offline whatever the check says. Pure. */
    fun syncState(bundle: BundleStatus, offline: Boolean): SyncChipState = when {
        offline -> SyncChipState.Offline
        bundle == BundleStatus.Checking -> SyncChipState.Syncing
        bundle is BundleStatus.Ready || bundle == BundleStatus.Unchanged -> SyncChipState.Synced
        else -> SyncChipState.Offline
    }
}

@Composable
private fun money(mtk: Long): String = Formats.money(mtk, if (LocalAppLanguage.current == AppLanguage.BN) UiLocale.BN else UiLocale.EN)

@Composable
private fun bundleLine(status: BundleStatus): String = when (status) {
    BundleStatus.Checking -> stringResource(R.string.home_bundle_checking)
    // Only the date takes Bengali digits; the bundle version and codes are identifiers and stay as sent.
    is BundleStatus.Ready -> stringResource(R.string.home_bundle_ready, localizedDigits(status.validFor), status.bundleVersion)
    BundleStatus.Unchanged -> stringResource(R.string.home_bundle_unchanged)
    BundleStatus.Offline -> stringResource(R.string.home_bundle_offline)
    is BundleStatus.Refused -> stringResource(R.string.home_bundle_refused, status.httpStatus.toString(), status.code ?: "-")
}

/**
 * The AMO and TSO Home (screens-sr s2 frame, android-glass s13): avatar initial, `ROLE - name (username)`, the business
 * date, the sun switch and the gear; the status row (sync chip and the bundle line); the offline, login-expired and
 * update banners; an optional hero row; the tiles in labelled groups; the device line. Everything is local state:
 * nothing here waits for the network. Language and logout live in [RoleSettingsContent].
 */
@Composable
fun RoleHomeContent(
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
    sunlight: Boolean = false,
    onSunlight: (Boolean) -> Unit = {},
    banners: List<RoleBanner> = emptyList(),
    /** Opens the sync detail sheet from the chip (screens-sr s0); null until the role's sheet lands (chip not tappable). */
    onSync: (() -> Unit)? = null,
) {
    val c = LocalAronColors.current
    // the tile groups are fixed for a catalogue: not rebuilt when a chip, banner or hero changes
    val groups = remember(tiles) { tiles.groupBy { it.group }.toSortedMap() }
    AronScreen(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // no bottom bar on these Homes: the content itself clears the navigation bar (edge-to-edge, screens-sr s0)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(bottom = AronTokens.Space.Xl),
        ) {
            // header: avatar, title and date, the sun switch and the gear (things used about once a day live up here)
            // The title is `type.heading`, up to 2 lines (screens-sr s2). The avatar is decorative and gives way to the title:
            // dropped from font scale 1.3 as the spec says, and also when the name would otherwise be cut (latched per title).
            var squeezed by remember(header.title) { mutableStateOf(false) }
            val showAvatar = LocalDensity.current.fontScale < AvatarDropFontScale && !squeezed
            val dateSpoken = stringResource(R.string.home_business_date, localizedDigits(header.subtitle))
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(start = AronTokens.Space.Screen, end = AronTokens.Space.S, top = AronTokens.Space.M, bottom = AronTokens.Space.S),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
            ) {
                if (showAvatar) AvatarInitial(user.fullName)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                    Text(
                        header.title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (it.hasVisualOverflow) squeezed = true }, modifier = Modifier.testTag(HomeTags.HEADER),
                    )
                    // the bare ISO date as the SR header shows it; TalkBack reads the full "Business date: ..." sentence
                    Text(
                        localizedDigits(header.subtitle), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, softWrap = false,
                        modifier = Modifier.testTag(HomeTags.DATE).semantics { contentDescription = dateSpoken },
                    )
                }
                SunlightToggle(sunlight, onSunlight, Modifier.testTag(RoleHomeTags.SUNLIGHT))
                AronIconButton(AronIcons.Settings, stringResource(R.string.set_open), onSettings, Modifier.testTag(RoleHomeTags.SETTINGS))
            }
            // status row: the chip is quiet when synced and opens the sync sheet when the host has one; the bundle line says what today's data check found
            Column(Modifier.padding(horizontal = AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                AronStatusChip(sync, Modifier.testTag(RoleHomeTags.SYNC), form = ChipForm.Full, onClick = onSync)
                // an offline unlock already says "offline" in the chip and the banner: the line is shown only when it adds something
                if (!(user.offline && bundle == BundleStatus.Offline)) {
                    Text(bundleLine(bundle), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.testTag(HomeTags.BUNDLE))
                }
            }
            Column(Modifier.padding(top = AronTokens.Space.S), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                if (user.offline) AronBanner(stringResource(CoreR.string.core_ui_offline_mode), Modifier.testTag(HomeTags.MODE), BannerKind.Offline)
                if (user.reauthRequired) AronBanner(stringResource(R.string.home_reauth_required), kind = BannerKind.Warning)
                if (user.updateRequired) AronBanner(stringResource(R.string.home_update_required), kind = BannerKind.Warning)
                banners.forEach { AronBanner(it.text, kind = it.kind) }
            }
            hero?.let { HeroRow(it) }
            groups.forEach { (group, items) ->
                AronSectionLabel(stringResource(group.label))
                AronTileGrid(items) { t, m ->
                    AronTile(stringResource(t.label), t.icon, onClick = { onTile(t) }, modifier = m.testTag(RoleHomeTags.tile(t.id)), badge = t.badge, compact = group.compact)
                }
            }
            health?.let { DeviceLine(it) }
        }
    }
}

/** Font scale from which the header avatar is dropped to give the title room (screens-sr s2). */
private const val AvatarDropFontScale = 1.3f

/** A 40 dp generic avatar with the first letter of the name; decorative (the title beside it carries the name). */
@Composable
private fun AvatarInitial(fullName: String) {
    val c = LocalAronColors.current
    val initial = fullName.trim().firstOrNull()?.uppercase().orEmpty()
    val fill = c.accentContainer
    Box(Modifier.size(40.dp).drawBehind { drawCircle(fill) }.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Text(initial, style = MaterialTheme.typography.titleMedium, color = c.accentOnContainer)
    }
}

@Composable
private fun HeroRow(hero: RoleHero) {
    // the two cards share one height (IntrinsicSize.Max) so a caption on one never leaves the other short
    Row(
        Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S).height(IntrinsicSize.Max).testTag(RoleHomeTags.HERO),
        horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        when (hero) {
            is RoleHero.Amo -> {
                AronStatCard(stringResource(R.string.rh_hero_amo_sales), money(hero.teamSalesMtk), Modifier.weight(1f).fillMaxHeight())
                AronStatCard(
                    stringResource(R.string.rh_hero_amo_visits), localizedNumber(hero.visits.toLong()), Modifier.weight(1f).fillMaxHeight(),
                    caption = hero.visitTarget?.let { stringResource(R.string.rh_hero_amo_visits_of, localizedNumber(it.toLong())) },
                )
            }
            is RoleHero.Tso -> AronStatCard(
                stringResource(R.string.rh_hero_tso_zones), localizedNumber(hero.zonesSubmitted.toLong()), Modifier.weight(1f),
                caption = stringResource(R.string.rh_hero_tso_zones_of, localizedNumber(hero.zonesTotal.toLong())),
            )
        }
    }
}

/** Device-health line (F-SR-064): caption text, no card; a warning banner under it when any figure is low. */
@Composable
private fun DeviceLine(h: DeviceHealth) {
    val c = LocalAronColors.current
    Column(Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.M).testTag(RoleHomeTags.DEVICE), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(
            listOf(
                stringResource(R.string.hm_health_battery, localizedNumber(h.batteryPct.toLong())),
                stringResource(R.string.hm_health_storage, localizedNumber(h.freeStorageMb)),
                stringResource(R.string.hm_health_pending, localizedNumber(h.pendingRows.toLong())),
                h.lastSyncAgeMin?.let { stringResource(R.string.hm_health_sync, localizedNumber(it)) } ?: stringResource(R.string.hm_health_never_synced),
            ).joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
        )
        if (h.anyWarning) AronBanner(stringResource(R.string.hm_health_warning), kind = BannerKind.Warning)
    }
}

/**
 * Settings of the role apps: language rows, the sunlight switch, an optional app-update row, the version and Logout
 * with its confirmation. [logoutMessage] is the role's own wording (AMO keeps its data, TSO wipes it, F-SYS-022).
 */
@Composable
fun RoleSettingsContent(
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
) {
    val c = LocalAronColors.current
    val language = LocalAppLanguage.current
    var confirm by rememberSaveable { mutableStateOf(false) }
    AronScreen(modifier, topBar = { AronTopBar(stringResource(R.string.set_title), onBack = onBack) }) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(bottom = AronTokens.Space.Xl),
        ) {
            AronSectionLabel(stringResource(R.string.set_language))
            Column(Modifier.padding(horizontal = AronTokens.Space.Screen)) {
                LanguageRow(stringResource(CoreR.string.core_ui_language_bangla), language == AppLanguage.BN, RowPosition.First, RoleHomeTags.SET_LANG_BN) { onLanguageSelect(AppLanguage.BN) }
                LanguageRow(stringResource(CoreR.string.core_ui_language_english), language == AppLanguage.EN, RowPosition.Last, RoleHomeTags.SET_LANG_EN) { onLanguageSelect(AppLanguage.EN) }
            }
            if (onSunlight != null) {
                AronSectionLabel(stringResource(R.string.rh_set_display))
                // the same [switch] row as the SR Settings (screens-sr s14): the whole 56 dp row toggles; the switch is decorative for TalkBack
                AronListRow(
                    stringResource(R.string.rh_set_sunlight), Modifier.padding(horizontal = AronTokens.Space.Screen).testTag(RoleHomeTags.SET_SUNLIGHT),
                    subtitle = stringResource(R.string.rh_set_sunlight_hint), flat = true, onClick = { onSunlight(!sunlight) },
                    leading = { Icon(AronIcons.Sun, contentDescription = null, tint = c.accent, modifier = Modifier.size(AronTokens.Size.Icon)) },
                    trailingContent = {
                        Switch(
                            sunlight, onCheckedChange = onSunlight, modifier = Modifier.clearAndSetSemantics { },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = c.accent, checkedThumbColor = c.textOnAccent,
                                uncheckedTrackColor = c.stateDisabledFill, uncheckedThumbColor = c.textSecondary, uncheckedBorderColor = c.borderSolid,
                            ),
                        )
                    },
                )
            }
            AronSectionLabel(stringResource(R.string.rh_set_about))
            Column(Modifier.padding(horizontal = AronTokens.Space.Screen)) {
                AronListRow(
                    stringResource(R.string.set_version, localizedDigits(versionText)), Modifier.testTag(RoleHomeTags.SET_VERSION),
                    flat = true, position = if (onUpdate != null) RowPosition.First else RowPosition.Only, divider = onUpdate != null,
                    leading = { Icon(AronIcons.Info, contentDescription = null, tint = c.accent, modifier = Modifier.size(AronTokens.Size.Icon)) },
                )
                if (onUpdate != null) {
                    AronListRow(
                        stringResource(if (updateAvailable) R.string.set_update_available else R.string.set_update), Modifier.testTag(RoleHomeTags.SET_UPDATE),
                        flat = true, position = RowPosition.Last, onClick = onUpdate,
                        leading = { Icon(AronIcons.CloudUpload, contentDescription = null, tint = c.accent, modifier = Modifier.size(AronTokens.Size.Icon)) },
                        trailingContent = { Icon(AronIcons.ChevronRight, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(AronTokens.Size.Icon)) },
                    )
                }
            }
            AronSecondaryButton(
                stringResource(R.string.set_logout), { confirm = true },
                Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.Xl).testTag(HomeTags.LOGOUT), icon = AronIcons.Logout,
            )
        }
    }
    if (confirm) {
        AronConfirmDialog(
            stringResource(R.string.set_logout_title), logoutMessage,
            stringResource(R.string.set_logout_confirm), stringResource(R.string.set_cancel),
            onConfirm = { confirm = false; onLogout() }, onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun LanguageRow(label: String, on: Boolean, position: RowPosition, tag: String, onClick: () -> Unit) {
    val c = LocalAronColors.current
    AronListRow(
        label, Modifier.testTag(tag).semantics { selected = on }, onClick = onClick, position = position, flat = true, divider = position == RowPosition.First,
        trailingContent = { if (on) Icon(AronIcons.CheckCircle, contentDescription = null, tint = c.accent, modifier = Modifier.size(AronTokens.Size.Icon)) },
    )
}

/**
 * The designed destination of a tile whose section has not landed yet: the frame with a back top bar, the section's
 * illustration, a one-line explanation and the synced-at caption. Never plain text, never a toast.
 */
@Composable
fun RoleSectionPlaceholder(
    title: String,
    icon: ImageVector,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    syncedAt: String? = null,
) {
    AronScreen(modifier, topBar = { AronTopBar(title, onBack = onBack) }) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)), contentAlignment = Alignment.Center) {
            AronEmptyState(
                stringResource(R.string.rh_empty_title, title), Modifier.testTag(RoleHomeTags.EMPTY),
                hint = stringResource(R.string.rh_empty_hint), icon = icon, syncedAt = syncedAt,
            )
        }
    }
}

/**
 * TSO > Device OTP: today the bind codes are read on the web Device OTP page; this screen says where. With [webOrigin]
 * (the dashboard origin from the host's build config, no path) the card shows the complete address to type; without it
 * the page path and the menu entry it sits under.
 */
@Composable
fun RoleDeviceOtpContent(onBack: () -> Unit, modifier: Modifier = Modifier, webOrigin: String? = null) {
    val c = LocalAronColors.current
    val path = stringResource(R.string.rh_tso_otp_url)
    val address = webOrigin?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { it + path } ?: path
    AronScreen(modifier, topBar = { AronTopBar(stringResource(R.string.rh_tso_device_otp), onBack = onBack) }) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(bottom = AronTokens.Space.Xl),
        ) {
            AronEmptyState(stringResource(R.string.rh_tso_otp_title), hint = stringResource(R.string.rh_tso_otp_hint), icon = AronIcons.Lock)
            AronCard(Modifier.padding(horizontal = AronTokens.Space.Screen)) {
                Row(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                    Icon(AronIcons.Lock, contentDescription = null, tint = c.accent, modifier = Modifier.size(AronTokens.Size.Icon))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                        Text(stringResource(R.string.rh_tso_otp_url_label), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        Text(address, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.testTag(RoleHomeTags.OTP_URL))
                        Text(stringResource(R.string.rh_tso_otp_menu_hint), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                }
            }
        }
    }
}
