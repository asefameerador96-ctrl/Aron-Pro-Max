package com.aktcl.aron.core.network

import com.aktcl.aron.contract.LoginRequest
import com.aktcl.aron.contract.LoginResponse
import com.aktcl.aron.contract.LogoutRequest
import com.aktcl.aron.contract.RefreshRequest
import com.aktcl.aron.contract.TokenPair
import kotlinx.serialization.KSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

internal val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

internal fun <T> jsonBody(serializer: KSerializer<T>, value: T): RequestBody =
    WireJson.requests.encodeToString(serializer, value).toRequestBody(JSON_MEDIA)

/** `POST /v1/auth/login`, `/refresh`, `/logout` (contract tag `auth`, docs/24 s8.1). */
class AuthApi(
    private val client: AronApiClient,
    private val proofSigner: DeviceProofSigner? = null,
    private val trustedNowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun login(request: LoginRequest, callTimeoutS: Long = LOGIN_CALL_TIMEOUT_S): ApiResult<LoginResponse> =
        client.call(
            path = "/v1/auth/login",
            auth = CallAuth.None,
            callTimeoutS = callTimeoutS,
            build = { post(jsonBody(LoginRequest.serializer(), request)) },
            decode = { body, _ -> WireJson.responses.decodeFromString(LoginResponse.serializer(), body) },
        )

    /** Rotates a refresh token. Sends `X-Device-Proof` over the s8.3 refresh string when the device key exists. */
    suspend fun refresh(refreshToken: String, grant: Grant): ApiResult<TokenPair> {
        val deviceUuid = client.deviceUuid
        val proof = if (deviceUuid != null) proofSigner?.sign(ProofStrings.refresh(deviceUuid, refreshToken, trustedNowMs())) else null
        return client.call(
            path = "/v1/auth/refresh",
            auth = CallAuth.None,
            build = {
                if (proof != null) header("X-Device-Proof", proof)
                post(jsonBody(RefreshRequest.serializer(), RefreshRequest(refreshToken, grant.wire, deviceUuid)))
            },
            decode = { body, _ -> WireJson.responses.decodeFromString(TokenPair.serializer(), body) },
        )
    }

    /**
     * Ends the full-grant session on the server; the upload grant survives unless [scope] says otherwise (D24-57).
     * Takes the access token explicitly because the phone clears its tokens before this best-effort call.
     */
    suspend fun logout(scope: String, refreshToken: String?, accessToken: String, callTimeoutS: Long = LOGOUT_CALL_TIMEOUT_S): ApiResult<Unit> =
        client.call(
            path = "/v1/auth/logout",
            auth = CallAuth.Bearer(accessToken),
            callTimeoutS = callTimeoutS,
            build = { post(jsonBody(LogoutRequest.serializer(), LogoutRequest(scope, refreshToken))) },
            decode = { _, _ -> },
        )

    companion object {
        /** Login gives up quickly on a bad link so the screen can unlock offline (docs/24 s8.1). */
        const val LOGIN_CALL_TIMEOUT_S: Long = 15

        /** Logout never makes the user wait: the local logout is already done when this call starts. */
        const val LOGOUT_CALL_TIMEOUT_S: Long = 5
    }
}
