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
 * [earliestLostAfterUtc] (asked with `?since=`) is the earliest loss of every generation minted after the one the phone
 * last handled, so two restores before the phone called are both covered (F-SYS-047 checker; backend-core BC-68).
 */
@Serializable
data class ServerGeneration(
    val generation: String,
    val kind: String,
    @SerialName("restore_point_utc") val restorePointUtc: String? = null,
    @SerialName("lost_after_utc") val lostAfterUtc: String? = null,
    @SerialName("minted_at") val mintedAt: String,
    @SerialName("previous_generation") val previousGeneration: String? = null,
    @SerialName("earliest_lost_after_utc") val earliestLostAfterUtc: String? = null,
)

/** Reads the server's current generation statement; [SyncGenerationApi] in production. */
fun interface GenerationApi {
    /** [since]: the generation this database last handled (`?since=`), or null. */
    suspend fun current(token: String, since: String?): ApiResult<ServerGeneration>
}

/** `GET /v1/sync/generation` (F-API-070) under the upload grant of the user whose rows are re-sent (as the batch). */
class SyncGenerationApi(private val client: AronApiClient) : GenerationApi {
    override suspend fun current(token: String, since: String?): ApiResult<ServerGeneration> = client.call(
        path = PATH,
        auth = CallAuth.Bearer(token),
        build = {
            // Only a well-formed lowercase uuid is sent (the server answers 400 to anything else).
            if (since != null && UUID.matches(since)) url(client.origin.path(PATH).newBuilder().addQueryParameter("since", since).build())
            get()
        },
        decode = { body, _ -> WireJson.responses.decodeFromString(ServerGeneration.serializer(), body) },
    )

    companion object {
        const val PATH = "/v1/sync/generation"
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
