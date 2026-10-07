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
 * N-037 `POST /v1/admin/notifications` through the production wiring with a recording sender: a TSO's announcement to
 * its zone reaches the SR's registered phone as one data-only message (kind, id, jitter, text; nothing personal);
 * a node outside the caller's reach is refused (403 geography, 404 user); a phone or an SR cannot send; the push switch
 * off answers 409 ERR_PUSH_DISABLED; every send is audited.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminNotificationTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    /** Moves forward only, in 31 s steps (DbServerConfig caches by this clock); stays on business date 2027-01-03. */
    private val now = java.util.concurrent.atomic.AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
    private val clock = AronClock { now.get() }
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val sent = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    private val fcm = "fcm-token-" + "c".repeat(40)
    private var mirZone = 0L
    private var otherZone = 0L
    private var division = 0L
    private var outsider = 0L
    private var sr = 0L
    private val tokens = HashMap<String, String>()

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
            mirZone = h.createQuery("SELECT id FROM app.zone WHERE code = 'Z-MIR'").mapTo(Long::class.java).one()
            division = h.createQuery("SELECT id FROM app.division WHERE code = 'D-DHK'").mapTo(Long::class.java).one()
            // TOTP is not what this test is about: no MFA role, so admin1001 logs in with a password.
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[]'::jsonb, '2026-01-01T00:00:00Z', max(config_version), 'test' FROM app.cfg_version")
            // A second territory and zone outside the TSO's territory, with one SR living there.
            h.execute("INSERT INTO app.territory (code, name, name_bn, division_id) SELECT 'T-DHK-S', 'Dhaka South', 'ঢাকা দক্ষিণ', id FROM app.division WHERE code = 'D-DHK'")
            otherZone = h.createQuery("INSERT INTO app.zone (code, name, name_bn, territory_id, dep_name) SELECT 'Z-GUL', 'Gulistan', 'গুলিস্তান', id, 'Gulistan DEP' FROM app.territory WHERE code = 'T-DHK-S' RETURNING id")
                .mapTo(Long::class.java).one()
            outsider = h.createQuery(
                "INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password) " +
                    "VALUES ('sr2002', 'Other SR', 'SR', 'Sales Representative', 'T-SR-2002', 'bn', :z, false, false) RETURNING id",
            ).bind("z", otherZone).mapTo(Long::class.java).one()
        }
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        val recorder = PushSender { token, data -> sent += token to data; true }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock, pushSender = recorder)
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }

    /** One token per user and client for the class (the fixed clock never resets the login limiter). */
    private suspend fun HttpClient.login(user: String, phone: Boolean = false): String = tokens.getOrPut("$user/$phone") {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody(if (phone) """{"username":"$user","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""" else """{"username":"$user","password":"$password","client":"web"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        Json.parseToJsonElement(r.bodyAsText()).jsonObject["access_token"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.registerPhone() {
        val phone = login("sr1001", phone = true)
        val r = put("/v1/devices/me/push-token") {
            bearerAuth(phone); header("X-Device-Id", devPhone); contentType(ContentType.Application.Json)
            setBody("""{"provider":"fcm","token":"$fcm","app_flavour":"sr"}""")
        }
        assertEquals(HttpStatusCode.NoContent, r.status, r.bodyAsText())
    }

    private suspend fun HttpClient.notify(token: String, scope: String, id: Long, kind: String = "announcement", extra: String = "", phone: Boolean = false): HttpResponse = post("/v1/admin/notifications") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        if (phone) header("X-Device-Id", devPhone)
        setBody("""{"kind":"$kind","scope_type":"$scope","scope_id":$id,"title_en":"Stock update","title_bn":"স্টক আপডেট","body_en":"New price list from Sunday","body_bn":"রবিবার থেকে নতুন মূল্য"$extra}""")
    }

    private fun awaitSent(n: Int) {
        val until = System.nanoTime() + 30_000_000_000L
        while (sent.size < n && System.nanoTime() < until) Thread.sleep(20)
    }

    @Test
    fun aTsoAnnouncementToItsZoneReachesTheSrPhoneAsOneDataMessage() = testApplication {
        application { aronApi(wiring) }
        client.registerPhone()
        val tso = client.login("tso1001")
        sent.clear()
        val r = client.notify(tso, "zone", mirZone)
        assertEquals(HttpStatusCode.Accepted, r.status, r.bodyAsText())
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(1, body["devices_targeted"]!!.jsonPrimitive.content.toInt())
        val id = body["notification_id"]!!.jsonPrimitive.content
        awaitSent(1)
        val (to, data) = sent.single()
        assertEquals(fcm, to)
        assertEquals(setOf("kind", "notification_id", "pull_after_s", "title_en", "title_bn", "body_en", "body_bn"), data.keys)
        assertEquals("announcement", data["kind"]); assertEquals(id, data["notification_id"])
        assertTrue(data["pull_after_s"]!!.toInt() in 0..120)
        assertTrue(data.values.none { "sr1001" in it || "Mirpur" in it }, "no personal or business data")
        assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'notification' AND entity_id = '$id' AND action = 'send' AND actor_username = 'tso1001'"))
    }

    @Test
    fun anUrgentConfigPullCarriesNoTextAndAShortJitter() = testApplication {
        application { aronApi(wiring) }
        client.registerPhone()
        sent.clear()
        val r = client.notify(client.login("admin1001"), "user", sr, kind = "config_pull", extra = ""","urgent":true""")
        assertEquals(HttpStatusCode.Accepted, r.status, r.bodyAsText())
        awaitSent(1)
        val data = sent.single().second
        assertEquals(setOf("kind", "notification_id", "pull_after_s", "urgent"), data.keys)
        assertEquals("true", data["urgent"], "F-SYS-073: the phone runs an urgent pull")
        assertTrue(data["pull_after_s"]!!.toInt() in 0..20)
    }

    @Test
    fun aNodeOutsideTheCallersReachIsRefused() = testApplication {
        application { aronApi(wiring) }
        val tso = client.login("tso1001")
        assertEquals(HttpStatusCode.Forbidden, client.notify(tso, "zone", otherZone).status)
        assertEquals(HttpStatusCode.Forbidden, client.notify(tso, "division", division).status, "the division has a zone outside the territory")
        assertEquals(HttpStatusCode.Forbidden, client.notify(tso, "global", 0).status)
        assertEquals(HttpStatusCode.NotFound, client.notify(tso, "user", outsider).status)
        assertEquals(0, count(
            "SELECT count(*) FROM app.audit_log WHERE entity = 'notification' AND actor_username = 'tso1001' AND (after->>'scope_type' IN ('division', 'global') " +
                "OR (after->>'scope_type' = 'zone' AND after->>'scope_id' = '$otherZone') OR (after->>'scope_type' = 'user' AND after->>'scope_id' = '$outsider'))",
        ))
    }

    @Test
    fun aPhoneOrAnAmoCannotSendAndABadBodyIs400() = testApplication {
        application { aronApi(wiring) }
        assertEquals(HttpStatusCode.Forbidden, client.notify(client.login("sr1001", phone = true), "zone", mirZone, phone = true).status)
        assertEquals(HttpStatusCode.Forbidden, client.notify(client.login("amo1001"), "zone", mirZone).status, "an AMO is not a sender")
        val admin = client.login("admin1001")
        assertEquals(HttpStatusCode.BadRequest, client.notify(admin, "zone", mirZone, kind = "sms").status)
        assertEquals(HttpStatusCode.BadRequest, client.notify(admin, "global", 5).status)
        assertEquals(HttpStatusCode.BadRequest, client.notify(admin, "zone", mirZone, extra = ""","phone":"017"""").status, "unknown member")
    }

    /**
     * The switch off answers 409 ERR_PUSH_DISABLED and nothing is audited; closed again, sends go through. DbServerConfig
     * resolves validity on the database clock and caches for 30 s of the injected clock, so the clock steps 31 s.
     */
    @Test
    fun thePushSwitchOffAnswers409() = testApplication {
        application { aronApi(wiring) }
        val admin = client.login("admin1001")
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.ops.push_enabled', 'global', 0, 'false'::jsonb, now() - interval '1 second', max(config_version), 'test' FROM app.cfg_version")
        }
        val audits = count("SELECT count(*) FROM app.audit_log WHERE entity = 'notification'")
        try {
            now.updateAndGet { it.plusSeconds(31) }
            val r = client.notify(admin, "zone", mirZone)
            assertEquals(HttpStatusCode.Conflict, r.status)
            assertTrue("ERR_PUSH_DISABLED" in r.bodyAsText())
            assertEquals(audits, count("SELECT count(*) FROM app.audit_log WHERE entity = 'notification'"))
        } finally {
            fresh.db.jdbi.useHandle<Exception> { h ->
                h.execute("UPDATE app.cfg_value SET effective_to = now() WHERE key = 'cfg.ops.push_enabled' AND scope_type = 'global' AND effective_to IS NULL")
            }
            now.updateAndGet { it.plusSeconds(31) }
        }
        assertEquals(HttpStatusCode.Accepted, client.notify(admin, "zone", mirZone).status, "on again once the row is closed")
    }
}
