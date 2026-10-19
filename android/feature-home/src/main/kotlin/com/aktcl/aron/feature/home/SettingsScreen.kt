package com.aktcl.aron.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronListRow
import com.aktcl.aron.core.ui.aron.AronLogoutButton
import com.aktcl.aron.core.ui.aron.AronScaffold as ParityScaffold
import com.aktcl.aron.core.ui.aron.AronSwitchRow
import com.aktcl.aron.core.ui.aron.AronTabs
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
    /** The device line (battery, free storage, pending uploads, last sync; moved here from Home): shown when non-null. */
    health: DeviceHealth? = null,
) {
    val look = LocalAronLook.current
    var confirm by rememberSaveable { mutableStateOf(false) }
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
            // language: the kit tabs, the current language selected
            GroupLabel(stringResource(R.string.set_language))
            val options = listOf(stringResource(CoreR.string.core_ui_language_bangla), stringResource(CoreR.string.core_ui_language_english))
            AronTabs(
                options, selected = if (language == AppLanguage.BN) 0 else 1,
                onSelect = { i -> val target = if (i == 0) AppLanguage.BN else AppLanguage.EN; if (target != language) onLanguageSelect(target) },
                modifier = Modifier.testTag(SettingsTags.LANGUAGE),
            )
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
            if (onSupport != null) {
                GroupLabel(stringResource(R.string.set_group_device))
                AronListRow(stringResource(R.string.set_support), Modifier.testTag(SettingsTags.SUPPORT), onClick = onSupport, trailing = "›")
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
            AronLogoutButton(stringResource(R.string.set_logout), { confirm = true }, Modifier.fillMaxWidth().padding(top = 8.dp).testTag(SettingsTags.LOGOUT))
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

/** A small grey group label above a block of rows. */
@Composable
private fun GroupLabel(text: String) {
    Text(text, color = LocalAronLook.current.inkMuted, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp, top = 6.dp).semantics { heading() })
}
