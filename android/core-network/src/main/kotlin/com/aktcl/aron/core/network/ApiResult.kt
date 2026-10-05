package com.aktcl.aron.core.network

import com.aktcl.aron.contract.ProblemCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * RFC 9457 problem details as the API sends them (docs/24 s3.4). Clients branch on [code] only, never on
 * [title] or [detail]. Read leniently: unknown members are ignored.
 */
@Serializable
data class Problem(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
    val code: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    val retryable: Boolean? = null,
    @SerialName("retry_after_s") val retryAfterS: Int? = null,
    @SerialName("server_time") val serverTime: String? = null,
    @SerialName("message_key") val messageKey: String? = null,
    val context: Map<String, JsonElement>? = null,
) {
    /** The contract code, or null when the server sent a code this build does not know. */
    val problemCode: ProblemCode? get() = code?.let { c -> ProblemCode.entries.firstOrNull { it.wire == c } }
}

/** Marker headers of an API-originated response (docs/24 s3.1 item 6). */
data class ResponseMeta(
    val httpStatus: Int,
    val requestId: String?,
    val serverTime: String?,
    val configVersion: Long?,
    val serverGeneration: String?,
    val bundleVersionCurrent: String?,
    val etag: String?,
    val retryAfterS: Int?,
)

/** Why a call produced no business answer. None of these may ever be shown as "wrong password" or similar. */
enum class TransportFailure {
    /** No route to the host, DNS failure, connection refused or reset (the phone is offline). */
    OFFLINE,

    /** Connect, read or whole-call timeout. */
    TIMEOUT,

    /** A response without `X-Aron-Api`: WAF page, gateway timeout or captive portal (D24-10). */
    EDGE_RESPONSE,

    /** An API response whose body could not be read as the contract says. */
    MALFORMED,
}

/** The outcome of one API call. */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T, val meta: ResponseMeta) : ApiResult<T>

    /** 304 for a conditional GET (bundle, delta, policy). */
    data class NotModified(val meta: ResponseMeta) : ApiResult<Nothing>

    /** A non-2xx API response with its problem details. */
    data class Failure(val httpStatus: Int, val problem: Problem, val meta: ResponseMeta) : ApiResult<Nothing>

    data class Transport(val failure: TransportFailure, val cause: Throwable? = null) : ApiResult<Nothing>
}
