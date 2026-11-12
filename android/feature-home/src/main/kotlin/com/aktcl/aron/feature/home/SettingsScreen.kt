package com.aktcl.aron.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.res.painterResource
import com.aktcl.aron.core.ui.aron.AronTile
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronListRow
import com.aktcl.aron.core.ui.aron.AronScaffold as ParityScaffold
import com.aktcl.aron.core.ui.aron.AronSwitchRow
import com.aktcl.aron.core.ui.aron.AronTopBar as ParityTopBar
import com.aktcl.aron.core.ui.R as CoreR
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.aktcl.aron.core.ui.AronYesButton
import com.aktcl.aron.core.ui.AronNoButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronSectionLabel
import com.aktcl.aron.core.ui.AronSegmented
import com.aktcl.aron.core.ui.AronStatusChip
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.ChipForm
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.core.ui.localizedDigits

/** What the Update Configuration tile found (SRM-B-52): a new one applied, the phone already had the latest, or it could not pull. */
enum class ConfigUpdate { UPDATED, ALREADY_CURRENT, FAILED }

object SettingsTags {
    const val LOGOUT = "set_logout"
    const val VERSION = "set_version"
    const val UPDATE = "set_update"
    const val PHOTOS_WIFI = "set_photos_wifi"
    const val SUPPORT = "set_support"
    const val PRINTER = "set_printer"
    const val SUNLIGHT = "set_sunlight"
    const val LANGUAGE = "set_language"
    const val UPDATE_CONFIG = "set_update_config"
    const val CONFIG_DIALOG = "set_config_dialog"
    const val SUBMIT_WARNING = "set_submit_warning"
}

/**
 * Settings (screens-sr s14; F-SR-005 language, F-SR-007 Logout): grouped field cards with 56 dp rows under `type.label`
 * group labels. The language switch persists in the app locale store; the sun switch is the same preference as the Home
 * header; Logout asks for confirmation and keeps the local database (the SR's saved work still uploads). [versionText]
 * is the single version string. Rows whose callback is null are not shown.
 */
@Composable
fun SettingsContent(
    versionText: String,
    onLanguageSelect: (AppLanguage) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    /** "App update" row (SR p7): opens the update page. Shown when non-null. */
    onUpdate: (() -> Unit)? = null,
    /** A newer release is waiting: the row says so. */
    updateAvailable: Boolean = false,
    /** "Photos only on Wi-Fi" switch (F-SYS-037). Shown when non-null. */
    photosWifiOnly: Boolean? = null,
    onPhotosWifiOnly: (Boolean) -> Unit = {},
    /** "PDA to Support" tile (F-SYS-021). Shown when non-null. */
    onSupport: (() -> Unit)? = null,
    /** "Printer diagnostics" (D-PRINT-02): Bluetooth, permissions, paired devices, test pages, report. Shown when non-null. */
    onPrinter: (() -> Unit)? = null,
    /** The top bar's back; null hides the back button (the screen is then a root). */
    onBack: (() -> Unit)? = null,
    /** The sun switch row (docs/32 s2a item 5); shown when non-null. */
    sunlight: Boolean? = null,
    onSunlight: (Boolean) -> Unit = {},
    /** The status chip of the top bar; shown when non-null. */
    sync: SyncChipState? = null,
    onSync: (() -> Unit)? = null,
    /** The device line (battery, free storage, pending uploads, last sync; moved here from Home): shown when non-null. */
    health: DeviceHealth? = null,
    /**
     * "Update Configuration" tile (SRM-B-52): pulls the bundle and configuration now (applied in place, nothing closes; owner
     * question 5 default) and says what it found. Shown when non-null. [configDownloading] is the day's own download flag, so a
     * pull that something else started also shows the busy label.
     */
    onUpdateConfig: (suspend () -> ConfigUpdate)? = null,
    configDownloading: Boolean = false,
    /**
     * SRM-B-55: asked when Log out is tapped; true = today has sales or visits and no Sales Submit, so the rep is reminded
     * first. Null = no check (AMO, TSO and previews log out as before).
     */
    submitPending: (suspend () -> Boolean)? = null,
    /** The warning's "বিক্রয় জমা" button: opens the Sales Submit screen. */
    onSalesSubmit: (() -> Unit)? = null,
) {
    val look = LocalAronLook.current
    var confirm by rememberSaveable { mutableStateOf(false) }
    var submitWarning by rememberSaveable { mutableStateOf(false) }
    // not saved: the pull's job lives in this composition's scope, so a recreated screen starts with the tile free again
    var configRunning by remember { mutableStateOf(false) }
    var configResult by rememberSaveable { mutableStateOf<ConfigUpdate?>(null) }
    val configBusy = configRunning || configDownloading
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val language = LocalAppLanguage.current
    ParityScaffold(
        modifier = modifier,
        topBar = {
            ParityTopBar(
                stringResource(R.string.set_title), onBack = onBack, backLabel = stringResource(CoreR.string.core_ui_back),
                trailing = { if (sync != null) AronStatusChip(sync, form = ChipForm.Compact, onClick = onSync) },
            )
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // the reference tiles (screenshot 63/64): language drop-down, PDA টু সাপোর্ট, Update Configuration, লগ আউট
            val tiles = buildList<@Composable (Modifier) -> Unit> {
                add { m -> LanguageTile(language, onLanguageSelect, m) }
                onSupport?.let { go -> add { m -> AronTile(stringResource(R.string.set_support), painterResource(CoreR.drawable.aron_art_settings), go, m.testTag(SettingsTags.SUPPORT)) } }
                onUpdateConfig?.let { run ->
                    add { m ->
                        AronTile(
                            stringResource(if (configBusy) R.string.set_update_config_busy else R.string.set_update_config), painterResource(CoreR.drawable.aron_art_settings),
                            { if (!configBusy) { configRunning = true; scope.launch { try { configResult = run() } finally { configRunning = false } } } },
                            m.testTag(SettingsTags.UPDATE_CONFIG),
                        )
                    }
                }
                add { m ->
                    AronTile(
                        stringResource(R.string.set_logout), null,
                        {
                            if (submitPending == null) confirm = true
                            else scope.launch {
                                // a failed read must not block Log out: it falls back to the normal confirm
                                val pending = try { submitPending() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
                                if (pending) submitWarning = true else confirm = true
                            }
                        },
                        m.testTag(SettingsTags.LOGOUT),
                    )
                }
            }
            tiles.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { t -> t(Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            // display: the sun switch and the photo rule
            if (sunlight != null || photosWifiOnly != null) {
                GroupLabel(stringResource(R.string.set_group_display))
                sunlight?.let { on ->
                    AronSwitchRow(stringResource(R.string.hm_sunlight), on, onSunlight, Modifier.testTag(SettingsTags.SUNLIGHT), subtitle = stringResource(R.string.set_sunlight_hint))
                }
                photosWifiOnly?.let { on ->
                    AronSwitchRow(stringResource(R.string.set_photos_wifi_only), on, onPhotosWifiOnly, Modifier.testTag(SettingsTags.PHOTOS_WIFI))
                }
            }
            // device: PDA to Support
            if (onPrinter != null) {
                GroupLabel(stringResource(R.string.set_group_device))
                onPrinter?.let { AronListRow(stringResource(R.string.set_printer), Modifier.testTag(SettingsTags.PRINTER), onClick = it, trailing = "›") }
            }
            // device status: the line Home no longer carries (battery, free storage, waiting uploads, last sync)
            health?.let { h ->
                GroupLabel(stringResource(R.string.set_group_status))
                SrDeviceLine(h)
            }
            // account: version, update, logout
            GroupLabel(stringResource(R.string.set_group_account))
            AronListRow(
                stringResource(R.string.set_version, localizedDigits(versionText)), Modifier.testTag(SettingsTags.VERSION),
                subtitle = if (onUpdate != null) stringResource(if (updateAvailable) R.string.set_update_hint else R.string.set_update_current) else null,
            )
            if (onUpdate != null) {
                AronListRow(
                    stringResource(if (updateAvailable) R.string.set_update_available else R.string.set_update), Modifier.testTag(SettingsTags.UPDATE),
                    onClick = onUpdate, trailing = "›",
                )
            }
        }
    }
    if (submitWarning) {
        // back or a tap outside cancels and stays in Settings; only "Log out anyway" goes on to the logout confirm
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { submitWarning = false },
            title = { Text(stringResource(R.string.set_submit_warning_title)) },
            text = { Text(stringResource(R.string.set_submit_warning_message)) },
            confirmButton = { AronYesButton(stringResource(R.string.set_submit_warning_submit), { submitWarning = false; if (onSalesSubmit != null) onSalesSubmit() }) },
            dismissButton = { AronNoButton(stringResource(R.string.set_submit_warning_anyway), { submitWarning = false; confirm = true }) },
        )
    }
    configResult?.let { r ->
        com.aktcl.aron.core.ui.AronInfoDialog(
            stringResource(when (r) { ConfigUpdate.UPDATED -> R.string.set_config_done_title; ConfigUpdate.ALREADY_CURRENT -> R.string.set_config_current_title; ConfigUpdate.FAILED -> R.string.set_config_failed_title }),
            stringResource(when (r) { ConfigUpdate.UPDATED -> R.string.set_config_done_message; ConfigUpdate.ALREADY_CURRENT -> R.string.set_config_current_message; ConfigUpdate.FAILED -> R.string.set_config_failed_message }),
            stringResource(R.string.set_ok), onDismiss = { configResult = null },
        )
    }
    if (confirm) {
        AronConfirmDialog(
            stringResource(R.string.set_logout_title), stringResource(R.string.set_logout_message),
            stringResource(R.string.set_logout_confirm), stringResource(R.string.set_cancel),
            onConfirm = { confirm = false; onLogout() }, onDismiss = { confirm = false },
        )
    }
}

/** A small grey group label above a block of rows. */
@Composable
private fun GroupLabel(text: String) {
    Text(text, color = LocalAronLook.current.inkMuted, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp, top = 6.dp).semantics { heading() })
}

/** The language drop-down tile: the current language ("en" / "বাংলা"), a menu of the two on tap. */
@Composable
private fun LanguageTile(current: AppLanguage, onSelect: (AppLanguage) -> Unit, modifier: Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val bn = stringResource(CoreR.string.core_ui_language_bangla)
    val en = stringResource(CoreR.string.core_ui_language_english)
    Box(modifier) {
        AronTile(if (current == AppLanguage.BN) bn else en, null, { open = true }, Modifier.fillMaxWidth().testTag(SettingsTags.LANGUAGE))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(AppLanguage.BN to bn, AppLanguage.EN to en).forEach { (l, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; if (l != current) onSelect(l) })
            }
        }
    }
}
