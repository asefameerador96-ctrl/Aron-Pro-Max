package com.aktcl.aron.core.session

import android.content.Context
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.core.network.AccessTokenSource
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.ApiResponseListener
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.AuthApi
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.DeviceProofSigner
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.core.network.SyncApi
import okhttp3.OkHttpClient
import java.io.File

/**
 * Wires the network and session objects of one app process. The app module exposes them through its Hilt module
 * (docs/24 s5.1: Hilt only in app modules); tests build it with fakes for the Keystore and Argon2.
 */
class SessionComponents(
    origin: ApiOrigin,
    appVersion: String,
    /** `app_sr`, `app_amo` or `app_tso`. */
    client: String,
    storageDir: File,
    cipher: SecretCipher,
    verifier: PasswordVerifier,
    okHttp: OkHttpClient = AronApiClient.defaultOkHttp(),
    proofSigner: DeviceProofSigner? = null,
    listener: ApiResponseListener? = null,
) {
    private val tokens = DelegatingTokenSource()

    val deviceIdentity = DeviceIdentity(storageDir)
    val apiClient = AronApiClient(origin, okHttp, ClientIdentity(appVersion) { deviceIdentity.deviceUuid }, tokens, listener)
    val authApi = AuthApi(apiClient, proofSigner)
    val syncApi = SyncApi(apiClient)
    val session = SessionRepository(authApi, SessionStore(File(storageDir, "session"), cipher), verifier, deviceIdentity, client)

    init {
        tokens.target = session
    }

    companion object {
        /** Production wiring: Keystore AES-GCM, native Argon2id, files under noBackupFilesDir. */
        fun create(context: Context, origin: ApiOrigin, appVersion: String, client: String, proofSigner: DeviceProofSigner? = null) =
            SessionComponents(
                origin = origin,
                appVersion = appVersion,
                client = client,
                storageDir = File(context.noBackupFilesDir, "aron"),
                cipher = KeystoreSecretCipher(),
                verifier = Argon2idPasswordVerifier(),
                proofSigner = proofSigner,
            )
    }
}

private class DelegatingTokenSource : AccessTokenSource {
    @Volatile var target: AccessTokenSource? = null
    override fun currentAccessToken(grant: Grant): String? = target?.currentAccessToken(grant)
    override suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: ProblemCode?): Boolean =
        target?.refreshAfterUnauthorized(grant, rejectedToken, code) ?: false
}
