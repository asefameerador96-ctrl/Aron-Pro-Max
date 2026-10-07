package com.aktcl.aron.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.core.common.LocaleDigits
import com.aktcl.aron.core.session.BindOutcome
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

    /** The 10-minute bind token ran out during the OTP step (F-SR-002): the SR logs in again, the OTP stays valid. */
    data object SignInAgain : LoginMessage
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
    /** Set after a [LoginOutcome.BindRequired]: the OTP step is showing. The bind token never leaves this process. */
    val bindToken: String? = null,
    val otp: OtpState = OtpState(),
)

/**
 * Login screen state (docs/24 s5.1: one StateFlow, intents in). The [login] use case is the session repository; it
 * decides between online login and offline unlock, so this screen behaves the same with or without a network.
 */
class LoginViewModel(
    private val login: suspend (username: String, password: String) -> LoginOutcome,
) : ViewModel() {
    /** F-SYS-003 device bind (token, OTP, password); null where the app has no bind step (then BindRequired is only a message). */
    var bind: (suspend (bindToken: String, otp: String, password: String) -> BindOutcome)? = null

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
                when {
                    outcome is LoginOutcome.LoggedIn -> it.copy(busy = false, password = "", message = null)
                    outcome is LoginOutcome.BindRequired && outcome.bindToken != null && bind != null ->
                        it.copy(busy = false, message = null, bindToken = outcome.bindToken, otp = OtpState())
                    else -> it.copy(busy = false, message = messageFor(outcome))
                }
            }
        }
    }

    fun onOtpDigits(raw: String) = _state.update { it.copy(otp = OtpModel.enter(it.otp, raw)) }

    fun onOtpVerify() {
        val s = _state.value
        val token = s.bindToken ?: return
        val bindCall = bind ?: return
        if (!s.otp.canVerify) return
        _state.update { it.copy(otp = it.otp.copy(busy = true, error = null)) }
        viewModelScope.launch {
            val outcome = runCatching { bindCall(token, s.otp.digits, s.password) }.getOrElse { BindOutcome.Failed(null) }
            _state.update {
                when {
                    outcome is BindOutcome.Bound -> it.copy(password = "", bindToken = null, otp = OtpModel.succeeded(it.otp))
                    outcome is BindOutcome.PasswordChangeRequired -> it.copy(password = "", bindToken = null, otp = OtpState(), message = LoginMessage.PasswordChangeRequired)
                    outcome is BindOutcome.Failed && OtpModel.errorOf(outcome.code, outcome.offline) == OtpError.SIGN_IN_AGAIN ->
                        it.copy(bindToken = null, otp = OtpState(), message = LoginMessage.SignInAgain)
                    outcome is BindOutcome.Failed -> it.copy(otp = OtpModel.failed(it.otp, outcome.code, outcome.offline))
                    else -> it
                }
            }
        }
    }

    /** Leaves the OTP step for the login form; the typed password is dropped (the rep re-enters it, so it never lingers behind a dead end). */
    fun onOtpBack() = _state.update { if (it.otp.busy) it else it.copy(bindToken = null, password = "", otp = OtpState()) }

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
