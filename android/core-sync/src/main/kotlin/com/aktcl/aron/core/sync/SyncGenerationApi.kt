package com.aktcl.aron.core.sync

import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.WireJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contract `ServerGeneration` (docs/24 s4.8). REQUEST: local wire DTO until `shared:contract` carries it, same names.
 * [kind] is initial, failover or pitr; [lostAfterUtc] is the first instant whose acknowledged writes may be gone.
 */
@Serializable
data class ServerGeneration(
    val generation: String,
    val kind: String,
    @SerialName("restore_point_utc") val restorePointUtc: String? = null,
    @SerialName("lost_after_utc") val lostAfterUtc: String? = null,
    @SerialName("minted_at") val mintedAt: String,
)

/** Reads the server's current generation statement; [SyncGenerationApi] in production. */
fun interface GenerationApi {
    suspend fun current(token: String): ApiResult<ServerGeneration>
}

/** `GET /v1/sync/generation` (F-API-070) under the upload grant of the user whose rows are re-sent (as the batch). */
class SyncGenerationApi(private val client: AronApiClient) : GenerationApi {
    override suspend fun current(token: String): ApiResult<ServerGeneration> = client.call(
        path = PATH,
        auth = CallAuth.Bearer(token),
        build = { get() },
        decode = { body, _ -> WireJson.responses.decodeFromString(ServerGeneration.serializer(), body) },
    )

    companion object {
        const val PATH = "/v1/sync/generation"
    }
}
