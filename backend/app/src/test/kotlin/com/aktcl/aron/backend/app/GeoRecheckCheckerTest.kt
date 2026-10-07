package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.backend.sync.GeoRecheck
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
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent T1 checker (refuter) for F-SYS-012. Same fixtures as GeoRecheckTest (seed, sr1001, POST /v1/sync/batch),
 * own FreshDb. Each test names the property it tries to break.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeoRecheckCheckerTest {
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
            // Poison hook for the savepoint and sweep tests: an update that sets server_verdict on a listed visit fails.
            h.execute("CREATE TABLE public.chk_poison (u uuid PRIMARY KEY)")
            h.execute(
                """
                CREATE FUNCTION public.chk_poison_fn() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN
                  IF NEW.server_verdict IS NOT NULL AND EXISTS (SELECT 1 FROM public.chk_poison p WHERE p.u = NEW.client_uuid) THEN
                    RAISE EXCEPTION 'checker poison %', NEW.client_uuid;
                  END IF;
                  RETURN NEW;
                END ${'$'}${'$'}
                """.trimIndent(),
            )
            h.execute("CREATE TRIGGER chk_poison_trg BEFORE UPDATE ON app.visit FOR EACH ROW EXECUTE FUNCTION public.chk_poison_fn()")
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

    /** A new active outlet on route r1 (copy of a seeded one) with a master pin and no location history. */
    private fun newOutlet(code: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h ->
        h.createQuery(
            """
            INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel, sub_channel_id, geo_class, lat, lng, location_basis)
            SELECT :code, name, owner_name, zone_id, route_id, cluster_id, channel, sub_channel_id, geo_class, :la, :ln, 'master' FROM app.outlet WHERE id = :t
            RETURNING id
            """.trimIndent(),
        ).bind("code", code).bind("la", pinLat).bind("ln", pinLng).bind("t", template).mapTo(Long::class.java).one()
    }

    private fun history(outlet: Long, lat: Double, validFromSql: String) = fresh.db.jdbi.useHandle<Exception> { h ->
        h.execute("INSERT INTO app.outlet_location_history (outlet_id, lat, lng, source, valid_from) VALUES (?, ?, ?, 'web_edit', $validFromSql)", outlet, lat, pinLng)
    }

    private fun commitCfg(key: String, scopeTypeSql: String, scopeIdSql: String, valueJson: String, fromSql: String, toSql: String?) {
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                """
                INSERT INTO app.cfg_version (config_version, kind, committed_by, summary)
                SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'geo checker' FROM app.cfg_version
                """.trimIndent(),
            )
            h.execute(
                """
                INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, reason)
                SELECT '$key', $scopeTypeSql, $scopeIdSql, '$valueJson'::jsonb, $fromSql, ${toSql ?: "NULL"}, max(config_version), 'geo checker'
                FROM app.cfg_version
                """.trimIndent(),
            )
        }
    }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun uuid() = UUID.randomUUID().toString()

    private data class Server(val verdict: String?, val distance: Double?, val radius: Int?)

    private fun server(clientUuid: String): Server = fresh.db.jdbi.withHandle<Server, Exception> { h ->
        h.createQuery("SELECT server_verdict, server_distance_m, server_radius_m FROM app.visit WHERE client_uuid = CAST(:c AS uuid)")
            .bind("c", clientUuid).map { rs, _ -> Server(rs.getString(1), rs.getObject(2) as Double?, rs.getObject(3) as Int?) }.one()
    }

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

    private suspend fun HttpClient.send(records: List<JsonObject>, want: String = "accepted"): JsonObject {
        val body = buildJsonObject {
            put("batch_uuid", uuid()); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
            put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
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

    private fun poison(cu: String) = fresh.db.jdbi.useHandle<Exception> { h -> h.execute("INSERT INTO public.chk_poison VALUES (CAST(? AS uuid))", cu) }

    // ---------------------------------------------------------------- pin history

    /**
     * Admin clears an outlet's pin (AdminOutlets derive: lat null -> location_basis 'none'); no history row is written
     * because outlet_location_history.lat is NOT NULL. The phone (bundle lat null) says no_outlet_location; the spec
     * (s11.2 row 3) says no_outlet_location. The server keeps judging against the withdrawn pin from history.
     */
    @Test
    fun clearedPinIsNotUsedForVisitsCapturedAfterwards() = testApplication {
        application { aronApi(wiring) }
        val o = newOutlet("CHK-CLR-1")
        history(o, pinLat, "TIMESTAMPTZ '2027-01-01 00:00:00+00'")
        // Pin cleared before the visit (and before the business date started).
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.outlet SET lat = NULL, lng = NULL, location_basis = 'none', location_confirmed = false WHERE id = ?", o) }
        val v = visit(o, 0.0)
        client.send(listOf(v))
        assertEquals("no_outlet_location", server(uuidOf(v)).verdict, "an outlet with location_basis none has no usable location (s11.2 row 3)")
    }

    /**
     * An outlet whose pin predates any history row (seeded/migrated/created outside AdminOutlets) gets its first history
     * row when the pin is edited. A visit captured before that edit was taken against the old pin (the phone had it in
     * its bundle) but the server finds no history row <= capture and stores no_outlet_location.
     */
    @Test
    @org.junit.jupiter.api.Disabled("routed: docs/requests/backend-core-outlet-pin-history.md (backend-admin, db); the pre-edit pin is not recorded")
    fun aPinEditDoesNotEraseThePinInForceBeforeIt() = testApplication {
        application { aronApi(wiring) }
        val o = newOutlet("CHK-MIG-1")
        // Pin edited at 05:00Z (after the 03:xx capture): first history row ever, 1 km north.
        history(o, north(1000.0), "TIMESTAMPTZ '2027-01-03 05:00:00+00'")
        val v = visit(o, 80.0)
        client.send(listOf(v))
        val s = server(uuidOf(v))
        assertEquals("in_range", s.verdict, "80 m from the pin the outlet had at capture (radius 100); got $s")
    }

    // ---------------------------------------------------------------- radius boundary (should hold)

    @Test
    fun radiusChangeAtDhakaMidnightIsResolvedForTheBusinessDate() = testApplication {
        application { aronApi(wiring) }
        val o = newOutlet("CHK-MID-1")
        history(o, pinLat, "TIMESTAMPTZ '2027-01-01 00:00:00+00'")
        // 100 m until Dhaka midnight starting 2027-01-03 (= 2027-01-02T18:00Z), 200 m from then.
        commitCfg("cfg.geo.radius_m", "'outlet'", "$o", "100", "TIMESTAMPTZ '2027-01-01 00:00:00+00'", "TIMESTAMPTZ '2027-01-02 18:00:00+00'")
        commitCfg("cfg.geo.radius_m", "'outlet'", "$o", "200", "TIMESTAMPTZ '2027-01-02 18:00:00+00'", null)
        val v = visit(o, 150.0, capturedAt = "2027-01-02T18:30:00.000Z") // Dhaka 00:30 on 2027-01-03
        client.send(listOf(v))
        val s = server(uuidOf(v))
        assertEquals(200, s.radius)
        assertEquals("in_range", s.verdict)
    }

    // ---------------------------------------------------------------- savepoint (should hold)

    @Test
    fun aFailingRecheckNeverRefusesTheVisit() = testApplication {
        application { aronApi(wiring) }
        val cu = uuid()
        poison(cu)
        val v = visit(newOutlet("CHK-SP-1").also { history(it, pinLat, "TIMESTAMPTZ '2027-01-01 00:00:00+00'") }, 10.0, cu = cu)
        client.send(listOf(v))
        assertNull(server(cu).verdict, "re-check failed in its savepoint; visit stored, verdict left for the sweep")
        assertEquals(1L, fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT count(*) FROM app.visit WHERE client_uuid = CAST(:c AS uuid)").bind("c", cu).mapTo(Long::class.java).one() })
    }

    // ---------------------------------------------------------------- sweep

    /**
     * The sweep fills server_verdict but neither emits a domain event nor marks the route-day dirty, so dw.fact_visit /
     * agg geo_valid_visits (the dashboards' geo-valid count, s11.3) never see the swept verdict.
     */
    @Test
    fun sweptVerdictReachesTheDashboards() = testApplication {
        application { aronApi(wiring) }
        val o = newOutlet("CHK-DW-1")
        history(o, pinLat, "TIMESTAMPTZ '2027-01-01 00:00:00+00'")
        val v = visit(o, 10.0)
        client.send(listOf(v))
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("UPDATE app.visit SET server_verdict = NULL, server_distance_m = NULL, server_radius_m = NULL, server_max_accuracy_m = NULL, server_checked_at = NULL WHERE client_uuid = CAST(? AS uuid)", uuidOf(v))
            h.execute("DELETE FROM app.dirty_key")
        }
        val maxEvent = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT coalesce(max(id), 0) FROM app.domain_event").mapTo(Long::class.java).one() }
        fresh.db.jdbi.inTransaction<Int, Exception> { h -> GeoRecheck.sweep(h, LocalDate.parse(day), 7, 1000, now) }
        assertEquals("in_range", server(uuidOf(v)).verdict)
        val dirty = fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("SELECT count(*) FROM app.dirty_key WHERE kind = 'route_day_agg' AND subject_id = :r AND business_date = :d")
                .bind("r", r1).bind("d", LocalDate.parse(day)).mapTo(Long::class.java).one()
        }
        val events = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT count(*) FROM app.domain_event WHERE id > :m").bind("m", maxEvent).mapTo(Long::class.java).one() }
        assertTrue(dirty > 0 || events > 0, "sweep changed server_verdict but the route-day is not re-projected (dirty=$dirty, new events=$events)")
    }

    /**
     * Rows whose re-check fails every time stay first in `ORDER BY business_date, id` and fill the LIMIT on every tick,
     * so a later healthy row is never swept (limit 2 here stands for the worker's 200).
     */
    @Test
    fun poisonRowsDoNotStarveTheSweep() = testApplication {
        application { aronApi(wiring) }
        val o = newOutlet("CHK-STV-1")
        history(o, pinLat, "TIMESTAMPTZ '2027-01-01 00:00:00+00'")
        val p1 = uuid(); val p2 = uuid()
        poison(p1); poison(p2)
        // Clear any older failed rows so exactly two poison rows lead the queue.
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.visit SET voided_at = now() WHERE server_verdict IS NULL") }
        client.send(listOf(visit(o, 5.0, cu = p1), visit(o, 6.0, cu = p2)))
        val good = visit(o, 7.0)
        client.send(listOf(good))
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("UPDATE app.visit SET server_verdict = NULL, server_distance_m = NULL, server_radius_m = NULL, server_max_accuracy_m = NULL, server_checked_at = NULL WHERE client_uuid = CAST(? AS uuid)", uuidOf(good))
        }
        // Random order: 20 sweeps of 2 out of 3 rows miss the healthy one with probability (1/3)^20.
        repeat(20) { fresh.db.jdbi.inTransaction<Int, Exception> { h -> GeoRecheck.sweep(h, LocalDate.parse(day), 7, 2, now) } }
        assertEquals("in_range", server(uuidOf(good)).verdict, "a healthy row behind permanently failing rows is never swept")
    }
}
