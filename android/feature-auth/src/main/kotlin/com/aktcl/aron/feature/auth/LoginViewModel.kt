package com.aktcl.aron.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.core.common.LocaleDigits
import com.aktcl.aron.core.session.LoginOutcome
import com.aktcl.aron.core.session.OfflineRefusal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A message the login screen shows; resolved to a Bangla or English string resource by the screen. */
sealed interface LoginMessage {
    data object EmptyFields : LoginMessage
    data object InvalidCredentials : LoginMessage
    data object BindRequired : LoginMessage
    data object PasswordChangeRequired : LoginMessage
    data class Locked(val retryAfterMinutes: Long?) : LoginMessage
    data object UserDisabled : LoginMessage
    data object DeviceNotEnrolled : LoginMessage
    data object DeviceBlocked : LoginMessage
    data object UpdateRequired : LoginMessage
    data class RateLimited(val retryAfterMinutes: Long?) : LoginMessage
    data class Refused(val code: String?) : LoginMessage
    data object OfflineNeverOnline : LoginMessage
    data object OfflineExpired : LoginMessage
    data object OfflineWrongPassword : LoginMessage
    data class OfflineCooldown(val untilMs: Long) : LoginMessage
    data object OfflineClockWrong : LoginMessage
}

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val busy: Boolean = false,
    val message: LoginMessage? = null,
)

/**
 * Login screen state (docs/24 s5.1: one StateFlow, intents in). The [login] use case is the session repository; it
 * decides between online login and offline unlock, so this screen behaves the same with or without a network.
 */
class LoginViewModel(
    private val login: suspend (username: String, password: String) -> LoginOutcome,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value.take(40), message = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value.take(128), message = null) }

    fun onTogglePasswordVisible() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun onSubmit() {
        val s = _state.value
        if (s.busy) return
        // Usernames never contain Bengali digits; normalise them in case the Bangla keyboard typed them.
        val username = LocaleDigits.toAscii(s.username).trim()
        if (username.isEmpty() || s.password.isEmpty()) {
            _state.update { it.copy(message = LoginMessage.EmptyFields) }
            return
        }
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val outcome = runCatching { login(username, s.password) }.getOrElse { LoginOutcome.Refused(null, null) }
            _state.update {
                if (outcome is LoginOutcome.LoggedIn) it.copy(busy = false, password = "", message = null)
                else it.copy(busy = false, message = messageFor(outcome))
            }
        }
    }

    companion object {
        fun messageFor(outcome: LoginOutcome): LoginMessage? = when (outcome) {
            is LoginOutcome.LoggedIn -> null
            LoginOutcome.InvalidCredentials -> LoginMessage.InvalidCredentials
            is LoginOutcome.BindRequired -> LoginMessage.BindRequired
            LoginOutcome.PasswordChangeRequired -> LoginMessage.PasswordChangeRequired
            is LoginOutcome.Refused -> when (outcome.code) {
                ProblemCode.ERR_AUTH_ACCOUNT_LOCKED.wire -> LoginMessage.Locked(outcome.retryAfterS?.let { minutesCeil(it) })
                ProblemCode.ERR_AUTH_USER_DISABLED.wire -> LoginMessage.UserDisabled
                ProblemCode.ERR_AUTH_PASSWORD_CHANGE_REQUIRED.wire -> LoginMessage.PasswordChangeRequired
                ProblemCode.ERR_DEVICE_NOT_ENROLLED.wire -> LoginMessage.DeviceNotEnrolled
                ProblemCode.ERR_DEVICE_SUSPENDED.wire, ProblemCode.ERR_DEVICE_REVOKED.wire, ProblemCode.ERR_DEVICE_UNBOUND.wire,
                ProblemCode.ERR_DEVICE_INTEGRITY_FAILED.wire -> LoginMessage.DeviceBlocked
                ProblemCode.ERR_APP_VERSION_UNSUPPORTED.wire -> LoginMessage.UpdateRequired
                ProblemCode.ERR_RATE_LIMITED.wire -> LoginMessage.RateLimited(outcome.retryAfterS?.let { minutesCeil(it) })
                else -> LoginMessage.Refused(outcome.code)
            }
            is LoginOutcome.OfflineUnavailable -> when (outcome.refusal) {
                OfflineRefusal.NEVER_ONLINE_ON_THIS_PHONE -> LoginMessage.OfflineNeverOnline
                OfflineRefusal.EXPIRED -> LoginMessage.OfflineExpired
                OfflineRefusal.WRONG_PASSWORD -> LoginMessage.OfflineWrongPassword
                OfflineRefusal.COOLDOWN -> LoginMessage.OfflineCooldown(outcome.cooldownUntilMs ?: 0)
                OfflineRefusal.CLOCK_INCONSISTENT -> LoginMessage.OfflineClockWrong
            }
        }

        private fun minutesCeil(seconds: Int): Long = (seconds + 59L) / 60L
    }
}
