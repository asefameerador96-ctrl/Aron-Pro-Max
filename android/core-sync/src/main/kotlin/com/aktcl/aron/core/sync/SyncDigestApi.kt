package com.aktcl.aron.core.sync

import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.WireJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Contract `SyncDigestRequest` (docs/24 s4.8). REQUEST: local wire DTOs until `shared:contract` carries them, same names. */
@Serializable
data class DigestRequest(
    @SerialName("device_uuid") val deviceUuid: String,
    val items: List<DigestItem>,
)

@Serializable
data class DigestItem(
    @SerialName("business_date") val businessDate: String,
    val type: String,
    val buckets: List<DigestBucket>,
)

@Serializable
data class DigestBucket(val count: Long, val hash: String)

/** Contract `SyncDigestResponse`: the (date, type, bucket indexes) whose count or hash differ. */
@Serializable
data class DigestResponse(val resend: List<DigestResend> = emptyList())

@Serializable
data class DigestResend(
    @SerialName("business_date") val businessDate: String,
    val type: String,
    val buckets: List<Int> = emptyList(),
)

/** `POST /v1/sync/digest`; [SyncDigestApi] in production. */
fun interface DigestApi {
    suspend fun compare(token: String, request: DigestRequest): ApiResult<DigestResponse>
}

/**
 * The digest rule both sides compute (backend-core's answer to android-core-backend-sync-digest.md, `SyncDigestService`):
 * bucket = the first hex digit of the lowercase client_uuid; count = rows in the bucket; hash = the sum, modulo 2^64, of
 * the uuid's first 8 bytes read as a big-endian unsigned integer (its first 16 hex digits), as 16 lowercase hex digits.
 */
object DigestHash {
    const val EMPTY = "0000000000000000"

    /** The first 8 bytes of [uuid], big-endian (`UUID.mostSignificantBits`); null for a malformed uuid. */
    fun high(uuid: String): Long? = runCatching { java.util.UUID.fromString(uuid).mostSignificantBits }.getOrNull()
        ?.takeIf { UUID_RE.matches(uuid.lowercase()) }

    /** The 16 buckets of [uuids] (malformed ones are left out: the server never stored them). */
    fun buckets(uuids: Collection<String>): List<DigestBucket> {
        val counts = LongArray(16)
        val sums = LongArray(16)
        for (u in uuids) {
            val hi = high(u) ?: continue
            val b = (hi ushr 60).toInt()
            counts[b]++
            sums[b] += hi // two's-complement addition is addition modulo 2^64
        }
        return (0 until 16).map { DigestBucket(counts[it], hex(sums[it])) }
    }

    fun hex(v: Long): String = java.lang.Long.toUnsignedString(v, 16).padStart(16, '0')

    private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
}

/** `POST /v1/sync/digest` (F-SYS-080) under the upload grant of the user whose rows are compared (as the batch). */
class SyncDigestApi(private val client: AronApiClient) : DigestApi {
    override suspend fun compare(token: String, request: DigestRequest): ApiResult<DigestResponse> = client.call(
        path = PATH,
        auth = CallAuth.Bearer(token),
        build = { post(WireJson.requests.encodeToString(DigestRequest.serializer(), request).toRequestBody(JSON)) },
        decode = { body, _ -> WireJson.responses.decodeFromString(DigestResponse.serializer(), body) },
    )

    companion object {
        const val PATH = "/v1/sync/digest"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
