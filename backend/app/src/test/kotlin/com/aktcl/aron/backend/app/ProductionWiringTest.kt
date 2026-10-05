package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The production object graph end to end on a fresh, migrated database: settings from the environment with the
 * signing key in a file (Key Vault reference locally), login, refresh, /me and the outlet list.
 */
class ProductionWiringTest {
    @Test
    fun loginRefreshAndMeThroughTheProductionWiring() {
        FreshDb.create().use { fresh ->
            val key = File.createTempFile("aron-jwt", ".pem").apply {
                deleteOnExit()
                val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
                writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
            }
            val hash = PasswordHasher().hash("correct horse 1")
            fresh.db.jdbi.useHandle<Exception> { h ->
                h.createUpdate("INSERT INTO app.app_user (username, full_name, role, password_hash, must_change_password) VALUES ('tso5012', 'TSO', 'TSO', :p, false)")
                    .bind("p", hash).execute()
            }
            val s = Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath, "ARON_JWT_KID" to "sig-test"))
            val w = Wiring.production(s)
            try {
                testApplication {
                    application { aronApi(w) }
                    val r = client.post("/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"tso5012","password":"correct horse 1","client":"web"}""")
                    }
                    assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
                    val b = Json.parseToJsonElement(r.bodyAsText()).jsonObject
                    val version = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT COALESCE(max(config_version), 0) FROM app.cfg_version").mapTo(Long::class.java).one() }
                    assertEquals(version.toString(), r.headers["X-Config-Version"], "the header carries the database's config_version")
                    val access = b["access_token"]!!.jsonPrimitive.content
                    assertEquals(HttpStatusCode.OK, client.get("/v1/me") { bearerAuth(access) }.status)
                    assertEquals(HttpStatusCode.OK, client.get("/v1/admin/outlets") { bearerAuth(access) }.status)
                    val rt = b["refresh_token"]!!.jsonPrimitive.content
                    val refreshed = client.post("/v1/auth/refresh") {
                        contentType(ContentType.Application.Json); setBody("""{"grant":"full","refresh_token":"$rt"}""")
                    }
                    assertEquals(HttpStatusCode.OK, refreshed.status, refreshed.bodyAsText())
                    assertEquals(HttpStatusCode.OK, client.get("/v1/health/ready").status)
                    assertEquals("sig-test", Json.parseToJsonElement(client.get("/v1/auth/jwks").bodyAsText()).jsonObject["keys"].toString().let { Regex("\"kid\":\"([^\"]+)\"").find(it)!!.groupValues[1] })
                    // A phone without device_uuid is refused before any hash work.
                    val phone = client.post("/v1/auth/login") {
                        contentType(ContentType.Application.Json); header("X-App-Version", "1.0.0+100")
                        setBody("""{"username":"tso5012","password":"correct horse 1","client":"app_tso"}""")
                    }
                    assertEquals(HttpStatusCode.BadRequest, phone.status)
                }
            } finally {
                w.database?.close()
            }
        }
    }
}
