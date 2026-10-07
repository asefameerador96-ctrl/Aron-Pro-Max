package com.aktcl.aron.backend.notify

import com.aktcl.aron.backend.platform.PlayIntegrityDecoder
import com.aktcl.aron.backend.platform.Secret
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Google's server-side decode of a standard Play Integrity token (N-027, docs/24 s10.4 item 3):
 * `POST https://playintegrity.googleapis.com/v1/{packageName}:decodeIntegrityToken` with an OAuth token of the service
 * account (scope `playintegrity`). Lives next to the FCM sender because both are Google service-account clients.
 * Built only when a service account is configured; the token and the credentials are never logged.
 */
class GooglePlayIntegrityDecoder(
    serviceAccountJson: Secret,
    private val baseUrl: String = "https://playintegrity.googleapis.com",
) : PlayIntegrityDecoder {
    private val credentials = com.google.auth.oauth2.GoogleCredentials.fromStream(serviceAccountJson.reveal().byteInputStream())
        .createScoped(listOf("https://www.googleapis.com/auth/playintegrity"))
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun decode(packageName: String, token: String): String {
        require(Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$").matches(packageName)) { "bad package name" }
        val bearer = synchronized(credentials) { credentials.refreshIfExpired(); credentials.accessToken?.tokenValue ?: throw IllegalStateException("no access token") }
        val body = JsonObject(mapOf("integrity_token" to JsonPrimitive(token))).toString()
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/v1/${URLEncoder.encode(packageName, Charsets.UTF_8)}:decodeIntegrityToken"))
            .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer $bearer").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) throw IllegalStateException("decodeIntegrityToken answered ${res.statusCode()}")
        val payload = (Json.parseToJsonElement(res.body()) as? JsonObject)?.get("tokenPayloadExternal") as? JsonObject
            ?: throw IllegalStateException("decodeIntegrityToken returned no tokenPayloadExternal")
        return payload.toString()
    }
}
