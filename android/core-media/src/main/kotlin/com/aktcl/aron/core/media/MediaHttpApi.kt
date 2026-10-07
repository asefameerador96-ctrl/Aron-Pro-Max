package com.aktcl.aron.core.media

import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.CallAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// Local mirrors of contract MediaSasRequest / MediaSasResponse (REQUEST: docs/requests/android-core-contract-dtos.md;
// they move to shared:contract with the same shapes when it hosts them).
@Serializable
internal data class SasRequestDto(val items: List<SasRequestItemDto>)

@Serializable
internal data class SasRequestItemDto(
    @SerialName("media_uuid") val mediaUuid: String,
    val purpose: String,
    val sha256: String,
    val bytes: Int,
    @SerialName("business_date") val businessDate: String,
    val mime: String = "image/jpeg",
)

@Serializable
internal data class SasResponseDto(val items: List<SasResponseItemDto>)

@Serializable
internal data class SasResponseItemDto(
    @SerialName("media_uuid") val mediaUuid: String,
    val method: String,
    @SerialName("upload_url") val uploadUrl: String,
    @SerialName("blob_path") val blobPath: String,
    @SerialName("required_headers") val requiredHeaders: Map<String, String>,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("already_uploaded") val alreadyUploaded: Boolean = false,
)

/**
 * One user's upload grant, which survives logout and user switch (docs/24 s5.3, D24-57): the app implements it with
 * `SessionRepository.uploadAccessToken(userId)` and `refresh(userId, Grant.UPLOAD, rejected)`, as core-sync does.
 */
interface MediaUploadAuth {
    suspend fun token(): String?
    /** A new token after [rejected] was refused; true when one is stored. */
    suspend fun refresh(rejected: String?): Boolean
}

/**
 * [MediaUploadApi] over HTTP for one user. The SAS call carries that user's upload-grant token, so photos keep going after
 * logout. The blob PUT goes on a separate bare client: no Aron headers and no bearer token ever reach Azure Storage, no
 * redirects are followed; the SAS in the URL is the only credential and is never logged.
 */
class MediaHttpApi(
    private val client: AronApiClient,
    baseClient: OkHttpClient,
    private val auth: MediaUploadAuth,
) : MediaUploadApi {
    private val blobClient = baseClient.newBuilder()
        .callTimeout(PUT_TIMEOUT_S, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override suspend fun sas(items: List<SasItem>): SasResult {
        val body = json.encodeToString(SasRequestDto.serializer(), SasRequestDto(items.map { SasRequestItemDto(it.mediaUuid, it.purpose, it.sha256, it.bytes, it.businessDate) }))
        val token = auth.token() ?: return SasResult.Retry("no_upload_token")
        var r = sasCall(body, token)
        if (r is ApiResult.Failure && r.httpStatus == 401 && auth.refresh(token)) {
            r = auth.token()?.let { sasCall(body, it) } ?: r
        }
        return when (r) {
            is ApiResult.Success -> SasResult.Ok(
                r.value.items.filter { it.method == "PUT" }.map { SasTarget(it.mediaUuid, it.uploadUrl, it.blobPath, it.requiredHeaders, it.alreadyUploaded) },
            )
            is ApiResult.Failure -> if (r.httpStatus == 400) SasResult.Rejected(r.problem.code ?: "400") else SasResult.Retry("http_${r.httpStatus}")
            is ApiResult.Transport -> SasResult.Retry(r.failure.name.lowercase())
            is ApiResult.NotModified -> SasResult.Retry("unexpected_304")
        }
    }

    private suspend fun sasCall(body: String, token: String) = client.call(
        path = "/v1/media/sas",
        auth = CallAuth.Bearer(token),
        build = { post(body.toRequestBody(JSON)) },
        decode = { text, _ -> json.decodeFromString(SasResponseDto.serializer(), text) },
    )

    override suspend fun put(target: SasTarget, jpeg: ByteArray): PutResult {
        val url = target.uploadUrl.toHttpUrlOrNull()?.takeIf { it.isHttps || it.host in LOOPBACK }
            ?: return PutResult.REJECTED // never send a photo over cleartext (loopback only for tests)
        val req = Request.Builder().url(url).put(jpeg.toRequestBody("image/jpeg".toMediaType()))
            .header("x-ms-blob-type", target.requiredHeaders["x-ms-blob-type"] ?: "BlockBlob")
            .header("Content-Type", target.requiredHeaders["Content-Type"] ?: "image/jpeg")
            .build()
        return try {
            runInterruptible(Dispatchers.IO) {
                blobClient.newCall(req).execute().use { resp ->
                    when (resp.code) {
                        200, 201 -> PutResult.STORED
                        403 -> PutResult.EXPIRED // the SAS ran out (or was refused): a fresh one on the next run
                        408, 429, 500, 502, 503, 504 -> PutResult.RETRY
                        else -> PutResult.REJECTED
                    }
                }
            }
        } catch (_: IOException) {
            PutResult.RETRY
        }
    }

    companion object {
        /** A 150 KB photo at 20 kB/s on a poor EDGE link still fits; a stuck PUT never holds the media job longer. */
        const val PUT_TIMEOUT_S = 60L
        private val JSON = "application/json".toMediaType()
        private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1")
    }
}
