package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Independent checker (T1) for F-API-005 GET /v1/sync/bundle. Every test here is written to FAIL on a defect found
 * in the builder's implementation; see the report for severity. The clock is mutable and every test uses its own
 * business date so the frozen route-day rows of one test never feed another.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BundleCheckerTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val now = AtomicReference(Instant.parse("2027-01-05T02:00:00Z"))
    private val clock = AronClock { now.get() }

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
            // A second SR (cover holder) in the same zone, same password.
            h.execute(
                """
                INSERT INTO app.app_user (username, full_name, role, locale, home_zone_id, password_hash, must_change_password, pilot, status)
                SELECT 'sr2002', 'Cover SR', role, locale, home_zone_id, password_hash, must_change_password, true, status FROM app.app_user WHERE username = 'sr1001'
                """.trimIndent(),
            )
            // Shared phone: sr2002 is the second user bound to the dev phone.
            h.execute(
                """
                INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, bound_via)
                SELECT d.id, u.id, 1, 'support' FROM app.device d, app.app_user u
                WHERE d.device_uuid = '$devPhone' AND u.username = 'sr2002'
                """.trimIndent(),
            )
        }
        // Minimum SR build 5 (as in BundleAcceptanceTest).
        commitCfg("cfg.release.min_version_code", "'global'", "0", """{"sr": 5, "amo": 1, "tso": 1}""", "now() - interval '1 day'", null)
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock)
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    /** One committed config change set with one value row (scope id given as SQL so it can be a subquery). */
    private fun commitCfg(key: String, scopeTypeSql: String, scopeIdSql: String, valueJson: String, fromSql: String, toSql: String?) {
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                """
                INSERT INTO app.cfg_version (config_version, kind, committed_by, summary)
                SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'checker' FROM app.cfg_version
                """.trimIndent(),
            )
            h.execute(
                """
                INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, reason)
                SELECT '$key', $scopeTypeSql, $scopeIdSql, '$valueJson'::jsonb, $fromSql, ${toSql ?: "NULL"}, max(config_version), 'checker'
                FROM app.cfg_version
                """.trimIndent(),
            )
        }
    }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private suspend fun HttpClient.token(user: String = "sr1001"): String {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"$user","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
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
        assertEquals(HttpStatusCode.OK, status, "bundle status: " + (if (status == HttpStatusCode.OK) "" else bodyAsText()))
        return json(GZIPInputStream(bodyAsBytes().inputStream()).readBytes().toString(Charsets.UTF_8))
    }

    private fun JsonObject.version(): String = this["meta"]!!.jsonObject["bundle_version"]!!.jsonPrimitive.content
    private fun JsonObject.routeByCode(code: String): JsonObject =
        this["routes"]!!.jsonArray.map { it.jsonObject }.first { it["route"]!!.jsonObject["code"]!!.jsonPrimitive.content == code }

    private fun userId(name: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h ->
        h.createQuery("SELECT id FROM app.app_user WHERE username = :u").bind("u", name).mapTo(Long::class.java).one()
    }

    // ---- ConfigValueJson (contract/slices/schemas/ConfigValueJson.yaml) ----
    private fun isScalar(e: JsonElement): Boolean = e is JsonPrimitive && (!e.isString || e.content.length <= 2000)
    private fun isNumberOrString(e: JsonElement, maxLen: Int): Boolean =
        e is JsonPrimitive && e !is JsonNull && (if (e.isString) e.content.length <= maxLen else e.booleanOrNull == null)

    private fun matchesConfigValueJson(v: JsonElement): Boolean = when (v) {
        is JsonNull -> false
        is JsonPrimitive -> !v.isString || v.content.length <= 4000
        is JsonArray -> v.size <= 500 && v.all { i -> isNumberOrString(i, 200) || (i is JsonObject && i.values.all(::isScalar)) }
        is JsonObject -> v.values.all { x ->
            isScalar(x) || (x is JsonArray && x.size <= 200 && x.all(::isScalar)) || (x is JsonObject && x.values.all(::isScalar))
        }
    }

    /**
     * DEFECT 1 (contract): `config.values[].value` must match ConfigValueJson. Two device keys
     * (`cfg.sync.reconcile_types`, `cfg.bundle.outlet_fields`) are shipped as object -> object -> array, a nesting the
     * schema does not allow, so a phone decoding with the generated contract types rejects the whole bundle.
     */
    @Test
    @Order(1)
    fun everyConfigValueInTheBundleMatchesConfigValueJson() = testApplication {
        now.set(Instant.parse("2027-01-05T02:00:00Z"))
        application { aronApi(wiring) }
        val b = client.bundle(client.token()).gunzipJson()
        val cfg = b["config"]!!.jsonObject
        val bad = (cfg["values"]!!.jsonArray + cfg["scheduled"]!!.jsonArray).map { it.jsonObject }
            .filterNot { matchesConfigValueJson(it["value"]!!) }.map { it["key"]!!.jsonPrimitive.content }
        assertEquals(emptyList(), bad, "config values outside ConfigValueJson")
    }

    /**
     * DEFECT 2 (versioning): `bundle_version` is `<date>:<snapshot_seq>` and the phone pulls a delta when
     * `X-Bundle-Version-Current` is NEWER than its own (s4.10). The seq is a content hash: after A -> B -> A the
     * server announces the first version again, so a phone holding B can neither tell it is older nor newer, and a
     * later snapshot reuses an earlier snapshot's version.
     */
    @Test
    @Order(2)
    fun aLaterSnapshotNeverReusesAnEarlierVersionAndTheSeqGrows() = testApplication {
        // Needs app.bundle_snapshot (docs/requests/backend-bundle-snapshot-table.md); runs as soon as the db lane ships it.
        org.junit.jupiter.api.Assumptions.assumeTrue(fresh.db.jdbi.withHandle<Boolean, Exception> { h ->
            h.createQuery("SELECT to_regclass('app.bundle_snapshot') IS NOT NULL").mapTo(Boolean::class.java).one()
        }, "app.bundle_snapshot not migrated yet")
        now.set(Instant.parse("2027-01-06T02:00:00Z"))
        application { aronApi(wiring) }
        val token = client.token()
        fun rename(to: String) = fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("UPDATE app.outlet SET name = '$to' WHERE id = (SELECT min(o.id) FROM app.outlet o JOIN app.route r ON r.id = o.route_id WHERE r.code = 'MIR-SR-D')")
        }
        val original = fresh.db.jdbi.withHandle<String, Exception> { h ->
            h.createQuery("SELECT name FROM app.outlet WHERE id = (SELECT min(o.id) FROM app.outlet o JOIN app.route r ON r.id = o.route_id WHERE r.code = 'MIR-SR-D')").mapTo(String::class.java).one()
        }
        val v1 = client.bundle(token).gunzipJson().version()
        rename("Checker renamed outlet")
        val v2 = client.bundle(token).gunzipJson().version()
        rename(original)
        val v3 = client.bundle(token).gunzipJson().version()
        assertNotEquals(v1, v2)
        assertNotEquals(v1, v3, "snapshot 3 must not reuse snapshot 1's version (v1=$v1 v2=$v2 v3=$v3)")
        val seq = listOf(v1, v2, v3).map { it.substringAfter(':').toLong() }
        assertTrue(seq[0] < seq[1] && seq[1] < seq[2], "snapshot_seq must grow with each new snapshot: $seq")
    }

    /**
     * DEFECT 3 (calendar / planning): `cfg.calendar.weekend_days` is scoped (global, wing, division). The bundle's
     * `calendar.weekend_days` is resolved for the user's chain, but `planned_today` and `target_outlets` come from
     * SqlRoutePlanner, which reads only the GLOBAL weekend. A division weekend on Sunday ships a bundle that says
     * "Sunday is the weekend" and still plans and targets the routes on that Sunday.
     */
    @Test
    @Order(3)
    fun aDivisionWeekendIsHonouredByPlannedTodayAndTargets() = testApplication {
        // Sunday 2027-02-07; the division of the SR's home zone has Sunday (ISO 7) as weekend for that week.
        now.set(Instant.parse("2027-02-07T02:00:00Z"))
        commitCfg(
            "cfg.calendar.weekend_days", "'division'",
            "(SELECT t.division_id FROM app.app_user u JOIN app.zone z ON z.id = u.home_zone_id JOIN app.territory t ON t.id = z.territory_id WHERE u.username = 'sr1001')",
            "[7]", "TIMESTAMPTZ '2027-02-01 00:00:00+00'", "TIMESTAMPTZ '2027-02-08 00:00:00+00'",
        )
        application { aronApi(wiring) }
        val b = client.bundle(client.token()).gunzipJson()
        val weekend = b["calendar"]!!.jsonObject["weekend_days"]!!.jsonArray.map { it.jsonPrimitive.int }
        assertEquals(listOf(7), weekend, "the division's weekend reaches the bundle")
        val routes = b["routes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(emptyList(), routes.filter { it["planned_today"]!!.jsonPrimitive.boolean }.map { it["route"]!!.jsonObject["code"]!!.jsonPrimitive.content },
            "no route may be planned on a day the bundle itself calls a weekend")
        assertEquals(0, routes.sumOf { it["target_outlets"]!!.jsonPrimitive.int })
    }

    /**
     * DEFECT 4 (day state / version gate, cover): when the primary SR fetched first, the route-day already has a
     * frozen target, so the cover holder's first bundle never records `acting_user_id` (the ON CONFLICT update is
     * guarded by `target_outlets IS NULL`). Consequence: the cover SR, already logged in on that date, gets 426 on
     * his next bundle once the minimum build is above his, i.e. an open day is cut off (s3.7: 426 only for a NEW date).
     */
    @Test
    @Order(4)
    fun aCoverHolderIsRecordedAsActingAndKeepsHisOpenDayBehindTheVersionGate() = testApplication {
        val tuesday = LocalDate.parse("2027-01-12")
        now.set(Instant.parse("2027-01-12T02:00:00Z"))
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                """
                INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, valid_to, reason)
                SELECT r.id, (SELECT id FROM app.app_user WHERE username = 'sr2002'), 'cover', DATE '2027-01-12', DATE '2027-01-13', 'checker cover'
                FROM app.route r WHERE r.code = 'MIR-SR-D'
                """.trimIndent(),
            )
        }
        application { aronApi(wiring) }
        // The primary opens the day first.
        client.bundle(client.token("sr1001")).gunzipJson()
        // The cover holder opens his day with a current build.
        val coverToken = client.token("sr2002")
        val cb = client.bundle(coverToken).gunzipJson()
        val covered = cb.routeByCode("MIR-SR-D")
        assertEquals("cover", covered["assignment_kind"]!!.jsonPrimitive.content)
        val acting = fresh.db.jdbi.withHandle<Long?, Exception> { h ->
            h.createQuery("SELECT d.acting_user_id FROM app.route_day d JOIN app.route r ON r.id = d.route_id WHERE r.code = 'MIR-SR-D' AND d.business_date = :d")
                .bind("d", tuesday).mapTo(Long::class.javaObjectType).one()
        }
        val secondTry = client.bundle(coverToken, version = "1.0.0+1")
        val problems = listOfNotNull(
            "route_day.acting_user_id = $acting, expected the cover holder ${userId("sr2002")}".takeIf { acting != userId("sr2002") },
            "the cover SR already logged in today but his old build got ${secondTry.status} (open day must finish, s3.7)".takeIf { secondTry.status != HttpStatusCode.OK },
        )
        assertEquals(emptyList(), problems)
    }

    /**
     * DEFECT 5 (s12.4 / s4.9, disputable): `target_outlets` is frozen by the PRE-FETCH of the evening before, not
     * by the first bundle of the day. An outlet added after the pre-fetch is in Sunday's outlets but not in Sunday's
     * frozen target, although the pre-fetch "never counts" and the KPI is "frozen at each route's first bundle of
     * the day".
     */
    @Test
    @Order(5)
    fun thePrefetchDoesNotFreezeTheTargetOfTheNextDay() = testApplication {
        // Saturday 2027-01-16 18:00 Dhaka: the phone pre-fetches Sunday.
        now.set(Instant.parse("2027-01-16T12:00:00Z"))
        application { aronApi(wiring) }
        val token = client.token()
        val pre = client.bundle(token, "?for=2027-01-17").gunzipJson()
        assertEquals(true, pre["meta"]!!.jsonObject["is_prefetch"]!!.jsonPrimitive.boolean)
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                """
                INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel)
                SELECT 'CHK-NEW-1', 'Checker outlet', 'Owner', r.zone_id, r.id, (SELECT id FROM app.cluster WHERE zone_id = r.zone_id LIMIT 1), 'GT'
                FROM app.route r WHERE r.code = 'MIR-SR-D'
                """.trimIndent(),
            )
        }
        // Sunday 08:00 Dhaka: the first real bundle of the day.
        now.set(Instant.parse("2027-01-17T02:00:00Z"))
        val day = client.bundle(client.token()).gunzipJson().routeByCode("MIR-SR-D")
        assertEquals(day["outlets"]!!.jsonArray.size, day["target_outlets"]!!.jsonPrimitive.int,
            "target = active outlets of the planned route at the first bundle OF THE DAY")
    }
    /**
     * PROBE (passes on a correct build): per-outlet radius precedence, s9.2 (outlet > route > zone > geo_class >
     * territory ...), ignoring expired and future rows.
     */
    @Test
    @Order(6)
    fun theOutletRadiusFollowsPrecedenceAndIgnoresExpiredAndFutureRows() = testApplication {
        now.set(Instant.parse("2027-01-24T02:00:00Z"))
        val outletIds = fresh.db.jdbi.withHandle<List<Long>, Exception> { h ->
            h.createQuery("SELECT o.id FROM app.outlet o JOIN app.route r ON r.id = o.route_id WHERE r.code = 'MIR-SR-D' AND o.status = 'active' ORDER BY o.id LIMIT 3")
                .mapTo(Long::class.java).list()
        }
        val (a, b, c) = outletIds
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.outlet SET geo_class = 'Urban' WHERE id = $a") }
        val from = "TIMESTAMPTZ '2027-01-20 00:00:00+00'"
        commitCfg("cfg.geo.radius_m", "'geo_class'", "(SELECT ordinal FROM app.geo_class_def WHERE geo_class = 'Urban')", "300", from, null)
        commitCfg("cfg.geo.radius_m", "'zone'", "(SELECT zone_id FROM app.route WHERE code = 'MIR-SR-D')", "150", from, null)
        commitCfg("cfg.geo.radius_m", "'outlet'", "$b", "250", from, "TIMESTAMPTZ '2027-01-23 00:00:00+00'")
        commitCfg("cfg.geo.radius_m", "'outlet'", "$c", "60", "TIMESTAMPTZ '2027-01-25 00:00:00+00'", null)
        commitCfg("cfg.geo.radius_m", "'route'", "(SELECT id FROM app.route WHERE code = 'MIR-SR-3F')", "120", from, null)
        application { aronApi(wiring) }
        val bundle = client.bundle(client.token()).gunzipJson()
        val radius = bundle["routes"]!!.jsonArray.flatMap { it.jsonObject["outlets"]!!.jsonArray }.map { it.jsonObject }
            .associate { it["outlet_id"]!!.jsonPrimitive.content.toLong() to it["radius_m"]!!.jsonPrimitive.int }
        assertEquals(listOf(150, 150, 150), listOf(radius[a], radius[b], radius[c]), "zone beats geo_class; expired and future outlet rows ignored")
        val r3f = bundle.routeByCode("MIR-SR-3F")["outlets"]!!.jsonArray.map { it.jsonObject["radius_m"]!!.jsonPrimitive.int }.toSet()
        assertEquals(setOf(120), r3f, "route beats zone")
    }
}
