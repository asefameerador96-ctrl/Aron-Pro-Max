package com.aktcl.aron.feature.auth

/** Four OTP boxes and Verify (F-SR-002). Error text is chosen by the screen from [OtpError], in Bangla or English. */
enum class OtpError {
    INVALID, EXPIRED, ATTEMPTS_EXCEEDED, LOCKED, OFFLINE, OTHER,

    /** 409: the account is already bound to its maximum of phones. The OTP stays valid: Verify again once one is freed. */
    DEVICE_LIMIT,
}

data class OtpState(val digits: String = "", val error: OtpError? = null, val busy: Boolean = false, val bound: Boolean = false) {
    val canVerify: Boolean get() = digits.length == LENGTH && !busy && !bound && error != OtpError.ATTEMPTS_EXCEEDED && error != OtpError.LOCKED
    companion object { const val LENGTH = 4 }
}

object OtpModel {
    /** Accepts typed or pasted text; keeps only the first four digits (Bengali digits become Western). */
    fun enter(state: OtpState, raw: String): OtpState {
        if (state.busy || state.bound) return state
        val western = raw.map { if (it in '০'..'৯') '0' + (it - '০') else it }.filter { it in '0'..'9' }.take(OtpState.LENGTH).joinToString("")
        return state.copy(digits = western, error = if (state.error == OtpError.INVALID || state.error == OtpError.EXPIRED) null else state.error)
    }

    /** Maps the contract's problem code to the screen error; the code is the only thing to branch on. */
    fun errorOf(problemCode: String?, offline: Boolean): OtpError = when {
        offline -> OtpError.OFFLINE
        problemCode == "ERR_AUTH_OTP_INVALID" -> OtpError.INVALID
        problemCode == "ERR_AUTH_OTP_EXPIRED" -> OtpError.EXPIRED
        problemCode == "ERR_AUTH_OTP_ATTEMPTS_EXCEEDED" -> OtpError.ATTEMPTS_EXCEEDED
        problemCode == "ERR_AUTH_BIND_LOCKED" -> OtpError.LOCKED
        problemCode == "ERR_DEVICE_LIMIT_REACHED" -> OtpError.DEVICE_LIMIT
        else -> OtpError.OTHER
    }

    fun failed(state: OtpState, problemCode: String?, offline: Boolean) = state.copy(busy = false, error = errorOf(problemCode, offline), digits = if (problemCode == "ERR_AUTH_OTP_INVALID") "" else state.digits)
    fun succeeded(state: OtpState) = state.copy(busy = false, error = null, bound = true)
}
