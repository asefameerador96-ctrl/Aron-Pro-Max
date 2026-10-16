package com.aktcl.aron.feature.home

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
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronSectionLabel
import com.aktcl.aron.core.ui.AronSegmented
import com.aktcl.aron.core.ui.AronStatusChip
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.ChipForm
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.core.ui.localizedDigits

object SettingsTags {
    const val LOGOUT = "set_logout"
    const val VERSION = "set_version"
    const val UPDATE = "set_update"
    const val PHOTOS_WIFI = "set_photos_wifi"
    const val SUPPORT = "set_support"
    const val SUNLIGHT = "set_sunlight"
    const val LANGUAGE = "set_language"
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
    /** The top bar's back; null hides the back button (the screen is then a root). */
    onBack: (() -> Unit)? = null,
    /** The sun switch row (docs/32 s2a item 5); shown when non-null. */
    sunlight: Boolean? = null,
    onSunlight: (Boolean) -> Unit = {},
    /** The status chip of the top bar; shown when non-null. */
    sync: SyncChipState? = null,
    onSync: (() -> Unit)? = null,
) {
    val c = LocalAronColors.current
    var confirm by rememberSaveable { mutableStateOf(false) }
    val language = LocalAppLanguage.current
    AronScreen(
        modifier = modifier,
        topBar = {
            AronTopBar(stringResource(R.string.set_title), onBack = onBack, trailing = { if (sync != null) AronStatusChip(sync, form = ChipForm.Compact, onClick = onSync) })
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = AronTokens.Space.Xl)) {
            // language: one segmented control, the current language selected
            AronSectionLabel(stringResource(R.string.set_language))
            val options = listOf(stringResource(com.aktcl.aron.core.ui.R.string.core_ui_language_bangla), stringResource(com.aktcl.aron.core.ui.R.string.core_ui_language_english))
            AronSegmented(
                options, selected = if (language == AppLanguage.BN) 0 else 1,
                onSelect = { i -> val target = if (i == 0) AppLanguage.BN else AppLanguage.EN; if (target != language) onLanguageSelect(target) },
                modifier = Modifier.padding(horizontal = AronTokens.Space.Screen).testTag(SettingsTags.LANGUAGE),
            )
            // display: the sun switch and the photo rule
            if (sunlight != null || photosWifiOnly != null) {
                AronSectionLabel(stringResource(R.string.set_group_display))
                SettingsGroup {
                    val rows = listOfNotNull(
                        sunlight?.let { on -> SwitchRow(stringResource(R.string.hm_sunlight), stringResource(R.string.set_sunlight_hint), on, onSunlight, SettingsTags.SUNLIGHT) },
                        photosWifiOnly?.let { on -> SwitchRow(stringResource(R.string.set_photos_wifi_only), null, on, onPhotosWifiOnly, SettingsTags.PHOTOS_WIFI) },
                    )
                    rows.forEachIndexed { i, r -> SettingsSwitchRow(r, positionOf(i, rows.size)) }
                }
            }
            // device: PDA to Support
            if (onSupport != null) {
                AronSectionLabel(stringResource(R.string.set_group_device))
                SettingsGroup {
                    AronListRow(
                        stringResource(R.string.set_support), Modifier.testTag(SettingsTags.SUPPORT), onClick = onSupport,
                        leading = { Icon(AronIcons.CloudUpload, contentDescription = null, tint = c.accent, modifier = Modifier.size(AronTokens.Size.Icon)) },
                        trailingContent = { Chevron() }, position = RowPosition.Only,
                    )
                }
            }
            // account: version, update, logout
            AronSectionLabel(stringResource(R.string.set_group_account))
            SettingsGroup {
                AronListRow(
                    stringResource(R.string.set_version, localizedDigits(versionText)), Modifier.testTag(SettingsTags.VERSION),
                    subtitle = if (onUpdate != null) stringResource(if (updateAvailable) R.string.set_update_hint else R.string.set_update_current) else null,
                    leading = { Icon(AronIcons.Verified, contentDescription = null, tint = c.accent, modifier = Modifier.size(AronTokens.Size.Icon)) },
                    position = if (onUpdate != null) RowPosition.First else RowPosition.Only, divider = onUpdate != null,
                )
                if (onUpdate != null) {
                    AronListRow(
                        stringResource(if (updateAvailable) R.string.set_update_available else R.string.set_update), Modifier.testTag(SettingsTags.UPDATE), onClick = onUpdate,
                        leading = { Icon(AronIcons.Refresh, contentDescription = null, tint = c.accent, modifier = Modifier.size(AronTokens.Size.Icon)) },
                        trailingContent = { Chevron() }, position = RowPosition.Last,
                    )
                }
            }
            AronTextButton(
                stringResource(R.string.set_logout), { confirm = true },
                Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.M).testTag(SettingsTags.LOGOUT),
                icon = AronIcons.Logout, color = c.dangerOnContainer,
            )
        }
    }
    if (confirm) {
        AronConfirmDialog(
            stringResource(R.string.set_logout_title), stringResource(R.string.set_logout_message),
            stringResource(R.string.set_logout_confirm), stringResource(R.string.set_cancel),
            onConfirm = { confirm = false; onLogout() }, onDismiss = { confirm = false },
        )
    }
}

private class SwitchRow(val title: String, val hint: String?, val on: Boolean, val onChange: (Boolean) -> Unit, val tag: String)

private fun positionOf(i: Int, n: Int): RowPosition = when {
    n == 1 -> RowPosition.Only
    i == 0 -> RowPosition.First
    i == n - 1 -> RowPosition.Last
    else -> RowPosition.Middle
}

/** One grouped field card (screens-sr s14 "iOS-style grouped list"): opaque, `radius.card`, rows with inset dividers. */
@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    AronCard(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.Screen)) { Column(Modifier.fillMaxWidth()) { content() } }
}

/**
 * A 56 dp switch row: the whole row toggles and is announced as a switch with its on/off state (docs/32 s2a item 3,
 * screens-sr s2); the Material switch itself is decorative for TalkBack so the state is spoken once.
 */
@Composable
private fun SettingsSwitchRow(r: SwitchRow, position: RowPosition) {
    val c = LocalAronColors.current
    AronListRow(
        r.title, Modifier.testTag(r.tag).semantics { role = Role.Switch; toggleableState = ToggleableState(r.on) }, subtitle = r.hint, onClick = { r.onChange(!r.on) },
        leading = { Icon(if (r.tag == SettingsTags.SUNLIGHT) AronIcons.Sun else AronIcons.Camera, contentDescription = null, tint = c.accent, modifier = Modifier.size(AronTokens.Size.Icon)) },
        trailingContent = {
            Switch(
                r.on, onCheckedChange = r.onChange, modifier = Modifier.clearAndSetSemantics { },
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.textOnAccent, uncheckedTrackColor = c.stateDisabledFill, uncheckedThumbColor = c.textSecondary, uncheckedBorderColor = c.borderSolid),
            )
        },
        position = position, divider = position == RowPosition.First || position == RowPosition.Middle,
    )
}

@Composable
private fun Chevron() {
    val c = LocalAronColors.current
    Icon(AronIcons.ChevronRight, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(AronTokens.Size.Icon))
}
