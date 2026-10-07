package com.aktcl.aron.core.network

import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.core.common.ClientIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** What the phone tells the API about itself on every call (docs/24 s3.2). */
class ClientIdentity(
    /** `<versionName>+<versionCode>`, sent as `X-App-Version`. */
    val appVersion: String,
    /** The device uuid (enrolment), sent as `X-Device-Id`; null until known. */
    val deviceUuid: () -> String?,
)

/**
 * F-SYS-047: the latest `X-Server-Generation` any API response carried in this process (one server per process). The sync
 * engine compares it with the generation each user's database last handled at the start of a run, so a restore is noticed
 * even by a phone whose next batch is far away (a bundle, config or health answer is enough). The nil uuid is ignored.
 */
object ServerGenerationHint {
    private const val NIL = "00000000-0000-4000-8000-000000000000"
    @Volatile var latest: String? = null
        private set

    fun observe(value: String) {
        val v = value.trim().lowercase()
        if (v.isNotEmpty() && v != NIL) latest = v
    }
}

/** Receives the marker headers of every API-originated response (trusted-time anchors, config version, generation). */
fun interface ApiResponseListener {
    fun onApiResponse(meta: ResponseMeta)
}

/** Which bearer token a call carries. */
sealed interface CallAuth {
    data object None : CallAuth
    data class Grant(val grant: com.aktcl.aron.core.network.Grant) : CallAuth

    /** A single-purpose token (`bind_token`, `mfa_token`) that is never refreshed. */
    data class Bearer(val token: String) : CallAuth {
        override fun toString(): String = "Bearer(***)"
    }
}

/**
 * Supplies access tokens and refreshes them after a 401 (implemented by core-session). A refresh failure never
 * touches local data: the caller simply gets the 401 back and the phone keeps working offline (docs/24 s3.4).
 */
interface AccessTokenSource {
    fun currentAccessToken(grant: Grant): String?

    /**
     * Called once per request after `401 ERR_TOKEN_EXPIRED` or `ERR_SCOPE_CHANGED` with the token that was
     * rejected. Returns true when a new access token is available for a single repeat of the request.
     */
    suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: ProblemCode?): Boolean
}

/**
 * The phone's HTTP client for the `/v1` API (docs/24 s3, D24-06): OkHttp with transparent gzip, the standard phone
 * headers, edge-response detection (`X-Aron-Api`), RFC 9457 problems and one refresh-and-repeat on an expired token.
 * It never retries on its own beyond that; retry policy belongs to the caller (the sync engine's backoff, s4.7).
 */
class AronApiClient(
    val origin: ApiOrigin,
    baseClient: OkHttpClient,
    private val identity: ClientIdentity,
    private val tokens: AccessTokenSource? = null,
    private val listener: ApiResponseListener? = null,
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .addInterceptor(StandardHeadersInterceptor(identity))
        .addNetworkInterceptor(RequestIdInterceptor)
        .build()

    val deviceUuid: String? get() = identity.deviceUuid()

    /**
     * Executes [build] against [path] and decodes a 2xx body with [decode]. [callTimeoutS] overrides the whole-call
     * timeout (login uses a short one so the screen falls back to offline unlock quickly).
     */
    suspend fun <T> call(
        path: String,
        auth: CallAuth,
        callTimeoutS: Long? = null,
        build: Request.Builder.() -> Unit,
        decode: (body: String, meta: ResponseMeta) -> T,
    ): ApiResult<T> = callStreaming(path, auth, callTimeoutS, build) { source, meta -> decode(source.readUtf8(), meta) }

    /**
     * Like [call], but a 2xx body is decoded straight from the response stream (AUD-PERF-06): a bundle of several MB of
     * JSON is never held as one String on a 2 GB phone. A connection lost mid-body is a transport failure.
     */
    suspend fun <T> callStreaming(
        path: String,
        auth: CallAuth,
        callTimeoutS: Long? = null,
        build: Request.Builder.() -> Unit,
        decode: (body: BufferedSource, meta: ResponseMeta) -> T,
    ): ApiResult<T> {
        val (first, sentToken) = attempt(path, auth, callTimeoutS, build, decode)
        if (auth is CallAuth.Grant && tokens != null && first is ApiResult.Failure && first.httpStatus == 401) {
            val code = first.problem.problemCode
            if (code == ProblemCode.ERR_TOKEN_EXPIRED || code == ProblemCode.ERR_SCOPE_CHANGED) {
                if (tokens.refreshAfterUnauthorized(auth.grant, sentToken, code)) {
                    return attempt(path, auth, callTimeoutS, build, decode).first
                }
            }
        }
        return first
    }

    private suspend fun <T> attempt(
        path: String,
        auth: CallAuth,
        callTimeoutS: Long?,
        build: Request.Builder.() -> Unit,
        decode: (BufferedSource, ResponseMeta) -> T,
    ): Pair<ApiResult<T>, String?> {
        val token = when (auth) {
            CallAuth.None -> null
            is CallAuth.Bearer -> auth.token
            // The token source may unwrap tokens with the Keystore (and settle the session restore): never on the caller's thread.
            is CallAuth.Grant -> tokens?.let { t -> withContext(Dispatchers.IO) { t.currentAccessToken(auth.grant) } }
        }
        val builder = Request.Builder().url(origin.path(path)).apply(build)
        if (token != null) builder.header("Authorization", "Bearer $token")
        val request = builder.build()
        val callClient = if (callTimeoutS != null) client.newBuilder().callTimeout(callTimeoutS, TimeUnit.SECONDS).build() else client
        val result = try {
            runInterruptible(Dispatchers.IO) {
                callClient.newCall(request).execute().use { response -> read(response, decode) }
            }
        } catch (e: IOException) {
            ApiResult.Transport(classify(e), e)
        }
        return result to token
    }

    private fun <T> read(response: Response, decode: (BufferedSource, ResponseMeta) -> T): ApiResult<T> {
        if (response.header(HEADER_ARON_API) != "1") return ApiResult.Transport(TransportFailure.EDGE_RESPONSE)
        val meta = ResponseMeta(
            httpStatus = response.code,
            requestId = response.header("X-Request-Id"),
            serverTime = response.header("X-Server-Time"),
            configVersion = response.header("X-Config-Version")?.toLongOrNull(),
            serverGeneration = response.header("X-Server-Generation"),
            bundleVersionCurrent = response.header("X-Bundle-Version-Current"),
            etag = response.header("ETag"),
            retryAfterS = response.header("Retry-After")?.trim()?.toIntOrNull(),
        )
        meta.serverGeneration?.let(ServerGenerationHint::observe)
        listener?.onApiResponse(meta)
        if (response.code == 304) return ApiResult.NotModified(meta)
        if (response.isSuccessful) {
            return try {
                ApiResult.Success(decode(response.body.source(), meta), meta)
            } catch (e: SerializationException) {
                ApiResult.Transport(TransportFailure.MALFORMED, e)
            } catch (e: IllegalArgumentException) {
                ApiResult.Transport(TransportFailure.MALFORMED, e)
            }
        }
        val body = response.body.string()
        val problem = runCatching { WireJson.responses.decodeFromString(Problem.serializer(), body) }
            .getOrElse { Problem(status = response.code) }
        return ApiResult.Failure(response.code, problem, meta)
    }

    companion object {
        const val HEADER_ARON_API = "X-Aron-Api"

        /** The shared OkHttp base: conservative timeouts for 2G-class links; per-call overrides where the spec asks. */
        fun defaultOkHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()

        internal fun classify(e: IOException): TransportFailure = when (e) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException, is SSLException -> TransportFailure.OFFLINE
            is InterruptedIOException -> TransportFailure.TIMEOUT
            is SocketException -> TransportFailure.OFFLINE
            else -> if (e.message?.contains("timeout", ignoreCase = true) == true) TransportFailure.TIMEOUT else TransportFailure.OFFLINE
        }
    }
}

/** `X-App-Version` and `X-Device-Id` on every call (docs/24 s3.2). */
private class StandardHeadersInterceptor(private val identity: ClientIdentity) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder().header("X-App-Version", identity.appVersion)
        identity.deviceUuid()?.let { builder.header("X-Device-Id", it) }
        return chain.proceed(builder.build())
    }
}

/** A new `X-Request-Id` per HTTP attempt (docs/24 s3.1 item 7), so it runs as a network interceptor. */
private object RequestIdInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("X-Request-Id", ClientIds.newUuid()).build())
}
