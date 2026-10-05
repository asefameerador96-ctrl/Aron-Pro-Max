package com.aktcl.aron.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.LanguageToggle
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Test tags of the login screen (Robolectric and instrumented UI tests). */
object LoginTags {
    const val USERNAME = "login_username"
    const val PASSWORD = "login_password"
    const val SUBMIT = "login_submit"
    const val MESSAGE = "login_message"
    const val VERSION = "login_version"
}

/**
 * The login screen of every field app (Day-1 shell, N-001). It works the same offline: the session decides between
 * online login and offline unlock. [appTitle] is the launcher label resource of the app (ARON SR, AMO, TSO).
 */
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    appTitle: String,
    versionName: String,
    onLanguageSelect: (AppLanguage) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoginContent(
        state = state,
        appTitle = appTitle,
        versionName = versionName,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onTogglePasswordVisible = viewModel::onTogglePasswordVisible,
        onSubmit = viewModel::onSubmit,
        onLanguageSelect = onLanguageSelect,
    )
}

@Composable
fun LoginContent(
    state: LoginUiState,
    appTitle: String,
    versionName: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onSubmit: () -> Unit,
    onLanguageSelect: (AppLanguage) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LanguageToggle(current = LocalAppLanguage.current, onSelect = onLanguageSelect)
        }
        Spacer(Modifier.height(32.dp))
        Text(text = appTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(text = stringResource(R.string.auth_login_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = state.username,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(R.string.auth_username)) },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag(LoginTags.USERNAME),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.auth_password)) },
            singleLine = true,
            enabled = !state.busy,
            visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            trailingIcon = {
                TextButton(onClick = onTogglePasswordVisible) {
                    Text(stringResource(if (state.passwordVisible) R.string.auth_password_hide else R.string.auth_password_show))
                }
            },
            modifier = Modifier.fillMaxWidth().testTag(LoginTags.PASSWORD),
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onSubmit,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag(LoginTags.SUBMIT),
        ) {
            if (state.busy) {
                Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp) }
            } else {
                Text(stringResource(R.string.auth_login_button))
            }
        }
        state.message?.let { message ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = messageText(message),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().testTag(LoginTags.MESSAGE),
            )
        }
        Spacer(Modifier.height(48.dp))
        Text(
            text = localizedDigits(stringResource(R.string.auth_version, versionName)),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(LoginTags.VERSION),
        )
        Text(text = stringResource(com.aktcl.aron.core.ui.R.string.core_ui_brand_footer), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun messageText(message: LoginMessage): String = when (message) {
    LoginMessage.EmptyFields -> stringResource(R.string.auth_error_empty)
    LoginMessage.InvalidCredentials -> stringResource(R.string.auth_error_invalid_credentials)
    LoginMessage.BindRequired -> stringResource(R.string.auth_error_bind_required)
    LoginMessage.PasswordChangeRequired -> stringResource(R.string.auth_error_password_change_required)
    is LoginMessage.Locked -> message.retryAfterMinutes?.let {
        pluralStringResource(R.plurals.auth_error_locked_minutes, it.toInt(), localizedNumber(it))
    } ?: stringResource(R.string.auth_error_locked)
    LoginMessage.UserDisabled -> stringResource(R.string.auth_error_user_disabled)
    LoginMessage.DeviceNotEnrolled -> stringResource(R.string.auth_error_device_not_enrolled)
    LoginMessage.DeviceBlocked -> stringResource(R.string.auth_error_device_blocked)
    LoginMessage.UpdateRequired -> stringResource(R.string.auth_error_update_required)
    is LoginMessage.RateLimited -> message.retryAfterMinutes?.let {
        pluralStringResource(R.plurals.auth_error_rate_limited_minutes, it.toInt(), localizedNumber(it))
    } ?: stringResource(R.string.auth_error_rate_limited)
    is LoginMessage.Refused -> stringResource(R.string.auth_error_refused, message.code ?: "-")
    LoginMessage.OfflineNeverOnline -> stringResource(R.string.auth_error_offline_never_online)
    LoginMessage.OfflineExpired -> stringResource(R.string.auth_error_offline_expired)
    LoginMessage.OfflineWrongPassword -> stringResource(R.string.auth_error_offline_wrong_password)
    LoginMessage.OfflineClockWrong -> stringResource(R.string.auth_error_offline_clock_wrong)
    is LoginMessage.OfflineCooldown -> stringResource(R.string.auth_error_offline_cooldown, localizedDigits(dhakaTime(message.untilMs)))
}

/** HH:mm in Asia/Dhaka (UTC+6) for the cool-down end. */
private fun dhakaTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Dhaka") }.format(Date(epochMs))
