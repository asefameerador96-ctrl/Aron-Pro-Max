package com.aktcl.aron.feature.tso

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.R as CoreR
import com.aktcl.aron.core.ui.aron.AronDialog
import com.aktcl.aron.core.ui.aron.AronListRow
import com.aktcl.aron.core.ui.aron.AronLogoutButton
import com.aktcl.aron.core.ui.aron.AronScaffold
import com.aktcl.aron.core.ui.aron.AronSwitchRow
import com.aktcl.aron.core.ui.aron.AronTopBar
import com.aktcl.aron.feature.home.HomeTags
import com.aktcl.aron.feature.home.R
import com.aktcl.aron.feature.home.RoleHomeTags

/**
 * TSO Settings in the existing-Aron look: top bar with the back chevron, white rows on the grey page (language, the optional
 * Sunlight theme, version, app update, PDA to Support, change password) and the Log out button, which asks first (it wipes this
 * user's data, F-SYS-022: [logoutMessage] says so).
 */
@Composable
fun TsoSettingsContent(
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
    onChangePassword: (() -> Unit)? = null,
) {
    val language = LocalAppLanguage.current
    var confirm by rememberSaveable { mutableStateOf(false) }
    AronScaffold(
        modifier = modifier,
        topBar = {
            AronTopBar(
                stringResource(R.string.set_title), icon = painterResource(CoreR.drawable.aron_ic_gear),
                onBack = onBack, backLabel = stringResource(CoreR.string.core_ui_back),
            )
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TsoSectionTitle(stringResource(R.string.set_language))
            LanguageRow(stringResource(CoreR.string.core_ui_language_bangla), language == AppLanguage.BN, RoleHomeTags.SET_LANG_BN) { onLanguageSelect(AppLanguage.BN) }
            LanguageRow(stringResource(CoreR.string.core_ui_language_english), language == AppLanguage.EN, RoleHomeTags.SET_LANG_EN) { onLanguageSelect(AppLanguage.EN) }
            if (onSunlight != null) {
                TsoSectionTitle(stringResource(R.string.rh_set_display))
                AronSwitchRow(
                    stringResource(R.string.rh_set_sunlight), sunlight, onSunlight, Modifier.testTag(RoleHomeTags.SET_SUNLIGHT),
                    subtitle = stringResource(R.string.rh_set_sunlight_hint),
                )
            }
            TsoSectionTitle(stringResource(R.string.rh_set_about))
            AronListRow(stringResource(R.string.set_version, localizedDigits(versionText)), Modifier.testTag(RoleHomeTags.SET_VERSION))
            if (onUpdate != null) {
                AronListRow(
                    stringResource(if (updateAvailable) R.string.set_update_available else R.string.set_update), Modifier.testTag(RoleHomeTags.SET_UPDATE),
                    trailing = ">", onClick = onUpdate,
                )
            }
            if (onSupport != null) AronListRow(stringResource(R.string.set_support), Modifier.testTag(RoleHomeTags.SET_SUPPORT), trailing = ">", onClick = onSupport)
            if (onChangePassword != null) {
                AronListRow(stringResource(com.aktcl.aron.feature.tso.R.string.tso_cp_row), Modifier.testTag(TsoSettingsTags.CHANGE_PASSWORD), trailing = ">", onClick = onChangePassword)
            }
            AronLogoutButton(stringResource(R.string.set_logout), { confirm = true }, Modifier.fillMaxWidth().testTag(HomeTags.LOGOUT))
        }
    }
    if (confirm) {
        AronDialog(
            stringResource(R.string.set_logout_title), logoutMessage, stringResource(R.string.set_logout_confirm),
            onConfirm = { confirm = false; onLogout() }, onDismiss = { confirm = false }, dismissText = stringResource(R.string.set_cancel),
        )
    }
}

@Composable
private fun LanguageRow(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    AronListRow(label, Modifier.testTag(tag).semantics { selected = on }, trailing = if (on) "✓" else null, onClick = onClick)
}
