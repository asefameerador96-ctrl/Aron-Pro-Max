package com.aktcl.aron.core.network

// The auth wire DTOs (LoginRequest, LoginResponse, RefreshRequest, TokenPair, LogoutRequest and their parts) are
// shared:contract's generated `com.aktcl.aron.contract.*` (N-002). What stays here is phone-side vocabulary only.

/** Values of `LoginResponse.status` (contract). */
object LoginStatus {
    const val OK = "ok"
    const val BIND_REQUIRED = "bind_required"
    const val MFA_REQUIRED = "mfa_required"
    const val PASSWORD_CHANGE_REQUIRED = "password_change_required"
}

/** Refresh grants of docs/24 s8.1. */
enum class Grant(val wire: String) {
    FULL("full"),
    UPLOAD("upload"),
}
