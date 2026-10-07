package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.contract.RecordOutcomeCode
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F-API-005 acceptance on the seed (N-008) through the production wiring: GET /v1/sync/bundle for the seeded SR on a
 * Sunday returns the planned routes, outlets with flags, products, the selling price lists, the sales plan, empty
 * targets and offers (docs/27), the geo config, the config snapshot and the business date, gzipped with an ETag, and
 * 304 on a repeat. The clock is a fixed Sunday, 2027-01-03 08:00 Dhaka (after the seed's config rows took effect).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BundleAcceptanceTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val sunday = LocalDate.parse("2027-01-03")
    private val clock = AronClock { Instant.parse("2027-01-03T02:00:00Z") }

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        val hash = PasswordHasher().hash(password)
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", hash).execute()
            // Minimum build 5 for the SR app: logins below it are refused, and so is a bundle for a NEW date.
            h.execute(
                """
                INSERT INTO app.cfg_version (config_version, kind, committed_by, summary)
                SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'test min version' FROM app.cfg_version
                """.trimIndent(),
            )
            h.execute(
                """
                INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason)
                SELECT 'cfg.release.min_version_code', 'global', 0, '{"sr": 5, "amo": 1, "tso": 1}'::jsonb, now() - interval '1 day', max(config_version), 'test'
                FROM app.cfg_version
                """.trimIndent(),
            )
            // A credit memo with an open due on the first Daily outlet (the due ledger is the source of open dues).
            h.execute(
                """
                INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, memo_client_uuid, memo_no, source_client_uuid)
                SELECT o.id, DATE '2027-01-02', 'opening_balance', 15000, NULL, NULL, gen_random_uuid()
                FROM app.outlet o JOIN app.route r ON r.id = o.route_id WHERE r.code = 'MIR-SR-D' ORDER BY o.id LIMIT 1
                """.trimIndent(),
            )
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

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private suspend fun HttpClient.token(): String {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.bundle(token: String, query: String = "", etag: String? = null, version: String = "1.0.9+9"): HttpResponse =
        get("/v1/sync/bundle$query") {
            bearerAuth(token); header("X-Device-Id", devPhone); header("X-App-Version", version); header(HttpHeaders.AcceptEncoding, "gzip")
            etag?.let { header(HttpHeaders.IfNoneMatch, it) }
        }

    private suspend fun HttpResponse.gunzipJson(): JsonObject {
        assertEquals("gzip", headers[HttpHeaders.ContentEncoding], "the bundle is gzipped on the wire")
        return json(GZIPInputStream(bodyAsBytes().inputStream()).readBytes().toString(Charsets.UTF_8))
    }

    private fun routeDay(code: String, date: LocalDate): Map<String, Any?> = fresh.db.jdbi.withHandle<Map<String, Any?>, Exception> { h ->
        h.createQuery("SELECT d.state, d.target_outlets, d.logged_in_at FROM app.route_day d JOIN app.route r ON r.id = d.route_id WHERE r.code = :c AND d.business_date = :d")
            .bind("c", code).bind("d", date).mapToMap().findOne().orElse(emptyMap())
    }

    @Test
    @Order(1)
    fun theSeededSrGetsItsWholeDayGzippedWithAnEtagAnd304OnRepeat() = testApplication {
        application { aronApi(wiring) }
        val token = client.token()
        val r = client.bundle(token)
        assertEquals(HttpStatusCode.OK, r.status)
        val b = r.gunzipJson()
        val meta = b["meta"]!!.jsonObject
        val version = meta["bundle_version"]!!.jsonPrimitive.content
        assertTrue(Regex("^2027-01-03:\\d{1,9}$").matches(version), version)
        assertEquals("\"$version\"", r.headers[HttpHeaders.ETag])
        assertEquals(version, r.headers["X-Bundle-Version-Current"])
        assertEquals("2027-01-03", meta["valid_for_business_date"]!!.jsonPrimitive.content)
        assertEquals(false, meta["is_prefetch"]!!.jsonPrimitive.boolean)
        assertEquals("SR", meta["role"]!!.jsonPrimitive.content)
        assertTrue(meta["config_version"]!!.jsonPrimitive.long >= 1)

        // Routes: all three assigned routes, Daily and 3F planned on Sunday, 2F not; 20 outlets each; target 40.
        val routes = b["routes"]!!.jsonArray.map { it.jsonObject }
        val byCode = routes.associateBy { it["route"]!!.jsonObject["code"]!!.jsonPrimitive.content }
        assertEquals(setOf("MIR-SR-D", "MIR-SR-3F", "MIR-SR-2F"), byCode.keys)
        assertEquals(mapOf("MIR-SR-D" to true, "MIR-SR-3F" to true, "MIR-SR-2F" to false), byCode.mapValues { it.value["planned_today"]!!.jsonPrimitive.boolean })
        assertEquals(40, routes.sumOf { it["target_outlets"]!!.jsonPrimitive.int })
        assertEquals(60, routes.sumOf { it["outlets"]!!.jsonArray.size })
        // F-SYS-086: the day's first login (logged_in) dirties each route-day's tile key.
        assertEquals(3L, fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("SELECT count(DISTINCT d.subject_id) FROM app.dirty_key d JOIN app.route r ON r.id = d.subject_id WHERE d.kind = 'route_day_agg' AND d.business_date = DATE '2027-01-03' AND r.code IN ('MIR-SR-D', 'MIR-SR-3F', 'MIR-SR-2F')").mapTo(Long::class.java).one()
        })
        routes.forEach { rt ->
            assertEquals("logged_in", rt["day_state"]!!.jsonObject["state"]!!.jsonPrimitive.content)
            assertEquals("primary", rt["assignment_kind"]!!.jsonPrimitive.content)
            assertEquals(emptyList(), rt["targets"]!!.jsonArray.toList(), "targets deferred (docs/27)")
            assertEquals(emptyList(), rt["achievement_mtd"]!!.jsonArray.toList())
            assertTrue(rt["sales_plan_sku_ids"]!!.jsonArray.isNotEmpty(), "the zone's sales plan")
        }
        // Outlets with their flags and the resolved geofence.
        val outlets = routes.flatMap { it["outlets"]!!.jsonArray.map { o -> o.jsonObject } }
        outlets.forEach { o ->
            assertEquals(100, o["radius_m"]!!.jsonPrimitive.int)
            assertEquals(100, o["max_accuracy_m"]!!.jsonPrimitive.int)
            assertEquals(emptyList(), o["programme_flags"]!!.jsonArray.toList())
            assertEquals(false, o["pending_request"]!!.jsonPrimitive.boolean)
            assertEquals(emptyList(), o["suggested_qty"]!!.jsonArray.toList(), "suggested_qty hook empty by default")
            assertTrue(o.containsKey("location_confirmed") && o.containsKey("cluster_name") && o.containsKey("open_due_mtk"))
        }
        assertEquals(1, outlets.count { it["open_due_mtk"]!!.jsonPrimitive.long == 15000L }, "the seeded opening balance")
        // Products and the selling price lists (outlet, cc, distributor).
        val skus = b["products"]!!.jsonObject["skus"]!!.jsonArray
        assertEquals(42, skus.size)
        assertTrue(b["products"]!!.jsonObject["nodes"]!!.jsonArray.isNotEmpty())
        val prices = b["prices"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("outlet", "cc", "distributor"), prices.map { it["price_type"]!!.jsonPrimitive.content }.toSet())
        assertEquals(42 * 3, prices.size)
        assertEquals(emptyList(), b["offers"]!!.jsonArray.toList(), "offers deferred (docs/27)")
        // Geo config and the config snapshot.
        val values = b["config"]!!.jsonObject["values"]!!.jsonArray.map { it.jsonObject }.associateBy { it["key"]!!.jsonPrimitive.content }
        assertEquals(100, values["cfg.geo.radius_m"]!!["value"]!!.jsonPrimitive.int)
        assertEquals("default", values["cfg.geo.radius_m"]!!["scope_type"]!!.jsonPrimitive.content)
        assertEquals("global", values["cfg.device.lockdown_level"]!!["scope_type"]!!.jsonPrimitive.content, "the dev override wins over the default")
        assertEquals("dev", values["cfg.device.lockdown_level"]!!["value"]!!.jsonPrimitive.content)
        // N-033: the device policy version (app-block list) is the config version the policy renders at.
        assertEquals(b["config"]!!.jsonObject["config_version"]!!.jsonPrimitive.long, b["device_policy_version"]!!.jsonPrimitive.long)
        assertTrue("cfg.device.blocked_packages" in values.keys, "the app-block list reaches the phone with the config")
        // V0055 field-app keys (F-SYS-024/028/029, docs/19 s9): global defaults, delivered to the device.
        mapOf("cfg.app.local_history_days" to 7, "cfg.app.outbox_keep_days" to 3, "cfg.app.image_cache_mb" to 40).forEach { (k, v) ->
            assertEquals(v, values[k]?.get("value")?.jsonPrimitive?.int, "$k reaches the phone")
        }
        assertTrue(values.keys.none { it == "cfg.geo.max_speed_kmh" || it == "cfg.device.require_enrolled" }, "server-only keys stay on the server")
        // Code lists, calendar, user, reason texts.
        assertTrue(b["code_lists"]!!.jsonArray.any { it.jsonObject["list_key"]!!.jsonPrimitive.content == "force_reason" })
        assertEquals(listOf(5), b["calendar"]!!.jsonObject["weekend_days"]!!.jsonArray.map { it.jsonPrimitive.int })
        val user = b["user"]!!.jsonObject
        assertEquals("sr1001", user["username"]!!.jsonPrimitive.content)
        assertEquals("bn", user["locale"]!!.jsonPrimitive.content)
        assertEquals(0, user["bind_ordinal"]!!.jsonPrimitive.int)
        val texts = b["reason_texts"]!!.jsonObject
        RecordOutcomeCode.entries.forEach { c -> assertTrue(texts[c.wire]!!.jsonObject["bn"]!!.jsonPrimitive.content.isNotBlank(), c.wire) }
        assertNull(b["programmes"]!!.jsonPrimitive.contentOrNullSafe())

        // The first bundle of the day logged the route-days in and froze the targets.
        assertEquals("logged_in", routeDay("MIR-SR-D", sunday)["state"])
        assertEquals(20, routeDay("MIR-SR-D", sunday)["target_outlets"])
        assertEquals(0, routeDay("MIR-SR-2F", sunday)["target_outlets"])

        // Repeat with the ETag: 304, no body.
        val again = client.bundle(token, etag = r.headers[HttpHeaders.ETag])
        assertEquals(HttpStatusCode.NotModified, again.status)
        assertEquals(0, again.bodyAsBytes().size)
        assertEquals(HttpStatusCode.NotModified, client.bundle(token, etag = "W/\"$version\"").status, "a weak tag from a proxy matches too")
    }

    @Test
    @Order(2)
    fun aNewOutletChangesTheVersionButTheFrozenTargetStays() = testApplication {
        application { aronApi(wiring) }
        val token = client.token()
        val v1 = client.bundle(token).gunzipJson()["meta"]!!.jsonObject["bundle_version"]!!.jsonPrimitive.content
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                """
                INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel)
                SELECT 'NEW-1', 'New outlet', 'Owner', r.zone_id, r.id, (SELECT id FROM app.cluster WHERE zone_id = r.zone_id LIMIT 1), 'GT'
                FROM app.route r WHERE r.code = 'MIR-SR-D'
                """.trimIndent(),
            )
        }
        val r = client.bundle(token, etag = "\"$v1\"")
        assertEquals(HttpStatusCode.OK, r.status, "the content changed, so the old ETag no longer matches")
        val b = r.gunzipJson()
        assertNotEquals(v1, b["meta"]!!.jsonObject["bundle_version"]!!.jsonPrimitive.content)
        val daily = b["routes"]!!.jsonArray.map { it.jsonObject }.first { it["route"]!!.jsonObject["code"]!!.jsonPrimitive.content == "MIR-SR-D" }
        assertEquals(21, daily["outlets"]!!.jsonArray.size)
        assertEquals(20, daily["target_outlets"]!!.jsonPrimitive.int, "frozen at the first bundle of the day")
    }

    @Test
    @Order(3)
    fun aPrefetchForTheNextWorkingDayNeverLogsInAndPastOrFarDatesAreRefused() = testApplication {
        application { aronApi(wiring) }
        val token = client.token()
        val monday = sunday.plusDays(1)
        val r = client.bundle(token, "?for=$monday")
        assertEquals(HttpStatusCode.OK, r.status)
        val meta = r.gunzipJson()["meta"]!!.jsonObject
        assertEquals(true, meta["is_prefetch"]!!.jsonPrimitive.boolean)
        assertEquals("$monday", meta["valid_for_business_date"]!!.jsonPrimitive.content)
        assertEquals("not_started", routeDay("MIR-SR-2F", monday)["state"], "a pre-fetch is never a login")
        assertNull(routeDay("MIR-SR-2F", monday)["logged_in_at"])
        assertEquals(HttpStatusCode.BadRequest, client.bundle(token, "?for=${sunday.minusDays(1)}").status)
        assertEquals(HttpStatusCode.BadRequest, client.bundle(token, "?for=${sunday.plusDays(2)}").status)
        assertEquals(HttpStatusCode.BadRequest, client.bundle(token, "?for=tomorrow").status)
    }

    @Test
    @Order(4)
    fun aBuildBelowTheMinimumKeepsTodayButCannotStartANewDate() = testApplication {
        application { aronApi(wiring) }
        val token = client.token()
        assertEquals(HttpStatusCode.OK, client.bundle(token, version = "1.0.0+1").status, "today is already open: the day finishes")
        val r = client.bundle(token, "?for=${sunday.plusDays(1)}", version = "1.0.0+1")
        assertEquals(HttpStatusCode.UpgradeRequired, r.status)
        assertEquals("ERR_APP_VERSION_UNSUPPORTED", json(r.bodyAsText())["code"]!!.jsonPrimitive.content)
        // F-SYS-054: the upload is never gated by the version; a build below the minimum still empties its outbox (one
        // record whose payload is not valid: acked one by one, the batch itself is 200).
        val cu = java.util.UUID.randomUUID().toString()
        val body = """{"batch_uuid":"${java.util.UUID.randomUUID()}","device_uuid":"$devPhone","schema_version":1,"app_version":"1.0.0+1","trigger":"manual",
            "sent_at_device":"2027-01-03T04:00:00.000Z","pending_rows":0,"time_anchors":[],"device_counts":{},
            "records":[{"type":"app_error","client_uuid":"$cu","family_uuid":"$cu","rank":0,"schema_version":1,"business_date":"$sunday",
              "captured_at":"2027-01-03T03:41:00.120Z","captured_elapsed_ms":1000,"boot_count":1,"clock_offset_ms":0,"captured_offline":true,
              "bundle_stale":false,"config_version":1,"payload":{}}]}"""
        val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(body.toByteArray()) } }.toByteArray()
        val up = client.post("/v1/sync/batch") {
            bearerAuth(token); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.0+1"); header("Content-Encoding", "gzip")
            setBody(io.ktor.http.content.ByteArrayContent(gz, ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, up.status, up.bodyAsText())
        assertEquals(1, json(up.bodyAsText())["acks"]!!.jsonArray.size)
    }

    @Test
    @Order(5)
    fun aWebTokenOrAMissingTokenGetsNoBundle() = testApplication {
        application { aronApi(wiring) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/sync/bundle").status)
        val web = json(client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json); setBody("""{"username":"tso1001","password":"$password","client":"web"}""")
        }.bodyAsText())["access_token"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/sync/bundle") { bearerAuth(web) }.status)
    }

    /** AUD-TP-3 scope case for GET /v1/sync/bundle: the bundle holds exactly the caller's assigned routes and their outlets. */
    @Test
    @Order(6)
    fun theBundleHoldsOnlyTheCallersOwnRoutesAndTheirOutletsWhateverTheQuerySays() = testApplication {
        application { aronApi(wiring) }
        // The seed assigns every route to sr1001: end the 2F assignment before today so its outlets are out of reach.
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("UPDATE app.route_assignment SET valid_to = DATE '2027-01-03' WHERE route_id = (SELECT id FROM app.route WHERE code = 'MIR-SR-2F')")
            h.execute("UPDATE app.app_user SET scope_version = scope_version + 1 WHERE username = 'sr1001'")
        }
        val t = client.token()
        val (own, others) = fresh.db.jdbi.withHandle<Pair<Set<Long>, Set<Long>>, Exception> { h ->
            val mine = h.createQuery(
                "SELECT o.id FROM app.outlet o JOIN app.route_assignment a ON a.route_id = o.route_id JOIN app.app_user u ON u.id = a.user_id " +
                    "WHERE u.username = 'sr1001' AND o.status = 'active' AND a.valid_from <= DATE '2027-01-03' AND (a.valid_to IS NULL OR a.valid_to > DATE '2027-01-03')",
            ).mapTo(Long::class.java).set()
            mine to h.createQuery("SELECT id FROM app.outlet").mapTo(Long::class.java).set().minus(mine)
        }
        assertTrue(others.isNotEmpty(), "the seed must hold outlets of other users' routes for this to mean anything")
        val otherRoute = fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("SELECT route_id FROM app.outlet WHERE id = :id").bind("id", others.first()).mapTo(Long::class.java).one()
        }
        for (query in listOf("", "?route_id=$otherRoute", "?zone_id=1&route_ids=$otherRoute")) {
            val r = client.bundle(t, query)
            if (r.status != HttpStatusCode.OK) { assertEquals(400, r.status.value, "a scope selector is refused, never honoured"); continue }
            val got = r.gunzipJson()["routes"]!!.jsonArray.flatMap { it.jsonObject["outlets"]!!.jsonArray.map { o -> o.jsonObject["outlet_id"]!!.jsonPrimitive.long } }.toSet()
            assertEquals(emptySet(), got - own, "outlets outside the caller's routes for '$query'")
        }
    }

    /** android-core-backend-config-delta-radius.md (F-SYS-053): a portal radius change reaches the outlets through the delta. */
    @Test
    @Order(7)
    fun anOutletRadiusChangeReachesThePhoneThroughTheConfigDelta() = testApplication {
        application { aronApi(wiring) }
        val t = client.token()
        val (outlet, before) = fresh.db.jdbi.withHandle<Pair<Long, Long>, Exception> { h ->
            h.createQuery("SELECT min(o.id) FROM app.outlet o JOIN app.route r ON r.id = o.route_id WHERE r.code = 'MIR-SR-D'").mapTo(Long::class.java).one() to
                h.createQuery("SELECT max(config_version) FROM app.cfg_version").mapTo(Long::class.java).one()
        }
        suspend fun delta(since: Long) = client.get("/v1/config/delta?since=$since") { bearerAuth(t); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9") }
        val quiet = delta(before)
        assertEquals(HttpStatusCode.NotModified, quiet.status, quiet.bodyAsText())
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'outlet radius' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.geo.radius_m', 'outlet', $outlet, '250'::jsonb, now() - interval '1 minute', max(config_version), 'wide yard' FROM app.cfg_version")
        }
        val r = delta(before)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val changes = json(r.bodyAsText())["outlet_radius_changes"]!!.jsonArray.map { it.jsonObject }
        val byOutlet = changes.associate { it["outlet_id"]!!.jsonPrimitive.long to it["radius_m"]!!.jsonPrimitive.int }
        assertEquals(250, byOutlet[outlet], changes.toString())
        assertTrue(byOutlet.filterKeys { it != outlet }.values.all { it == 100 }, "the other outlets keep the resolved default")
        assertEquals(HttpStatusCode.NotModified, delta(before + 1).status, "nothing changed after the radius version")
    }

    @Test
    @Order(8)
    fun avKvContentAndThePosmSurveyReachTheSrsBundleWithoutOtherRoutesOutlets() = testApplication {
        application { aronApi(wiring) }
        val t = client.token()
        val (own, offRoute) = fresh.db.jdbi.withHandle<Pair<Long, Long>, Exception> { h ->
            val own = h.createQuery("SELECT min(o.id) FROM app.outlet o JOIN app.route r ON r.id = o.route_id WHERE r.code = 'MIR-SR-D'").mapTo(Long::class.java).one()
            val off = h.createQuery("SELECT min(id) FROM app.outlet WHERE route_id IS NULL OR route_id NOT IN (SELECT route_id FROM app.route_assignment a JOIN app.app_user u ON u.id = a.user_id WHERE u.username = 'sr1001')").mapTo(Long::class.java).findOne().orElse(-1L)
            fun item(title: String, kind: String, seq: Int, outlets: String, from: String = "2027-01-01", to: String = "2027-01-31") = h.execute(
                "INSERT INTO app.content_item (kind, title_en, asset_url, sha256, bytes, valid_from, valid_to, sequence, outlet_ids) " +
                    "VALUES ('$kind', '$title', 'https://example.invalid/$title', sha256('$title'::bytea), 1000, DATE '$from', DATE '$to', $seq, '$outlets'::bigint[])",
            )
            item("everyone", "av", 1, "{}")
            item("mine", "kv", 2, "{$own,$off}")
            item("elsewhere", "kv", 3, "{$off}")
            item("expired", "av", 4, "{}", "2026-12-01", "2026-12-31")
            val survey = h.createQuery("INSERT INTO app.survey (kind, valid_from) VALUES ('posm', DATE '2027-01-01') RETURNING id").mapTo(Long::class.java).one()
            h.execute(
                "INSERT INTO app.survey_version (survey_id, version, title_en, questions) VALUES (?, 1, 'POSM', CAST(? AS jsonb))", survey,
                """[{"question_id":1,"key":"q1","answer_type":"bool","label_en":"POSM present?","label_bn":"পস আছে?","required":true,"show_if_key":null,"show_if_bool":null,"photo":false},
                    {"question_id":2,"key":"q1_1","answer_type":"photo_only","label_en":"Photo","label_bn":null,"required":true,"show_if_key":"q1","show_if_bool":true,"photo":true}]""",
            )
            // An AMO survey with a version: only the role filter keeps it off the SR's phone.
            val amo = h.createQuery("INSERT INTO app.survey (kind, valid_from) VALUES ('amo_survey', DATE '2027-01-01') RETURNING id").mapTo(Long::class.java).one()
            h.execute("INSERT INTO app.survey_version (survey_id, version, title_en, questions) VALUES (?, 1, 'AMO', CAST(? AS jsonb))", amo,
                """[{"question_id":1,"key":"a1","answer_type":"text","label_en":"Note","label_bn":null,"required":false,"show_if_key":null,"show_if_bool":null,"photo":false},
                    {"key":"broken","answer_type":"bool"}]""")
            own to off
        }
        val b = client.bundle(t).gunzipJson()
        val content = b["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("everyone", "mine"), content.map { it["title_en"]!!.jsonPrimitive.content }, "valid, active, on the caller's outlets, in play order")
        assertEquals(emptyList(), content[0]["outlet_ids"]!!.jsonArray.toList(), "empty = every outlet")
        assertEquals(listOf(own), content[1]["outlet_ids"]!!.jsonArray.map { it.jsonPrimitive.long }, "another route's outlet is not named to this phone")
        assertTrue(Regex("^[0-9a-f]{64}$").matches(content[0]["sha256"]!!.jsonPrimitive.content))
        val survey = b["surveys"]!!.jsonArray.single().jsonObject
        val qs = survey["questions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(false, true), qs.map { it["requires_photo"]!!.jsonPrimitive.boolean })
        assertEquals("q1", qs[1]["show_if_key"]!!.jsonPrimitive.content, "Q1.1 is shown only when Q1 is yes")
        assertEquals("পস আছে?", qs[0]["label_bn"]!!.jsonPrimitive.content)
        assertTrue(survey.keys.none { "point" in it } && qs.none { q -> q.keys.any { "point" in it } }, "loyalty points are deferred (docs/27)")
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content
}
