package com.aktcl.aron.core.sync.push

import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.DeviceProofSigner
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.core.network.ProofStrings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Sends this phone's FCM token; [PushTokenApi] in production. */
fun interface PushTokenSender {
    suspend fun register(token: String): ApiResult<Unit>
}

/**
 * `PUT /v1/devices/me/push-token` (registerPushToken, N-037): `{provider: "fcm", token, app_flavour}` under the signed-in
 * user's full grant, with the `device` proof of docs/24 s8.3 over the exact body bytes.
 */
class PushTokenApi(
    private val client: AronApiClient,
    private val signer: DeviceProofSigner?,
    private val deviceUuid: () -> String?,
    private val appFlavour: String,
    private val trustedNowMs: () -> Long,
) : PushTokenSender {
    override suspend fun register(token: String): ApiResult<Unit> {
        val body = buildJsonObject { put("provider", "fcm"); put("token", token); put("app_flavour", appFlavour) }.toString().toByteArray(Charsets.UTF_8)
        val proof = deviceUuid()?.let { uuid -> signer?.sign(ProofStrings.device(uuid, "PUT $PATH", body, trustedNowMs())) }
        return client.call(
            path = PATH,
            auth = CallAuth.Grant(Grant.FULL),
            build = {
                if (proof != null) header("X-Device-Proof", proof)
                put(body.toRequestBody(JSON))
            },
            decode = { _, _ -> },
        )
    }

    companion object {
        const val PATH = "/v1/devices/me/push-token"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

enum class TokenOutcome { NO_TOKEN, ALREADY, REGISTERED, OFFLINE, FAILED, REFUSED, BACKED_OFF }

/**
 * Keeps the server's copy of this phone's FCM token pointing at the signed-in user (N-038). It sends only when the token
 * or the user changed since the last answered registration, so calling [ensure] on every login, resume and token refresh
 * costs nothing most of the time. The server moves a token to whoever registered it last (a shared phone changes hands),
 * so the record is one "user|token" pair, not one per user: user A coming back after B registers again.
 * A refusal (4xx other than 401/429) is not retried for [refusalBackoffMs] on trusted time (e.g. a phone not enrolled yet).
 */
class PushTokenRegistrar(
    private val sender: PushTokenSender,
    private val store: Store,
    private val trustedNowMs: () -> Long,
    private val refusalBackoffMs: Long = 12 * 3_600_000L,
) {
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String?)

        class Memory : Store {
            private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
            override fun get(key: String) = map[key]
            override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
        }
    }

    private val lock = Mutex()

    suspend fun ensure(userId: Long, token: String?): TokenOutcome = lock.withLock {
        if (token.isNullOrBlank()) return@withLock TokenOutcome.NO_TOKEN
        val pair = "$userId|" + ProofStrings.sha256Hex(token.toByteArray(Charsets.UTF_8))
        if (store.get(KEY_REGISTERED) == pair) return@withLock TokenOutcome.ALREADY
        val refused = store.get(KEY_REFUSED)?.split('#')
        if (refused != null && refused.size == 2 && refused[0] == pair) {
            val at = refused[1].toLongOrNull()
            val now = trustedNowMs()
            if (at != null && now >= at && now - at < refusalBackoffMs) return@withLock TokenOutcome.BACKED_OFF
        }
        when (val r = sender.register(token)) {
            is ApiResult.Success, is ApiResult.NotModified -> {
                store.put(KEY_REGISTERED, pair)
                store.put(KEY_REFUSED, null)
                TokenOutcome.REGISTERED
            }
            is ApiResult.Transport -> TokenOutcome.OFFLINE
            is ApiResult.Failure -> if (r.httpStatus == 401 || r.httpStatus == 429 || r.httpStatus >= 500) {
                TokenOutcome.FAILED
            } else {
                store.put(KEY_REFUSED, pair + "#" + trustedNowMs())
                TokenOutcome.REFUSED
            }
        }
    }


    /**
     * An online login or a finished bind may have fixed what was refused (e.g. the device key reached the server): the
     * next [ensure] asks again instead of waiting out the back-off.
     */
    fun retryRefused() = store.put(KEY_REFUSED, null)

    companion object {
        const val KEY_REGISTERED = "registered"
        const val KEY_REFUSED = "refused"
    }
}
