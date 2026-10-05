package com.aktcl.aron.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire models of the auth operations of contract/openapi.yaml (LoginRequest, LoginResponse, RefreshRequest, TokenPair,
// LogoutRequest and their parts). REQUEST: docs/requests/android-core-contract-dtos.md asks the shared lane to host the
// DTOs in shared:contract; until then these mirror the YAML and AuthDtoContractTest checks every member name against it.

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    /** `app_sr`, `app_amo` or `app_tso` (the phone flavour). */
    val client: String,
    @SerialName("device_uuid") val deviceUuid: String?,
) {
    override fun toString(): String = "LoginRequest(username=$username, client=$client, deviceUuid=$deviceUuid, password=***)"
}

@Serializable
data class LoginResponse(
    /** `ok`, `bind_required`, `mfa_required` or `password_change_required`. */
    val status: String,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("access_expires_at") val accessExpiresAt: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("refresh_expires_at") val refreshExpiresAt: String? = null,
    @SerialName("upload_refresh_token") val uploadRefreshToken: String? = null,
    @SerialName("bind_token") val bindToken: String? = null,
    @SerialName("mfa_token") val mfaToken: String? = null,
    val user: UserSummary,
    val scope: ScopeSummary? = null,
    val device: LoginDevice? = null,
    @SerialName("config_version") val configVersion: Long,
    @SerialName("server_time") val serverTime: String,
    @SerialName("min_app_version_code") val minAppVersionCode: Int? = null,
) {
    override fun toString(): String = "LoginResponse(status=$status, user=${user.username}, tokens=***)"

    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_BIND_REQUIRED = "bind_required"
        const val STATUS_MFA_REQUIRED = "mfa_required"
        const val STATUS_PASSWORD_CHANGE_REQUIRED = "password_change_required"
    }
}

@Serializable
data class UserSummary(
    @SerialName("user_id") val userId: Long,
    val username: String,
    @SerialName("full_name") val fullName: String,
    val role: String,
    val designation: String? = null,
    val locale: String,
)

@Serializable
data class ScopeSummary(
    @SerialName("scope_version") val scopeVersion: Long,
    val nodes: List<NodeRef> = emptyList(),
)

@Serializable
data class NodeRef(
    val type: String,
    val id: Long,
    val code: String? = null,
    val name: String? = null,
)

@Serializable
data class LoginDevice(
    @SerialName("device_id") val deviceId: Long,
    @SerialName("bind_ordinal") val bindOrdinal: Int? = null,
    @SerialName("memo_seq_block_size") val memoSeqBlockSize: Int,
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String?,
    /** `full` or `upload`. */
    val grant: String,
    @SerialName("device_uuid") val deviceUuid: String?,
) {
    override fun toString(): String = "RefreshRequest(grant=$grant, deviceUuid=$deviceUuid, refreshToken=***)"
}

@Serializable
data class TokenPair(
    @SerialName("access_token") val accessToken: String,
    @SerialName("access_expires_at") val accessExpiresAt: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("refresh_expires_at") val refreshExpiresAt: String,
    @SerialName("scope_version") val scopeVersion: Long,
    @SerialName("server_time") val serverTime: String,
) {
    override fun toString(): String = "TokenPair(accessExpiresAt=$accessExpiresAt, scopeVersion=$scopeVersion, tokens=***)"
}

@Serializable
data class LogoutRequest(
    /** `session`, `upload` or `all`. */
    val scope: String,
    @SerialName("refresh_token") val refreshToken: String?,
) {
    override fun toString(): String = "LogoutRequest(scope=$scope, refreshToken=***)"
}

/** Refresh grants of docs/24 s8.1. */
enum class Grant(val wire: String) {
    FULL("full"),
    UPLOAD("upload"),
}
