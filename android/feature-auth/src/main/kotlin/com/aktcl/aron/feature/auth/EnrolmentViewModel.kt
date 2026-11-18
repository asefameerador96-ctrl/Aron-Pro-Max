package com.aktcl.aron.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aktcl.aron.core.session.EnrolmentOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A message the enrolment screen shows; resolved to a Bangla or English string resource by the screen. */
sealed interface EnrolMessage {
    data object Empty : EnrolMessage
    data object Unreadable : EnrolMessage
    data class Waiting(val offline: Boolean) : EnrolMessage
    data class Refused(val code: String) : EnrolMessage
}

data class EnrolUiState(
    /** The token or QR text being entered; cleared once it is handed over (the coordinator keeps it until the answer). */
    val input: String = "",
    val busy: Boolean = false,
    /** A token is stored and waits for the server: the screen offers "Try again" instead of a new entry. */
    val pending: Boolean = false,
    val message: EnrolMessage? = null,
    val enrolled: Boolean = false,
)

/**
 * The enrolment step before the first login of a new install (docs/24 s10.4): a token from the admin portal (typed or pasted) goes to [submit] (core-sync `DeviceEnrolment.submit`); null retries the stored one. A stored token is
 * retried once when the screen opens (a kill mid-enrolment leaves it stored). Nothing here is on a sale path.
 */
class EnrolmentViewModel(
    private val submit: suspend (input: String?) -> EnrolmentOutcome,
    pending: Boolean,
    refusedCode: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(EnrolUiState(pending = pending, message = refusedCode?.let { EnrolMessage.Refused(it) }))
    val state: StateFlow<EnrolUiState> = _state.asStateFlow()

    init {
        if (pending) send(null)
    }

    fun onInput(value: String) = _state.update { it.copy(input = value, message = null) }

    fun onSubmit() {
        val s = _state.value
        if (s.busy) return
        if (s.input.isBlank()) { _state.update { it.copy(message = EnrolMessage.Empty) }; return }
        send(s.input)
    }

    fun onRetry() { if (!_state.value.busy) send(null) }

    private fun send(input: String?) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val outcome = try { submit(input) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { EnrolmentOutcome.Waiting(offline = false) }
            _state.update {
                when (outcome) {
                    EnrolmentOutcome.Enrolled -> it.copy(busy = false, input = "", pending = false, enrolled = true)
                    is EnrolmentOutcome.Waiting -> it.copy(busy = false, input = "", pending = true, message = EnrolMessage.Waiting(outcome.offline))
                    is EnrolmentOutcome.Refused -> it.copy(busy = false, input = "", pending = false, message = EnrolMessage.Refused(outcome.code))
                    // An unreadable entry keeps the text so a typo can be corrected; a real token for another server is
                    // not kept on screen.
                    is EnrolmentOutcome.Unreadable -> it.copy(busy = false, message = EnrolMessage.Unreadable,
                        input = if (outcome.reason == "other_server") "" else it.input)
                }
            }
        }
    }
}
