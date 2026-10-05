package com.aktcl.aron.backend.auth

import com.aktcl.aron.contract.Role
import kotlinx.serialization.Serializable

// Mirrors of contract/openapi.yaml schemas (LoginRequest, LoginResponse, RefreshRequest, TokenPair, Jwks, Me, ...).
// Member names are the wire names. When shared:contract publishes these DTOs, this file is replaced by them.

private val USERNAME = Regex("^[A-Za-z][A-Za-z0-9._-]{2,39}$")
private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

@Serializable
data class LoginRequest(val username: String, val password: String, val client: String, val device_uuid: String? = null) {
    init {
        require(USERNAME.matches(username)) { "/username: pattern" }
        require(password.length in 1..128) { "/password: length" }
        require(client in setOf("app_sr", "app_amo", "app_tso", "web")) { "/client: enum" }
        require(device_uuid == null || UUID_V4.matches(device_uuid)) { "/device_uuid: pattern" }
    }
}

@Serializable
data class UserSummary(val user_id: Long, val username: String, val full_name: String, val role: Role, val designation: String?, val locale: String)

@Serializable
data class NodeRef(val type: String, val id: Long, val code: String? = null, val name: String? = null)

@Serializable
data class ScopeSummary(val scope_version: Long, val nodes: List<NodeRef>)

@Serializable
data class LoginDevice(val device_id: Long, val bind_ordinal: Int?, val memo_seq_block_size: Int)

@Serializable
data class LoginResponse(
    val status: String,
    val access_token: String?,
    val access_expires_at: String?,
    val refresh_token: String?,
    val refresh_expires_at: String?,
    val upload_refresh_token: String?,
    val bind_token: String?,
    val mfa_token: String?,
    val user: UserSummary,
    val scope: ScopeSummary?,
    val device: LoginDevice?,
    val config_version: Long,
    val server_time: String,
    val min_app_version_code: Int?,
)

@Serializable
data class RefreshRequest(val grant: String, val refresh_token: String? = null, val device_uuid: String? = null) {
    init {
        require(grant == "full" || grant == "upload") { "/grant: enum" }
        require(refresh_token == null || refresh_token.length in 43..64) { "/refresh_token: length" }
        require(device_uuid == null || UUID_V4.matches(device_uuid)) { "/device_uuid: pattern" }
    }
}

@Serializable
data class TokenPair(
    val access_token: String,
    val access_expires_at: String,
    val refresh_token: String?,
    val refresh_expires_at: String,
    val scope_version: Long,
    val server_time: String,
)

@Serializable
data class JwkEcPublic(val kty: String, val crv: String, val x: String, val y: String, val kid: String, val use: String, val alg: String)

@Serializable
data class Jwks(val keys: List<JwkEcPublic>)

@Serializable
data class Me(val user: UserSummary, val permissions: List<String>, val scope: ScopeSummary, val pii: Boolean, val mfa_enabled: Boolean)
