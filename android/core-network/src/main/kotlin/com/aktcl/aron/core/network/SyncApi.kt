package com.aktcl.aron.core.network

/** `GET /v1/sync/bundle` and its pages, and `HEAD /v1/health` (docs/24 s4.7 T3, s4.10). */
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

    /**
     * `GET /v1/sync/bundle/page` (s4.10 Size): one page of a paged section of [bundleVersion]; the body is returned raw
     * (`BundlePage`), merged into the bundle before it is applied.
     */
    suspend fun bundlePage(bundleVersion: String, section: String, page: Int): ApiResult<String> {
        val path = "/v1/sync/bundle/page"
        return client.call(
            path = path,
            auth = CallAuth.Grant(Grant.FULL),
            build = {
                url(
                    client.origin.path(path).newBuilder().addQueryParameter("bundle_version", bundleVersion)
                        .addQueryParameter("section", section).addQueryParameter("page", page.toString()).build(),
                )
                get()
            },
            decode = { body, _ -> body },
        )
    }

    /** `GET /v1/config/delta?since=` (s4.10 Config delta): the raw `ConfigDelta`, 304 when unchanged, 410 when too far behind. */
    suspend fun configDelta(since: Long): ApiResult<String> {
        val path = "/v1/config/delta"
        return client.call(
            path = path,
            auth = CallAuth.Grant(Grant.FULL),
            build = {
                url(client.origin.path(path).newBuilder().addQueryParameter("since", since.toString()).build())
                header("X-Config-Version", since.toString())
                get()
            },
            decode = { body, _ -> body },
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
