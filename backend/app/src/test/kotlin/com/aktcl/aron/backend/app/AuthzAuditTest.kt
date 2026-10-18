package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.analytics.Aggregator
import com.aktcl.aron.backend.platform.AronClock
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Authorisation audit (qa lane, 2026-10-18) of the admin and data-entry routes of the last build days: enrolment tokens,
 * config changes and versions, dues adjustments, price batches, web entry and QC entry, paper backfill (data entry),
 * entry unlocks and the drill-down reads (report queries, login/submit and sync-health dashboards); `/v1/admin/releases`
 * is in the contract but not served (probed: every role must get 404).
 *
 * Every seeded role calls every route (SR on its phone; AMO, TSO, DMO, WM, TOP, ANALYST, SUPPORT, ADMIN, SUPERADMIN on
 * the web; WM, TOP and ANALYST are inserted here, the others come from db/seed) in a world with a mirrored second
 * division (ApiWorld, `LKX`). Six checks, one table row per probe:
 * - ROLE: a role outside docs/24 s8.5 (and outside the code's own role set) is answered 2xx;
 * - IDOR: the other division's ids (route, zone, outlet, user, node, record row ids) in a path, query or body of a
 *   scoped caller are answered with its data or acted on;
 * - MASS / STRICT: a body field the caller may not set (status, decided_by, created_by, version, ...) is accepted or
 *   stored; an unknown field is accepted (the API decodes strictly);
 * - STEPUP: an admin token without the second factor (`amr` pwd) works while `cfg.auth.mfa_required_roles` names the
 *   role (the policy is switched on in this test's own database: ApiWorld turns MFA off for its logins);
 * - PII: a phone number, NID, owner name, address or full name appears in an answer (no token carries the `pii` claim);
 * - LIMIT: a `limit` or `page_size` outside the contract (0, negative, above the maximum, huge, not a number) is
 *   served, a contract-valid one is refused, or a missing one does not get the contract default.
 *
 * Verdicts: FINDING (a defect; its key must be in `authz-audit-baseline.txt`, which may only shrink), QUESTION (the
 * code and the spec disagree or the spec is silent; routed as a question, never fails the test), NOTE (context).
 * Report with every probe, status and evidence: build/authz-audit.txt. Secrets (enrolment token, QR) are never written.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthzAuditTest {
    private lateinit var world: ApiWorld
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private val day = ApiWorld.BUSINESS_DATE
    private val now = AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
    private val clock = AronClock { now.get() }

    enum class Verdict { OK, FINDING, QUESTION, NOTE }

    data class Row(val check: String, val route: String, val role: String, val variant: String, val request: String, val status: Int, val verdict: Verdict, val detail: String) {
        val key: String get() = "$check $route $role $variant"
    }

    private val rows = mutableListOf<Row>()
    private val failures = mutableListOf<String>()   // positive controls that did not hold (a broken harness, not a finding)

    /** Role name to caller (SR on its phone). */
    private val callers = linkedMapOf(
        "SR" to ApiWorld.Caller("sr1001", "app_sr", ApiWorld.SR_PHONE),
        "AMO" to ApiWorld.Caller("amo1001", "web", null),
        "TSO" to ApiWorld.Caller("tso1001", "web", null),
        "DMO" to ApiWorld.Caller("dmo1001", "web", null),
        "WM" to ApiWorld.Caller("qawm1001", "web", null),
        "TOP" to ApiWorld.Caller("qatop1001", "web", null),
        "ANALYST" to ApiWorld.Caller("qaanalyst1001", "web", null),
        "SUPPORT" to ApiWorld.Caller("support1001", "web", null),
        "ADMIN" to ApiWorld.Caller("admin1001", "web", null),
        "SUPERADMIN" to ApiWorld.Caller("superadmin1001", "web", null),
    )
    private val roles get() = callers.keys.toList()
    private val tokens = HashMap<String, String>()
    private var super2Token = ""

    // own division (Z-MIR) and the other (Z-LKX)
    private var zone = 0L; private var route = 0L; private var outlet = 0L; private var territory = 0L; private var division = 0L; private var srId = 0L
    private var oZone = 0L; private var oRoute = 0L; private var oOutlet = 0L; private var oTerritory = 0L; private var oDivision = 0L; private var oSrId = 0L
    private var sku = 0L; private var skuPrice = 0L; private var tokenId = 0L; private var ownAdjustment = 0L; private var oAdjustment = 0L; private var ownUnlock = 0L; private var oUnlock = 0L
    private var adminId = 0L; private var superId = 0L; private var faultCode = ""
    private var ownVersion = 0L
    private val memoSeq = AtomicInteger(900)

    @BeforeAll
    fun setUp() {
        world = ApiWorld(repoRoot, mirrors = listOf(ApiWorld.OTHER), clock = clock)
        seed()
        testApplication {
            application { aronApi(world.wiring) }
            for ((role, c) in callers) tokens[role] = world.login(client, c) ?: error("no token for $role (${c.user})")
            super2Token = world.login(client, ApiWorld.Caller("qasuper2", "web", null)) ?: error("no token for qasuper2")
            client.positiveControls()
            client.roleMatrix()
            client.reportsAndDashboards()
            client.idor()
            client.massAssignment()
            client.limits()
            client.stepUp()
            client.piiRule()
            notes()
        }
        writeReport()
    }

    @AfterAll
    fun tearDown() { world.close() }

    // ---- world ------------------------------------------------------------------------------------------------------

    private fun seed() = world.fresh.db.jdbi.useHandle<Exception> { h ->
        fun id(sql: String) = h.createQuery(sql).mapTo(Long::class.java).one()
        zone = id("SELECT id FROM app.zone WHERE code = 'Z-MIR'"); oZone = id("SELECT id FROM app.zone WHERE code = 'Z-LKX'")
        territory = id("SELECT territory_id FROM app.zone WHERE id = $zone"); oTerritory = id("SELECT territory_id FROM app.zone WHERE id = $oZone")
        division = id("SELECT division_id FROM app.territory WHERE id = $territory"); oDivision = id("SELECT division_id FROM app.territory WHERE id = $oTerritory")
        route = id("SELECT id FROM app.route WHERE code = 'MIR-SR-D'"); oRoute = id("SELECT id FROM app.route WHERE code = 'LKX-SR-D'")
        outlet = id("SELECT min(id) FROM app.outlet WHERE route_id = $route AND price_type = 'outlet'"); oOutlet = id("SELECT min(id) FROM app.outlet WHERE route_id = $oRoute AND price_type = 'outlet'")
        srId = id("SELECT id FROM app.app_user WHERE username = 'sr1001'"); oSrId = id("SELECT id FROM app.app_user WHERE username = 'lkxsr1001'")
        adminId = id("SELECT id FROM app.app_user WHERE username = 'admin1001'"); superId = id("SELECT id FROM app.app_user WHERE username = 'superadmin1001'")
        h.createQuery("SELECT s.id, p.amount_mtk FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' AND p.valid_to IS NULL WHERE p.amount_mtk > 0 ORDER BY s.id LIMIT 1")
            .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.one().let { sku = it.first; skuPrice = it.second }
        // The roles db/seed does not have: WM (the wing both divisions share), TOP and ANALYST (national), a second SUPERADMIN (checker).
        for ((u, role) in listOf("qawm1001" to "WM", "qatop1001" to "TOP", "qaanalyst1001" to "ANALYST", "qasuper2" to "SUPERADMIN")) {
            h.execute(
                "INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password, password_hash) " +
                    "SELECT '$u', 'QA $role', '$role', 'QA', upper('$u'), 'en', home_zone_id, true, false, password_hash FROM app.app_user WHERE username = 'admin1001'",
            )
            val node = if (role == "WM") "'wing', (SELECT wing_id FROM app.division WHERE id = $division)" else "'national', 0"
            h.execute("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, reason) SELECT id, $node, DATE '2026-01-01', 'qa authz audit' FROM app.app_user WHERE username = '$u'")
        }
        // PII markers: every user gets a phone (the seed gives outlets phones, owners and addresses; the outlet NID is envelope-encrypted, V0049).
        h.execute("UPDATE app.app_user SET phone = '0191' || lpad(id::text, 7, '0')")
        // A published SR release (enrolment tokens need one) and 120 tokens (the default page is 100).
        h.execute(
            "INSERT INTO app.app_release (flavour, version_name, version_code, abi, sha256, size_bytes, download_url, signing_cert_sha256, status, created_by, published_at, published_by) " +
                "VALUES ('sr', '1.0.9', 9, 'universal', sha256('qa-release'::bytea), 1000, 'https://example.invalid/sr.apk', sha256('qa-cert'::bytea), 'published', $adminId, now(), $superId)",
        )
        h.execute("INSERT INTO app.enrolment_token (token_sha256, token_prefix, flavour, lockdown_level, max_uses, expires_at, created_by, note) SELECT sha256(('qa-token-' || g)::bytea), 'qaqaqa', 'sr', 'prod', 1, now() + interval '7 days', $adminId, 'qa page row' FROM generate_series(1, 120) g")
        tokenId = id("SELECT max(id) FROM app.enrolment_token")
        // 120 dues adjustments and entry unlocks of each division's own zone (pages), one of each in the other division.
        h.execute("INSERT INTO app.dues_adjustment (client_uuid, outlet_id, kind, amount_mtk, reason, created_by) SELECT gen_random_uuid(), $outlet, 'correction', 1000 + g, 'qa page row ' || g, $adminId FROM generate_series(1, 120) g")
        ownAdjustment = id("SELECT max(id) FROM app.dues_adjustment")
        h.execute("INSERT INTO app.dues_adjustment (client_uuid, outlet_id, kind, amount_mtk, reason, created_by) VALUES (gen_random_uuid(), $oOutlet, 'correction', 777, 'LKX adjustment for the audit', $adminId)")
        oAdjustment = id("SELECT max(id) FROM app.dues_adjustment")
        h.execute("INSERT INTO app.entry_unlock (scope_type, scope_id, from_date, to_date, reason, expires_at, created_by) SELECT 'zone', $zone, DATE '$day' - 1, DATE '$day' - 1, 'qa page row ' || g, now() + interval '400 days', $adminId FROM generate_series(1, 120) g")
        ownUnlock = id("SELECT max(id) FROM app.entry_unlock")
        h.execute("INSERT INTO app.entry_unlock (scope_type, scope_id, from_date, to_date, reason, expires_at, created_by) VALUES ('zone', $oZone, DATE '$day' - 1, DATE '$day' - 1, 'LKX unlock for the audit', now() + interval '400 days', $adminId)")
        oUnlock = id("SELECT max(id) FROM app.entry_unlock")
        h.execute("SET session_replication_role = replica")
        try {
            h.execute("INSERT INTO app.web_entry_route_day (client_uuid, route_id, business_date, successful_calls, target_outlets, entered_by) VALUES (gen_random_uuid(), $oRoute, '$day'::date, 3, 9, $oSrId)")
        } finally { h.execute("SET session_replication_role = origin") }
        ownVersion = id("SELECT max(config_version) FROM app.cfg_version")
        faultCode = h.createQuery("SELECT min(code) FROM app.code_list_item WHERE list_key = 'qc_fault_type' AND valid_from <= DATE '$day' AND (valid_to IS NULL OR valid_to > DATE '$day')").mapTo(String::class.java).one()
        Aggregator.refreshDimensions(h)
    }

    // ---- http -------------------------------------------------------------------------------------------------------

    class Hit(val status: Int, val body: String, val setCookies: List<String> = emptyList()) {
        val ok get() = status in 200..299
        val json: JsonElement? get() = runCatching { Json.parseToJsonElement(body) }.getOrNull()
        fun items(): Int? = (json as? JsonObject)?.let { (it["items"] ?: it["rows"]) as? JsonArray }?.size
        fun code(): String = (json as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull ?: ""
    }

    private suspend fun HttpClient.hit(role: String, method: HttpMethod, path: String, body: String? = null, token: String? = tokens[role], cookie: String? = null): Hit {
        val r = request(path) {
            this.method = method
            header("X-App-Version", ApiWorld.APP_VERSION)
            if (token != null) header("Authorization", "Bearer $token")
            if (role == "SR") header("X-Device-Id", ApiWorld.SR_PHONE)
            if (cookie != null) header("Cookie", cookie)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return Hit(r.status.value, r.bodyAsText(), r.headers.getAll("Set-Cookie") ?: emptyList())
    }

    private fun uuid() = UUID.randomUUID().toString()
    private fun obj(vararg kv: Pair<String, Any?>): JsonObject = buildJsonObject { kv.forEach { (k, v) -> put(k, js(v)) } }
    private fun js(v: Any?): JsonElement = when (v) {
        null -> kotlinx.serialization.json.JsonNull; is JsonElement -> v; is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); is String -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { js(it) }); else -> error("json of $v")
    }
    private fun JsonObject.with(k: String, v: Any?) = JsonObject(this + (k to js(v)))

    /** Never write an enrolment secret or a provisioning QR into the report. */
    private fun redact(path: String, body: String): String =
        if (path.startsWith("/v1/admin/enrolment-tokens") && !path.endsWith("/revoke") && body.contains("\"token\"")) "<enrolment secret redacted>"
        else body.replace(Regex("\"(access_token|refresh_token|mfa_token|upload_refresh_token|token|qr_json)\"\\s*:\\s*\"[^\"]*\""), "\"$1\":\"<redacted>\"")

    private fun record(check: String, route: String, role: String, variant: String, method: HttpMethod, path: String, body: String?, hit: Hit?, verdict: Verdict, detail: String) {
        val req = "${method.value} $path" + (body?.let { " " + it.take(300) } ?: "")
        rows += Row(check, route, role, variant, req, hit?.status ?: 0, verdict, detail + (hit?.let { " | answer: " + redact(path, it.body).take(300) } ?: ""))
    }

    // ---- request bodies (own division, valid for an allowed role) -------------------------------------------------

    private fun tokenBody() = obj("flavour" to "sr", "lockdown_level" to "prod", "max_uses" to 1, "expires_in_h" to 1, "zone_id" to zone, "note" to "qa authz audit")
    private fun cfgBody(scopeType: String = "zone", scopeId: Long = zone, value: Any = 120, key: String = "cfg.geo.radius_m") =
        obj("reason" to "qa authz audit change", "changes" to listOf(obj("key" to key, "scope_type" to scopeType, "scope_id" to scopeId, "value" to value)))
    private fun dueBody(o: Long = outlet) = obj("client_uuid" to uuid(), "outlet_id" to o, "kind" to "correction", "amount_mtk" to 1500, "reason" to "qa authz audit adjustment")
    private fun priceBody(batch: String = uuid(), amount: Long = skuPrice + 10, from: String = "2027-01-20") =
        obj("batch_uuid" to batch, "valid_from" to from, "prices" to listOf(obj("sku_id" to sku, "price_type" to "outlet", "amount_mtk" to amount, "per_base_qty" to 1)), "change_reason" to "qa authz audit price change")
    private fun routeDayBody(r: Long = route) = obj("client_uuid" to uuid(), "route_id" to r, "business_date" to day, "lines" to listOf(obj("sku_id" to sku, "issue_qty_base" to 20, "return_qty_base" to 0, "memo_count" to 1)), "successful_calls" to 0, "change_reason" to "qa authz audit re-entry")
    private fun qcBody(z: Long = zone, r: Long? = route) = obj("client_uuid" to uuid(), "source" to "market", "business_date" to day, "zone_id" to z, "rows" to listOf(obj("sku_id" to sku, "fault_type_code" to faultCode, "qty_base" to 1)), "route_id" to r)
    private fun paperBody(user: Long = srId, username: String = "sr1001", r: Long = route, o: Long = outlet) =
        obj("client_uuid" to uuid(), "memo_no" to "$username-270103-${memoSeq.incrementAndGet()}", "business_date" to day, "user_id" to user, "route_id" to r, "outlet_id" to o,
            "lines" to listOf(obj("sku_id" to sku, "qty_base" to 10, "line_kind" to "sale")), "paid_mtk" to 0, "reason" to "qa authz audit paper memo")
    private fun unlockBody(z: Long = zone) = obj("scope_type" to "zone", "scope_id" to z, "from" to "2027-01-02", "to" to "2027-01-02", "reason" to "qa authz audit unlock")
    private fun reportBody(geo: JsonObject? = null, pageSize: Any = 500) =
        JsonObject(mapOf("period" to obj("date" to day), "output" to obj("format" to "json", "page_size" to pageSize)) + (geo?.let { mapOf("geo" to it) } ?: emptyMap()))

    // ---- positive controls ------------------------------------------------------------------------------------------

    private var pendingBatch = ""
    private var adminChange = 0L
    private var c3Change = 0L

    private suspend fun HttpClient.control(what: String, role: String, method: HttpMethod, path: String, body: String?, want: Set<Int>, token: String? = tokens[role]): Hit {
        val h = hit(role, method, path, body, token)
        if (h.status !in want) failures += "positive control '$what' ($role ${method.value} $path): ${h.status} ${redact(path, h.body).take(300)}"
        record("CONTROL", path, role, what, method, path, body, h, Verdict.NOTE, "positive control, expected $want")
        return h
    }

    private suspend fun HttpClient.positiveControls() {
        val p = HttpMethod.Post; val g = HttpMethod.Get
        control("token", "SUPPORT", p, "/v1/admin/enrolment-tokens", tokenBody().toString(), setOf(201))
        control("dues", "ADMIN", p, "/v1/admin/dues-adjustments", dueBody().toString(), setOf(201))
        val batch = uuid()
        control("preview", "ADMIN", p, "/v1/admin/prices/preview", priceBody(batch).toString(), setOf(200))
        control("publish", "ADMIN", p, "/v1/admin/prices", priceBody(batch).toString(), setOf(201))
        // A batch above cfg.price.max_change_pct waits for a checker: the decision probes use it.
        pendingBatch = uuid()
        control("preview-big", "ADMIN", p, "/v1/admin/prices/preview", priceBody(pendingBatch, skuPrice * 5, "2027-01-21").toString(), setOf(200))
        control("publish-big", "ADMIN", p, "/v1/admin/prices", priceBody(pendingBatch, skuPrice * 5, "2027-01-21").toString(), setOf(201))
        adminChange = control("cfg", "ADMIN", p, "/v1/admin/config/changes", cfgBody().toString(), setOf(201)).json?.jsonObject?.get("change_id")?.jsonPrimitive?.content?.toLong() ?: 0
        // A change on the other division's zone, so a TSO's config reads can be judged.
        control("cfg-lkx", "ADMIN", p, "/v1/admin/config/changes", cfgBody("zone", oZone, 130).toString(), setOf(201))
        // A class C3 change (outlet radius above 3 x its zone) waits for a second SUPERADMIN.
        c3Change = control("cfg-c3", "SUPERADMIN", p, "/v1/admin/config/changes", cfgBody("outlet", outlet, 900).toString(), setOf(201)).json?.jsonObject?.get("change_id")?.jsonPrimitive?.content?.toLong() ?: 0
        control("route-day", "TSO", p, "/v1/web-entry/route-day", routeDayBody().toString(), setOf(200))
        control("qc", "TSO", p, "/v1/web-entry/qc", qcBody().toString(), setOf(200))
        control("paper", "ADMIN", p, "/v1/admin/data-entry", paperBody().toString(), setOf(200))
        control("unlock", "SUPPORT", p, "/v1/admin/entry-unlocks", unlockBody().toString(), setOf(201))
        control("reports", "ADMIN", g, "/v1/reports", null, setOf(200))
    }

    // ---- ROLE: every route x every role ------------------------------------------------------------------------------

    /** One route of the audit: who may call it (docs/24 s8.5 as the code implements it), and who docs/24 also names (a question when refused). */
    class RouteSpec(val name: String, val method: HttpMethod, val path: () -> String, val body: (() -> String)?, val allowed: Set<String>, val docsAlso: Set<String> = emptySet(), val writeSkipAllowed: Boolean = false)

    private val all = setOf("SR", "AMO", "TSO", "DMO", "WM", "TOP", "ANALYST", "SUPPORT", "ADMIN", "SUPERADMIN")
    private val cfgReaders = setOf("TSO", "DMO", "WM", "TOP", "ANALYST", "SUPPORT", "ADMIN", "SUPERADMIN")
    private val masterReaders = cfgReaders
    private val dashboardRoles = all - setOf("SR", "AMO", "SUPPORT")

    private fun specs(): List<RouteSpec> {
        val g = HttpMethod.Get; val p = HttpMethod.Post
        return listOf(
            RouteSpec("GET /v1/admin/enrolment-tokens", g, { "/v1/admin/enrolment-tokens" }, null, setOf("SUPPORT", "ADMIN", "SUPERADMIN"), docsAlso = setOf("DMO", "WM")),
            RouteSpec("POST /v1/admin/enrolment-tokens", p, { "/v1/admin/enrolment-tokens" }, { tokenBody().toString() }, setOf("SUPPORT", "ADMIN", "SUPERADMIN"), writeSkipAllowed = true),
            RouteSpec("POST /v1/admin/enrolment-tokens/{}/revoke", p, { "/v1/admin/enrolment-tokens/$tokenId/revoke" }, null, setOf("SUPPORT", "ADMIN", "SUPERADMIN"), writeSkipAllowed = true),
            RouteSpec("GET /v1/admin/config/keys", g, { "/v1/admin/config/keys" }, null, cfgReaders),
            RouteSpec("GET /v1/admin/config/values", g, { "/v1/admin/config/values?key=cfg.geo.radius_m" }, null, cfgReaders),
            RouteSpec("GET /v1/admin/config/resolve", g, { "/v1/admin/config/resolve?key=cfg.geo.radius_m&node_type=zone&node_id=$zone" }, null, cfgReaders),
            RouteSpec("GET /v1/admin/config/changes", g, { "/v1/admin/config/changes" }, null, cfgReaders),
            RouteSpec("GET /v1/admin/config/changes/{}", g, { "/v1/admin/config/changes/$adminChange" }, null, cfgReaders),
            RouteSpec("POST /v1/admin/config/changes", p, { "/v1/admin/config/changes" }, { cfgBody().toString() }, setOf("TSO", "ADMIN", "SUPERADMIN"), writeSkipAllowed = true),
            RouteSpec("POST /v1/admin/config/changes/{}/decision", p, { "/v1/admin/config/changes/$c3Change/decision" }, { obj("decision" to "approve").toString() }, setOf("ADMIN", "SUPERADMIN"), writeSkipAllowed = true),
            RouteSpec("POST /v1/admin/config/versions/{}/rollback", p, { "/v1/admin/config/versions/$ownVersion/rollback" }, { obj("mode" to "revert_this_version", "reason" to "qa authz audit rollback").toString() }, setOf("TSO", "ADMIN", "SUPERADMIN"), writeSkipAllowed = true),
            RouteSpec("GET /v1/admin/config/versions", g, { "/v1/admin/config/versions" }, null, cfgReaders),
            RouteSpec("GET /v1/admin/config/versions/{}", g, { "/v1/admin/config/versions/$ownVersion" }, null, cfgReaders),
            RouteSpec("GET /v1/admin/config/blast-radius", g, { "/v1/admin/config/blast-radius?scope_type=zone&scope_id=$zone" }, null, cfgReaders),
            RouteSpec("GET /v1/admin/config/reach/{}", g, { "/v1/admin/config/reach/$ownVersion?zone_id=$zone" }, null, cfgReaders),
            RouteSpec("GET /v1/admin/dues-adjustments", g, { "/v1/admin/dues-adjustments" }, null, setOf("ADMIN", "SUPERADMIN")),
            RouteSpec("POST /v1/admin/dues-adjustments", p, { "/v1/admin/dues-adjustments" }, { dueBody().toString() }, setOf("ADMIN", "SUPERADMIN"), writeSkipAllowed = true),
            RouteSpec("POST /v1/admin/dues-adjustments/{}/decision", p, { "/v1/admin/dues-adjustments/$ownAdjustment/decision" }, { obj("decision" to "approve").toString() }, setOf("SUPERADMIN"), writeSkipAllowed = true),
            RouteSpec("GET /v1/admin/prices", g, { "/v1/admin/prices" }, null, masterReaders),
            RouteSpec("POST /v1/admin/prices/preview", p, { "/v1/admin/prices/preview" }, { priceBody().toString() }, setOf("ADMIN", "SUPERADMIN"), writeSkipAllowed = true),
            RouteSpec("POST /v1/admin/prices", p, { "/v1/admin/prices" }, { priceBody().toString() }, setOf("ADMIN", "SUPERADMIN"), writeSkipAllowed = true),
            RouteSpec("POST /v1/admin/prices/batches/{}/decision", p, { "/v1/admin/prices/batches/$pendingBatch/decision" }, { obj("decision" to "approve").toString() }, setOf("SUPERADMIN"), writeSkipAllowed = true),
            RouteSpec("GET /v1/web-entry/route-day", g, { "/v1/web-entry/route-day?route_id=$route&business_date=$day" }, null, setOf("TSO", "ADMIN", "SUPERADMIN")),
            RouteSpec("POST /v1/web-entry/route-day", p, { "/v1/web-entry/route-day" }, { routeDayBody().toString() }, setOf("TSO", "ADMIN", "SUPERADMIN"), writeSkipAllowed = true),
            RouteSpec("POST /v1/web-entry/qc", p, { "/v1/web-entry/qc" }, { qcBody().toString() }, setOf("TSO", "ADMIN", "SUPERADMIN"), writeSkipAllowed = true),
            RouteSpec("POST /v1/admin/data-entry", p, { "/v1/admin/data-entry" }, { paperBody().toString() }, setOf("SUPPORT", "ADMIN", "SUPERADMIN"), writeSkipAllowed = true),
            RouteSpec("GET /v1/admin/entry-unlocks", g, { "/v1/admin/entry-unlocks" }, null, setOf("SUPPORT", "ADMIN", "SUPERADMIN")),
            RouteSpec("POST /v1/admin/entry-unlocks", p, { "/v1/admin/entry-unlocks" }, { unlockBody().toString() }, setOf("SUPPORT", "ADMIN", "SUPERADMIN"), writeSkipAllowed = true),
            RouteSpec("POST /v1/admin/entry-unlocks/{}/expire", p, { "/v1/admin/entry-unlocks/$ownUnlock/expire" }, null, setOf("SUPPORT", "ADMIN", "SUPERADMIN"), writeSkipAllowed = true),
            RouteSpec("GET /v1/dashboards/login-submit", g, { "/v1/dashboards/login-submit?business_date=$day" }, null, dashboardRoles),
            RouteSpec("GET /v1/dashboards/sync-health", g, { "/v1/dashboards/sync-health?business_date=$day" }, null, dashboardRoles + "SUPPORT"),
            // Everyone lists their own exports (ReportApi); the admin roles everyone's.
            RouteSpec("GET /v1/report-exports", g, { "/v1/report-exports" }, null, all),
            // Contract only: no role may reach an unserved release surface.
            RouteSpec("GET /v1/admin/releases", g, { "/v1/admin/releases" }, null, emptySet()),
            RouteSpec("POST /v1/admin/releases", p, { "/v1/admin/releases" }, { "{}" }, emptySet()),
        )
    }

    private suspend fun HttpClient.roleMatrix() {
        for (s in specs()) for (role in roles) {
            val path = s.path(); val body = s.body?.invoke()
            if (role in s.allowed && s.writeSkipAllowed) {
                record("ROLE", s.name, role, "allowed", s.method, path, body, null, Verdict.OK, "allowed write: exercised by the positive controls and the mass-assignment probes")
                continue
            }
            val h = hit(role, s.method, path, body)
            val v = when {
                role !in s.allowed && h.ok -> Verdict.FINDING
                role !in s.allowed -> Verdict.OK
                h.status == 403 && role in s.docsAlso -> Verdict.QUESTION
                h.status == 403 -> Verdict.NOTE
                else -> Verdict.OK
            }
            val why = when (v) {
                Verdict.FINDING -> "served to a role outside docs/24 s8.5"
                Verdict.QUESTION -> "docs/24 s8.5 gives $role R here; the code refuses"
                Verdict.NOTE -> "an allowed role was refused (rule, not role)"
                else -> if (role in s.allowed) "allowed" else "refused"
            }
            record("ROLE", s.name, role, if (role in s.allowed) "allowed" else "denied", s.method, path, body, h, v, why)
            if (s.name.startsWith("GET /v1/admin/releases") || s.name.startsWith("POST /v1/admin/releases")) {
                if (h.status != 404 && h.status != 401 && h.status != 403) record("ROLE", s.name, role, "unserved", s.method, path, body, h, Verdict.FINDING, "the contract-only release surface answered")
            }
        }
    }

    // ---- reports and dashboards (the drill-down reads) ----------------------------------------------------------------

    private val reportKeys = mutableListOf<String>()

    private suspend fun HttpClient.reportsAndDashboards() {
        val defs = hit("ADMIN", HttpMethod.Get, "/v1/reports").json!!.jsonObject["items"]!!.jsonArray.map { it.jsonObject }
        reportKeys += defs.map { it["report_key"]!!.jsonPrimitive.content }
        val supportServed = mutableListOf<String>(); val srServed = mutableListOf<String>()
        for (role in roles) {
            val listed = hit(role, HttpMethod.Get, "/v1/reports").json?.jsonObject?.get("items")?.jsonArray?.map { it.jsonObject["report_key"]!!.jsonPrimitive.content }?.toSet() ?: emptySet()
            for (key in reportKeys) {
                val path = "/v1/reports/$key/query"; val body = reportBody().toString()
                val h = hit(role, HttpMethod.Post, path, body)
                val v = when {
                    key !in listed && h.ok -> Verdict.FINDING
                    else -> Verdict.OK
                }
                if (h.ok && role == "SUPPORT") supportServed += key
                if (h.ok && role == "SR") srServed += key
                record("ROLE", "POST /v1/reports/{}/query", role, key, HttpMethod.Post, path, body, h, v, if (v == Verdict.FINDING) "served a report the role's own catalogue does not list" else if (h.ok) "served" else "refused")
                if (h.ok) piiScan("POST /v1/reports/{}/query", role, key, h, reportPii = true)
            }
        }
        // docs/24 s8.5: SUPPORT reads sync health only (the dashboards say so too: DASHBOARD_ROLES excludes SUPPORT).
        if (supportServed.isNotEmpty()) record("ROLE", "POST /v1/reports/{}/query", "SUPPORT", "business-reports", HttpMethod.Post, "/v1/reports/{key}/query", null, null, Verdict.FINDING,
            "SUPPORT runs ${supportServed.size} business reports (${supportServed.joinToString()}); docs/24 s8.5 gives SUPPORT 'R (sync health)' only, and DASHBOARD_ROLES excludes SUPPORT for the same reason; WEB_ROLES in ReportEngine.kt includes it")
        if (srServed.isNotEmpty()) record("ROLE", "POST /v1/reports/{}/query", "SR", "any-report", HttpMethod.Post, "/v1/reports/{key}/query", null, null, Verdict.FINDING, "SR runs ${srServed.joinToString()}")
    }

    // ---- IDOR: scoped callers with the other division's ids -------------------------------------------------------------

    private fun leaksOther(text: String) = text.contains("lkx", ignoreCase = true) ||
        Regex("\"(zone_id|route_id|outlet_id|node_id|scope_id|id)\"\\s*:\\s*($oZone|$oRoute|$oOutlet)\\b").containsMatchIn(text)

    private suspend fun HttpClient.idorProbe(route: String, role: String, variant: String, method: HttpMethod, path: String, body: String?, acted: (() -> Boolean)? = null, questionIfServed: String? = null) {
        val h = hit(role, method, path, body)
        val leak = h.ok && leaksOther(h.body)
        val didAct = acted?.invoke() == true
        val v = when {
            didAct -> Verdict.FINDING
            leak && questionIfServed != null -> Verdict.QUESTION
            leak -> Verdict.FINDING
            h.ok && method != HttpMethod.Get && questionIfServed == null -> Verdict.FINDING
            else -> Verdict.OK
        }
        val why = when {
            didAct -> "acted on the other division"
            leak && questionIfServed != null -> questionIfServed
            leak -> "answered with the other division's data"
            v == Verdict.FINDING -> "a write naming the other division was accepted"
            else -> "refused or nothing of the other division"
        }
        record("IDOR", route, role, variant, method, path, body, h, v, why)
    }

    private fun count(sql: String): Long = world.fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }

    private suspend fun HttpClient.idor() {
        val g = HttpMethod.Get; val p = HttpMethod.Post
        val tsoId = count("SELECT id FROM app.app_user WHERE username = 'tso1001'")
        // Web entry (TSO, own zones only).
        idorProbe("GET /v1/web-entry/route-day", "TSO", "other-route", g, "/v1/web-entry/route-day?route_id=$oRoute&business_date=$day", null)
        idorProbe("POST /v1/web-entry/route-day", "TSO", "other-route", p, "/v1/web-entry/route-day", routeDayBody(oRoute).toString(),
            acted = { count("SELECT count(*) FROM app.web_entry_route_day WHERE route_id = $oRoute AND entered_by = $tsoId") > 0 })
        idorProbe("POST /v1/web-entry/qc", "TSO", "other-zone", p, "/v1/web-entry/qc", qcBody(oZone, oRoute).toString(),
            acted = { count("SELECT count(*) FROM app.qc_summary_entry WHERE zone_id = $oZone AND entered_by = $tsoId") > 0 })
        idorProbe("POST /v1/web-entry/qc", "TSO", "own-zone-other-route", p, "/v1/web-entry/qc", qcBody(zone, oRoute).toString(),
            acted = { count("SELECT count(*) FROM app.qc_summary_entry WHERE route_id = $oRoute") > 0 })
        // Config (TSO radius proposals of own territory; the tool reads check reach).
        for ((type, id) in listOf("zone" to oZone, "route" to oRoute, "outlet" to oOutlet)) {
            idorProbe("POST /v1/admin/config/changes", "TSO", "other-$type", p, "/v1/admin/config/changes", cfgBody(type, id, 110).toString(),
                acted = { count("SELECT count(*) FROM app.cfg_change c WHERE c.requested_by = $tsoId AND c.items::text LIKE '%\"scope_id\": $id%'") > 0 })
        }
        for (role in listOf("TSO", "DMO")) {
            idorProbe("GET /v1/admin/config/blast-radius", role, "other-zone", g, "/v1/admin/config/blast-radius?scope_type=zone&scope_id=$oZone", null)
            idorProbe("GET /v1/admin/config/density", role, "other-zone", g, "/v1/admin/config/density?scope_type=zone&scope_id=$oZone", null)
            idorProbe("GET /v1/admin/config/reach/{}", role, "other-zone", g, "/v1/admin/config/reach/$ownVersion?zone_id=$oZone", null)
            idorProbe("GET /v1/admin/config/reach/{}/pending", role, "other-zone", g, "/v1/admin/config/reach/$ownVersion/pending?zone_id=$oZone", null)
            val q = "config values and change sets are read nationally by every config reader (no reach filter in ConfigService.values/changes/resolve); is a $role meant to read the other division's zone settings?"
            idorProbe("GET /v1/admin/config/values", role, "other-zone", g, "/v1/admin/config/values?key=cfg.geo.radius_m&scope_type=zone&scope_id=$oZone", null, questionIfServed = q)
            idorProbe("GET /v1/admin/config/resolve", role, "other-zone", g, "/v1/admin/config/resolve?key=cfg.geo.radius_m&node_type=zone&node_id=$oZone", null, questionIfServed = q)
            idorProbe("GET /v1/admin/config/changes", role, "list", g, "/v1/admin/config/changes", null, questionIfServed = q)
        }
        // Reports (the drill-down selectors) and the dashboard drills, as each scoped supervisor.
        for (role in listOf("AMO", "TSO", "DMO")) {
            for ((sel, id) in listOf("zone" to oZone, "territory" to oTerritory, "division" to oDivision, "route" to oRoute)) {
                for (key in reportKeys.take(40)) {
                    idorProbe("POST /v1/reports/{}/query", role, "$key geo.$sel", p, "/v1/reports/$key/query", reportBody(obj(sel to listOf(id))).toString())
                }
            }
            for ((level, id) in listOf("zone" to oZone, "territory" to oTerritory, "division" to oDivision)) {
                idorProbe("GET /v1/dashboards/login-submit", role, "node $level", g, "/v1/dashboards/login-submit?business_date=$day&level=$level&node_id=$id", null)
                idorProbe("GET /v1/dashboards/sync-health", role, "node $level", g, "/v1/dashboards/sync-health?business_date=$day&level=$level&node_id=$id", null)
            }
        }
        // National writers: reach is the whole country (ReachCalculator: ADMIN, SUPERADMIN, SUPPORT, TOP, ANALYST are national by role),
        // so the other division's ids are in reach by design. What must still hold is the integrity of a body that mixes divisions.
        val adminP = HttpMethod.Post
        run {
            val b = paperBody(o = oOutlet)
            val h = hit("ADMIN", adminP, "/v1/admin/data-entry", b.toString())
            val cu = b["client_uuid"]!!.jsonPrimitive.content
            val stored = count("SELECT count(*) FROM app.memo WHERE client_uuid = '$cu'") > 0
            record("IDOR", "POST /v1/admin/data-entry", "ADMIN", "own-sr-own-route-other-outlet", adminP, "/v1/admin/data-entry", b.toString(), h,
                if (h.ok || stored) Verdict.FINDING else Verdict.OK, if (h.ok || stored) "a memo of sr1001 on a Mirpur route was keyed against an outlet of the other division (stored=$stored)" else "refused")
        }
        run {
            val b = paperBody(user = oSrId, username = "lkxsr1001")
            val h = hit("ADMIN", adminP, "/v1/admin/data-entry", b.toString())
            val cu = b["client_uuid"]!!.jsonPrimitive.content
            val stored = count("SELECT count(*) FROM app.memo WHERE client_uuid = '$cu'") > 0
            record("IDOR", "POST /v1/admin/data-entry", "ADMIN", "other-sr-own-route", adminP, "/v1/admin/data-entry", b.toString(), h,
                if (h.ok || stored) Verdict.FINDING else Verdict.OK, if (h.ok || stored) "a memo of the other division's SR was keyed on a Mirpur route he is not assigned to (stored=$stored)" else "refused")
        }
        run {
            val b = paperBody(user = adminId, username = "admin1001")
            val h = hit("SUPPORT", adminP, "/v1/admin/data-entry", b.toString())
            val cu = b["client_uuid"]!!.jsonPrimitive.content
            val stored = count("SELECT count(*) FROM app.memo WHERE client_uuid = '$cu'") > 0
            record("IDOR", "POST /v1/admin/data-entry", "SUPPORT", "memo-for-an-admin-user", adminP, "/v1/admin/data-entry", b.toString(), h,
                if (h.ok || stored) Verdict.FINDING else Verdict.OK, if (h.ok || stored) "SUPPORT keyed a sales memo in the name of an ADMIN account (stored=$stored)" else "refused")
        }
        // Row ids of the other division as national callers: reachable by design; recorded so the table shows the decision.
        for ((route, path) in listOf("POST /v1/admin/entry-unlocks/{}/expire" to "/v1/admin/entry-unlocks/$oUnlock/expire")) {
            val h = hit("SUPPORT", adminP, path)
            record("IDOR", route, "SUPPORT", "other-division-row", adminP, path, null, h, Verdict.NOTE, "SUPPORT is national (docs/24 s8.4); acting on the other division's row is in reach")
        }
        record("IDOR", "national-roles", "ADMIN", "n/a", HttpMethod.Get, "-", null, null, Verdict.NOTE,
            "ADMIN, SUPERADMIN, SUPPORT, TOP and ANALYST are national by role (ReachCalculator.nationalRoles), so dues adjustments, price batches, enrolment tokens, data entry and entry unlocks have no horizontal boundary to cross")
    }

    // ---- MASS / STRICT: fields the caller may not set, and unknown fields --------------------------------------------

    private suspend fun HttpClient.massProbe(route: String, role: String, field: String, method: HttpMethod, path: String, body: JsonObject, stored: (() -> Boolean)? = null, token: String? = tokens[role]) {
        val h = hit(role, method, path, body.toString(), token)
        val st = stored?.invoke() == true
        val v = if (h.ok || st) Verdict.FINDING else Verdict.OK
        val why = when {
            st -> "the forbidden field was stored"
            h.ok -> "a body with '$field' was accepted (strict decoding expected 400)"
            h.status !in setOf(400, 422) -> "refused with ${h.status} (not 400/422)"
            else -> "rejected"
        }
        record(if (field.startsWith("unknown")) "STRICT" else "MASS", route, role, field, method, path, body.toString(), h, v, why)
    }

    private suspend fun HttpClient.massAssignment() {
        val p = HttpMethod.Post
        val forbidden = listOf("status" to "approved", "approved_by" to adminId, "created_by" to adminId, "decided_by" to superId, "version" to 99, "is_current" to true, "zone_id" to oZone, "unknown_field_qa" to "x")
        // Dues adjustment proposal (maker ADMIN).
        for ((f, v) in forbidden + listOf("created_by_user_id" to superId, "decided_by_user_id" to superId, "adjustment_id" to 1, "decided_at" to "2027-01-03T04:00:00Z")) {
            val b = dueBody().with(f, v); val cu = b["client_uuid"]!!.jsonPrimitive.content
            massProbe("POST /v1/admin/dues-adjustments", "ADMIN", f.replace("unknown_field_qa", "unknown"), p, "/v1/admin/dues-adjustments", b, { count("SELECT count(*) FROM app.dues_adjustment WHERE client_uuid = '$cu'") > 0 })
        }
        // Decision on a pending row by the checker, with fields the checker may not set (amounts on someone else's row).
        val decPath = "/v1/admin/dues-adjustments/$ownAdjustment/decision"
        for ((f, v) in listOf("amount_mtk" to 1, "status" to "approved", "decided_by" to adminId, "outlet_id" to oOutlet, "unknown_field_qa" to "x")) {
            massProbe("POST /v1/admin/dues-adjustments/{}/decision", "SUPERADMIN", f.replace("unknown_field_qa", "unknown"), p, decPath, obj("decision" to "reject", f to v),
                { count("SELECT count(*) FROM app.dues_adjustment WHERE id = $ownAdjustment AND status <> 'pending'") > 0 })
        }
        // Duplicate keys: the second value must not win silently.
        run {
            val body = """{"decision":"reject","decision":"approve"}"""
            val h = hit("SUPERADMIN", p, decPath, body)
            val decided = count("SELECT count(*) FROM app.dues_adjustment WHERE id = $ownAdjustment AND status <> 'pending'") > 0
            record("STRICT", "POST /v1/admin/dues-adjustments/{}/decision", "SUPERADMIN", "duplicate-key", p, decPath, body, h, if (h.ok || decided) Verdict.FINDING else Verdict.OK, if (h.ok) "a body with a duplicated key was accepted" else "rejected")
        }
        // Separation of duties: the maker never decides his own proposal.
        run {
            val b = dueBody(); val cu = b["client_uuid"]!!.jsonPrimitive.content
            hit("SUPERADMIN", p, "/v1/admin/dues-adjustments", b.toString())
            val id = count("SELECT coalesce(max(id), 0) FROM app.dues_adjustment WHERE client_uuid = '$cu'")
            val h = hit("SUPERADMIN", p, "/v1/admin/dues-adjustments/$id/decision", obj("decision" to "approve").toString())
            record("MASS", "POST /v1/admin/dues-adjustments/{}/decision", "SUPERADMIN", "own-proposal", p, "/v1/admin/dues-adjustments/$id/decision", null, h, if (h.ok) Verdict.FINDING else Verdict.OK, if (h.ok) "the maker approved his own adjustment" else "refused (separation of duties)")
        }
        // Price batches.
        for ((f, v) in forbidden + listOf("price_list_version" to 99, "submitted_by" to adminId, "decided_by_user_id" to superId)) {
            val b = priceBody().with(f, v); val bu = b["batch_uuid"]!!.jsonPrimitive.content
            for (op in listOf("/v1/admin/prices/preview", "/v1/admin/prices")) {
                massProbe("POST $op", "ADMIN", f.replace("unknown_field_qa", "unknown"), p, op, b, { count("SELECT count(*) FROM app.price_batch WHERE batch_uuid = '$bu'") > 0 })
            }
        }
        run {
            val row = obj("sku_id" to sku, "price_type" to "outlet", "amount_mtk" to skuPrice + 1, "per_base_qty" to 1, "valid_to" to "2027-02-01")
            val b = priceBody().with("prices", listOf(row)); val bu = b["batch_uuid"]!!.jsonPrimitive.content
            massProbe("POST /v1/admin/prices/preview", "ADMIN", "prices[].valid_to", p, "/v1/admin/prices/preview", b, { count("SELECT count(*) FROM app.price_batch WHERE batch_uuid = '$bu'") > 0 })
        }
        val pbPath = "/v1/admin/prices/batches/$pendingBatch/decision"
        for ((f, v) in listOf("status" to "published", "decided_by" to adminId, "price_list_version" to 1, "unknown_field_qa" to "x")) {
            massProbe("POST /v1/admin/prices/batches/{}/decision", "SUPERADMIN", f.replace("unknown_field_qa", "unknown"), p, pbPath, obj("decision" to "reject", f to v),
                { count("SELECT count(*) FROM app.price_batch WHERE batch_uuid = '$pendingBatch' AND status <> 'pending_approval'") > 0 })
        }
        run {
            val h = hit("ADMIN", p, pbPath, obj("decision" to "approve").toString())
            record("MASS", "POST /v1/admin/prices/batches/{}/decision", "ADMIN", "maker-decides", p, pbPath, null, h, if (h.ok) Verdict.FINDING else Verdict.OK, if (h.ok) "the maker (ADMIN) decided his own batch" else "refused")
        }
        // Config change sets.
        for ((f, v) in forbidden + listOf("requested_by" to adminId, "approver" to superId, "risk_class" to 0, "change_id" to 1, "config_version" to 1)) {
            val marker = "qa mass $f"
            val b = cfgBody().with("reason", marker).with(f, v)
            massProbe("POST /v1/admin/config/changes", "ADMIN", f.replace("unknown_field_qa", "unknown"), p, "/v1/admin/config/changes", b, { count("SELECT count(*) FROM app.cfg_change WHERE reason = '$marker'") > 0 })
        }
        for ((f, v) in listOf("is_current" to true, "old_value" to 1, "config_version" to 1)) {
            val marker = "qa mass item $f"
            val item = obj("key" to "cfg.geo.radius_m", "scope_type" to "zone", "scope_id" to zone, "value" to 120, f to v)
            val b = obj("reason" to marker, "changes" to listOf(item))
            massProbe("POST /v1/admin/config/changes", "ADMIN", "changes[].$f", p, "/v1/admin/config/changes", b, { count("SELECT count(*) FROM app.cfg_change WHERE reason = '$marker'") > 0 })
        }
        run {
            val marker = "qa break glass by admin"
            val b = cfgBody().with("reason", marker).with("break_glass", true)
            massProbe("POST /v1/admin/config/changes", "ADMIN", "break_glass", p, "/v1/admin/config/changes", b, { count("SELECT count(*) FROM app.cfg_change WHERE reason LIKE '$marker%'") > 0 })
        }
        run {
            val marker = "qa tso price rail change"
            val b = obj("reason" to marker, "changes" to listOf(obj("key" to "cfg.price.max_change_pct", "scope_type" to "global", "scope_id" to 0, "value" to 100)))
            massProbe("POST /v1/admin/config/changes", "TSO", "key-outside-radius", p, "/v1/admin/config/changes", b, { count("SELECT count(*) FROM app.cfg_change WHERE reason = '$marker'") > 0 })
        }
        for ((f, v) in listOf("approver" to adminId, "status" to "applied", "unknown_field_qa" to "x")) {
            massProbe("POST /v1/admin/config/changes/{}/decision", "SUPERADMIN", f.replace("unknown_field_qa", "unknown"), p, "/v1/admin/config/changes/$c3Change/decision", obj("decision" to "approve", f to v),
                { count("SELECT count(*) FROM app.cfg_change WHERE change_id = $c3Change AND status <> 'pending_approval'") > 0 }, token = super2Token)
        }
        // Enrolment tokens.
        for ((f, v) in listOf("used_count" to 0, "created_by" to adminId, "revoked_at" to "2027-01-03T04:00:00Z", "token" to "A".repeat(43), "token_prefix" to "AAAAAA", "unknown_field_qa" to "x")) {
            val marker = "qa mass $f"
            massProbe("POST /v1/admin/enrolment-tokens", "ADMIN", f.replace("unknown_field_qa", "unknown"), p, "/v1/admin/enrolment-tokens", tokenBody().with("note", marker).with(f, v),
                { count("SELECT count(*) FROM app.enrolment_token WHERE note = '$marker'") > 0 })
        }
        // Web entry (TSO, own route).
        for ((f, v) in listOf("saved_by_user_id" to adminId, "source" to "app", "target_outlets" to 1, "app_overlap" to false, "entered_by" to adminId, "unknown_field_qa" to "x")) {
            val b = routeDayBody().with(f, v); val cu = b["client_uuid"]!!.jsonPrimitive.content
            massProbe("POST /v1/web-entry/route-day", "TSO", f.replace("unknown_field_qa", "unknown"), p, "/v1/web-entry/route-day", b, { count("SELECT count(*) FROM app.web_entry_route_day WHERE client_uuid = '$cu'") > 0 })
        }
        run {
            // `sale_qty_base` is an answer field the request type shares: it may be ignored, never stored against issue - return.
            val line = obj("sku_id" to sku, "issue_qty_base" to 20, "return_qty_base" to 5, "memo_count" to 1, "sale_qty_base" to 9999)
            val b = routeDayBody().with("lines", listOf(line))
            val h = hit("TSO", p, "/v1/web-entry/route-day", b.toString())
            val echoed = h.ok && h.body.contains("9999")
            record("MASS", "POST /v1/web-entry/route-day", "TSO", "lines[].sale_qty_base", p, "/v1/web-entry/route-day", b.toString(), h, if (echoed) Verdict.FINDING else Verdict.NOTE,
                if (echoed) "the client's sale_qty_base was kept" else "accepted and ignored (sale = issue - return on the server); strict decoding lets the answer-only field in")
        }
        for ((f, v) in listOf("entered_by" to adminId, "replayed" to true, "stored_rows" to 5, "unknown_field_qa" to "x")) {
            val b = qcBody().with(f, v); val cu = b["client_uuid"]!!.jsonPrimitive.content
            massProbe("POST /v1/web-entry/qc", "TSO", f.replace("unknown_field_qa", "unknown"), p, "/v1/web-entry/qc", b, { count("SELECT count(*) FROM app.qc_summary_entry WHERE client_uuid = '$cu'") > 0 })
        }
        run {
            val b = qcBody(r = null).with("source", "warehouse").with("reason", "qa warehouse by a tso"); val cu = b["client_uuid"]!!.jsonPrimitive.content
            massProbe("POST /v1/web-entry/qc", "TSO", "source=warehouse", p, "/v1/web-entry/qc", JsonObject(b - "route_id"), { count("SELECT count(*) FROM app.qc_summary_entry WHERE client_uuid = '$cu'") > 0 })
        }
        // Paper backfill: the server prices; the browser never sends money or a status.
        for ((f, v) in listOf("net_mtk" to 1, "gross_mtk" to 1, "status" to "created", "created_by" to adminId, "unknown_field_qa" to "x")) {
            val b = paperBody().with(f, v); val cu = b["client_uuid"]!!.jsonPrimitive.content
            massProbe("POST /v1/admin/data-entry", "ADMIN", f.replace("unknown_field_qa", "unknown"), p, "/v1/admin/data-entry", b, { count("SELECT count(*) FROM app.memo WHERE client_uuid = '$cu'") > 0 })
        }
        for ((f, v) in listOf("price_mtk" to 1, "base_price_mtk" to 1, "gross_mtk" to 1)) {
            val line = obj("sku_id" to sku, "qty_base" to 10, "line_kind" to "sale", f to v)
            val b = paperBody().with("lines", listOf(line)); val cu = b["client_uuid"]!!.jsonPrimitive.content
            massProbe("POST /v1/admin/data-entry", "ADMIN", "lines[].$f", p, "/v1/admin/data-entry", b, { count("SELECT count(*) FROM app.memo WHERE client_uuid = '$cu'") > 0 })
        }
        // Entry unlocks.
        for ((f, v) in listOf("created_by" to adminId, "expires_at" to "2030-01-01T00:00:00Z", "status" to "active", "unlock_id" to 1, "unknown_field_qa" to "x")) {
            val marker = "qa mass unlock $f"
            massProbe("POST /v1/admin/entry-unlocks", "SUPPORT", f.replace("unknown_field_qa", "unknown"), p, "/v1/admin/entry-unlocks", unlockBody().with("reason", marker).with(f, v), { count("SELECT count(*) FROM app.entry_unlock WHERE reason = '$marker'") > 0 })
        }
        // Report queries: no scope member may come from the client.
        for ((f, v) in listOf("zone_ids" to listOf(oZone), "user_id" to oSrId, "pii" to true, "scope" to "national")) {
            massProbe("POST /v1/reports/{}/query", "TSO", f, p, "/v1/reports/${reportKeys.first()}/query", reportBody().with(f, v), null)
        }
        // Config C2 keys that switch a control off: an ADMIN alone (no second person, only the C2 delay).
        for ((key, value) in listOf("cfg.auth.mfa_required_roles" to buildJsonArray { }, "cfg.price.max_change_pct" to JsonPrimitive(100))) {
            val marker = "qa control off $key"
            val b = obj("reason" to marker, "changes" to listOf(obj("key" to key, "scope_type" to "global", "scope_id" to 0, "value" to value)))
            val h = hit("ADMIN", p, "/v1/admin/config/changes", b.toString())
            val status = h.json?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
            record("MASS", "POST /v1/admin/config/changes", "ADMIN", "control-off $key", p, "/v1/admin/config/changes", b.toString(), h, if (h.ok && status != "pending_approval") Verdict.QUESTION else Verdict.OK,
                if (h.ok && status != "pending_approval") "one ADMIN schedules '$key' = $value with no second approver (risk class C2 in the registry, as docs/24 s9.5 lists it); this switches off ${if (key.contains("mfa")) "MFA for the admin roles" else "the price maker-checker rail"} — should it be C3?" else "needs a second person")
            h.json?.jsonObject?.get("change_id")?.jsonPrimitive?.content?.toLongOrNull()?.let { id -> hit("ADMIN", p, "/v1/admin/config/changes/$id/decision", obj("decision" to "cancel").toString()) }
        }
    }

    // ---- LIMIT --------------------------------------------------------------------------------------------------------

    private suspend fun HttpClient.limits() {
        val lists = listOf(
            "GET /v1/admin/enrolment-tokens" to "/v1/admin/enrolment-tokens?active_only=false",
            "GET /v1/admin/dues-adjustments" to "/v1/admin/dues-adjustments",
            "GET /v1/admin/prices" to "/v1/admin/prices",
            "GET /v1/admin/entry-unlocks" to "/v1/admin/entry-unlocks",
            "GET /v1/admin/config/values" to "/v1/admin/config/values?key=cfg.geo.radius_m",
            "GET /v1/admin/config/changes" to "/v1/admin/config/changes",
            "GET /v1/admin/config/versions" to "/v1/admin/config/versions",
            "GET /v1/admin/config/reach/{}/pending" to "/v1/admin/config/reach/$ownVersion/pending?zone_id=$zone",
            "GET /v1/report-exports" to "/v1/report-exports",
            "GET /v1/dashboards/sync-health" to "/v1/dashboards/sync-health?business_date=$day",
        )
        val g = HttpMethod.Get
        for ((route, base) in lists) {
            val sep = if ('?' in base) "&" else "?"
            for (bad in listOf("0", "-1", "501", "1000000", "2147483648", "99999999999999999999", "abc", "1.5", "")) {
                val path = "$base${sep}limit=$bad"
                val h = hit("ADMIN", g, path)
                record("LIMIT", route, "ADMIN", "limit=$bad", g, path, null, h, if (h.ok) Verdict.FINDING else Verdict.OK,
                    if (h.ok) "served ${h.items()} items for an out-of-contract limit" else "refused ${h.status} ${h.code()}")
            }
            run {
                val path = "$base${sep}limit=500"
                val h = hit("ADMIN", g, path)
                val v = when { !h.ok -> Verdict.FINDING; (h.items() ?: 0) > 500 -> Verdict.FINDING; else -> Verdict.OK }
                record(if (h.ok) "LIMIT" else "LIMIT-CONTRACT", route, "ADMIN", "limit=500", g, path, null, h, v,
                    if (!h.ok) "the contract maximum (500) is refused: the server's own maximum is lower (contract divergence, not unbounded)" else "items=${h.items()}")
            }
            run {
                val h = hit("ADMIN", g, base)
                val n = h.items()
                val v = when { !h.ok -> Verdict.NOTE; n != null && n > 100 -> Verdict.FINDING; else -> Verdict.OK }
                record("LIMIT", route, "ADMIN", "no-limit", g, base, null, h, v, "items=$n (contract default 100)")
                // With more than 100 rows seeded the default page must be exactly the contract default.
                if (route in setOf("GET /v1/admin/enrolment-tokens", "GET /v1/admin/dues-adjustments", "GET /v1/admin/entry-unlocks") && n != null && n != 100) {
                    record("LIMIT-CONTRACT", route, "ADMIN", "default", g, base, null, h, Verdict.FINDING, "default page is $n, contract says 100")
                }
                if (route.startsWith("GET /v1/admin/config/") && n != null && n < 100 && h.body.contains("\"next_cursor\":\"")) {
                    record("LIMIT-CONTRACT", route, "ADMIN", "default", g, base, null, h, Verdict.FINDING, "default page is $n with more rows behind it, contract says 100")
                }
            }
        }
        // Report page_size is an enum (10, 25, 50, 100, 500).
        val key = reportKeys.first()
        for (bad in listOf<Any>(0, -1, 7, 501, 1000, 1000000, 2147483647)) {
            val path = "/v1/reports/$key/query"; val body = reportBody(pageSize = bad).toString()
            val h = hit("ADMIN", HttpMethod.Post, path, body)
            record("LIMIT", "POST /v1/reports/{}/query", "ADMIN", "page_size=$bad", HttpMethod.Post, path, body, h, if (h.ok) Verdict.FINDING else Verdict.OK, if (h.ok) "served rows=${h.items()}" else "refused ${h.status}")
        }
        run {
            val path = "/v1/reports/$key/query"; val body = obj("period" to obj("date" to day), "output" to obj("format" to "json")).toString()
            val h = hit("ADMIN", HttpMethod.Post, path, body)
            val ps = h.json?.jsonObject?.get("page_size")?.jsonPrimitive?.contentOrNull
            record("LIMIT", "POST /v1/reports/{}/query", "ADMIN", "no-page_size", HttpMethod.Post, path, body, h, if (h.ok && ps != "50") Verdict.FINDING else Verdict.OK, "page_size=$ps (contract default 50)")
        }
    }

    // ---- STEPUP -------------------------------------------------------------------------------------------------------

    private fun setMfaRoles(json: String) = world.fresh.db.jdbi.useHandle<Exception> { h ->
        h.execute("UPDATE app.cfg_value SET effective_to = now() WHERE key = 'cfg.auth.mfa_required_roles' AND scope_type = 'global' AND effective_to IS NULL")
        h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '$json'::jsonb, now(), max(config_version), 'qa authz audit' FROM app.cfg_version")
        // The server config snapshot lives 30 s on the API's clock.
        now.set(now.get().plusSeconds(31))
    }

    private suspend fun HttpClient.stepUp() {
        val p = HttpMethod.Post; val g = HttpMethod.Get
        // A password-only session opened while MFA was off, with its refresh token.
        val login = hit("ADMIN", p, "/v1/auth/login", """{"username":"admin1001","password":"${world.password}","client":"web"}""", token = null)
        val pwdToken = login.json!!.jsonObject["access_token"]!!.jsonPrimitive.content
        // The web login answers the refresh token as the aron_rt cookie only.
        val refresh = login.setCookies.firstOrNull { it.startsWith("aron_rt=") }?.substringAfter("aron_rt=")?.substringBefore(';')?.takeIf { it.isNotEmpty() }
        setMfaRoles("""["ADMIN", "SUPERADMIN", "SUPPORT"]""")
        try {
            val fresh = hit("ADMIN", p, "/v1/auth/login", """{"username":"admin1001","password":"${world.password}","client":"web"}""", token = null)
            val st = fresh.json?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
            val issued = fresh.json?.jsonObject?.get("access_token")?.jsonPrimitive?.contentOrNull != null
            record("STEPUP", "POST /v1/auth/login", "ADMIN", "mfa-policy-on", p, "/v1/auth/login", "{username, password, client: web}", fresh, if (issued) Verdict.FINDING else Verdict.OK, "status=$st, access token issued=$issued")
            val mfaToken = fresh.json?.jsonObject?.get("mfa_token")?.jsonPrimitive?.contentOrNull
            if (mfaToken != null) {
                val h = hit("ADMIN", g, "/v1/admin/dues-adjustments", token = mfaToken)
                record("STEPUP", "GET /v1/admin/dues-adjustments", "ADMIN", "mfa-token-as-bearer", g, "/v1/admin/dues-adjustments", null, h, if (h.ok) Verdict.FINDING else Verdict.OK, if (h.ok) "the MFA-step token opened an admin route" else "refused ${h.status}")
            }
            if (refresh != null) {
                val h = hit("ADMIN", p, "/v1/auth/refresh", """{"refresh_token":null,"grant":"full"}""", token = null, cookie = "aron_rt=$refresh")
                val issuedR = h.json?.jsonObject?.get("access_token")?.jsonPrimitive?.contentOrNull != null
                record("STEPUP", "POST /v1/auth/refresh", "ADMIN", "pwd-family", p, "/v1/auth/refresh", "{refresh_token: null, grant: full} + aron_rt cookie", h, if (issuedR) Verdict.FINDING else Verdict.OK, if (issuedR) "a password-only family refreshed under the MFA policy" else "refused ${h.status} ${h.code()}")
            } else record("STEPUP", "POST /v1/auth/refresh", "ADMIN", "pwd-family", p, "/v1/auth/refresh", null, null, Verdict.NOTE, "the web login set no aron_rt cookie, not exercised")
            // The resource side: the still-valid password-only access token on the money, config and device writes.
            val served = mutableListOf<String>()
            val writes = listOf(
                "POST /v1/admin/dues-adjustments" to { dueBody().toString() },
                "POST /v1/admin/prices/preview" to { priceBody().toString() },
                "POST /v1/admin/config/changes" to { cfgBody().with("reason", "qa step-up probe change").toString() },
                "POST /v1/admin/enrolment-tokens" to { tokenBody().toString() },
                "POST /v1/admin/entry-unlocks" to { unlockBody().toString() },
                "POST /v1/admin/data-entry" to { paperBody().toString() },
            )
            for ((route, body) in writes) {
                val path = route.substringAfter(' ')
                val b = body()
                val h = hit("ADMIN", p, path, b, token = pwdToken)
                if (h.ok) served += "$route ${h.status}"
                record("STEPUP", route, "ADMIN", "amr=pwd", p, path, b, h, Verdict.NOTE, if (h.ok) "served to a password-only token" else "refused ${h.status} ${h.code()}")
            }
            if (served.isNotEmpty()) record("STEPUP", "admin-writes", "ADMIN", "amr-pwd-token-under-mfa-policy", p, "-", null, null, Verdict.FINDING,
                "no route checks the token's amr: with cfg.auth.mfa_required_roles naming ADMIN, a password-only access token minted before the policy applied still writes: ${served.joinToString()} (MFA is enforced only at login and refresh; the token lives up to 15 min)")
        } finally {
            setMfaRoles("[]")
        }
    }

    // ---- PII ----------------------------------------------------------------------------------------------------------

    private val piiMarkers = listOf(
        "phone number" to Regex("01[3-9][0-9]{8}"), "NID" to Regex("\"nid(_enc)?\"\\s*:\\s*\"[^\"]+"), "owner name" to Regex("Owner [A-Z0-9]+-\\d+"), "address" to Regex("Road \\d+, "),
    )
    private val fullName = Regex("Test (SR|AMO|TSO|DMO|Admin|Super Admin|Support) ?(\\(|\")|QA (WM|TOP|ANALYST|SUPERADMIN)\"")

    private fun piiScan(route: String, role: String, variant: String, h: Hit, reportPii: Boolean = false) {
        val hits = piiMarkers.mapNotNull { (what, rx) -> rx.find(h.body)?.let { "$what '${it.value.take(4)}…'" } }.toMutableList()
        if (reportPii) fullName.find(h.body)?.let { hits += "full name (a pii column in the reports)" }
        if (hits.isNotEmpty()) record("PII", route, role, variant, HttpMethod.Get, route, null, null, Verdict.FINDING, "no token carries the pii claim, yet the answer has: ${hits.joinToString()}")
    }

    private suspend fun HttpClient.piiRule() {
        // Every 2xx answer recorded so far (role matrix, idor, controls) is scanned once more by route and role.
        val g = HttpMethod.Get
        for (s in specs().filter { it.method == g }) for (role in roles) {
            val h = hit(role, g, s.path())
            if (h.ok) piiScan(s.name, role, "read", h)
        }
        for (path in listOf("/v1/admin/dues-adjustments?outlet_id=$outlet", "/v1/web-entry/route-day?route_id=$route&business_date=$day")) {
            val h = hit("ADMIN", g, path); if (h.ok) piiScan("GET ${path.substringBefore('?')}", "ADMIN", "filtered", h)
        }
        val me = hit("ADMIN", g, "/v1/me").json?.jsonObject?.get("pii")?.jsonPrimitive?.contentOrNull
        record("PII", "GET /v1/me", "ADMIN", "pii-claim", g, "/v1/me", null, null, Verdict.QUESTION,
            "pii=$me for ADMIN: TokenSubject.pii is never set at login, so no role ever gets phone or owner columns (docs/21 s4.5 gives the PII right to some supervisor and admin roles with re-authentication within cfg.pii.reauth_min, which does not exist yet); intended for now?")
    }

    private fun notes() {
        record("NOTE", "GET /v1/admin/releases", "ALL", "unserved", HttpMethod.Get, "/v1/admin/releases", null, null, Verdict.QUESTION,
            "the contract has /v1/admin/releases, /{release_id} and /policy (docs/24 s8.5: SUPPORT R, ADMIN W draft, SUPERADMIN publish) but no route serves them; the enrolment-token path reads app_release directly. Who builds it and when?")
        record("NOTE", "GET /v1/admin/config/*", "DMO,WM,TOP,ANALYST,SUPPORT", "read", HttpMethod.Get, "/v1/admin/config/*", null, null, Verdict.QUESTION,
            "docs/24 s8.5 'Config changes' is — for DMO, WM, TOP, ANALYST and SUPPORT; ConfigApi.CONFIG_READERS lets them read keys, values, changes and versions. Is read access intended?")
        record("NOTE", "editor_permission", "ADMIN", "unenforced", HttpMethod.Post, "/v1/admin/config/changes", null, null, Verdict.QUESTION,
            "cfg_key.editor_permission (cfg.edit.security, cfg.edit.finance, ...) is loaded but never checked, and no token carries permissions (TokenSubject.permissions is never set), so every ADMIN edits every C0..C2 key and `finance.backdate` can never be granted")
    }

    // ---- verdict ------------------------------------------------------------------------------------------------------

    private fun baseline(): Set<String> = javaClass.getResource("/authz-audit-baseline.txt")!!.readText().lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()

    private fun writeReport() {
        val out = File("build/authz-audit.txt").apply { parentFile.mkdirs() }
        out.writeText(buildString {
            appendLine("# Authorisation audit (AuthzAuditTest): ${rows.size} probes, ${rows.count { it.verdict == Verdict.FINDING }} findings, ${rows.count { it.verdict == Verdict.QUESTION }} questions")
            for (v in listOf(Verdict.FINDING, Verdict.QUESTION, Verdict.NOTE, Verdict.OK)) {
                appendLine(); appendLine("## $v")
                rows.filter { it.verdict == v }.sortedBy { it.key }.forEach { r -> appendLine("${r.key}\t${r.status}\t${r.request}\t${r.detail}") }
            }
        })
    }

    @Test
    fun positiveControlsHold() {
        assertTrue(failures.isEmpty(), "the audit's own setup is broken (these must succeed for the probes to mean anything):\n" + failures.joinToString("\n"))
    }

    @Test
    fun findingsMatchTheShrinkingBaseline() {
        val found = rows.filter { it.verdict == Verdict.FINDING }.map { it.key }.toSet()
        val base = baseline()
        val msg = buildString {
            (found - base).sorted().takeIf { it.isNotEmpty() }?.let { append("new authorisation findings (fix them; see build/authz-audit.txt):\n  " + it.joinToString("\n  ") + "\n") }
            (base - found).sorted().takeIf { it.isNotEmpty() }?.let { append("fixed, remove from authz-audit-baseline.txt:\n  " + it.joinToString("\n  ")) }
        }
        assertTrue(msg.isEmpty(), msg)
    }

    @Test
    fun everyAuditedRouteWasProbedForEveryRole() {
        val missing = specs().flatMap { s -> roles.filter { r -> rows.none { it.check == "ROLE" && it.route == s.name && it.role == r } }.map { "${s.name} $it" } }
        assertTrue(missing.isEmpty(), "not probed: $missing")
        assertTrue(rows.count { it.check == "ROLE" && it.route == "POST /v1/reports/{}/query" } >= reportKeys.size * roles.size, "every report for every role")
        assertTrue(reportKeys.isNotEmpty())
    }
}
