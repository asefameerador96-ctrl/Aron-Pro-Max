package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.rules.Geo
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AUD-REL-02: a transient database failure inside a family (serialization failure, deadlock) is retried in a fresh
 * transaction; the record is stored once and acked accepted. A failure that persists past the tries is parked
 * `server_error` (retryable), as before. Real batches through POST /v1/sync/batch on the seed (sr1001).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestRetryTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val now = Instant.parse("2027-01-03T06:00:00Z")
    private val clock = AronClock { now }
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val day = "2027-01-03"
    private val seq = AtomicInteger(0)
    private var r1 = 0L
    private var template = 0L
    private val pinLat = 23.80
    private val pinLng = 90.36
    private var token: String? = null

    private fun north(m: Double) = pinLat + m / (Geo.EARTH_RADIUS_M * PI / 180.0)

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            val sr = h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one()
            r1 = h.createQuery("SELECT min(route_id) FROM app.route_assignment WHERE user_id = :u").bind("u", sr).mapTo(Long::class.java).one()
            template = h.createQuery("SELECT id FROM app.outlet WHERE route_id = :r AND status = 'active' ORDER BY id LIMIT 1").bind("r", r1).mapTo(Long::class.java).one()
            // Fault hook: an INSERT of a listed visit fails with SQLSTATE 40001 while its budget lasts. The budget is spent
            // through a sequence per uuid, which a rolled-back try does not give back.
            h.execute("CREATE TABLE public.chk_fault (u uuid PRIMARY KEY, fails int NOT NULL, seq text NOT NULL)")
            h.execute(
                """
                CREATE FUNCTION public.chk_fault_fn() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                DECLARE f record;
                BEGIN
                  SELECT * INTO f FROM public.chk_fault WHERE u = NEW.client_uuid;
                  IF FOUND AND nextval(f.seq) <= f.fails THEN
                    RAISE EXCEPTION 'injected serialization failure %', NEW.client_uuid USING ERRCODE = '40001';
                  END IF;
                  RETURN NEW;
                END ${'$'}${'$'}
                """.trimIndent(),
            )
            h.execute("CREATE TRIGGER chk_fault_trg BEFORE INSERT ON app.visit FOR EACH ROW EXECUTE FUNCTION public.chk_fault_fn()")
        }
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock)
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    /** A new active outlet on route r1 (copy of a seeded one) with a master pin. */
    private fun newOutlet(code: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h ->
        h.createQuery(
            """
            INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel, sub_channel_id, geo_class, lat, lng, location_basis)
            SELECT :code, name, owner_name, zone_id, route_id, cluster_id, channel, sub_channel_id, geo_class, :la, :ln, 'master' FROM app.outlet WHERE id = :t
            RETURNING id
            """.trimIndent(),
        ).bind("code", code).bind("la", pinLat).bind("ln", pinLng).bind("t", template).mapTo(Long::class.java).one()
    }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun uuid() = UUID.randomUUID().toString()

    private suspend fun HttpClient.login(): String = token ?: run {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content.also { token = it }
    }

    private fun visit(outlet: Long, metresNorth: Double, capturedAt: String? = null, cu: String = uuid()): JsonObject {
        val n = seq.incrementAndGet()
        return buildJsonObject {
            put("type", "visit"); put("client_uuid", cu); put("family_uuid", cu); put("rank", 0); put("schema_version", 1)
            put("business_date", day); put("captured_at", capturedAt ?: "2027-01-03T03:%02d:%02d.000Z".format(n / 60, n % 60))
            put("captured_elapsed_ms", 18330000 + n); put("boot_count", 412)
            put("clock_offset_ms", 0); put("captured_offline", true); put("route_id", r1); put("bundle_version", "$day:3")
            put("bundle_stale", false); put("config_version", 1)
            put("payload", json(
                """
                {"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"2027-01-03T03:41:00.120Z","sequence_no":$n,"planned":true,
                 "fix":{"purpose":"visit_open","fix_status":"ok","lat":${north(metresNorth)},"lng":$pinLng,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}},
                 "geo":{"verdict":"in_range","distance_m":$metresNorth,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}
                """.trimIndent(),
            ))
        }
    }

    private fun uuidOf(rec: JsonObject) = rec["client_uuid"]!!.jsonPrimitive.content

    private suspend fun HttpClient.send(records: List<JsonObject>, want: String = "accepted", trigger: String = "manual"): JsonObject {
        val body = buildJsonObject {
            put("batch_uuid", uuid()); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
            put("trigger", trigger); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
            put("time_anchors", JsonArray(emptyList())); put("device_counts", buildJsonObject {}); put("records", JsonArray(records))
        }.toString()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(body.toByteArray()) } }.toByteArray()
        val t = login()
        val r = post("/v1/sync/batch") {
            bearerAuth(t); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); header("Content-Encoding", "gzip")
            setBody(io.ktor.http.content.ByteArrayContent(gz, ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val b = json(r.bodyAsText())
        b["acks"]!!.jsonArray.forEach { a -> assertEquals(want, a.jsonObject["status"]!!.jsonPrimitive.content, a.toString()) }
        return b
    }


    private fun fault(cu: String, fails: Int) = fresh.db.jdbi.useHandle<Exception> { h ->
        val seq = "public.chk_seq_" + cu.replace("-", "")
        h.execute("CREATE SEQUENCE $seq")
        h.execute("INSERT INTO public.chk_fault VALUES (CAST(? AS uuid), ?, ?)", cu, fails, seq)
    }

    private fun visits(cu: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h ->
        h.createQuery("SELECT count(*) FROM app.visit WHERE client_uuid = CAST(:c AS uuid)").bind("c", cu).mapTo(Long::class.java).one()
    }

    private fun registry(cu: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h ->
        h.createQuery("SELECT count(*) FROM app.ingest_registry WHERE client_uuid = CAST(:c AS uuid)").bind("c", cu).mapTo(Long::class.java).one()
    }

    /** Times the fault trigger ran for [cu] (its sequence is never rolled back). */
    private fun faultCalls(cu: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h ->
        h.createQuery("SELECT last_value FROM public.chk_seq_" + cu.replace("-", "")).mapTo(Long::class.java).one()
    }

    @Test
    fun aTransientFailureIsRetriedAndTheVisitStoredOnce() = testApplication {
        application { aronApi(wiring) }
        val o = newOutlet("RTY-OK-1")
        val cu = uuid()
        fault(cu, 2)
        val other = visit(o, 20.0)
        client.send(listOf(visit(o, 10.0, cu = cu), other))
        assertEquals(1L, visits(cu), "stored once after two failed tries")
        assertEquals(1L, registry(cu), "one registry row")
        assertEquals(3L, faultCalls(cu), "two failed tries and the one that stored")
        assertEquals(1L, visits(uuidOf(other)))
    }

    @Test
    fun aFailurePastTheTriesIsParkedRetryableAndStoredOnTheResend() = testApplication {
        application { aronApi(wiring) }
        val o = newOutlet("RTY-PARK-1")
        val cu = uuid()
        fault(cu, 3)
        val v = visit(o, 10.0, cu = cu)
        val b = client.send(listOf(v), want = "rejected")
        val ack = b["acks"]!!.jsonArray.single().jsonObject
        assertEquals("server_error", ack["code"]!!.jsonPrimitive.content, ack.toString())
        assertEquals("true", ack["retryable"]!!.jsonPrimitive.content, ack.toString())
        assertEquals(0L, visits(cu))
        // The phone resends in a new batch; the fault budget is spent, so the visit is stored, once.
        client.send(listOf(v))
        client.send(listOf(v), want = "duplicate")
        assertEquals(1L, visits(cu))
    }

    /** The visit dated [date], captured at 05:00Z that day. */
    private fun oldVisit(outlet: Long, date: String): JsonObject {
        val v = visit(outlet, 10.0, capturedAt = "${date}T05:00:00.000Z")
        return JsonObject(v + ("business_date" to kotlinx.serialization.json.JsonPrimitive(date)))
    }

    private fun restore(startedSql: String, lostAfterSql: String) = fresh.db.jdbi.useTransaction<Exception> { h ->
        h.execute("UPDATE app.server_generation SET is_current = false WHERE is_current")
        h.execute("INSERT INTO app.server_generation (generation, kind, started_at, lost_after_utc) VALUES (gen_random_uuid(), 'pitr_restore', $startedSql, $lostAfterSql)")
    }

    /**
     * F-SYS-089: after a restore a re-sent row older than the window is accepted (flag resync_late), never quarantined;
     * bounded by what the lost lineage could have accepted, for a young generation only, on `resync` and `digest_resend`.
     */
    @Test
    fun aResyncRowOlderThanTheWindowIsAcceptedOnlyAfterARestore() = testApplication {
        application { aronApi(wiring) }
        val o = newOutlet("RTY-RSY-1")
        // 2026-12-26 is 8 days before 2027-01-03: outside cfg.sync.max_backdate_days (7).
        client.send(listOf(oldVisit(o, "2026-12-26")), want = "quarantined")
        client.send(listOf(oldVisit(o, "2026-12-26")), want = "quarantined", trigger = "resync") // no restore yet
        // Restored at 04:00Z; acks after 2027-01-01 23:00Z (Dhaka 2027-01-02) were lost: rows back to 2026-12-26 were acceptable.
        restore("TIMESTAMPTZ '2027-01-03 04:00:00+00'", "TIMESTAMPTZ '2027-01-01 23:00:00+00'")
        client.send(listOf(oldVisit(o, "2026-12-26")), want = "quarantined") // not a re-send
        val late = oldVisit(o, "2026-12-26")
        client.send(listOf(late), want = "accepted", trigger = "resync")
        assertEquals(1L, visits(uuidOf(late)))
        // V0056: the accepted row carries the flag; a duplicate re-send (touch(), no registry write) leaves it.
        assertEquals("{resync_late}", flagsText(uuidOf(late)))
        client.send(listOf(late), want = "duplicate", trigger = "resync")
        assertEquals("{resync_late}", flagsText(uuidOf(late)))
        client.send(listOf(oldVisit(o, "2026-12-26")), want = "accepted", trigger = "digest_resend")
        client.send(listOf(oldVisit(o, "2026-12-25")), want = "quarantined", trigger = "resync") // beyond what a lineage accepted
        // An old generation (restored days ago): the allowance has expired.
        restore("TIMESTAMPTZ '2027-01-01 04:00:00+00'", "TIMESTAMPTZ '2026-12-31 23:00:00+00'")
        client.send(listOf(oldVisit(o, "2026-12-26")), want = "quarantined", trigger = "resync")
    }

    private fun flagsText(clientUuid: String): String = fresh.db.jdbi.withHandle<String, Exception> { h ->
        h.createQuery("SELECT flags::text FROM app.ingest_registry WHERE client_uuid = CAST(:c AS uuid)").bind("c", clientUuid).mapTo(String::class.java).one()
    }

    /** An attendance check-out captured at [capturedAt] on [day]. */
    private fun checkOut(capturedAt: String): JsonObject {
        val cu = uuid()
        val n = seq.incrementAndGet()
        return buildJsonObject {
            put("type", "attendance_event"); put("client_uuid", cu); put("family_uuid", cu); put("rank", 0); put("schema_version", 1)
            put("business_date", day); put("captured_at", capturedAt); put("captured_elapsed_ms", 18330000 + n); put("boot_count", 412)
            put("clock_offset_ms", 0); put("captured_offline", true); put("config_version", 1)
            put("payload", json(
                """
                {"kind":"check_out","fix":{"purpose":"attendance_out","fix_status":"ok","lat":$pinLat,"lng":$pinLng,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                 "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}}
                """.trimIndent(),
            ))
        }
    }

    /**
     * s4.5 `checkout_too_early` (BC-63): a check-out before cfg.day.checkout_earliest_time (17:00 Dhaka, inclusive) is
     * accepted and flagged until a review path can release a quarantined one; it is never lost.
     */
    @Test
    fun aCheckOutBeforeTheEarliestTimeIsAcceptedNeverQuarantined() = testApplication {
        application { aronApi(wiring) }
        val early = checkOut("2027-01-03T05:30:00.000Z") // 11:30 Dhaka
        client.send(listOf(early))
        assertEquals("{checkout_too_early}", flagsText(uuidOf(early)), "stored on the registry row (V0059)")
        assertEquals(1L, fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("SELECT count(*) FROM app.attendance_event WHERE client_uuid = CAST(:c AS uuid)").bind("c", uuidOf(early)).mapTo(Long::class.java).one()
        })
    }

    /**
     * F-SYS-091 (s11.4): a row stamped below a config version the phone had already applied before the row's capture is
     * accepted and flagged `config_stamp_regress`; the third such row of the device and day raises CONFIG_STAMP_REGRESS once.
     */
    @Test
    fun rowsStampedBelowAnAppliedConfigAreFlaggedAndTheThirdRaisesTheSignal() = testApplication {
        application { aronApi(wiring) }
        val outlet = newOutlet("RTY-CSR-1")
        val ack = uuid()
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                """
                INSERT INTO app.cfg_ack (client_uuid, family_uuid, business_date, user_id, device_id, captured_at, config_version, acked_config_version, applied_at)
                SELECT CAST('$ack' AS uuid), CAST('$ack' AS uuid), DATE '2027-01-03', d.user_id, d.id, TIMESTAMPTZ '2027-01-02T23:00:00Z', 5, 5, TIMESTAMPTZ '2027-01-02T23:00:00Z'
                  FROM (SELECT dv.id, (SELECT id FROM app.app_user WHERE username = 'sr1001') AS user_id FROM app.device dv WHERE dv.device_uuid = '$devPhone') d
                """.trimIndent(),
            )
        }
        fun signals() = fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("SELECT count(*) FROM app.risk_signal WHERE code = 'CONFIG_STAMP_REGRESS' AND subject_id = '$devPhone'").mapTo(Long::class.java).one()
        }
        try {
            val before = visit(outlet, 10.0, capturedAt = "2027-01-02T22:30:00.000Z") // captured before the ack: not judged
            client.send(listOf(before))
            assertEquals("{}", flagsText(uuidOf(before)))
            val rows = List(3) { visit(outlet, 10.0) } // config_version 1 < 5
            client.send(rows.take(2))
            rows.take(2).forEach { assertEquals("{config_stamp_regress}", flagsText(uuidOf(it))) }
            assertEquals(0L, signals(), "two rows: no signal yet")
            client.send(rows.drop(2))
            assertEquals(1L, signals(), "the third row raises it")
            client.send(listOf(visit(outlet, 10.0)))
            assertEquals(1L, signals(), "once per device and day")
        } finally {
            fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.cfg_ack SET voided_at = now() WHERE client_uuid = CAST('$ack' AS uuid)") }
        }
    }
}
