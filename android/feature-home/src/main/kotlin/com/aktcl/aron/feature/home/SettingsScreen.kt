package com.aktcl.aron.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LanguageToggle
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.localizedDigits

object SettingsTags {
    const val LOGOUT = "set_logout"
    const val VERSION = "set_version"
}

/**
 * Settings (F-SR-005 language, F-SR-007 Logout). The language switch persists in the app locale store; Logout asks for
 * confirmation and keeps the local database (the SR's saved work still uploads). [versionText] is the single version string.
 */
@Composable
fun SettingsContent(
    versionText: String,
    onLanguageSelect: (AppLanguage) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        Text(stringResource(R.string.set_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.set_language), style = MaterialTheme.typography.labelLarge)
        LanguageToggle(LocalAppLanguage.current, onLanguageSelect)
        Text(stringResource(R.string.set_version, localizedDigits(versionText)), Modifier.testTag(SettingsTags.VERSION), style = MaterialTheme.typography.bodyMedium)
        AronSecondaryButton(stringResource(R.string.set_logout), { confirm = true }, Modifier.testTag(SettingsTags.LOGOUT))
    }
    if (confirm) {
        AronConfirmDialog(
            stringResource(R.string.set_logout_title), stringResource(R.string.set_logout_message),
            stringResource(R.string.set_logout_confirm), stringResource(R.string.set_cancel),
            onConfirm = { confirm = false; onLogout() }, onDismiss = { confirm = false },
        )
    }
}
