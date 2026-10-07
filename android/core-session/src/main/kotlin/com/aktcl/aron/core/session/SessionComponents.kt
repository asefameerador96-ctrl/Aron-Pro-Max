package com.aktcl.aron.core.session

import android.content.Context
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.core.common.WallClock
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
    /** `<versionName>+<versionCode>` (`X-App-Version`, batch `app_version`). */
    val appVersion: String,
    /** `app_sr`, `app_amo` or `app_tso`. */
    client: String,
    storageDir: File,
    cipher: SecretCipher,
    verifier: PasswordVerifier,
    /** The base client; core-media and the updater derive their bare blob clients from it (one connection pool). */
    val okHttp: OkHttpClient = AronApiClient.defaultOkHttp(),
    /** Signs `X-Device-Proof` once the device key exists (enrolment); null before. */
    val proofSigner: DeviceProofSigner? = null,
    listener: ApiResponseListener? = null,
    /** Overrides the trusted clock (tests); production uses [trustedClock]. */
    clock: WallClock? = null,
    /** `Settings.Global.BOOT_COUNT`; the trusted clock's anchors are valid within one boot. */
    bootCount: () -> Int = { 0 },
) {
    private val tokens = DelegatingTokenSource()

    /** Trusted time (docs/24 s3.8, F-SYS-049): fed by every API response's `X-Server-Time`. */
    val trustedClock = TrustedClockSource(
        File(storageDir, "time-anchors"), bootCount,
        bootId = { runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } },
    )
    val clock: WallClock = clock ?: trustedClock

    val deviceIdentity = DeviceIdentity(storageDir)

    /** SQLCipher passphrases of the per-user databases, wrapped by the same Keystore cipher as the tokens. */
    val databaseKeys = DatabaseKeys(File(storageDir, "dbkeys"), cipher)
    val apiClient = AronApiClient(origin, okHttp, ClientIdentity(appVersion) { deviceIdentity.deviceUuid }, tokens,
        ApiResponseListener { meta -> trustedClock.onApiResponse(meta); listener?.onApiResponse(meta) })
    val authApi = AuthApi(apiClient, proofSigner, this.clock::nowMs)
    val syncApi = SyncApi(apiClient)
    val session = SessionRepository(authApi, SessionStore(File(storageDir, "session"), cipher), verifier, deviceIdentity, client, this.clock)

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
                bootCount = { android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0) },
            )
    }
}

private class DelegatingTokenSource : AccessTokenSource {
    @Volatile var target: AccessTokenSource? = null
    override fun currentAccessToken(grant: Grant): String? = target?.currentAccessToken(grant)
    override suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: ProblemCode?): Boolean =
        target?.refreshAfterUnauthorized(grant, rejectedToken, code) ?: false
}
