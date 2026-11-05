package com.aktcl.aron.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
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
    const val ENROL = "login_enrol"
}

/**
 * The login screen of every field app (Day-1 shell, N-001; screens-sr s1). It works the same offline: the session
 * decides between online login and offline unlock. [appTitle] is the launcher label resource of the app (ARON SR, AMO,
 * TSO). [sunlight] and [onSunlight] wire the sun switch of the top row (it works before sign-in; the host stores it).
 */
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    appTitle: String,
    versionName: String,
    onLanguageSelect: (AppLanguage) -> Unit,
    /** Shown with the "not enrolled" refusal while this phone is not enrolled (see [EnrolmentGate]). */
    onEnrol: (() -> Unit)? = null,
    sunlight: Boolean = false,
    onSunlight: ((Boolean) -> Unit)? = null,
    /** [LoginVariant.Tso] draws the TSO screen of screenshot 5 (D-TSO-MAN-15); the TSO app never reaches the OTP step (D-AUTH-TSO-NOBIND). */
    variant: LoginVariant = LoginVariant.Standard,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (variant == LoginVariant.Tso && state.bindToken == null) {
        TsoLoginContent(
            state, viewModel::onUsernameChange, viewModel::onPasswordChange, viewModel::onTogglePasswordVisible, viewModel::onSubmit,
            onLanguageSelect, onEnrol,
        )
        return
    }
    if (state.bindToken != null) {
        androidx.activity.compose.BackHandler(onBack = viewModel::onOtpBack)
        OtpContent(
            state.otp, viewModel::onOtpDigits, viewModel::onOtpVerify, onBack = viewModel::onOtpBack,
            appTitle = appTitle, onLanguageSelect = onLanguageSelect, sunlight = sunlight, onSunlight = onSunlight,
        )
        return
    }
    LoginContent(
        state = state,
        appTitle = appTitle,
        versionName = versionName,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onTogglePasswordVisible = viewModel::onTogglePasswordVisible,
        onSubmit = viewModel::onSubmit,
        onLanguageSelect = onLanguageSelect,
        onEnrol = onEnrol,
        sunlight = sunlight,
        onSunlight = onSunlight,
    )
}

/**
 * The login frame (screens-sr s1): the language toggle and sun switch row, the brand block, the field card (username,
 * password with show or hide), the banner slot, the footer caption and the bar primary riding above the keyboard.
 * The primary is disabled with its reason until both fields are non-empty; while busy the label stays and the arc turns.
 */
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
    onEnrol: (() -> Unit)? = null,
    sunlight: Boolean = false,
    onSunlight: ((Boolean) -> Unit)? = null,
) {
    val filled = state.username.isNotBlank() && state.password.isNotEmpty()
    val wrong = state.message == LoginMessage.InvalidCredentials || state.message == LoginMessage.OfflineWrongPassword
    ScreenWithBar(Modifier.fillMaxSize(), bar = {
        Column(Modifier.fillMaxWidth()) {
            AuthBar(
                primaryText = stringResource(if (state.busy) R.string.auth_logging_in else R.string.auth_login_button),
                onPrimary = onSubmit, primaryTag = LoginTags.SUBMIT,
                enabled = filled && !state.busy, busy = state.busy,
                reason = stringResource(R.string.auth_error_empty),
            )
        }
    }) {
        ScrollColumn(contentPadding = PaddingValues(horizontal = AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            AuthTopRow(onLanguageSelect, sunlight, onSunlight)
            Spacer(Modifier.height(AronTokens.Space.L))
            BrandBlock(appTitle)
            Spacer(Modifier.height(AronTokens.Space.L))
            AuthCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.L)) {
                    AuthField(
                        value = state.username, onValueChange = onUsernameChange, label = stringResource(R.string.auth_username),
                        modifier = Modifier.testTag(LoginTags.USERNAME), enabled = !state.busy, error = wrong,
                        keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next,
                    )
                    AuthField(
                        value = state.password, onValueChange = onPasswordChange, label = stringResource(R.string.auth_password),
                        modifier = Modifier.testTag(LoginTags.PASSWORD), enabled = !state.busy, error = wrong,
                        password = !state.passwordVisible, imeAction = ImeAction.Done,
                        onImeAction = { if (filled && !state.busy) onSubmit() },
                        trailing = {
                            AuthLink(stringResource(if (state.passwordVisible) R.string.auth_password_hide else R.string.auth_password_show), onTogglePasswordVisible)
                        },
                    )
                    state.message?.let { message ->
                        // A solid banner (docs/32 s2a): readable in sun, never colour alone; the kind says how serious it is.
                        AuthBanner(messageText(message), Modifier.testTag(LoginTags.MESSAGE), kind = bannerKind(message))
                        if (message == LoginMessage.DeviceNotEnrolled && onEnrol != null) {
                            AuthLink(stringResource(R.string.enrol_title), onEnrol, Modifier.fillMaxWidth().testTag(LoginTags.ENROL))
                        }
                    }
                }
            }
            AuthFooter(localizedDigits(stringResource(R.string.auth_version, versionName)), Modifier.testTag(LoginTags.VERSION))
        }
    }
}

/** The message of a login attempt on its notice card (shared by both variants). */
@Composable
internal fun LoginMessageCard(message: LoginMessage, modifier: Modifier = Modifier) {
    AuthBanner(messageText(message), modifier, kind = bannerKind(message))
}

/** Danger only for a wrong password or a refusal; a lock, a rate limit, an update or a bind step warn; never-online is the offline note. */
private fun bannerKind(message: LoginMessage): BannerKind = when (message) {
    LoginMessage.InvalidCredentials, LoginMessage.OfflineWrongPassword, LoginMessage.UserDisabled, LoginMessage.DeviceBlocked,
    LoginMessage.PasswordChangeRequired, LoginMessage.EmptyFields, is LoginMessage.Refused -> BannerKind.Error
    LoginMessage.OfflineNeverOnline -> BannerKind.Offline
    else -> BannerKind.Warning
}

@Composable
private fun messageText(message: LoginMessage): String = when (message) {
    LoginMessage.EmptyFields -> stringResource(R.string.auth_error_empty)
    LoginMessage.InvalidCredentials -> stringResource(R.string.auth_error_invalid_credentials)
    LoginMessage.BindRequired -> stringResource(R.string.auth_error_bind_required)
    LoginMessage.SignInAgain -> stringResource(R.string.otp_sign_in_again)
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
