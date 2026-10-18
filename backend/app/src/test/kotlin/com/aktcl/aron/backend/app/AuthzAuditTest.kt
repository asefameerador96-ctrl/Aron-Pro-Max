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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Authorisation audit (qa lane, 2026-10-18) of the admin, data-entry and drill-down routes: enrolment tokens, config
 * changes, versions and tools, dues adjustments, price batches, web entry and QC, paper backfill (data entry), entry
 * unlocks, device OTPs, route assignments, report queries and exports, and the dashboards. Contract-only surfaces
 * (`/v1/admin/releases`, `/v1/dashboards/targets`, `/v1/web-entry/outlet-sku`) must answer 404 to everyone.
 *
 * Every role calls every route (SR on its phone; AMO, TSO, DMO, WM, TOP, ANALYST, SUPPORT, ADMIN, SUPERADMIN on the web;
 * WM, TOP, ANALYST and a second SUPERADMIN are inserted here) in an ApiWorld with a mirrored second division (`LKX`).
 * The oracle is docs/24 s8.5 written down in this file, never the server's own answers. Checks, one table row per probe:
 * - ROLE: a role outside docs/24 s8.5 is served, or a refused role gets anything but 401/403/404;
 * - IDOR: the other division's ids (route, zone, outlet, user, node, record row ids) of a scoped caller are answered
 *   with its data or acted on;
 * - MASS / STRICT: a field the caller may not set is accepted or stored; an unknown field is accepted; a maker decides
 *   his own proposal (separation of duties);
 * - STEPUP: a password-only (`amr` pwd) ADMIN, SUPERADMIN or SUPPORT token writes while `cfg.auth.mfa_required_roles`
 *   names the role (switched on in this test's own database; ApiWorld turns MFA off for its logins);
 * - PII: a phone number, NID, owner name or address (or a full name in a report) appears in any 2xx answer (no token
 *   carries the `pii` claim);
 * - LIMIT / LIMIT-CONTRACT: a `limit` or `page_size` outside the contract is served; a contract-valid one is refused, or
 *   the default page is not the contract default.
 * Any 5xx is a FINDING. Verdicts: FINDING (a defect; the key must be in `authz-audit-baseline.txt`, which may only
 * shrink), QUESTION (docs and code disagree on an open point; never fails), NOTE (context). Report:
 * build/authz-audit.txt; the finding and question keys alone: build/authz-audit-keys.txt (identical across runs).
 * Secrets (enrolment token, QR, OTP, access and refresh tokens) are redacted by key before anything is written.
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
    private val secrets = mutableListOf<String>()    // real secrets the controls received: must never reach the report

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

    private var zone = 0L; private var route = 0L; private var outlet = 0L; private var territory = 0L; private var division = 0L; private var srId = 0L
    private var oZone = 0L; private var oRoute = 0L; private var oOutlet = 0L; private var oTerritory = 0L; private var oDivision = 0L; private var oSrId = 0L
    private var sku = 0L; private var skuPrice = 0L; private var tokenId = 0L; private var revokeTokenId = 0L
    private var ownAdjustment = 0L; private var matrixAdjustment = 0L; private var controlAdjustment = 0L
    private var ownUnlock = 0L; private var oUnlock = 0L
    private var adminId = 0L; private var superId = 0L; private var tsoId = 0L; private var dmoId = 0L; private var faultCode = ""
    private var ownVersion = 0L; private var lkxVersion = 0L
    private var tsoExport = ""; private var dmoExport = ""; private var lkxVisits = 0L
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
            client.reports()
            client.exports()
            client.idor()
            client.massAssignment()
            client.limits()
            client.stepUp()
            piiScannerSelfTest()
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
        tsoId = id("SELECT id FROM app.app_user WHERE username = 'tso1001'"); dmoId = id("SELECT id FROM app.app_user WHERE username = 'dmo1001'")
        h.createQuery("SELECT s.id, p.amount_mtk FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' AND p.valid_to IS NULL WHERE p.amount_mtk > 0 ORDER BY s.id LIMIT 1")
            .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.one().let { sku = it.first; skuPrice = it.second }
        faultCode = h.createQuery("SELECT min(code) FROM app.code_list_item WHERE list_key = 'qc_fault_type' AND valid_from <= DATE '$day' AND (valid_to IS NULL OR valid_to > DATE '$day')").mapTo(String::class.java).one()
        // The roles db/seed does not have: WM (the wing both divisions share), TOP and ANALYST (national), a second SUPERADMIN (checker).
        for ((u, role) in listOf("qawm1001" to "WM", "qatop1001" to "TOP", "qaanalyst1001" to "ANALYST", "qasuper2" to "SUPERADMIN")) {
            h.execute(
                "INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password, password_hash) " +
                    "SELECT '$u', 'QA $role', '$role', 'QA', upper('$u'), 'en', home_zone_id, true, false, password_hash FROM app.app_user WHERE username = 'admin1001'",
            )
            val node = if (role == "WM") "'wing', (SELECT wing_id FROM app.division WHERE id = $division)" else "'national', 0"
            h.execute("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, reason) SELECT id, $node, DATE '2026-01-01', 'qa authz audit' FROM app.app_user WHERE username = '$u'")
        }
        // PII markers: every user gets a phone (outlets have phones, owners and addresses from the seed; the outlet NID is envelope-encrypted, V0049).
        h.execute("UPDATE app.app_user SET phone = '0191' || lpad(id::text, 7, '0')")
        // A published SR release (enrolment tokens need one) and 120 tokens (the default page is 100).
        h.execute(
            "INSERT INTO app.app_release (flavour, version_name, version_code, abi, sha256, size_bytes, download_url, signing_cert_sha256, status, created_by, published_at, published_by) " +
                "VALUES ('sr', '1.0.9', 9, 'universal', sha256('qa-release'::bytea), 1000, 'https://example.invalid/sr.apk', sha256('qa-cert'::bytea), 'published', $adminId, now(), $superId)",
        )
        h.execute("INSERT INTO app.enrolment_token (token_sha256, token_prefix, flavour, lockdown_level, max_uses, expires_at, created_by, note) SELECT sha256(('qa-token-' || g)::bytea), 'qaqaqa', 'sr', 'prod', 1, now() + interval '7 days', $adminId, 'qa page row' FROM generate_series(1, 120) g")
        tokenId = id("SELECT max(id) FROM app.enrolment_token"); revokeTokenId = tokenId - 1
        // 120 dues adjustments and entry unlocks of the own zone (pages), one of each in the other division.
        h.execute("INSERT INTO app.dues_adjustment (client_uuid, outlet_id, kind, amount_mtk, reason, created_by) SELECT gen_random_uuid(), $outlet, 'correction', 1000 + g, 'qa page row ' || g, $adminId FROM generate_series(1, 120) g")
        ownAdjustment = id("SELECT max(id) FROM app.dues_adjustment"); matrixAdjustment = ownAdjustment - 1; controlAdjustment = ownAdjustment - 2
        h.execute("INSERT INTO app.dues_adjustment (client_uuid, outlet_id, kind, amount_mtk, reason, created_by) VALUES (gen_random_uuid(), $oOutlet, 'correction', 777, 'LKX adjustment for the audit', $adminId)")
        h.execute("INSERT INTO app.entry_unlock (scope_type, scope_id, from_date, to_date, reason, expires_at, created_by) SELECT 'zone', $zone, DATE '$day' - 1, DATE '$day' - 1, 'qa page row ' || g, now() + interval '400 days', $adminId FROM generate_series(1, 120) g")
        ownUnlock = id("SELECT max(id) FROM app.entry_unlock")
        h.execute("INSERT INTO app.entry_unlock (scope_type, scope_id, from_date, to_date, reason, expires_at, created_by) VALUES ('zone', $oZone, DATE '$day' - 1, DATE '$day' - 1, 'LKX unlock for the audit', now() + interval '400 days', $adminId)")
        oUnlock = id("SELECT max(id) FROM app.entry_unlock")
        h.execute("SET session_replication_role = replica")
        try {
            h.execute("INSERT INTO app.web_entry_route_day (client_uuid, route_id, business_date, successful_calls, target_outlets, entered_by) VALUES (gen_random_uuid(), $oRoute, '$day'::date, 3, 9, $oSrId)")
        } finally { h.execute("SET session_replication_role = origin") }
        ownVersion = id("SELECT max(config_version) FROM app.cfg_version")
        // More than 50 config versions, change sets and values, so a default page of 50 shows against the contract's 100.
        h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT $ownVersion + g, 'change', $adminId, 'qa page row ' || g FROM generate_series(1, 60) g")
        h.execute("INSERT INTO app.cfg_change (status, items, reason, risk_class, requested_by) SELECT 'cancelled', '[]'::jsonb, 'qa page row ' || g, 0, $adminId FROM generate_series(1, 60) g")
        h.execute(
            "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) " +
                "SELECT 'cfg.geo.radius_m', 'outlet', o.id, '100'::jsonb, now() - interval '1 day', $ownVersion + 1, 'qa page row' FROM app.outlet o WHERE o.code LIKE 'MIR-%' AND o.id <> $outlet",
        )
        // An applied radius of the other division's zone (its own version), so the config reads have something of LKX to show.
        lkxVersion = ownVersion + 61
        h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) VALUES ($lkxVersion, 'change', $adminId, 'LKX zone radius for the audit')")
        h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) VALUES ('cfg.geo.radius_m', 'zone', $oZone, '137'::jsonb, now() - interval '1 day', $lkxVersion, 'LKX radius for the audit')")
        ownVersion = id("SELECT max(config_version) FROM app.cfg_version")
        // Export jobs of two scoped users (queued: no file, so no signed URL is minted in the test).
        tsoExport = UUID.randomUUID().toString(); dmoExport = UUID.randomUUID().toString()
        for ((uid, eid) in listOf(tsoId to tsoExport, dmoId to dmoExport)) {
            h.execute("INSERT INTO app.report_export (export_id, report_key, user_id, format, status, filters, scope_hash) VALUES ('$eid'::uuid, 'retailer-list', $uid, 'xlsx', 'queued', '{}'::jsonb, 'qa')")
        }
        // Geofence visits in the other division (calibration reads app.visit), so an unrefused calibration of its zone has rows.
        h.execute("SET session_replication_role = replica")
        try {
            h.execute(
                "INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_kind, outlet_id, opened_at, sequence_no, planned, " +
                    "verdict, distance_m, radius_m_used, max_accuracy_m_used, location_basis, geo_action) " +
                    "SELECT u, u, DATE '$day', $oSrId, $oRoute, TIMESTAMPTZ '${day}T03:00:00Z', 1, 'sr_call', o.id, TIMESTAMPTZ '${day}T03:00:00Z', 1, true, 'in_range', 40 + o.id % 30, 100, 50, 'master', 'sale_allowed' " +
                    "FROM (SELECT id, gen_random_uuid() AS u FROM app.outlet WHERE route_id = $oRoute) o",
            )
        } finally { h.execute("SET session_replication_role = origin") }
        lkxVisits = id("SELECT count(*) FROM app.visit WHERE route_id = $oRoute")
        Aggregator.refreshDimensions(h)
    }

    // ---- http -------------------------------------------------------------------------------------------------------

    class Hit(val status: Int, val body: String, val setCookies: List<String> = emptyList()) {
        val ok get() = status in 200..299
        val json: JsonElement? get() = runCatching { Json.parseToJsonElement(body) }.getOrNull()
        fun items(): Int? = (json as? JsonObject)?.let { (it["items"] ?: it["rows"]) as? JsonArray }?.size
        fun code(): String = (json as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull ?: ""
        fun str(k: String): String? = ((json as? JsonObject)?.get(k) as? JsonPrimitive)?.contentOrNull
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
        null -> JsonNull; is JsonElement -> v; is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); is String -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { js(it) }); else -> error("json of $v")
    }
    private fun JsonObject.with(k: String, v: Any?) = JsonObject(this + (k to js(v)))

    /** Keys whose values are secrets: replaced wherever they occur in an answer before it is written anywhere. */
    private val secretKeys = setOf("enrolment_token", "aron.enrolment_token", "qr_text", "qr_payload", "token", "otp", "access_token", "refresh_token", "upload_refresh_token", "mfa_token", "password_change_token", "bind_token")

    private fun redactJson(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (k, v) -> if (k in secretKeys && v !is JsonNull) JsonPrimitive("<redacted>") else redactJson(v) })
        is JsonArray -> JsonArray(e.map { redactJson(it) })
        else -> e
    }
    private fun redact(body: String): String = runCatching { redactJson(Json.parseToJsonElement(body)).toString() }.getOrElse { if (body.length > 40 && body.trim().startsWith("{")) "<unparsed answer withheld>" else body }

    private fun record(check: String, route: String, role: String, variant: String, method: HttpMethod, path: String, body: String?, hit: Hit?, verdict: Verdict, detail: String) {
        val req = "${method.value} $path" + (body?.let { " " + it.take(300) } ?: "")
        var v = verdict; var d = detail
        if (hit != null && hit.status >= 500) {
            if (check == "CONTROL") failures += "positive control $route $role $variant answered ${hit.status}"
            else { v = Verdict.FINDING; d = "server error ${hit.status}: $detail" }
        }
        rows += Row(check, route, role, variant, req, hit?.status ?: 0, v, d + (hit?.let { " | answer: " + redact(it.body).take(300) } ?: ""))
        if (hit != null && hit.ok) piiScan(route, role, "$check $variant", hit, reportPii = route.startsWith("POST /v1/reports"))
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
    private fun otpBody(user: Long = srId) = obj("user_id" to user, "reason" to "qa authz audit device otp")
    private fun actionBody(r: Long = route) = obj("action_uuid" to uuid(), "route_id" to r, "business_date" to day, "note" to "qa authz audit action")
    private fun reportBody(geo: JsonObject? = null, pageSize: Any = 500) =
        JsonObject(mapOf("period" to obj("date" to day), "output" to obj("format" to "json", "page_size" to pageSize)) + (geo?.let { mapOf("geo" to it) } ?: emptyMap()))

    // ---- positive controls ------------------------------------------------------------------------------------------

    private var pendingBatch = ""; private var matrixBatch = ""
    private var adminChange = 0L; private var lkxChange = 0L; private var c3Change = 0L; private var c3Version = 0L

    private suspend fun HttpClient.control(what: String, role: String, method: HttpMethod, path: String, body: String?, want: Set<Int>, token: String? = tokens[role]): Hit {
        val h = hit(role, method, path, body, token)
        if (h.status !in want) failures += "positive control '$what' ($role ${method.value} $path): ${h.status} ${redact(h.body).take(300)}"
        record("CONTROL", path, role, what, method, path, body, h, Verdict.NOTE, "positive control, expected $want")
        return h
    }

    private fun Hit.long(k: String): Long = str(k)?.toLongOrNull() ?: 0L
    private fun count(sql: String): Long = world.fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }

    private suspend fun HttpClient.positiveControls() {
        val p = HttpMethod.Post; val g = HttpMethod.Get
        control("token", "SUPPORT", p, "/v1/admin/enrolment-tokens", tokenBody().toString(), setOf(201)).str("enrolment_token")?.let { secrets += it }
        control("token-revoke", "SUPPORT", p, "/v1/admin/enrolment-tokens/$revokeTokenId/revoke", null, setOf(200))
        control("dues", "ADMIN", p, "/v1/admin/dues-adjustments", dueBody().toString(), setOf(201))
        control("dues-decision", "SUPERADMIN", p, "/v1/admin/dues-adjustments/$controlAdjustment/decision", obj("decision" to "approve").toString(), setOf(200), token = super2Token)
        if (count("SELECT count(*) FROM app.dues_adjustment WHERE id = $controlAdjustment AND status = 'approved'") != 1L) failures += "dues decision control: the row is not approved"
        val batch = uuid()
        control("preview", "ADMIN", p, "/v1/admin/prices/preview", priceBody(batch).toString(), setOf(200))
        control("publish", "ADMIN", p, "/v1/admin/prices", priceBody(batch).toString(), setOf(201))
        // Batches above cfg.price.max_change_pct wait for a checker: one for the mass probes, one for the role matrix, one for this control.
        suspend fun bigBatch(from: String, role: String = "ADMIN"): String {
            val b = uuid()
            control("preview-big", role, p, "/v1/admin/prices/preview", priceBody(b, skuPrice * 5, from).toString(), setOf(200))
            control("publish-big", role, p, "/v1/admin/prices", priceBody(b, skuPrice * 5, from).toString(), setOf(201))
            return b
        }
        pendingBatch = bigBatch("2027-01-21"); matrixBatch = bigBatch("2027-01-22")
        val controlBatch = bigBatch("2027-01-23")
        control("batch-decision", "SUPERADMIN", p, "/v1/admin/prices/batches/$controlBatch/decision", obj("decision" to "reject").toString(), setOf(200))
        if (count("SELECT count(*) FROM app.price_batch WHERE batch_uuid = '$controlBatch' AND status = 'rejected'") != 1L) failures += "price batch decision control: the batch is not rejected"
        adminChange = control("cfg", "ADMIN", p, "/v1/admin/config/changes", cfgBody().toString(), setOf(201)).long("change_id")
        lkxChange = control("cfg-lkx", "ADMIN", p, "/v1/admin/config/changes", cfgBody("zone", oZone, 130).toString(), setOf(201)).long("change_id")
        // Class C3 changes (an outlet radius above 3 x its zone) wait for a second SUPERADMIN: one approved here, one kept pending for the probes.
        val c3Control = control("cfg-c3", "SUPERADMIN", p, "/v1/admin/config/changes", cfgBody("outlet", outlet, 900).toString(), setOf(201)).long("change_id")
        control("cfg-decision", "SUPERADMIN", p, "/v1/admin/config/changes/$c3Control/decision", obj("decision" to "approve").toString(), setOf(200), token = super2Token)
        c3Version = count("SELECT max(config_version) FROM app.cfg_version WHERE change_id = $c3Control")
        if (c3Version == 0L) failures += "config decision control: no version committed"
        control("cfg-rollback", "SUPERADMIN", p, "/v1/admin/config/versions/$c3Version/rollback", obj("mode" to "revert_this_version", "reason" to "qa authz audit rollback").toString(), setOf(201))
        val outlet2 = count("SELECT min(id) FROM app.outlet WHERE route_id = $route AND price_type = 'outlet' AND id > $outlet")
        c3Change = control("cfg-c3-pending", "SUPERADMIN", p, "/v1/admin/config/changes", cfgBody("outlet", outlet2, 950).toString(), setOf(201)).long("change_id")
        control("route-day", "TSO", p, "/v1/web-entry/route-day", routeDayBody().toString(), setOf(200))
        control("qc", "TSO", p, "/v1/web-entry/qc", qcBody().toString(), setOf(200))
        control("paper", "ADMIN", p, "/v1/admin/data-entry", paperBody().toString(), setOf(200))
        control("unlock", "SUPPORT", p, "/v1/admin/entry-unlocks", unlockBody().toString(), setOf(201))
        // Lead's ruling: a TSO issues device OTPs for SRs in its own reach (the LKX SR is refused in idor()).
        control("tso-otp-own-sr", "TSO", p, "/v1/admin/device-otps", otpBody().toString(), setOf(201))
        control("unlock-expire", "SUPPORT", p, "/v1/admin/entry-unlocks/${ownUnlock - 1}/expire", null, setOf(200))
        control("reports", "ADMIN", g, "/v1/reports", null, setOf(200))
        val cal = control("lkx-calibration", "ADMIN", g, "/v1/admin/config/calibration?scope_type=zone&scope_id=$oZone", null, setOf(200))
        if (lkxVisits == 0L || !cal.body.contains("\"visits\":$lkxVisits")) failures += "the LKX calibration control shows none of the $lkxVisits seeded visits: ${cal.body.take(200)}"
        val br = control("lkx-blast-radius", "ADMIN", g, "/v1/admin/config/blast-radius?scope_type=zone&scope_id=$oZone", null, setOf(200))
        if (!Regex("\"outlets\"\\s*:\\s*[1-9]").containsMatchIn(br.body)) failures += "the LKX blast-radius control counts no outlets: ${br.body.take(200)}"
    }

    // ---- ROLE: every route x every role (docs/24 s8.5 as the oracle) -------------------------------------------------

    /**
     * One route: the roles docs/24 s8.5 allows ([docs]); roles where docs and code disagree on a point already open
     * ([open]: served or refused there is a QUESTION, not a finding); allowed roles a business rule refuses with 403
     * on this probe's target ([rule], a NOTE).
     */
    class RouteSpec(val name: String, val method: HttpMethod, val path: () -> String, val body: (() -> String)?, val docs: Set<String>, val open: Set<String> = emptySet(), val rule: Set<String> = emptySet())

    private val all = setOf("SR", "AMO", "TSO", "DMO", "WM", "TOP", "ANALYST", "SUPPORT", "ADMIN", "SUPERADMIN")
    private val writers = setOf("ADMIN", "SUPERADMIN")
    /** docs/24 s8.5 "Config changes": TSO (radius proposals), ADMIN, SUPERADMIN; — for everyone else. */
    private val cfgDocs = setOf("TSO", "ADMIN", "SUPERADMIN")
    /** docs/24 s8.5 "Master data": R for DMO, WM, TOP, ANALYST, SUPPORT; W for ADMIN, SUPERADMIN (the TSO reads are an open point). */
    private val masterDocs = setOf("DMO", "WM", "TOP", "ANALYST", "SUPPORT", "ADMIN", "SUPERADMIN")
    /**
     * Lead's ruling (2026-10-18): the TSO reads prices (read only) and route assignments and issues SR device OTPs, as the
     * current Aron does (docs/14 3b/4c, docs/15, docs/19); docs/24 s8.5's "R (OTP view)" cell is stale
     * (docs/requests/qa-authz-audit-findings.md). Those three TSO rows are allowed; the scope probes stay as controls.
     */
    /** "Dashboards, reports, team map": TSO and above on the web (AMO reads its app home; SUPPORT sync health only). */
    private val dashDocs = setOf("TSO", "DMO", "WM", "TOP", "ANALYST", "ADMIN", "SUPERADMIN")

    private fun specs(): List<RouteSpec> {
        val g = HttpMethod.Get; val p = HttpMethod.Post
        val dec = { d: String -> { obj("decision" to d).toString() } }
        return listOf(
            RouteSpec("GET /v1/admin/enrolment-tokens", g, { "/v1/admin/enrolment-tokens" }, null, setOf("DMO", "WM", "SUPPORT", "ADMIN", "SUPERADMIN"), open = setOf("DMO", "WM")),
            RouteSpec("POST /v1/admin/enrolment-tokens", p, { "/v1/admin/enrolment-tokens" }, { tokenBody().toString() }, setOf("SUPPORT", "ADMIN", "SUPERADMIN")),
            RouteSpec("POST /v1/admin/enrolment-tokens/{}/revoke", p, { "/v1/admin/enrolment-tokens/$tokenId/revoke" }, null, setOf("SUPPORT", "ADMIN", "SUPERADMIN")),
            RouteSpec("GET /v1/admin/device-otps", g, { "/v1/admin/device-otps" }, null, setOf("TSO", "DMO", "WM", "SUPPORT", "ADMIN", "SUPERADMIN")),
            RouteSpec("POST /v1/admin/device-otps", p, { "/v1/admin/device-otps" }, { otpBody().toString() }, setOf("TSO", "SUPPORT", "ADMIN", "SUPERADMIN")),
            RouteSpec("GET /v1/admin/config/keys", g, { "/v1/admin/config/keys" }, null, cfgDocs),
            RouteSpec("GET /v1/admin/config/values", g, { "/v1/admin/config/values?key=cfg.geo.radius_m" }, null, cfgDocs),
            RouteSpec("GET /v1/admin/config/resolve", g, { "/v1/admin/config/resolve?key=cfg.geo.radius_m&node_type=zone&node_id=$zone" }, null, cfgDocs),
            RouteSpec("GET /v1/admin/config/changes", g, { "/v1/admin/config/changes" }, null, cfgDocs),
            RouteSpec("GET /v1/admin/config/changes/{}", g, { "/v1/admin/config/changes/$adminChange" }, null, cfgDocs),
            RouteSpec("POST /v1/admin/config/changes", p, { "/v1/admin/config/changes" }, { cfgBody().toString() }, cfgDocs),
            RouteSpec("POST /v1/admin/config/changes/{}/decision", p, { "/v1/admin/config/changes/$c3Change/decision" }, dec("approve"), writers, rule = setOf("ADMIN")),
            RouteSpec("POST /v1/admin/config/versions/{}/rollback", p, { "/v1/admin/config/versions/$c3Version/rollback" }, { obj("mode" to "revert_this_version", "reason" to "qa authz audit rollback").toString() }, cfgDocs, rule = setOf("ADMIN", "TSO")),
            RouteSpec("GET /v1/admin/config/versions", g, { "/v1/admin/config/versions" }, null, cfgDocs),
            RouteSpec("GET /v1/admin/config/versions/{}", g, { "/v1/admin/config/versions/$ownVersion" }, null, cfgDocs),
            RouteSpec("GET /v1/admin/config/blast-radius", g, { "/v1/admin/config/blast-radius?scope_type=zone&scope_id=$zone" }, null, cfgDocs),
            RouteSpec("GET /v1/admin/config/whatif", g, { "/v1/admin/config/whatif?scope_type=zone&scope_id=$zone&value=120" }, null, cfgDocs),
            RouteSpec("GET /v1/admin/config/density", g, { "/v1/admin/config/density?scope_type=zone&scope_id=$zone" }, null, cfgDocs),
            RouteSpec("GET /v1/admin/config/calibration", g, { "/v1/admin/config/calibration?scope_type=zone&scope_id=$zone" }, null, cfgDocs),
            RouteSpec("GET /v1/admin/config/reach/{}", g, { "/v1/admin/config/reach/$ownVersion?zone_id=$zone" }, null, cfgDocs),
            RouteSpec("GET /v1/admin/config/reach/{}/pending", g, { "/v1/admin/config/reach/$ownVersion/pending?zone_id=$zone" }, null, cfgDocs),
            RouteSpec("GET /v1/admin/dues-adjustments", g, { "/v1/admin/dues-adjustments" }, null, writers),
            RouteSpec("POST /v1/admin/dues-adjustments", p, { "/v1/admin/dues-adjustments" }, { dueBody().toString() }, writers),
            RouteSpec("POST /v1/admin/dues-adjustments/{}/decision", p, { "/v1/admin/dues-adjustments/$matrixAdjustment/decision" }, dec("approve"), setOf("SUPERADMIN")),
            RouteSpec("GET /v1/admin/prices", g, { "/v1/admin/prices" }, null, masterDocs + "TSO"),
            RouteSpec("POST /v1/admin/prices/preview", p, { "/v1/admin/prices/preview" }, { priceBody().toString() }, writers),
            RouteSpec("POST /v1/admin/prices", p, { "/v1/admin/prices" }, { priceBody().toString() }, writers),
            RouteSpec("POST /v1/admin/prices/batches/{}/decision", p, { "/v1/admin/prices/batches/$matrixBatch/decision" }, dec("approve"), setOf("SUPERADMIN")),
            RouteSpec("GET /v1/routes/{}/assignments", g, { "/v1/routes/$route/assignments" }, null, masterDocs + "TSO"),
            RouteSpec("GET /v1/web-entry/route-day", g, { "/v1/web-entry/route-day?route_id=$route&business_date=$day" }, null, setOf("TSO", "ADMIN", "SUPERADMIN")),
            RouteSpec("POST /v1/web-entry/route-day", p, { "/v1/web-entry/route-day" }, { routeDayBody().toString() }, setOf("TSO", "ADMIN", "SUPERADMIN")),
            RouteSpec("POST /v1/web-entry/qc", p, { "/v1/web-entry/qc" }, { qcBody().toString() }, setOf("TSO", "ADMIN", "SUPERADMIN")),
            RouteSpec("POST /v1/admin/data-entry", p, { "/v1/admin/data-entry" }, { paperBody().toString() }, setOf("SUPPORT", "ADMIN", "SUPERADMIN")),
            RouteSpec("GET /v1/admin/entry-unlocks", g, { "/v1/admin/entry-unlocks" }, null, setOf("SUPPORT", "ADMIN", "SUPERADMIN")),
            RouteSpec("POST /v1/admin/entry-unlocks", p, { "/v1/admin/entry-unlocks" }, { unlockBody().toString() }, setOf("SUPPORT", "ADMIN", "SUPERADMIN")),
            RouteSpec("POST /v1/admin/entry-unlocks/{}/expire", p, { "/v1/admin/entry-unlocks/$ownUnlock/expire" }, null, setOf("SUPPORT", "ADMIN", "SUPERADMIN")),
            RouteSpec("GET /v1/dashboards/summary", g, { "/v1/dashboards/summary?from=$day&to=$day" }, null, dashDocs),
            RouteSpec("GET /v1/dashboards/geo-validation", g, { "/v1/dashboards/geo-validation?from=$day&to=$day" }, null, dashDocs),
            RouteSpec("GET /v1/dashboards/daily-tracking", g, { "/v1/dashboards/daily-tracking?business_date=$day" }, null, dashDocs),
            RouteSpec("POST /v1/dashboards/daily-tracking/actions", p, { "/v1/dashboards/daily-tracking/actions" }, { actionBody().toString() }, setOf("TSO", "DMO", "WM", "ADMIN", "SUPERADMIN")),
            RouteSpec("GET /v1/dashboards/login-submit", g, { "/v1/dashboards/login-submit?business_date=$day" }, null, dashDocs),
            RouteSpec("GET /v1/dashboards/sync-health", g, { "/v1/dashboards/sync-health?business_date=$day" }, null, dashDocs + "SUPPORT"),
            // Everyone lists their own exports (ReportApi); judged per owner in exports().
            RouteSpec("GET /v1/report-exports", g, { "/v1/report-exports" }, null, all),
            // Releases (built by lead-backend, Q7; docs/24 s8.5: SUPPORT R, ADMIN W draft, SUPERADMIN publish).
            RouteSpec("GET /v1/admin/releases", g, { "/v1/admin/releases" }, null, setOf("SUPPORT", "ADMIN", "SUPERADMIN")),
            RouteSpec("POST /v1/admin/releases", p, { "/v1/admin/releases" }, { "{}" }, setOf("ADMIN", "SUPERADMIN")),
            // Contract only (targets and Astha outlet-SKU entry deferred, docs/27): 404 for everyone.
            RouteSpec("GET /v1/dashboards/targets", g, { "/v1/dashboards/targets" }, null, emptySet()),
            RouteSpec("POST /v1/web-entry/outlet-sku", p, { "/v1/web-entry/outlet-sku" }, { "{}" }, emptySet()),
        )
    }

    private val refused = setOf(401, 403, 404)

    private suspend fun HttpClient.roleMatrix() {
        for (s in specs()) for (role in roles) {
            val path = s.path(); val body = s.body?.invoke()
            val h = hit(role, s.method, path, body)
            val (v, why) = when {
                role in s.open -> (if (h.ok || h.status in refused) Verdict.QUESTION else Verdict.FINDING) to "docs/24 s8.5 and the code disagree for $role here (open point); answered ${h.status}"
                role !in s.docs && h.ok -> Verdict.FINDING to "served to a role outside docs/24 s8.5"
                role !in s.docs && h.status !in refused -> Verdict.FINDING to "a refused role must get 401/403/404, got ${h.status} ${h.code()} (work or validation before the role check)"
                role !in s.docs -> Verdict.OK to "refused"
                h.status == 403 && role in s.rule -> Verdict.NOTE to "an allowed role refused by a business rule on this target"
                h.status == 403 -> Verdict.QUESTION to "docs/24 s8.5 gives $role this route; the code refuses"
                else -> Verdict.OK to "allowed (${h.status})"
            }
            record("ROLE", s.name, role, if (role in s.docs) "allowed" else "denied", s.method, path, body, h, v, why)
        }
    }

    // ---- reports (docs/24 s8.5: AMO only its app-home reports, SUPPORT and SR none) ------------------------------------

    private val reportKeys = mutableListOf<String>()
    /** F-AMO-032 and F-AMO-033 (F-API-017b) plus the AMO Report tile's per-route reports (docs/requests/android-amo-report-access.md, D-AMO-REPORT-01). */
    private val amoReports = setOf("std-memo", "sales-summary", "route-memo", "route-std")

    private fun reportDocs(role: String, key: String) = when (role) {
        "SR", "SUPPORT" -> false
        "AMO" -> key in amoReports
        else -> true
    }

    private suspend fun HttpClient.reports() {
        reportKeys += hit("ADMIN", HttpMethod.Get, "/v1/reports").json!!.jsonObject["items"]!!.jsonArray.map { it.jsonObject["report_key"]!!.jsonPrimitive.content }
        for (role in roles) {
            val served = mutableListOf<String>(); val badStatus = mutableListOf<String>(); val refusedAllowed = mutableListOf<String>()
            for (key in reportKeys) {
                val path = "/v1/reports/$key/query"; val body = reportBody().toString()
                val h = hit(role, HttpMethod.Post, path, body)
                val docs = reportDocs(role, key)
                if (!docs && h.ok) served += key
                if (!docs && !h.ok && h.status !in refused) badStatus += "$key:${h.status}"
                if (docs && h.status == 403) refusedAllowed += key
                record("ROLE", "POST /v1/reports/{}/query", role, key, HttpMethod.Post, path, body, h, Verdict.NOTE, "docs allow=$docs, answered ${h.status}")
            }
            val q = "POST /v1/reports/{key}/query"
            if (served.isNotEmpty()) record("ROLE", "POST /v1/reports/{}/query", role, "docs-denied", HttpMethod.Post, q, null, null, Verdict.FINDING,
                "$role runs ${served.size} reports docs/24 s8.5 does not give it (${served.joinToString()})" + if (role == "SUPPORT") "; docs give SUPPORT 'R (sync health)' only and DASHBOARD_ROLES already excludes it; ReportEngine.WEB_ROLES includes it" else "")
            if (badStatus.isNotEmpty()) record("ROLE", "POST /v1/reports/{}/query", role, "denied-status", HttpMethod.Post, q, null, null, Verdict.FINDING, "refusals other than 401/403/404: ${badStatus.joinToString()}")
            if (refusedAllowed.isNotEmpty()) record("ROLE", "POST /v1/reports/{}/query", role, "docs-allowed-refused", HttpMethod.Post, q, null, null, Verdict.QUESTION, "docs give $role these reports, the code refuses: ${refusedAllowed.joinToString()}")
        }
    }

    // ---- report exports: who lists and opens whose -----------------------------------------------------------------

    private suspend fun HttpClient.exports() {
        val g = HttpMethod.Get
        val owner = mapOf(tsoExport to "TSO", dmoExport to "DMO")
        for (role in roles) {
            val list = hit(role, g, "/v1/report-exports")
            val others = owner.filter { (id, o) -> o != role && list.body.contains(id) }.values
            val v = when { others.isEmpty() -> Verdict.OK; role in writers -> Verdict.NOTE; else -> Verdict.FINDING }
            record("IDOR", "GET /v1/report-exports", role, "others-listed", g, "/v1/report-exports", null, list, v,
                if (others.isEmpty()) "only own exports" else "lists the exports of ${others.joinToString()}" + if (v == Verdict.FINDING) " (ReportApi.ADMIN_VIEWERS includes SUPPORT; docs/24 s8.5 gives SUPPORT no reports)" else " (admin role)")
            for ((id, o) in owner) {
                if (o == role) continue
                val h = hit(role, g, "/v1/report-exports/$id")
                val vv = when { !h.ok -> Verdict.OK; role in writers -> Verdict.NOTE; else -> Verdict.FINDING }
                record("IDOR", "GET /v1/report-exports/{}", role, "export-of-$o", g, "/v1/report-exports/$id", null, h, vv,
                    if (h.ok) "opens the $o's export job (a done job carries a signed read URL of the file, ExportJobService.job)" else "refused ${h.status}")
            }
        }
        // A scoped caller cannot widen the list with user_id.
        val h = hit("TSO", g, "/v1/report-exports?user_id=$dmoId")
        record("IDOR", "GET /v1/report-exports", "TSO", "user_id-of-DMO", g, "/v1/report-exports?user_id=$dmoId", null, h, if (h.body.contains(dmoExport)) Verdict.FINDING else Verdict.OK, "user_id is ignored for non-admin roles")
    }

    // ---- IDOR: scoped callers with the other division's ids -------------------------------------------------------------

    private fun leaksOther(text: String) = text.contains("lkx", ignoreCase = true) ||
        Regex("\"(zone_id|route_id|outlet_id|node_id|scope_id|id)\"\\s*:\\s*($oZone|$oRoute|$oOutlet)\\b").containsMatchIn(text) ||
        Regex("\"value\"\\s*:\\s*137\\b").containsMatchIn(text)

    /** Reach-checked refusals that answer ERR_FORBIDDEN instead of the contract's ERR_OUT_OF_SCOPE (a NOTE, by route). */
    private val forbiddenNotOutOfScope = sortedSetOf<String>()

    /**
     * A scoped caller names an id outside its reach. On a reach-checked route ([reachFree] false) any 2xx is a finding and
     * the expected answer is 403 (ERR_OUT_OF_SCOPE); [validation] allows a 400 with that problem detail (a body that is
     * refused as inconsistent before reach). On a reach-free read or a list ([reachFree] true) the answer is judged by
     * content: the other division's marker, ids or seeded values.
     */
    private suspend fun HttpClient.idorProbe(route: String, role: String, variant: String, method: HttpMethod, path: String, body: String?, acted: (() -> Boolean)? = null, reachFree: Boolean = false, validation: String? = null) {
        val h = hit(role, method, path, body)
        val didAct = acted?.invoke() == true
        val leak = h.ok && leaksOther(h.body)
        val (v, why) = when {
            didAct -> Verdict.FINDING to "acted on the other division"
            reachFree && leak -> Verdict.FINDING to "answered with the other division's data"
            reachFree && h.ok && method != HttpMethod.Get -> Verdict.FINDING to "a write naming the other division was accepted"
            reachFree -> Verdict.OK to if (h.ok) "answered ${h.status} without anything of the other division" else "refused ${h.status} ${h.code()}"
            h.ok -> Verdict.FINDING to "an out-of-reach id was answered ${h.status} (expected 403 ERR_OUT_OF_SCOPE)" + if (leak) "; the answer carries the other division" else ""
            h.status == 403 -> Verdict.OK to "refused 403 ${h.code()}"
            h.status == 400 && validation != null && h.str("detail")?.contains(validation) == true -> Verdict.OK to "refused 400 (${h.str("detail")})"
            else -> Verdict.FINDING to "an out-of-reach id got ${h.status} ${h.code()} (expected 403 ERR_OUT_OF_SCOPE)"
        }
        if (!reachFree && h.status == 403 && h.code() == "ERR_FORBIDDEN") forbiddenNotOutOfScope += route
        record("IDOR", route, role, variant, method, path, body, h, v, why)
    }

    private suspend fun HttpClient.idor() {
        val g = HttpMethod.Get; val p = HttpMethod.Post
        idorProbe("GET /v1/web-entry/route-day", "TSO", "other-route", g, "/v1/web-entry/route-day?route_id=$oRoute&business_date=$day", null)
        idorProbe("POST /v1/web-entry/route-day", "TSO", "other-route", p, "/v1/web-entry/route-day", routeDayBody(oRoute).toString(),
            acted = { count("SELECT count(*) FROM app.web_entry_route_day WHERE route_id = $oRoute AND entered_by = $tsoId") > 0 })
        idorProbe("POST /v1/web-entry/qc", "TSO", "other-zone", p, "/v1/web-entry/qc", qcBody(oZone, oRoute).toString(),
            acted = { count("SELECT count(*) FROM app.qc_summary_entry WHERE zone_id = $oZone AND entered_by = $tsoId") > 0 })
        idorProbe("POST /v1/web-entry/qc", "TSO", "own-zone-other-route", p, "/v1/web-entry/qc", qcBody(zone, oRoute).toString(),
            acted = { count("SELECT count(*) FROM app.qc_summary_entry WHERE route_id = $oRoute") > 0 }, validation = "body.route_id")
        for ((type, id) in listOf("zone" to oZone, "route" to oRoute, "outlet" to oOutlet)) {
            idorProbe("POST /v1/admin/config/changes", "TSO", "other-$type", p, "/v1/admin/config/changes", cfgBody(type, id, 110).toString(),
                acted = { count("SELECT count(*) FROM app.cfg_change c WHERE c.requested_by = $tsoId AND c.items::text LIKE '%\"scope_id\": $id%'") > 0 })
        }
        idorProbe("POST /v1/admin/config/versions/{}/rollback", "TSO", "other-division-version", p, "/v1/admin/config/versions/$lkxVersion/rollback", obj("mode" to "revert_this_version", "reason" to "qa authz audit rollback lkx").toString(),
            acted = { count("SELECT count(*) FROM app.cfg_change WHERE requested_by = $tsoId AND reason LIKE '%rollback lkx%'") > 0 })
        for (role in listOf("TSO", "DMO")) {
            idorProbe("GET /v1/admin/config/blast-radius", role, "other-zone", g, "/v1/admin/config/blast-radius?scope_type=zone&scope_id=$oZone", null)
            idorProbe("GET /v1/admin/config/whatif", role, "other-zone", g, "/v1/admin/config/whatif?scope_type=zone&scope_id=$oZone&value=120", null)
            idorProbe("GET /v1/admin/config/density", role, "other-zone", g, "/v1/admin/config/density?scope_type=zone&scope_id=$oZone", null)
            idorProbe("GET /v1/admin/config/calibration", role, "other-zone", g, "/v1/admin/config/calibration?scope_type=zone&scope_id=$oZone", null)
            idorProbe("GET /v1/admin/config/reach/{}", role, "other-zone", g, "/v1/admin/config/reach/$ownVersion?zone_id=$oZone", null)
            idorProbe("GET /v1/admin/config/reach/{}/pending", role, "other-zone", g, "/v1/admin/config/reach/$ownVersion/pending?zone_id=$oZone", null)
            // No reach filter in ConfigService.values/changes/resolve/versionDetail: the other division's settings and change sets.
            idorProbe("GET /v1/admin/config/values", role, "other-zone", g, "/v1/admin/config/values?key=cfg.geo.radius_m&scope_type=zone&scope_id=$oZone", null, reachFree = true)
            idorProbe("GET /v1/admin/config/resolve", role, "other-zone", g, "/v1/admin/config/resolve?key=cfg.geo.radius_m&node_type=zone&node_id=$oZone", null, reachFree = true)
            idorProbe("GET /v1/admin/config/changes", role, "list", g, "/v1/admin/config/changes", null, reachFree = true)
            idorProbe("GET /v1/admin/config/changes/{}", role, "other-division-change", g, "/v1/admin/config/changes/$lkxChange", null, reachFree = true)
            idorProbe("GET /v1/admin/config/versions/{}", role, "other-division-version", g, "/v1/admin/config/versions/$lkxVersion", null, reachFree = true)
            idorProbe("GET /v1/routes/{}/assignments", role, "other-route", g, "/v1/routes/$oRoute/assignments", null)
            idorProbe("GET /v1/admin/device-otps", role, "other-zone", g, "/v1/admin/device-otps?zone_id=$oZone", null)
            idorProbe("GET /v1/admin/device-otps", role, "list", g, "/v1/admin/device-otps", null, reachFree = true)
            idorProbe("POST /v1/dashboards/daily-tracking/actions", role, "other-route", p, "/v1/dashboards/daily-tracking/actions", actionBody(oRoute).toString())
        }
        idorProbe("POST /v1/admin/device-otps", "TSO", "other-sr", p, "/v1/admin/device-otps", otpBody(oSrId).toString())
        for (role in listOf("AMO", "TSO", "DMO")) {
            for ((sel, id) in listOf("zone" to oZone, "territory" to oTerritory, "division" to oDivision, "route" to oRoute)) {
                for (key in reportKeys) idorProbe("POST /v1/reports/{}/query", role, "$key geo.$sel", p, "/v1/reports/$key/query", reportBody(obj(sel to listOf(id))).toString())
            }
            for ((level, id) in listOf("zone" to oZone, "territory" to oTerritory, "division" to oDivision)) {
                idorProbe("GET /v1/dashboards/login-submit", role, "node $level", g, "/v1/dashboards/login-submit?business_date=$day&level=$level&node_id=$id", null)
                idorProbe("GET /v1/dashboards/sync-health", role, "node $level", g, "/v1/dashboards/sync-health?business_date=$day&level=$level&node_id=$id", null)
                idorProbe("GET /v1/dashboards/summary", role, "node $level", g, "/v1/dashboards/summary?from=$day&to=$day&level=$level&node_id=$id", null)
                idorProbe("GET /v1/dashboards/geo-validation", role, "node $level", g, "/v1/dashboards/geo-validation?from=$day&to=$day&level=$level&node_id=$id", null)
                idorProbe("GET /v1/dashboards/daily-tracking", role, "node $level", g, "/v1/dashboards/daily-tracking?business_date=$day&level=$level&node_id=$id", null)
            }
        }
        // National writers: whole-country reach by role (ReachCalculator.nationalRoles); a body that mixes divisions must still be refused.
        for ((variant, role, b) in listOf(
            Triple("own-sr-own-route-other-outlet", "ADMIN", paperBody(o = oOutlet)),
            Triple("other-sr-own-route", "ADMIN", paperBody(user = oSrId, username = "lkxsr1001")),
            Triple("memo-for-an-admin-user", "SUPPORT", paperBody(user = adminId, username = "admin1001")),
        )) {
            val h = hit(role, p, "/v1/admin/data-entry", b.toString())
            val cu = b["client_uuid"]!!.jsonPrimitive.content
            val stored = count("SELECT count(*) FROM app.memo WHERE client_uuid = '$cu'") > 0
            record("IDOR", "POST /v1/admin/data-entry", role, variant, p, "/v1/admin/data-entry", b.toString(), h, if (h.ok || stored) Verdict.FINDING else Verdict.OK,
                if (h.ok || stored) "an inconsistent paper memo was keyed (stored=$stored)" else "refused (${h.code()} ${h.str("detail")})")
        }
        run {
            val path = "/v1/admin/entry-unlocks/$oUnlock/expire"
            val h = hit("SUPPORT", p, path)
            record("IDOR", "POST /v1/admin/entry-unlocks/{}/expire", "SUPPORT", "other-division-row", p, path, null, h, Verdict.NOTE, "SUPPORT is national (docs/24 s8.4); the other division's row is in reach")
        }
        record("IDOR", "national-roles", "ADMIN", "n/a", HttpMethod.Get, "-", null, null, Verdict.NOTE,
            "ADMIN, SUPERADMIN, SUPPORT, TOP and ANALYST are national by role (ReachCalculator.nationalRoles): dues adjustments, price batches, enrolment tokens, data entry and entry unlocks have no horizontal boundary")
    }

    // ---- MASS / STRICT ------------------------------------------------------------------------------------------------

    private suspend fun HttpClient.massProbe(route: String, role: String, field: String, method: HttpMethod, path: String, body: JsonObject, stored: (() -> Boolean)?, token: String? = tokens[role]) {
        val h = hit(role, method, path, body.toString(), token)
        val st = stored?.invoke() == true
        val name = field.replace("unknown_field_qa", "unknown")
        val v = if (h.ok || st) Verdict.FINDING else Verdict.OK
        val why = when {
            st -> "the forbidden field's request was stored"
            h.ok -> "a body with '$name' was accepted (strict decoding expected 400)"
            h.status !in setOf(400, 422) -> "refused with ${h.status} ${h.code()} (not 400/422)"
            else -> "rejected"
        }
        record(if (name == "unknown") "STRICT" else "MASS", route, role, name, method, path, body.toString(), h, v, why)
    }

    /** A maker deciding his own proposal: exactly 409 with [code], and the row still pending afterwards. */
    private suspend fun HttpClient.sodProbe(route: String, path: String, code: String, stillPending: () -> Boolean, token: String = tokens["SUPERADMIN"]!!) {
        val body = obj("decision" to "approve").toString()
        val h = hit("SUPERADMIN", HttpMethod.Post, path, body, token)
        val pending = stillPending()
        val good = h.status == 409 && h.code() == code && pending
        record("MASS", route, "SUPERADMIN", "own-proposal", HttpMethod.Post, path, body, h, if (good) Verdict.OK else Verdict.FINDING,
            if (good) "refused $code, still pending" else "separation of duties broken: ${h.status} ${h.code()}, still pending=$pending (expected 409 $code)")
    }

    private suspend fun HttpClient.massAssignment() {
        val p = HttpMethod.Post
        val forbidden = listOf("status" to "approved", "approved_by" to adminId, "created_by" to adminId, "decided_by" to superId, "version" to 99, "is_current" to true, "zone_id" to oZone, "unknown_field_qa" to "x")
        for ((f, v) in forbidden + listOf("created_by_user_id" to superId, "decided_by_user_id" to superId, "adjustment_id" to 1, "decided_at" to "2027-01-03T04:00:00Z")) {
            val b = dueBody().with(f, v); val cu = b["client_uuid"]!!.jsonPrimitive.content
            massProbe("POST /v1/admin/dues-adjustments", "ADMIN", f, p, "/v1/admin/dues-adjustments", b, { count("SELECT count(*) FROM app.dues_adjustment WHERE client_uuid = '$cu'") > 0 })
        }
        val decPath = "/v1/admin/dues-adjustments/$ownAdjustment/decision"
        val ownDecided = { count("SELECT count(*) FROM app.dues_adjustment WHERE id = $ownAdjustment AND status <> 'pending'") > 0 }
        for ((f, v) in listOf("amount_mtk" to 1, "status" to "approved", "decided_by" to adminId, "outlet_id" to oOutlet, "unknown_field_qa" to "x")) {
            massProbe("POST /v1/admin/dues-adjustments/{}/decision", "SUPERADMIN", f, p, decPath, obj("decision" to "reject", f to v), ownDecided)
        }
        run {
            val body = """{"decision":"reject","decision":"approve"}"""
            val h = hit("SUPERADMIN", p, decPath, body)
            val decided = ownDecided()
            record("STRICT", "POST /v1/admin/dues-adjustments/{}/decision", "SUPERADMIN", "duplicate-key", p, decPath, body, h, if (h.ok || decided) Verdict.FINDING else Verdict.OK, if (h.ok || decided) "a body with a duplicated key was accepted" else "rejected")
        }
        // Separation of duties: dues adjustment, C3 config change and price batch, each decided by its own SUPERADMIN maker.
        run {
            val b = dueBody(); val cu = b["client_uuid"]!!.jsonPrimitive.content
            val made = hit("SUPERADMIN", p, "/v1/admin/dues-adjustments", b.toString())
            if (made.status != 201) failures += "SoD setup: SUPERADMIN could not propose an adjustment (${made.status})"
            val id = count("SELECT coalesce(max(id), 0) FROM app.dues_adjustment WHERE client_uuid = '$cu'")
            sodProbe("POST /v1/admin/dues-adjustments/{}/decision", "/v1/admin/dues-adjustments/$id/decision", "ERR_SEPARATION_OF_DUTIES", { count("SELECT count(*) FROM app.dues_adjustment WHERE id = $id AND status = 'pending'") == 1L })
        }
        sodProbe("POST /v1/admin/config/changes/{}/decision", "/v1/admin/config/changes/$c3Change/decision", "ERR_CFG_SELF_APPROVAL", { count("SELECT count(*) FROM app.cfg_change WHERE change_id = $c3Change AND status = 'pending_approval'") == 1L })
        run {
            val b = uuid()
            val pv = hit("SUPERADMIN", p, "/v1/admin/prices/preview", priceBody(b, skuPrice * 40, "2027-01-24").toString())
            val pb = hit("SUPERADMIN", p, "/v1/admin/prices", priceBody(b, skuPrice * 40, "2027-01-24").toString())
            if (pv.status != 200 || pb.status != 201 || count("SELECT count(*) FROM app.price_batch WHERE batch_uuid = '$b' AND status = 'pending_approval'") != 1L) failures += "SoD setup: SUPERADMIN batch not pending (${pv.status}, ${pb.status})"
            sodProbe("POST /v1/admin/prices/batches/{}/decision", "/v1/admin/prices/batches/$b/decision", "ERR_SEPARATION_OF_DUTIES", { count("SELECT count(*) FROM app.price_batch WHERE batch_uuid = '$b' AND status = 'pending_approval'") == 1L })
        }
        // Price batches.
        for ((f, v) in forbidden + listOf("price_list_version" to 99, "submitted_by" to adminId, "decided_by_user_id" to superId)) {
            val b = priceBody().with(f, v); val bu = b["batch_uuid"]!!.jsonPrimitive.content
            for (op in listOf("/v1/admin/prices/preview", "/v1/admin/prices")) {
                massProbe("POST $op", "ADMIN", f, p, op, b, { count("SELECT count(*) FROM app.price_batch WHERE batch_uuid = '$bu'") > 0 })
            }
        }
        run {
            val row = obj("sku_id" to sku, "price_type" to "outlet", "amount_mtk" to skuPrice + 1, "per_base_qty" to 1, "valid_to" to "2027-02-01")
            val b = priceBody().with("prices", listOf(row)); val bu = b["batch_uuid"]!!.jsonPrimitive.content
            massProbe("POST /v1/admin/prices/preview", "ADMIN", "prices[].valid_to", p, "/v1/admin/prices/preview", b, { count("SELECT count(*) FROM app.price_batch WHERE batch_uuid = '$bu'") > 0 })
        }
        val pbPath = "/v1/admin/prices/batches/$pendingBatch/decision"
        for ((f, v) in listOf("status" to "published", "decided_by" to adminId, "price_list_version" to 1, "unknown_field_qa" to "x")) {
            massProbe("POST /v1/admin/prices/batches/{}/decision", "SUPERADMIN", f, p, pbPath, obj("decision" to "reject", f to v),
                { count("SELECT count(*) FROM app.price_batch WHERE batch_uuid = '$pendingBatch' AND status <> 'pending_approval'") > 0 })
        }
        run {
            val h = hit("ADMIN", p, pbPath, obj("decision" to "approve").toString())
            record("MASS", "POST /v1/admin/prices/batches/{}/decision", "ADMIN", "maker-decides", p, pbPath, null, h, if (h.ok || h.status !in refused) Verdict.FINDING else Verdict.OK, if (h.ok) "the maker (ADMIN) decided his own batch" else "refused ${h.status}")
        }
        // Config change sets.
        for ((f, v) in forbidden + listOf("requested_by" to adminId, "approver" to superId, "risk_class" to 0, "change_id" to 1, "config_version" to 1)) {
            val marker = "qa mass $f"
            massProbe("POST /v1/admin/config/changes", "ADMIN", f, p, "/v1/admin/config/changes", cfgBody().with("reason", marker).with(f, v), { count("SELECT count(*) FROM app.cfg_change WHERE reason = '$marker'") > 0 })
        }
        for ((f, v) in listOf("is_current" to true, "old_value" to 1, "config_version" to 1)) {
            val marker = "qa mass item $f"
            val item = obj("key" to "cfg.geo.radius_m", "scope_type" to "zone", "scope_id" to zone, "value" to 120, f to v)
            massProbe("POST /v1/admin/config/changes", "ADMIN", "changes[].$f", p, "/v1/admin/config/changes", obj("reason" to marker, "changes" to listOf(item)), { count("SELECT count(*) FROM app.cfg_change WHERE reason = '$marker'") > 0 })
        }
        run {
            val marker = "qa break glass by admin"
            massProbe("POST /v1/admin/config/changes", "ADMIN", "break_glass", p, "/v1/admin/config/changes", cfgBody().with("reason", marker).with("break_glass", true), { count("SELECT count(*) FROM app.cfg_change WHERE reason LIKE '$marker%'") > 0 })
        }
        run {
            val marker = "qa tso price rail change"
            val b = obj("reason" to marker, "changes" to listOf(obj("key" to "cfg.price.max_change_pct", "scope_type" to "global", "scope_id" to 0, "value" to 100)))
            massProbe("POST /v1/admin/config/changes", "TSO", "key-outside-radius", p, "/v1/admin/config/changes", b, { count("SELECT count(*) FROM app.cfg_change WHERE reason = '$marker'") > 0 })
        }
        for ((f, v) in listOf("approver" to adminId, "status" to "applied", "unknown_field_qa" to "x")) {
            massProbe("POST /v1/admin/config/changes/{}/decision", "SUPERADMIN", f, p, "/v1/admin/config/changes/$c3Change/decision", obj("decision" to "approve", f to v),
                { count("SELECT count(*) FROM app.cfg_change WHERE change_id = $c3Change AND status <> 'pending_approval'") > 0 }, token = super2Token)
        }
        for ((f, v) in listOf("mode" to "rollback_to_this_version", "unknown_field_qa" to "x", "requested_by" to adminId)) {
            val marker = "qa mass rollback $f"
            val b = obj("mode" to "revert_this_version", "reason" to marker).with(f, v)
            if (f == "mode") continue
            massProbe("POST /v1/admin/config/versions/{}/rollback", "SUPERADMIN", f, p, "/v1/admin/config/versions/$c3Version/rollback", b, { count("SELECT count(*) FROM app.cfg_change WHERE reason LIKE '%$marker%'") > 0 })
        }
        // Enrolment tokens and their revocation.
        for ((f, v) in listOf("used_count" to 0, "created_by" to adminId, "revoked_at" to "2027-01-03T04:00:00Z", "token" to "A".repeat(43), "token_prefix" to "AAAAAA", "unknown_field_qa" to "x")) {
            val marker = "qa mass $f"
            massProbe("POST /v1/admin/enrolment-tokens", "ADMIN", f, p, "/v1/admin/enrolment-tokens", tokenBody().with("note", marker).with(f, v), { count("SELECT count(*) FROM app.enrolment_token WHERE note = '$marker'") > 0 })
        }
        // Device OTPs.
        for ((f, v) in listOf("otp" to "123456", "issued_by" to adminId, "unknown_field_qa" to "x")) {
            massProbe("POST /v1/admin/device-otps", "SUPPORT", f, p, "/v1/admin/device-otps", otpBody().with("reason", "qa mass otp $f").with(f, v), { count("SELECT count(*) FROM app.device_otp WHERE reason = 'qa mass otp $f'") > 0 })
        }
        // Web entry (TSO, own route).
        for ((f, v) in listOf("saved_by_user_id" to adminId, "source" to "app", "target_outlets" to 1, "app_overlap" to false, "entered_by" to adminId, "unknown_field_qa" to "x")) {
            val b = routeDayBody().with(f, v); val cu = b["client_uuid"]!!.jsonPrimitive.content
            massProbe("POST /v1/web-entry/route-day", "TSO", f, p, "/v1/web-entry/route-day", b, { count("SELECT count(*) FROM app.web_entry_route_day WHERE client_uuid = '$cu'") > 0 })
        }
        run {
            // `sale_qty_base` is an answer field the request type shares: ignored, never kept against issue - return.
            val line = obj("sku_id" to sku, "issue_qty_base" to 20, "return_qty_base" to 5, "memo_count" to 1, "sale_qty_base" to 9999)
            val b = routeDayBody().with("lines", listOf(line))
            val h = hit("TSO", p, "/v1/web-entry/route-day", b.toString())
            val echoed = h.ok && h.body.contains("9999")
            record("MASS", "POST /v1/web-entry/route-day", "TSO", "lines[].sale_qty_base", p, "/v1/web-entry/route-day", b.toString(), h, if (echoed) Verdict.FINDING else Verdict.NOTE,
                if (echoed) "the client's sale_qty_base was kept" else "accepted and ignored (sale = issue - return on the server); the answer-only field passes strict decoding")
        }
        for ((f, v) in listOf("entered_by" to adminId, "replayed" to true, "stored_rows" to 5, "unknown_field_qa" to "x")) {
            val b = qcBody().with(f, v); val cu = b["client_uuid"]!!.jsonPrimitive.content
            massProbe("POST /v1/web-entry/qc", "TSO", f, p, "/v1/web-entry/qc", b, { count("SELECT count(*) FROM app.qc_summary_entry WHERE client_uuid = '$cu'") > 0 })
        }
        run {
            val b = JsonObject(qcBody(r = null).with("source", "warehouse").with("reason", "qa warehouse by a tso") - "route_id"); val cu = b["client_uuid"]!!.jsonPrimitive.content
            massProbe("POST /v1/web-entry/qc", "TSO", "source=warehouse", p, "/v1/web-entry/qc", b, { count("SELECT count(*) FROM app.qc_summary_entry WHERE client_uuid = '$cu'") > 0 })
        }
        // Paper backfill: the server prices; the browser never sends money or a status.
        for ((f, v) in listOf("net_mtk" to 1, "gross_mtk" to 1, "status" to "created", "created_by" to adminId, "unknown_field_qa" to "x")) {
            val b = paperBody().with(f, v); val cu = b["client_uuid"]!!.jsonPrimitive.content
            massProbe("POST /v1/admin/data-entry", "ADMIN", f, p, "/v1/admin/data-entry", b, { count("SELECT count(*) FROM app.memo WHERE client_uuid = '$cu'") > 0 })
        }
        for ((f, v) in listOf("price_mtk" to 1, "base_price_mtk" to 1, "gross_mtk" to 1)) {
            val b = paperBody().with("lines", listOf(obj("sku_id" to sku, "qty_base" to 10, "line_kind" to "sale", f to v))); val cu = b["client_uuid"]!!.jsonPrimitive.content
            massProbe("POST /v1/admin/data-entry", "ADMIN", "lines[].$f", p, "/v1/admin/data-entry", b, { count("SELECT count(*) FROM app.memo WHERE client_uuid = '$cu'") > 0 })
        }
        // Entry unlocks.
        for ((f, v) in listOf("created_by" to adminId, "expires_at" to "2030-01-01T00:00:00Z", "status" to "active", "unlock_id" to 1, "unknown_field_qa" to "x")) {
            val marker = "qa mass unlock $f"
            massProbe("POST /v1/admin/entry-unlocks", "SUPPORT", f, p, "/v1/admin/entry-unlocks", unlockBody().with("reason", marker).with(f, v), { count("SELECT count(*) FROM app.entry_unlock WHERE reason = '$marker'") > 0 })
        }
        // Tracking actions.
        for ((f, v) in listOf("created_by_user_id" to adminId, "notified_user_ids" to listOf(oSrId), "unknown_field_qa" to "x")) {
            massProbe("POST /v1/dashboards/daily-tracking/actions", "TSO", f, p, "/v1/dashboards/daily-tracking/actions", actionBody().with(f, v), null)
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
            val status = h.str("status")
            record("MASS", "POST /v1/admin/config/changes", "ADMIN", "control-off $key", p, "/v1/admin/config/changes", b.toString(), h, if (h.ok && status != "pending_approval") Verdict.QUESTION else Verdict.OK,
                if (h.ok && status != "pending_approval") "one ADMIN schedules '$key' = $value with no second approver (C2 in the registry and docs/24 s9.5); this switches off ${if (key.contains("mfa")) "MFA for the admin roles" else "the price maker-checker rail"}. Should it be C3?" else "needs a second person")
            h.long("change_id").takeIf { it > 0 }?.let { id -> hit("ADMIN", p, "/v1/admin/config/changes/$id/decision", obj("decision" to "cancel").toString()) }
        }
    }

    // ---- LIMIT --------------------------------------------------------------------------------------------------------

    private suspend fun HttpClient.limits() {
        val lists = listOf(
            "GET /v1/admin/enrolment-tokens" to "/v1/admin/enrolment-tokens?active_only=false",
            "GET /v1/admin/dues-adjustments" to "/v1/admin/dues-adjustments",
            "GET /v1/admin/prices" to "/v1/admin/prices",
            "GET /v1/admin/entry-unlocks" to "/v1/admin/entry-unlocks",
            "GET /v1/admin/device-otps" to "/v1/admin/device-otps",
            "GET /v1/admin/config/values" to "/v1/admin/config/values?key=cfg.geo.radius_m",
            "GET /v1/admin/config/changes" to "/v1/admin/config/changes",
            "GET /v1/admin/config/versions" to "/v1/admin/config/versions",
            "GET /v1/admin/config/reach/{}/pending" to "/v1/admin/config/reach/$ownVersion/pending?zone_id=$zone",
            "GET /v1/report-exports" to "/v1/report-exports",
            "GET /v1/dashboards/sync-health" to "/v1/dashboards/sync-health?business_date=$day",
            "GET /v1/dashboards/daily-tracking" to "/v1/dashboards/daily-tracking?business_date=$day",
        )
        /** Lists seeded with more than 100 rows (the default page must be exactly 100) and with more than 50 (must not be 50). */
        val over100 = setOf("GET /v1/admin/enrolment-tokens", "GET /v1/admin/dues-adjustments", "GET /v1/admin/entry-unlocks")
        val over50 = setOf("GET /v1/admin/config/values", "GET /v1/admin/config/changes", "GET /v1/admin/config/versions")
        val g = HttpMethod.Get
        for ((route, base) in lists) {
            val sep = if ('?' in base) "&" else "?"
            for (bad in listOf("0", "-1", "501", "1000000", "2147483648", "99999999999999999999", "abc", "1.5", "")) {
                val path = "$base${sep}limit=$bad"
                val h = hit("ADMIN", g, path)
                record("LIMIT", route, "ADMIN", "limit=$bad", g, path, null, h, if (h.ok || h.status != 400) Verdict.FINDING else Verdict.OK,
                    if (h.ok) "served ${h.items()} items for an out-of-contract limit" else "refused ${h.status} ${h.code()}")
            }
            run {
                val path = "$base${sep}limit=500"
                val h = hit("ADMIN", g, path)
                val v = if (!h.ok || (h.items() ?: 0) > 500) Verdict.FINDING else Verdict.OK
                record(if (h.ok) "LIMIT" else "LIMIT-CONTRACT", route, "ADMIN", "limit=500", g, path, null, h, v,
                    if (!h.ok) "the contract maximum (500) is refused: the server's own maximum is lower (contract divergence, not unbounded)" else "items=${h.items()}")
            }
            run {
                val h = hit("ADMIN", g, base)
                val n = h.items()
                record("LIMIT", route, "ADMIN", "no-limit", g, base, null, h, if (n != null && n > 100) Verdict.FINDING else if (h.ok) Verdict.OK else Verdict.NOTE, "items=$n (contract default 100)")
                if (route in over100 && n != 100) record("LIMIT-CONTRACT", route, "ADMIN", "default", g, base, null, h, Verdict.FINDING, "default page is $n with more than 100 rows seeded; contract says 100")
                if (route in over50 && n != null && n < 100) record("LIMIT-CONTRACT", route, "ADMIN", "default", g, base, null, h, Verdict.FINDING, "default page is $n with more than 50 rows seeded; contract says 100")
            }
        }
        val key = reportKeys.first()
        for (bad in listOf<Any>(0, -1, 7, 501, 1000, 1000000, 2147483647)) {
            val path = "/v1/reports/$key/query"; val body = reportBody(pageSize = bad).toString()
            val h = hit("ADMIN", HttpMethod.Post, path, body)
            record("LIMIT", "POST /v1/reports/{}/query", "ADMIN", "page_size=$bad", HttpMethod.Post, path, body, h, if (h.ok || h.status != 400) Verdict.FINDING else Verdict.OK, if (h.ok) "served rows=${h.items()}" else "refused ${h.status}")
        }
        run {
            val path = "/v1/reports/$key/query"; val body = obj("period" to obj("date" to day), "output" to obj("format" to "json")).toString()
            val h = hit("ADMIN", HttpMethod.Post, path, body)
            val ps = h.str("page_size")
            record("LIMIT", "POST /v1/reports/{}/query", "ADMIN", "no-page_size", HttpMethod.Post, path, body, h, if (!h.ok || ps != "50") Verdict.FINDING else Verdict.OK, "page_size=$ps (contract default 50)")
        }
    }

    // ---- STEPUP -------------------------------------------------------------------------------------------------------

    /**
     * Switches `cfg.auth.mfa_required_roles`. The boundary is the earlier of the database's and the API's clock, so both
     * the server-config snapshot (database now()) and the resolver (API clock) see the new row on any calendar date.
     */
    private fun setMfaRoles(json: String) = world.fresh.db.jdbi.useHandle<Exception> { h ->
        val at = "least(now(), '${now.get()}'::timestamptz)"
        h.execute("UPDATE app.cfg_value SET effective_to = $at WHERE key = 'cfg.auth.mfa_required_roles' AND scope_type = 'global' AND effective_to IS NULL")
        h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '$json'::jsonb, $at, max(config_version), 'qa authz audit' FROM app.cfg_version")
        // The server config snapshot lives 30 s on the API's clock.
        now.set(now.get().plusSeconds(31))
    }

    private suspend fun HttpClient.stepUp() {
        val p = HttpMethod.Post; val g = HttpMethod.Get
        val login = hit("ADMIN", p, "/v1/auth/login", """{"username":"admin1001","password":"${world.password}","client":"web"}""", token = null)
        val refresh = login.setCookies.firstOrNull { it.startsWith("aron_rt=") }?.substringAfter("aron_rt=")?.substringBefore(';')?.takeIf { it.isNotEmpty() }
        setMfaRoles("""["ADMIN", "SUPERADMIN", "SUPPORT"]""")
        try {
            for (role in listOf("ADMIN", "SUPERADMIN", "SUPPORT")) {
                val user = callers[role]!!.user
                val fresh = hit(role, p, "/v1/auth/login", """{"username":"$user","password":"${world.password}","client":"web"}""", token = null)
                val issued = fresh.str("access_token") != null
                record("STEPUP", "POST /v1/auth/login", role, "mfa-policy-on", p, "/v1/auth/login", "{username, password, client: web}", fresh, if (issued || fresh.str("status") != "mfa_required") Verdict.FINDING else Verdict.OK, "status=${fresh.str("status")}, access token issued=$issued")
                fresh.str("mfa_token")?.let { mt ->
                    val h = hit(role, g, "/v1/admin/entry-unlocks", token = mt)
                    record("STEPUP", "GET /v1/admin/entry-unlocks", role, "mfa-token-as-bearer", g, "/v1/admin/entry-unlocks", null, h, if (h.status != 401) Verdict.FINDING else Verdict.OK, if (h.ok) "the MFA-step token opened an admin route" else "refused ${h.status}")
                }
            }
            if (refresh != null) {
                val h = hit("ADMIN", p, "/v1/auth/refresh", """{"refresh_token":null,"grant":"full"}""", token = null, cookie = "aron_rt=$refresh")
                val issuedR = h.str("access_token") != null
                record("STEPUP", "POST /v1/auth/refresh", "ADMIN", "pwd-family", p, "/v1/auth/refresh", "{refresh_token: null, grant: full} + aron_rt cookie", h, if (issuedR) Verdict.FINDING else Verdict.OK, if (issuedR) "a password-only family refreshed under the MFA policy" else "refused ${h.status} ${h.code()}")
            } else failures += "step-up: the web login set no aron_rt cookie"
            // The resource side: each role's password-only access token from before the policy, on its own writes.
            val writes = mapOf(
                "ADMIN" to listOf<Pair<String, () -> String>>(
                    "POST /v1/admin/dues-adjustments" to { dueBody().toString() },
                    "POST /v1/admin/prices/preview" to { priceBody().toString() },
                    "POST /v1/admin/config/changes" to { cfgBody().with("reason", "qa step-up probe change").toString() },
                    "POST /v1/admin/enrolment-tokens" to { tokenBody().toString() },
                    "POST /v1/admin/entry-unlocks" to { unlockBody().toString() },
                    "POST /v1/admin/data-entry" to { paperBody().toString() },
                ),
                "SUPERADMIN" to listOf(
                    "POST /v1/admin/dues-adjustments" to { dueBody().toString() },
                    "POST /v1/admin/config/changes" to { cfgBody().with("reason", "qa step-up probe superadmin").toString() },
                ),
                "SUPPORT" to listOf(
                    "POST /v1/admin/entry-unlocks" to { unlockBody().toString() },
                    "POST /v1/admin/enrolment-tokens" to { tokenBody().toString() },
                    "POST /v1/admin/device-otps" to { otpBody().toString() },
                ),
            )
            for ((role, list) in writes) {
                val served = mutableListOf<String>()
                for ((route, body) in list) {
                    val path = route.substringAfter(' '); val b = body()
                    val h = hit(role, p, path, b)
                    if (h.ok) served += "$route ${h.status}"
                    record("STEPUP", route, role, "amr=pwd", p, path, b, h, Verdict.NOTE, if (h.ok) "served to a password-only token" else "refused ${h.status} ${h.code()}")
                }
                if (served.isNotEmpty()) record("STEPUP", "admin-writes", role, "amr-pwd-token-under-mfa-policy", p, "-", null, null, Verdict.FINDING,
                    "no route checks the token's amr: with cfg.auth.mfa_required_roles naming $role, a password-only access token minted before the policy applied still writes: ${served.joinToString()} (MFA is enforced only at login and refresh; a web token lives up to 15 min)")
            }
        } finally {
            setMfaRoles("[]")
        }
    }

    // ---- PII ----------------------------------------------------------------------------------------------------------

    private val piiMarkers = listOf(
        "phone number" to Regex("(?<![0-9A-Za-z-])01[3-9][0-9]{8}(?![0-9A-Za-z])"), "NID" to Regex("\"nid(_enc)?\"\\s*:\\s*\"[^\"]+"),
        "owner name" to Regex("Owner [A-Z0-9]+-\\d+"), "address" to Regex("Road \\d+, "),
    )
    private val fullName = Regex("Test (SR|AMO|TSO|DMO|Admin|Super Admin|Support) ?(\\(|\")|QA (WM|TOP|ANALYST|SUPERADMIN)\"")

    private fun piiHits(text: String, reportPii: Boolean): List<String> =
        piiMarkers.mapNotNull { (what, rx) -> rx.find(text)?.let { "$what '${it.value.take(4)}…'" } } + (if (reportPii && fullName.containsMatchIn(text)) listOf("full name (a pii column in the reports)") else emptyList())

    private fun piiScan(route: String, role: String, variant: String, h: Hit, reportPii: Boolean) {
        val hits = piiHits(h.body, reportPii)
        if (hits.isNotEmpty()) rows += Row("PII", route, role, variant, route, h.status, Verdict.FINDING, "no token carries the pii claim, yet the 2xx answer has: ${hits.joinToString()}")
    }

    /** The scanner finds what it is meant to find: a seeded outlet's phone, owner and address, and a report full name. */
    private fun piiScannerSelfTest() {
        val (phone, owner, address) = world.fresh.db.jdbi.withHandle<Triple<String, String, String>, Exception> { h ->
            h.createQuery("SELECT contact_number, owner_name, address FROM app.outlet WHERE id = $outlet").map { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.one()
        }
        val sample = """{"rows":[{"phone":"$phone","owner_name":"$owner","address":"$address","full_name":"Test SR (Mirpur)"}]}"""
        val found = piiHits(sample, reportPii = true)
        if (found.size != 4) failures += "PII scanner self-test found ${found.size} of 4 markers: $found"
        if (piiHits("""{"client_uuid":"0171234567-8","id":10171234567}""", reportPii = false).isNotEmpty()) failures += "PII scanner flags ids as phone numbers"
    }

    private fun notes() {
        if (forbiddenNotOutOfScope.isNotEmpty()) record("NOTE", "out-of-reach-refusals", "SCOPED", "error-code", HttpMethod.Get, "-", null, null, Verdict.NOTE,
            "refused with 403 ERR_FORBIDDEN instead of ERR_OUT_OF_SCOPE: ${forbiddenNotOutOfScope.joinToString()}")
        record("NOTE", "GET /v1/me", "ALL", "pii-claim", HttpMethod.Get, "/v1/me", null, null, Verdict.QUESTION,
            "TokenSubject.pii is never set at login, so no role ever gets phone or owner columns (docs/21 s4.5 gives the PII right to some supervisor and admin roles, with re-authentication within cfg.pii.reauth_min, which does not exist yet). Intended for now?")
        record("NOTE", "GET /v1/admin/releases", "ALL", "unserved", HttpMethod.Get, "/v1/admin/releases", null, null, Verdict.QUESTION,
            "the contract has /v1/admin/releases, /{release_id} and /policy (docs/24 s8.5: SUPPORT R, ADMIN W draft, SUPERADMIN publish) but no route serves them; the enrolment-token path reads app_release directly. Who builds it and when?")
        record("NOTE", "editor_permission", "ADMIN", "unenforced", HttpMethod.Post, "/v1/admin/config/changes", null, null, Verdict.QUESTION,
            "cfg_key.editor_permission (cfg.edit.security, cfg.edit.finance, ...) is loaded but never checked, and no token carries permissions (TokenSubject.permissions is never set): every ADMIN edits every C0..C2 key, and `finance.backdate` can never be granted")
    }

    // ---- verdict ------------------------------------------------------------------------------------------------------

    private fun baseline(): Set<String> = javaClass.getResource("/authz-audit-baseline.txt")!!.readText().lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()

    private fun reportText() = buildString {
        appendLine("# Authorisation audit (AuthzAuditTest): ${rows.size} probes, ${rows.count { it.verdict == Verdict.FINDING }} findings, ${rows.count { it.verdict == Verdict.QUESTION }} questions")
        for (v in listOf(Verdict.FINDING, Verdict.QUESTION, Verdict.NOTE, Verdict.OK)) {
            appendLine(); appendLine("## $v")
            rows.filter { it.verdict == v }.sortedBy { it.key }.forEach { r -> appendLine("${r.key}\t${r.status}\t${r.request}\t${r.detail}") }
        }
    }

    private fun writeReport() {
        File("build").mkdirs()
        File("build/authz-audit.txt").writeText(reportText())
        File("build/authz-audit-keys.txt").writeText(rows.filter { it.verdict == Verdict.FINDING || it.verdict == Verdict.QUESTION }.map { "${it.verdict} ${it.key}" }.distinct().sorted().joinToString("\n", postfix = "\n"))
    }

    @Test
    fun positiveControlsHold() {
        assertTrue(failures.isEmpty(), "the audit's own setup is broken (these must hold for the probes to mean anything):\n" + failures.joinToString("\n"))
    }

    @Test
    fun noSecretReachesTheReport() {
        assertTrue(secrets.isNotEmpty(), "the enrolment-token control returned no secret to check for")
        val text = reportText() + File("build/authz-audit.txt").readText()
        val leaked = secrets.filter { it.length >= 16 && text.contains(it) }
        assertTrue(leaked.isEmpty(), "a real secret reached the report")
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
        val missing = specs().flatMap { s -> roles.filter { r -> rows.none { it.check == "ROLE" && it.route == s.name && it.role == r && it.status != 0 } }.map { "${s.name} $it" } }
        assertTrue(missing.isEmpty(), "not probed: $missing")
        val reportProbes = rows.count { it.check == "ROLE" && it.route == "POST /v1/reports/{}/query" && it.status != 0 }
        assertTrue(reportKeys.isNotEmpty() && reportProbes == reportKeys.size * roles.size, "every report for every role ($reportProbes)")
    }
}
