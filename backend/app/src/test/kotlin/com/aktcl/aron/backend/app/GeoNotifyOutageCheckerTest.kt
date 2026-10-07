package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.notify.PushNotifier
import com.aktcl.aron.backend.notify.PushSender
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.NIL_GENERATION
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.backend.platform.isTransientDbFailure
import com.aktcl.aron.backend.sync.ServerGeneration
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.sql.Connection
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Independent checker (docs/26 s4) for F-SYS-012 (server geo re-check), N-037 (POST /v1/admin/notifications) and
 * AUD-REL-01/02 (DB outage handling), commit a9a9d77c. Production wiring, seeded database, fixed clock Sunday
 * 2027-01-03 10:00 Dhaka (as GeoRecheckTest and AdminNotificationTest).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeoNotifyOutageCheckerTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val day = "2027-01-03"
    private val fcm = "fcm-token-" + "d".repeat(40)
    private val sent = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    private var routeId = 0L
    private var mirZone = 0L
    private var sr = 0L
    private val outlets = mutableListOf<Long>()
    private val captureSeq = java.util.concurrent.atomic.AtomicInteger(0)
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
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[]'::jsonb, '2026-01-01T00:00:00Z', max(config_version), 'test' FROM app.cfg_version")
            routeId = h.createQuery("SELECT id FROM app.route WHERE code = 'MIR-SR-D'").mapTo(Long::class.java).one()
            mirZone = h.createQuery("SELECT id FROM app.zone WHERE code = 'Z-MIR'").mapTo(Long::class.java).one()
            sr = h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one()
            outlets += h.createQuery("SELECT id FROM app.outlet WHERE route_id = :r ORDER BY id").bind("r", routeId).mapTo(Long::class.java).list()
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

    // ---- fixtures ----------------------------------------------------------------------------------------------------

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun uuid() = UUID.randomUUID().toString()
    private fun ApplicationTestBuilder.app() = application { aronApi(wiring) }
    private fun <T> sql(f: (org.jdbi.v3.core.Handle) -> T): T = fresh.db.jdbi.withHandle<T, Exception> { f(it) }

    /** One token per user and client for the class (the fixed clock never resets the login limiter). */
    private suspend fun HttpClient.login(user: String, phone: Boolean = false): String = tokens.getOrPut("$user/$phone") {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody(if (phone) """{"username":"$user","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""" else """{"username":"$user","password":"$password","client":"web"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content
    }

    private fun gz(s: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(s.toByteArray()) } }.toByteArray()

    private suspend fun HttpClient.sendBatch(records: List<JsonObject>): JsonObject {
        val body = buildJsonObject {
            put("batch_uuid", uuid()); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
            put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
            put("time_anchors", kotlinx.serialization.json.JsonArray(emptyList()))
            put("device_counts", buildJsonObject { put(day, buildJsonObject { put("visit", 1) }) })
            put("records", kotlinx.serialization.json.JsonArray(records))
        }.toString()
        return json(post("/v1/sync/batch") {
            bearerAuth(login("sr1001", phone = true)); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); header("Content-Encoding", "gzip")
            setBody(io.ktor.http.content.ByteArrayContent(gz(body), ContentType.Application.Json))
        }.bodyAsText())
    }

    private fun statuses(r: JsonObject) = r["acks"]!!.jsonArray.map { it.jsonObject["status"]!!.jsonPrimitive.content + "/" + (it.jsonObject["code"]?.toString() ?: "") }

    private fun nextAt(): String = Instant.parse("2027-01-02T18:30:00Z").plusSeconds(captureSeq.incrementAndGet() * 60L).toString()

    private fun visitRecord(visit: String, outlet: Long, at: String, lat: Double, lng: Double, verdict: String = "in_range", action: String = "sale_allowed", basis: String = "master") = buildJsonObject {
        put("type", "visit"); put("client_uuid", visit); put("family_uuid", visit); put("rank", 0); put("schema_version", 1)
        put("business_date", day); put("captured_at", at); put("captured_elapsed_ms", 18330000); put("boot_count", 412)
        put("clock_offset_ms", -1840); put("captured_offline", true); put("route_id", routeId); put("bundle_version", "$day:3")
        put("bundle_stale", false); put("config_version", 1)
        put("payload", json(
            """
            {"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"$at","sequence_no":1,"planned":true,
             "fix":{"purpose":"visit_open","fix_status":"ok","lat":$lat,"lng":$lng,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                    "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}},
             "geo":{"verdict":"$verdict","distance_m":${if (verdict == "no_outlet_location") "null" else "5.0"},"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"$basis","action":"$action"}}
            """.trimIndent(),
        ))
    }

    private fun serverVerdict(visit: String): String? = sql { h ->
        h.createQuery("SELECT server_verdict FROM app.visit WHERE client_uuid = CAST(:u AS uuid)").bind("u", visit).mapTo(String::class.java).findOne().orElse(null)
    }

    private fun seedPin(outlet: Long): Pair<Double, Double> = sql { h ->
        h.createQuery("SELECT lat, lng FROM app.outlet WHERE id = :o").bind("o", outlet).map { rs, _ -> rs.getDouble(1) to rs.getDouble(2) }.one()
    }

    // ---- F-SYS-012 -----------------------------------------------------------------------------------------------------

    /**
     * An outlet whose pin was cleared (admin web edit: lat/lng null, basis none; AdminOutlets writes no history row for a
     * clear) has no usable location at capture: the phone says no_outlet_location and so must the server. The re-check
     * prefers the stale 2026-01-01 history row over the outlet's current basis and stores in_range.
     */
    @Test
    fun aClearedPinIsNoOutletLocationOnTheServerToo() = testApplication {
        app()
        val o = outlets[10]
        val (lat, lng) = seedPin(o)
        sql { h -> h.execute("UPDATE app.outlet SET lat = NULL, lng = NULL, location_basis = 'none', location_confirmed = false WHERE id = $o") }
        val v = uuid()
        val r = client.sendBatch(listOf(visitRecord(v, o, "2027-01-03T03:50:00Z", lat, lng, "no_outlet_location", "force_sale", "none")))
        assertEquals(listOf("accepted/null"), statuses(r))
        assertEquals("no_outlet_location", serverVerdict(v), "the outlet had no pin at capture; the server judged against a stale history row")
    }

    /** Same root cause: a placeholder pin is not a usable location (docs/24 s11.2 check 3), whatever the history says. */
    @Test
    fun aPlaceholderPinIsNoOutletLocationOnTheServer() = testApplication {
        app()
        val o = outlets[11]
        val (lat, lng) = seedPin(o)
        sql { h -> h.execute("UPDATE app.outlet SET location_basis = 'placeholder', location_confirmed = false WHERE id = $o") }
        val v = uuid()
        assertEquals(listOf("accepted/null"), statuses(client.sendBatch(listOf(visitRecord(v, o, nextAt(), lat, lng, "no_outlet_location", "force_sale", "placeholder")))))
        assertEquals("no_outlet_location", serverVerdict(v))
    }

    /**
     * An outlet with no pin at capture, pinned (web edit, history row valid_from 03:00Z) before the upload: the location
     * "in force at captured_at" is none. With no history row valid at capture the re-check falls back to the outlet's
     * current row, i.e. the pin set after the capture, and stores in_range.
     */
    @Test
    fun aPinSetAfterTheCaptureIsNotUsedForTheVisit() = testApplication {
        app()
        val (lat, lng) = seedPin(outlets[12])
        val o = sql { h ->
            h.createQuery(
                "INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel, location_basis) " +
                    "SELECT 'CHK-G3', 'Checker G3', 'Owner', zone_id, route_id, cluster_id, channel, 'none' FROM app.outlet WHERE id = :s RETURNING id",
            ).bind("s", outlets[12]).mapTo(Long::class.java).one()
        }
        val v = uuid()
        val at = nextAt() // 2027-01-02 ~18:3x Z: before the pin below
        sql { h ->
            h.execute("UPDATE app.outlet SET lat = $lat, lng = $lng, location_basis = 'master' WHERE id = $o")
            h.execute("INSERT INTO app.outlet_location_history (outlet_id, lat, lng, source, basis, valid_from) VALUES ($o, $lat, $lng, 'web_edit', 'master', '2027-01-03T03:00:00Z')")
        }
        assertEquals(listOf("accepted/null"), statuses(client.sendBatch(listOf(visitRecord(v, o, at, lat, lng, "no_outlet_location", "force_sale", "none")))))
        assertEquals("no_outlet_location", serverVerdict(v), "the pin was set at 03:00Z, after the capture")
    }

    /**
     * The re-check is enrichment and must never refuse a visit (s11.3), but it runs unguarded in the record's savepoint:
     * any throw parks the visit `server_error` and the phone resends it forever. A malformed policy value (here an array
     * where the enum string belongs; only the admin API validates the shape, the database does not) is enough.
     */
    @Test
    fun aRecheckFailureNeverRefusesTheVisit() = testApplication {
        app()
        val o = outlets[13]
        val (lat, lng) = seedPin(o)
        sql { h ->
            // Zone scope (the key's lowest level), in force for one minute only so no other visit of the class sees it.
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, reason) SELECT 'cfg.geo.mock_policy', 'zone', $mirZone, '[\"block_sale\"]'::jsonb, '2027-01-03T03:40:00Z', '2027-01-03T03:41:00Z', max(config_version), 'test' FROM app.cfg_version")
        }
        val v = uuid()
        assertEquals(listOf("accepted/null"), statuses(client.sendBatch(listOf(visitRecord(v, o, "2027-01-03T03:40:30Z", lat, lng)))))
    }

    /** Pins: a duplicate resend stays a duplicate and keeps the first re-check (holds). */
    @Test
    fun aResendDoesNotRecheck() = testApplication {
        app()
        val o = outlets[14]
        val (lat, lng) = seedPin(o)
        val v = uuid()
        val rec = visitRecord(v, o, nextAt(), lat, lng)
        assertEquals(listOf("accepted/null"), statuses(client.sendBatch(listOf(rec))))
        val first = sql { h -> h.createQuery("SELECT server_verdict || server_checked_at::text FROM app.visit WHERE client_uuid = CAST(:u AS uuid)").bind("u", v).mapTo(String::class.java).one() }
        assertEquals(listOf("duplicate/null"), statuses(client.sendBatch(listOf(rec))))
        assertEquals(first, sql { h -> h.createQuery("SELECT server_verdict || server_checked_at::text FROM app.visit WHERE client_uuid = CAST(:u AS uuid)").bind("u", v).mapTo(String::class.java).one() })
    }

    // ---- N-037 ---------------------------------------------------------------------------------------------------------

    private suspend fun HttpClient.registerPhone() {
        val r = put("/v1/devices/me/push-token") {
            bearerAuth(login("sr1001", phone = true)); header("X-Device-Id", devPhone); contentType(ContentType.Application.Json)
            setBody("""{"provider":"fcm","token":"$fcm","app_flavour":"sr"}""")
        }
        assertEquals(HttpStatusCode.NoContent, r.status, r.bodyAsText())
    }

    private suspend fun HttpClient.notify(token: String, scope: String, id: Long): HttpResponse = post("/v1/admin/notifications") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody("""{"kind":"config_pull","scope_type":"$scope","scope_id":$id,"title_en":"Pull","body_en":"Pull config"}""")
    }

    /**
     * SR phones are shared (CLAUDE.md; device_binding allows one active binding per (device, user)). A device with two
     * bound SRs, both inside the caller's reach, is answered 404 "no such device": `singleOrNull()` over the bindings.
     */
    @Test
    fun aSharedPhoneCanBeTargetedAsADevice() = testApplication {
        app()
        client.registerPhone()
        val device = sql { h -> h.createQuery("SELECT id FROM app.device WHERE device_uuid = CAST(:d AS uuid)").bind("d", devPhone).mapTo(Long::class.java).one() }
        sql { h ->
            val other = h.createQuery(
                "INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password) " +
                    "VALUES ('sr1077', 'Shared SR', 'SR', 'Sales Representative', 'T-SR-1077', 'bn', :z, false, false) RETURNING id",
            ).bind("z", mirZone).mapTo(Long::class.java).one()
            h.execute("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal) VALUES ($device, $other, 0)")
        }
        val r = client.notify(client.login("admin1001"), "device", device)
        assertEquals(HttpStatusCode.Accepted, r.status, r.bodyAsText())
        assertEquals(1, json(r.bodyAsText())["devices_targeted"]!!.jsonPrimitive.content.toInt())
    }

    /**
     * A broadcast shares the 4-thread nudge pool with task-assigned nudges (N-037 acceptance: the SR's push within 30 s).
     * Five 200-token chunks at 25 ms per FCM send hold every worker for 5 s; a task nudge queued behind them waits that
     * long. At fleet size (8,500 phones, 43 chunks, ~100 ms per send) the wait is minutes.
     */
    @Test
    fun aBroadcastDoesNotDelayATaskNudge() = testApplication {
        app()
        client.registerPhone()
        val nudgeAt = java.util.concurrent.atomic.AtomicLong(0)
        val slow = PushSender { token, data ->
            if (token.startsWith("bulk-")) Thread.sleep(25) else if (data["kind"] == "sync_nudge") nudgeAt.compareAndSet(0, System.nanoTime())
            true
        }
        val notifier = PushNotifier(fresh.db, wiring.config, slow, clock)
        notifier.broadcast((1L..1000L).map { -it to "bulk-$it" }) { mapOf("kind" to "announcement") }
        Thread.sleep(100)
        val t0 = System.nanoTime()
        notifier.nudge(sr, "task_assigned")
        val until = System.nanoTime() + 20_000_000_000L
        while (nudgeAt.get() == 0L && System.nanoTime() < until) Thread.sleep(20)
        val waitedMs = (nudgeAt.get() - t0) / 1_000_000
        notifier.drain(20_000)
        assertTrue(nudgeAt.get() != 0L && waitedMs < 2_000, "the task nudge waited $waitedMs ms behind the broadcast")
    }

    // ---- AUD-REL-01/02 -------------------------------------------------------------------------------------------------

    /** A DataSource whose getConnection can be held (a slow first load) or failed (an outage), delegating otherwise. */
    private class CtlDs(private val d: DataSource) : DataSource by d {
        @Volatile var hold: CountDownLatch? = null
        @Volatile var fail = false
        override fun getConnection(): Connection {
            hold?.await(10, TimeUnit.SECONDS)
            if (fail) throw java.sql.SQLTransientConnectionException("pool timeout (test)")
            return d.connection
        }
    }

    /**
     * Cold replica, healthy database: while one request loads the config snapshot, a concurrent request gets
     * RegistryDefaults, which lacks most keys, so `config.int("cfg.sys.schedule_horizon_days")` throws
     * IllegalStateException (not transient: a 500). Before a9a9d77c each caller loaded for itself.
     */
    @Test
    fun aColdReplicaServesDatabaseConfigToConcurrentRequests() {
        val ds = CtlDs(fresh.dataSource)
        val cfg = DbServerConfig(Database(ds), RegistryDefaults(), clock)
        ds.hold = CountDownLatch(1)
        val first = Thread { runCatching { cfg.int("cfg.sys.schedule_horizon_days") } }.apply { start() }
        Thread.sleep(300)
        try {
            val r = runCatching { cfg.int("cfg.sys.schedule_horizon_days") }
            assertEquals(7, r.getOrElse { throw AssertionError("concurrent read on a cold replica failed: $it", it) })
        } finally { ds.hold!!.countDown(); first.join(5_000) }
    }

    /**
     * Same for the generation: a concurrent request on a cold replica gets NIL_GENERATION on X-Server-Generation and in
     * the batch response with a healthy database; the phone takes a changed generation as a failover (s4.8: resend 24 h).
     */
    @Test
    fun aColdReplicaNeverAnswersTheNilGenerationWhileTheDatabaseIsUp() {
        val ds = CtlDs(fresh.dataSource)
        val gen = ServerGeneration(Database(ds))
        ds.hold = CountDownLatch(1)
        val first = Thread { gen.current() }.apply { start() }
        Thread.sleep(300)
        try {
            assertNotEquals(NIL_GENERATION, gen.current())
        } finally { ds.hold!!.countDown(); first.join(5_000) }
    }

    /**
     * Cold replica, database down then back: during the back-off (2..30 s) every config read answers from
     * RegistryDefaults; a key it lacks throws IllegalStateException, which isTransientDbFailure maps to 500 (the phone
     * bisects), during the outage and for up to 30 s after the database is back.
     */
    @Test
    fun aConfigReadDuringTheBackOffIsTheValueOrTransientNever500() {
        val ds = CtlDs(fresh.dataSource)
        val cfg = DbServerConfig(Database(ds), RegistryDefaults(), clock)
        ds.fail = true
        val down = runCatching { cfg.int("cfg.sys.schedule_horizon_days") }
        assertTrue(down.isSuccess || isTransientDbFailure(down.exceptionOrNull()!!), "during the outage: ${down.exceptionOrNull()}")
        ds.fail = false
        val up = runCatching { cfg.int("cfg.sys.schedule_horizon_days") }
        assertTrue(up.isSuccess || isTransientDbFailure(up.exceptionOrNull()!!), "database back, inside the back-off: ${up.exceptionOrNull()}")
    }

    /** Pins: liveness and the platform headers never wait on a held database (holds). */
    @Test
    fun livenessDoesNotWaitWhileTheDatabaseHangs() {
        val ds = CtlDs(fresh.dataSource)
        val gen = ServerGeneration(Database(ds))
        val cfg = DbServerConfig(Database(ds), RegistryDefaults(), clock)
        ds.hold = CountDownLatch(1)
        try {
            val t0 = System.nanoTime()
            gen.cached(); cfg.cachedConfigVersion()
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 100)
        } finally { ds.hold!!.countDown() }
    }
}
