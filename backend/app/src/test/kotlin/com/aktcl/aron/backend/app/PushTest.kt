package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.notify.PushSender
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * N-037 through the production wiring with a recording sender in place of FCM: the SR's phone registers its token; an
 * AMO assigning a task sends one data-only push to that phone within 30 seconds, carrying no task data; a replay sends
 * nothing; `cfg.notify.task_push_enabled` false sends nothing; a token FCM reports unregistered is revoked.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PushTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val now = java.util.concurrent.atomic.AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
    private val clock = AronClock { now.get() }
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val sent = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    @Volatile private var unregistered = setOf<String>()
    private var sr = 0L
    private val token1 = "fcm-token-" + "a".repeat(40)
    private val token2 = "fcm-token-" + "b".repeat(40)

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            sr = h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one()
        }
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        val recorder = PushSender { token, data -> sent += token to data; token !in unregistered }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock, pushSender = recorder)
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }

    private suspend fun HttpClient.login(user: String, phone: Boolean): String {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody(if (phone) """{"username":"$user","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""" else """{"username":"$user","password":"$password","client":"web"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["access_token"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.register(token: String, fcm: String): HttpResponse = put("/v1/devices/me/push-token") {
        bearerAuth(token); header("X-Device-Id", devPhone); contentType(ContentType.Application.Json)
        setBody("""{"provider":"fcm","token":"$fcm","app_flavour":"sr"}""")
    }

    private suspend fun HttpClient.assign(amo: String, taskUuid: String): HttpResponse = post("/v1/tasks") {
        bearerAuth(amo); contentType(ContentType.Application.Json)
        setBody("""{"task_uuid":"$taskUuid","task_type_code":"display_check","assignee_user_id":$sr,"title":"Fix the display"}""")
    }

    /** Waits up to 30 s (the acceptance bound) for [n] messages. */
    private fun awaitSent(n: Int) {
        val until = System.nanoTime() + 30_000_000_000L
        while (sent.size < n && System.nanoTime() < until) Thread.sleep(20)
    }

    @Test
    fun anAssignedTaskSendsOneDataOnlyPushToTheSrsPhone() = testApplication {
        application { aronApi(wiring) }
        val phone = client.login("sr1001", phone = true)
        assertEquals(HttpStatusCode.NoContent, client.register(phone, token1).status)
        assertEquals(HttpStatusCode.NoContent, client.register(phone, token1).status, "the same token again is one row")
        assertEquals(1, count("SELECT count(*) FROM app.push_token WHERE user_id = $sr AND revoked_at IS NULL"))
        val amo = client.login("amo1001", phone = false)
        val t = UUID.randomUUID().toString()
        sent.clear()
        assertEquals(HttpStatusCode.OK, client.assign(amo, t).status)
        awaitSent(1)
        assertEquals(1, sent.size, "one push within 30 seconds")
        val (to, data) = sent.single()
        assertEquals(token1, to)
        assertEquals(mapOf("kind" to "sync_nudge", "reason" to "task_assigned"), data, "no task data in the push")
        assertTrue(data.values.none { t in it || "display" in it.lowercase() })
        // A replay of the same create sends nothing more.
        assertEquals(HttpStatusCode.OK, client.assign(amo, t).status)
        Thread.sleep(300)
        assertEquals(1, sent.size)

        // The switch: no push while cfg.notify.task_push_enabled is false.
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'test push off' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.notify.task_push_enabled', 'global', 0, 'false'::jsonb, now() - interval '1 day', max(config_version), 'test' FROM app.cfg_version")
        }
        now.updateAndGet { it.plusSeconds(31) } // past the server config cache (30 s)
        sent.clear()
        assertEquals(HttpStatusCode.OK, client.assign(amo, UUID.randomUUID().toString()).status)
        Thread.sleep(500)
        assertEquals(0, sent.size, "switched off")
    }

    @Test
    fun aNewTokenReplacesTheOldAndAnUnregisteredTokenIsRevoked() = testApplication {
        application { aronApi(wiring) }
        val phone = client.login("sr1001", phone = true)
        client.register(phone, token1)
        assertEquals(HttpStatusCode.NoContent, client.register(phone, token2).status)
        assertEquals(1, count("SELECT count(*) FROM app.push_token WHERE user_id = $sr AND revoked_at IS NULL"))
        assertEquals(1, count("SELECT count(*) FROM app.push_token WHERE user_id = $sr AND revoke_reason = 'replaced' AND token_sha256 = sha256(convert_to('$token1', 'UTF8'))"))
        assertEquals(HttpStatusCode.BadRequest, client.put("/v1/devices/me/push-token") {
            bearerAuth(phone); header("X-Device-Id", devPhone); contentType(ContentType.Application.Json); setBody("""{"provider":"apns","token":"$token2","app_flavour":"sr"}""")
        }.status)
        unregistered = setOf(token2)
        val notifier = com.aktcl.aron.backend.notify.PushNotifier(fresh.db, com.aktcl.aron.backend.platform.RegistryDefaults(), { tok, _ -> tok !in unregistered }, clock)
        assertEquals(0, notifier.deliver(sr, "task_assigned"))
        assertEquals(0, count("SELECT count(*) FROM app.push_token WHERE user_id = $sr AND revoked_at IS NULL"), "an unregistered token is revoked")
    }
}
