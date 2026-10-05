package com.aktcl.aron.core.network

import kotlinx.serialization.json.Json

/**
 * JSON settings of docs/24 s3.1 item 2: responses are read leniently (unknown members ignored, so a new response
 * member is never breaking); requests are written with only the members the DTO declares, nulls omitted.
 */
object WireJson {
    val responses: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    val requests: Json = Json {
        encodeDefaults = true
        explicitNulls = false
    }
}
