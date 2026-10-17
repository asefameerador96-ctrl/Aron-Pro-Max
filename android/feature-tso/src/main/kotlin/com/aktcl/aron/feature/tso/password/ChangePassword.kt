package com.aktcl.aron.feature.tso.password

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Contract ChangePasswordRequest bounds; the full policy (deny-list, history, 24 h) is the server's. */
const val MIN_NEW_PASSWORD = 8
const val MAX_PASSWORD = 128

enum class CpError { CURRENT_EMPTY, NEW_SHORT, NEW_LONG, NEW_SAME, MISMATCH }

sealed interface CpOutcome {
    data object Ok : CpOutcome
    /** 401: the current password is wrong (or the session ended). */
    data object WrongCurrent : CpOutcome
    /** 400 `ERR_AUTH_PASSWORD_POLICY`: the new password breaks the policy. */
    data object Policy : CpOutcome
    data object RateLimited : CpOutcome
    data object Offline : CpOutcome
    data object Failed : CpOutcome
}

/** Online only: a password is changed on the server; there is no queue and the secrets are never stored or logged. */
fun interface ChangePasswordApi { suspend fun change(current: String, new: String): CpOutcome }

fun validatePasswordChange(current: String, new: String, confirm: String): List<CpError> {
    val e = mutableListOf<CpError>()
    if (current.isEmpty()) e += CpError.CURRENT_EMPTY
    if (new.length < MIN_NEW_PASSWORD) e += CpError.NEW_SHORT else if (new.length > MAX_PASSWORD) e += CpError.NEW_LONG
    if (new.isNotEmpty() && new == current) e += CpError.NEW_SAME
    if (new != confirm) e += CpError.MISMATCH
    return e
}

sealed interface CpPhase {
    data object Editing : CpPhase
    data object Sending : CpPhase
    data object Done : CpPhase
    data class Invalid(val errors: List<CpError>) : CpPhase
    data class Refused(val why: CpOutcome) : CpPhase
}

class ChangePasswordState(private val api: ChangePasswordApi) {
    private val _s = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _s.asStateFlow()
    private var busy = false

    data class Ui(val current: String = "", val new: String = "", val confirm: String = "", val phase: CpPhase = CpPhase.Editing, val visible: Boolean = false) {
        override fun toString() = "Ui(phase=$phase)" // never prints the passwords
    }

    private fun edit(f: (Ui) -> Ui) { if (_s.value.phase is CpPhase.Sending || _s.value.phase is CpPhase.Done) return; _s.value = f(_s.value).copy(phase = CpPhase.Editing) }
    fun setCurrent(v: String) = edit { it.copy(current = v.take(MAX_PASSWORD + 1)) }
    fun setNew(v: String) = edit { it.copy(new = v.take(MAX_PASSWORD + 1)) }
    fun setConfirm(v: String) = edit { it.copy(confirm = v.take(MAX_PASSWORD + 1)) }
    fun toggleVisible() { _s.value = _s.value.copy(visible = !_s.value.visible) }

    suspend fun submit() {
        val s = _s.value
        if (busy || s.phase is CpPhase.Sending || s.phase is CpPhase.Done) return
        val errors = validatePasswordChange(s.current, s.new, s.confirm)
        if (errors.isNotEmpty()) { _s.value = s.copy(phase = CpPhase.Invalid(errors)); return }
        busy = true
        _s.value = s.copy(phase = CpPhase.Sending)
        try {
            val o = runCatching { api.change(s.current, s.new) }.getOrElse { CpOutcome.Failed }
            // on success every secret is dropped from memory state at once
            _s.value = if (o is CpOutcome.Ok) Ui(phase = CpPhase.Done) else _s.value.copy(phase = CpPhase.Refused(o))
        } finally { busy = false }
    }
}
