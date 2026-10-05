package com.aktcl.aron.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.LanguageToggle
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.localizedDigits

object HomeTags {
    const val HEADER = "home_header"
    const val DATE = "home_business_date"
    const val MODE = "home_mode"
    const val BUNDLE = "home_bundle"
    const val LOGOUT = "home_logout"
}

/** Who is logged in, as the session reports it. */
data class HomeUser(
    val fullName: String,
    val username: String,
    val role: String,
    val offline: Boolean,
    val reauthRequired: Boolean,
    val updateRequired: Boolean,
)

@Composable
fun HomePlaceholderScreen(
    viewModel: HomePlaceholderViewModel,
    user: HomeUser,
    onLogout: () -> Unit,
    onLanguageSelect: (AppLanguage) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomePlaceholderContent(state, user, onLogout, onLanguageSelect)
}

@Composable
fun HomePlaceholderContent(state: HomeUiState, user: HomeUser, onLogout: () -> Unit, onLanguageSelect: (AppLanguage) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LanguageToggle(current = LocalAppLanguage.current, onSelect = onLanguageSelect)
        }
        Text(
            text = stringResource(R.string.home_header, user.role, user.fullName, user.username),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag(HomeTags.HEADER),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = localizedDigits(stringResource(R.string.home_business_date, state.businessDate)),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(HomeTags.DATE),
        )
        Spacer(Modifier.height(16.dp))
        if (user.offline) Banner(stringResource(com.aktcl.aron.core.ui.R.string.core_ui_offline_mode), HomeTags.MODE)
        if (user.reauthRequired) Banner(stringResource(R.string.home_reauth_required), null)
        if (user.updateRequired) Banner(stringResource(R.string.home_update_required), null)
        Spacer(Modifier.height(8.dp))
        Text(text = bundleText(state.bundle), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(HomeTags.BUNDLE))
        Spacer(Modifier.height(24.dp))
        Text(text = stringResource(R.string.home_placeholder_note), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onLogout, modifier = Modifier.testTag(HomeTags.LOGOUT)) { Text(stringResource(R.string.home_logout)) }
    }
}

@Composable
private fun Banner(text: String, tag: String?) {
    val m = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp)
    Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = if (tag != null) m.testTag(tag) else m)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun bundleText(status: BundleStatus): String = when (status) {
    BundleStatus.Checking -> stringResource(R.string.home_bundle_checking)
    is BundleStatus.Ready -> localizedDigits(stringResource(R.string.home_bundle_ready, status.validFor, status.bundleVersion))
    BundleStatus.Unchanged -> stringResource(R.string.home_bundle_unchanged)
    BundleStatus.Offline -> stringResource(R.string.home_bundle_offline)
    is BundleStatus.Refused -> localizedDigits(stringResource(R.string.home_bundle_refused, status.httpStatus.toString(), status.code ?: "-"))
}
