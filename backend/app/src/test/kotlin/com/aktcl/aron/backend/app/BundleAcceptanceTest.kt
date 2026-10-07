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

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content
}
