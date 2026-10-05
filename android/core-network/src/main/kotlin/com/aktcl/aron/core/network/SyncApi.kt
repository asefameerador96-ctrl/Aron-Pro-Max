package com.aktcl.aron.core.network

/** `GET /v1/sync/bundle` (head only on Day 1) and `HEAD /v1/health` (docs/24 s4.7 T3, s4.10). */
class SyncApi(private val client: AronApiClient) {

    suspend fun bundle(forDate: String? = null, ifNoneMatch: String? = null, configVersion: Long? = null): ApiResult<BundleDownload> {
        val path = "/v1/sync/bundle"
        return client.call(
            path = path,
            auth = CallAuth.Grant(Grant.FULL),
            build = {
                if (forDate != null) url(client.origin.path(path).newBuilder().addQueryParameter("for", forDate).build())
                if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch)
                if (configVersion != null) header("X-Config-Version", configVersion.toString())
                get()
            },
            decode = { body, meta ->
                BundleDownload(WireJson.responses.decodeFromString(BundleHead.serializer(), body), body, meta.etag)
            },
        )
    }

    /** Connectivity validation: true only for an API answer (with `X-Aron-Api`) within 3 s. */
    suspend fun healthy(): Boolean {
        val result = client.call(
            path = "/v1/health",
            auth = CallAuth.None,
            callTimeoutS = 3,
            build = { head() },
            decode = { _, _ -> },
        )
        return result is ApiResult.Success
    }
}
