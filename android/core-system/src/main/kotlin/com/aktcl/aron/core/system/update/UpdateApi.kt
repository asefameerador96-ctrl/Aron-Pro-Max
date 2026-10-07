package com.aktcl.aron.core.system.update

import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.Grant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Local mirrors of contract UpdateCheck / AppRelease (REQUEST: docs/requests/android-core-contract-dtos.md).
@Serializable
internal data class UpdateCheckDto(
    @SerialName("update_available") val updateAvailable: Boolean,
    val blocked: Boolean,
    @SerialName("min_version_code") val minVersionCode: Int,
    val latest: AppReleaseDto? = null,
    @SerialName("prompt_policy") val promptPolicy: String? = null,
    @SerialName("wifi_only") val wifiOnly: Boolean? = null,
)

@Serializable
internal data class AppReleaseDto(
    @SerialName("version_name") val versionName: String,
    @SerialName("version_code") val versionCode: Int,
    val abi: String,
    val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("download_url") val downloadUrl: String,
    @SerialName("signing_cert_sha256") val signingCertSha256: String,
    val status: String,
    @SerialName("notes_en") val notesEn: String? = null,
    @SerialName("notes_bn") val notesBn: String? = null,
)

sealed interface UpdateCheckResult {
    data class Ok(val info: UpdateInfo) : UpdateCheckResult
    /** Offline or the server is busy: keep the last answer; the day never waits for this. */
    data class Unavailable(val code: String) : UpdateCheckResult
}

/** `GET /v1/app/update-check` (F-API-029) for this flavour, version code and ABI. */
class UpdateApi(private val client: AronApiClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(flavour: String, versionCode: Int, abi: String): UpdateCheckResult {
        val path = "/v1/app/update-check"
        val r = client.call(
            path = path,
            auth = CallAuth.Grant(Grant.FULL),
            build = {
                url(client.origin.path(path).newBuilder().addQueryParameter("flavour", flavour)
                    .addQueryParameter("version_code", versionCode.toString()).addQueryParameter("abi", abi).build())
                get()
            },
            decode = { body, _ -> json.decodeFromString(UpdateCheckDto.serializer(), body) },
        )
        return when (r) {
            is ApiResult.Success -> UpdateCheckResult.Ok(r.value.toInfo())
            is ApiResult.Failure -> UpdateCheckResult.Unavailable("http_${r.httpStatus}")
            is ApiResult.Transport -> UpdateCheckResult.Unavailable(r.failure.name.lowercase())
            is ApiResult.NotModified -> UpdateCheckResult.Unavailable("unexpected_304")
        }
    }
}

internal fun UpdateCheckDto.toInfo() = UpdateInfo(
    updateAvailable = updateAvailable,
    blocked = blocked,
    minVersionCode = minVersionCode,
    // Only a published release is ever offered.
    latest = latest?.takeIf { it.status == "published" }?.let {
        ReleaseInfo(it.versionName, it.versionCode, it.abi, it.sha256.lowercase(), it.sizeBytes, it.downloadUrl, it.signingCertSha256.lowercase(), it.notesEn, it.notesBn)
    },
    promptPolicy = promptPolicy ?: "prompt",
    wifiOnly = wifiOnly ?: true,
)
