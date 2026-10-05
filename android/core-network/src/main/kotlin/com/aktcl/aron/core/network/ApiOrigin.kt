package com.aktcl.aron.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The API origin (docs/24 s3.1 item 1): scheme and host (and port), no path, no query. The contract paths already
 * start with `/v1`, so the origin never carries one. It comes from `BuildConfig.API_BASE_URL` (Gradle property
 * `aron.apiBaseUrl`) or, on an enrolled phone, from the provisioning extra `aron.api_base_url`, which wins
 * ([resolve]).
 *
 * HTTPS is required. Plain HTTP is accepted only for loopback and emulator hosts when [allowCleartextLoopback] is
 * set (local MockWebServer tests and a developer API on the laptop), never for a real host.
 */
class ApiOrigin private constructor(val url: HttpUrl) {

    /** `https://host[:port]` without a trailing slash. */
    val origin: String = url.toString().trimEnd('/')

    /** Absolute URL of a contract path such as `/v1/auth/login`. */
    fun path(contractPath: String): HttpUrl {
        require(contractPath.startsWith("/v1/") || contractPath == "/v1") { "contract paths start with /v1: $contractPath" }
        return url.newBuilder().encodedPath(contractPath).build()
    }

    override fun toString(): String = origin
    override fun equals(other: Any?): Boolean = other is ApiOrigin && other.origin == origin
    override fun hashCode(): Int = origin.hashCode()

    companion object {
        private val LOOPBACK = setOf("localhost", "127.0.0.1", "10.0.2.2", "::1", "[::1]")

        /** Parses and validates an origin; throws [IllegalArgumentException] with the reason when it is not a bare origin. */
        fun parse(value: String, allowCleartextLoopback: Boolean = false): ApiOrigin {
            val trimmed = value.trim()
            val url = trimmed.toHttpUrlOrNull() ?: throw IllegalArgumentException("not an absolute http(s) URL: '$trimmed'")
            require(url.encodedPath == "/") { "the API base URL is an origin without a path (docs/24 s3.1): '$trimmed'" }
            require(url.encodedQuery == null && url.encodedFragment == null) { "no query or fragment allowed: '$trimmed'" }
            require(url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty()) { "no credentials in the base URL" }
            if (!url.isHttps) {
                require(allowCleartextLoopback && url.host in LOOPBACK) { "the API base URL must be https: '$trimmed'" }
            }
            return ApiOrigin(url)
        }

        /** The provisioning extra wins over the build config when it is present and valid (docs/24 s3.1, s10.4). */
        fun resolve(buildConfigOrigin: String, provisionedOrigin: String?, allowCleartextLoopback: Boolean = false): ApiOrigin {
            val provisioned = provisionedOrigin?.takeIf { it.isNotBlank() }
                ?.let { runCatching { parse(it, allowCleartextLoopback) }.getOrNull() }
            return provisioned ?: parse(buildConfigOrigin, allowCleartextLoopback)
        }
    }
}
