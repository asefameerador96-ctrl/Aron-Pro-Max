package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.PathSegmentWildcardRouteSelector
import io.ktor.server.routing.RoutingNode
import java.io.File
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

/**
 * N-014: the world the contract and scope-leak harnesses drive through the PRODUCTION wiring: a migrated database with
 * the db lane's seed (db/seed), a throwaway SR and phone for the admin writes (suspend, reset, deactivate) so the callers
 * keep their sessions, the per-minute limits lifted, TOTP off for the web roles (the contract suite's world makes DMO
 * MFA-required for its MFA fixture) and an in-memory blob store behind every blob port ([FakeBlobs]). Each tag in [mirrors] loads the seed
 * again under other codes (for `LKX`: division D-LKX, territory T-LKX, zone Z-LKX, routes and outlets LKX-*, users
 * lkx*): a complete division no scoped seed user may ever see, with its own SR (`lkxsr1001`, primary on LKX-SR-*).
 */
class ApiWorld(
    private val repoRoot: File,
    mirrors: List<String> = emptyList(),
    val clock: AronClock = AronClock { Instant.parse("2027-01-03T04:00:00Z") },
    extraRecordHandlers: List<com.aktcl.aron.backend.platform.RecordHandler> = emptyList(),
    /** Load [conformanceSeed]: the rows the contract suite's fixtures need that no served operation creates. */
    conformance: Boolean = false,
) : AutoCloseable {
    data class Caller(val user: String, val client: String, val device: String?)

    val fresh: FreshDb = FreshDb.create()
    val password = "throwaway-" + System.nanoTime()
    /** Resource ids by path segment (`outlets`, `routes`, ...) in the home world and, with the mirror, in the other. */
    val ids = HashMap<String, Long>()
    val otherIds = HashMap<String, Long>()
    val wiring: Wiring
    /** Each user's latest login answer (refresh token, bind or MFA token). */
    val logins = java.util.concurrent.ConcurrentHashMap<String, com.fasterxml.jackson.databind.JsonNode>()
    /** The in-memory blob store behind every blob port (SAS, photo writer, photo read): blob calls answer 2xx here. */
    val blobs = FakeBlobs()
    /** The key pair of the seeded SR phone ([SR_PHONE]): its public JWK replaces the seed placeholder, so the harness signs device proofs. */
    val phoneKey: java.security.KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    /** The API's signing key (a throwaway per world): a second wiring over a restored copy accepts the same tokens. */
    val jwtKeyFile: File

    init {
        // The blob ports are the harness's in-memory store, but other Azure wiring (exports) still reads the process
        // environment: with ARON_BLOB_ACCOUNT set the harness would reach real Azure (checker finding, N-014).
        check(System.getenv("ARON_BLOB_ACCOUNT").isNullOrEmpty()) { "unset ARON_BLOB_ACCOUNT for the qa harness" }
        val seed = repoRoot.resolve("db/seed").listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }
        fresh.dataSource.connection.use { c ->
            seed.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
            for (tag in mirrors) listOf("01_geography_people.sql", "03_outlets_and_plans.sql").forEach { n -> c.createStatement().use { it.execute(mirrored(repoRoot.resolve("db/seed/$n").readText(), tag)) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            for ((k, v) in listOf("cfg.api.rl.device_per_min" to "100000", "cfg.api.rl.user_per_min" to "100000", "cfg.auth.mfa_required_roles" to if (conformance) "[\"DMO\"]" else "[]")) {
                h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT '$k', 'global', 0, '$v'::jsonb, TIMESTAMPTZ '$CFG_FROM', max(config_version), 'qa harness' FROM app.cfg_version")
            }
            h.execute("INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password) SELECT 'qa9001', 'QA Throwaway', role, designation, 'QA9001', locale, home_zone_id, false, false FROM app.app_user WHERE username = 'sr1001'")
            h.execute(
                "INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, trust_level, public_key_jwk, public_key_thumbprint, attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version) " +
                    "SELECT '$THROWAWAY_PHONE', flavour, app_package, status, device_owner, lockdown_level, trust_level, public_key_jwk, 'qa-throwaway-0009', attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version FROM app.device WHERE device_uuid = '$SR_PHONE'",
            )
            h.createUpdate("UPDATE app.device SET public_key_jwk = CAST(:jwk AS jsonb) WHERE device_uuid = '$SR_PHONE'").bind("jwk", publicJwk(phoneKey)).execute()
            fun load(into: HashMap<String, Long>, zone: String, outletPrefix: String, user: String, device: String?) {
                fun one(sql: String) = h.createQuery(sql).mapTo(Long::class.java).findOne().orElse(null)
                mapOf(
                    "outlets" to "SELECT min(id) FROM app.outlet WHERE code LIKE '$outletPrefix-%'",
                    "routes" to "SELECT min(r.id) FROM app.route r JOIN app.zone z ON z.id = r.zone_id WHERE z.code = '$zone'",
                    "users" to "SELECT id FROM app.app_user WHERE username = '$user'",
                    "zones" to "SELECT id FROM app.zone WHERE code = '$zone'",
                    "clusters" to "SELECT min(c.id) FROM app.cluster c JOIN app.zone z ON z.id = c.zone_id WHERE z.code = '$zone'",
                    "territories" to "SELECT territory_id FROM app.zone WHERE code = '$zone'",
                    "divisions" to "SELECT t.division_id FROM app.zone z JOIN app.territory t ON t.id = z.territory_id WHERE z.code = '$zone'",
                    "skus" to "SELECT min(id) FROM app.sku",
                    "devices" to (device?.let { "SELECT id FROM app.device WHERE device_uuid = '$it'" } ?: "SELECT NULL::bigint WHERE false"),
                ).forEach { (k, sql) -> one(sql)?.let { into[k] = it } }
                into["zone"] = into["zones"] ?: 0
            }
            load(ids, "Z-MIR", "MIR", "qa9001", THROWAWAY_PHONE)
            if (conformance) conformanceSeed(h)
            mirrors.firstOrNull()?.let { tag -> load(otherIds, "Z-$tag", tag, "${tag.lowercase()}sr1001", null) }
        }
        jwtKeyFile = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to jwtKeyFile.absolutePath)), clock, extraRecordHandlers, blobPorts = blobs.ports())
    }

    /**
     * Rows for the contract suite (N-014 E) that no served operation creates, in the home world, and their ids in [ids]:
     * a second pending outlet request (reject), an export job (read), a risk signal (review), yesterday's route-day
     * (Daily Tracking action) and the dw dimensions (the worker role fills them in production).
     */
    private fun conformanceSeed(h: org.jdbi.v3.core.Handle) {
        fun one(sql: String) = h.createQuery(sql).mapTo(Long::class.java).one()
        h.execute(
            "INSERT INTO app.outlet_change_request (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, request_type, outlet_id, proposed, note) " +
                "SELECT '$SECOND_REQUEST', '$SECOND_REQUEST', DATE '$BUSINESS_DATE', u.id, o.route_id, TIMESTAMPTZ '${BUSINESS_DATE}T03:00:00Z', 0, 'info', o.id, '{\"name\":\"QA Reject Me\"}'::jsonb, 'qa harness' " +
                "FROM app.app_user u, app.outlet o WHERE u.username = 'tso1001' AND o.id = (SELECT min(id) FROM app.outlet WHERE code LIKE 'MIR-%' AND status = 'active')",
        )
        h.execute(
            "INSERT INTO app.report_export (export_id, report_key, user_id, format, status, filters, scope_hash, row_count, finished_at) " +
                "SELECT '$SAMPLE_UUID', 'retailer-list', id, 'xlsx', 'done', '{}'::jsonb, 'qa', 0, TIMESTAMPTZ '${BUSINESS_DATE}T03:30:00Z' FROM app.app_user WHERE username = 'tso1001'",
        )
        ids["risk-signals"] = one(
            "INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, route_id, zone_id, score, config_version) " +
                "SELECT 'GEO_MOCK', 2, DATE '$BUSINESS_DATE', 'user', 'qa-signal-1', u.id, r.id, r.zone_id, 50, 0 FROM app.app_user u, app.route r WHERE u.username = 'sr1001' AND r.code = 'MIR-SR-D' RETURNING id",
        )
        h.execute("INSERT INTO app.route_day (route_id, business_date, planned) SELECT id, DATE '$YESTERDAY', true FROM app.route WHERE code = 'MIR-SR-D'")
        com.aktcl.aron.backend.analytics.Aggregator.refreshDimensions(h)
        ids["outlet_req"] = one("SELECT max(id) FROM app.outlet WHERE code LIKE 'MIR-%' AND status = 'active'")
        ids["sr_user"] = one("SELECT id FROM app.app_user WHERE username = 'sr1001'")
        ids["sub_channel"] = one("SELECT min(id) FROM app.sub_channel WHERE status = 'active'")
        ids["sr_route"] = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'") // sr1001's primary route
        ids["variants"] = one("SELECT min(id) FROM app.product_node WHERE level = 'variant'")
        ids["paper_outlet"] = one("SELECT id FROM app.outlet WHERE code = 'MIR-D-001'")
        ids["paper_sku"] = one("SELECT id FROM app.sku WHERE code = 'MaxR-10S'")
        // A pending C3 config change requested by admin1001 (decided by superadmin1001: never the requester).
        ids["cfg_change"] = one(
            "INSERT INTO app.cfg_change (status, items, reason, risk_class, requested_by, requested_at) SELECT 'pending_approval', " +
                "jsonb_build_array(jsonb_build_object('key', 'cfg.geo.radius_m', 'scope_type', 'outlet', 'scope_id', o.id, 'value', 120, 'effective_from', null, 'effective_to', null, 'old_value', null)), " +
                "'qa harness: seeded pending change', 3, u.id, TIMESTAMPTZ '${BUSINESS_DATE}T03:00:00Z' FROM app.app_user u, (SELECT min(id) AS id FROM app.outlet WHERE code LIKE 'MIR-%') o WHERE u.username = 'admin1001' RETURNING change_id",
        )
        // A published SR release (enrolment tokens provision one; no served operation publishes releases). version_code 1
        // keeps GET /v1/app/update-check answering "no update" for the sample version.
        h.execute(
            "INSERT INTO app.app_release (flavour, version_name, version_code, abi, sha256, size_bytes, download_url, signing_cert_sha256, status, rollout_pct, created_by, published_at, published_by) " +
                "SELECT 'sr', '1.0.0', 1, 'universal', sha256('qa-release-sr'::bytea), 1000000, 'https://example.com/qa/aron-sr.apk', sha256('qa-signing-cert'::bytea), 'published', 100, a.id, now(), s.id " +
                "FROM app.app_user a, app.app_user s WHERE a.username = 'admin1001' AND s.username = 'superadmin1001'",
        )
        // A throwaway AMO with a zone scope for the scope write (an SR's reach comes from its routes, so it is refused).
        h.execute("INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password) SELECT 'qa9002', 'QA Throwaway AMO', role, designation, 'QA9002', locale, home_zone_id, false, false FROM app.app_user WHERE username = 'amo1001'")
        h.execute("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, reason) SELECT u.id, 'zone', z.id, DATE '2026-01-01', 'qa harness' FROM app.app_user u, app.zone z WHERE u.username = 'qa9002' AND z.code = 'Z-MIR'")
        ids["scope_user"] = one("SELECT id FROM app.app_user WHERE username = 'qa9002'")
        // Another SR route of Z-MIR for the route-assignment create (MIR-SR-D already carries qa9001's backfill cover).
        ids["cover_route"] = one("SELECT min(id) FROM app.route WHERE code LIKE 'MIR-SR-%' AND code <> 'MIR-SR-D' AND kind = 'sr' AND status = 'active'")
        // A proof phone with the harness key (device-proof calls: policy, nonce, status), so the SR phone's trust never moves;
        // an unbound SR and an unbound phone with the placeholder key (bind-device).
        h.execute("INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, trust_level, public_key_jwk, public_key_thumbprint, attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version) SELECT '$PROOF_PHONE', flavour, app_package, 'active', device_owner, lockdown_level, 'normal', public_key_jwk, 'qa-proof-000a', attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version FROM app.device WHERE device_uuid = '$SR_PHONE'")
        h.execute("INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, trust_level, public_key_jwk, public_key_thumbprint, attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version) SELECT '$BIND_PHONE', flavour, app_package, 'active', device_owner, lockdown_level, trust_level, public_key_jwk, 'qa-bind-000b', attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version FROM app.device WHERE device_uuid = '$THROWAWAY_PHONE'")
        h.execute("INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password, password_hash) SELECT '$BIND_USER', 'QA Bind', role, designation, 'QA9003', locale, home_zone_id, false, false, password_hash FROM app.app_user WHERE username = 'sr1001'")
        ids["bind_user"] = one("SELECT id FROM app.app_user WHERE username = '$BIND_USER'")
        // A stored photo of sr1001 (read-url reads app.media; a media_meta record leaves it pending_blob).
        h.execute(
            "INSERT INTO app.media (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, purpose, ref_type, ref_client_uuid, sha256, bytes, width, height, blob_path, taken_at, status, stored_at, blob_bytes, blob_sha256) " +
                "SELECT '$STORED_PHOTO', gen_random_uuid(), DATE '$BUSINESS_DATE', u.id, TIMESTAMPTZ '${BUSINESS_DATE}T03:00:00Z', 0, 'outlet_capture', 'visit', gen_random_uuid(), decode(repeat('ab', 32), 'hex'), 1000, 640, 480, " +
                "'photos/$BUSINESS_DATE/$SR_PHONE/$STORED_PHOTO.jpg', TIMESTAMPTZ '${BUSINESS_DATE}T03:00:00Z', 'stored', now(), 1000, decode(repeat('ab', 32), 'hex') FROM app.app_user u WHERE u.username = 'sr1001'",
        )
        // A paged bundle section: the threshold at its floor (500) and 501 open tasks for sr1001.
        h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.bundle.page_threshold_rows', 'global', 0, '500'::jsonb, TIMESTAMPTZ '$CFG_FROM', max(config_version), 'qa harness' FROM app.cfg_version")
        h.execute(
            "INSERT INTO app.task (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, task_type_code, assignee_user_id, title, source, status) " +
                "SELECT gen_random_uuid(), gen_random_uuid(), DATE '$BUSINESS_DATE', a.id, TIMESTAMPTZ '${BUSINESS_DATE}T03:00:00Z', 0, 'general', s.id, 'QA paging task ' || g, 'online', 'ongoing' " +
                "FROM generate_series(1, 501) g, app.app_user s, app.app_user a WHERE s.username = 'sr1001' AND a.username = 'amo1001'",
        )
        // A tutorial video asset (the sample asset is content_av; a tutorial needs its own purpose).
        h.execute("INSERT INTO app.admin_asset (asset_id, purpose, mime, bytes, sha256, blob_path) VALUES ('$TUTORIAL_ASSET', 'tutorial_video', 'video/mp4', 1, decode(repeat('00', 32), 'hex'), 'assets/tutorial_video/$TUTORIAL_ASSET.mp4')")
        // Paper backfill is keyed for the throwaway SR on sr1001's route (a cover), so sr1001's day is untouched.
        h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason) SELECT r.id, u.id, 'cover', DATE '2026-01-01', 'qa harness: paper backfill' FROM app.route r, app.app_user u WHERE r.code = 'MIR-SR-D' AND u.username = 'qa9001'")
        // Quarantine: a phone memo held as memo_no_duplicate of the paper memo (with its held line), for match-backfill,
        // and a separate open item for resolve. Payloads are valid sync records (the quarantine read validates them).
        ids["quarantine_match"] = quarantine(h, "00000000-0000-4000-8000-0000000000e2", PAPER_MEMO_NO, "memo_no_duplicate", "held: memo_no $PAPER_MEMO_NO already stored", withLine = true)
        ids["quarantine_resolve"] = quarantine(h, "00000000-0000-4000-8000-0000000000e5", "qa9001-270103-002", "scope_out_of_reach", "qa resolve fixture", withLine = false)
        // A quarantine item of sr1001 resolved after the bundle cursor (the clock): the delta carries the resolution.
        h.execute(
            "INSERT INTO app.sync_quarantine (client_uuid, record_type, code, status, payload_sha256, payload, user_id, business_date, resolved_at, resolution_note) " +
                "SELECT '$RESOLVED_ITEM', 'memo', 'memo_no_duplicate', 'discarded', decode(repeat('cd', 32), 'hex'), jsonb_set(q.payload, '{client_uuid}', '\"$RESOLVED_ITEM\"'), u.id, DATE '$BUSINESS_DATE', TIMESTAMPTZ '${BUSINESS_DATE}T04:30:00Z', 'qa harness' " +
                "FROM app.app_user u, app.sync_quarantine q WHERE u.username = 'sr1001' AND q.id = ${ids["quarantine_resolve"]}",
        )
    }

    /** One held phone memo of qa9001 on MIR-SR-D (net 9200 = one MaxR-10S stick at the outlet price), optionally with its held line; the memo row's id. */
    private fun quarantine(h: org.jdbi.v3.core.Handle, memo: String, memoNo: String, code: String, detail: String, withLine: Boolean): Long {
        val line = memo.dropLast(1) + "f"
        val visit = memo.dropLast(1) + "d"
        return h.createQuery(
            """
            WITH v AS (SELECT (SELECT id FROM app.app_user WHERE username = 'qa9001') u, (SELECT id FROM app.route WHERE code = 'MIR-SR-D') r,
                              (SELECT id FROM app.outlet WHERE code = 'MIR-D-001') o, (SELECT id FROM app.sku WHERE code = 'MaxR-10S') s),
            env AS (SELECT v.*, jsonb_build_object('family_uuid', '$visit', 'schema_version', 1, 'business_date', '$BUSINESS_DATE',
                           'captured_at', '2027-01-02T18:01:00.000Z', 'captured_elapsed_ms', 0, 'boot_count', 0, 'clock_offset_ms', 0, 'captured_offline', false,
                           'route_id', v.r, 'bundle_stale', false, 'config_version', 1) e FROM v),
            recs AS (
              SELECT u, r, 'memo' t, '$memo'::uuid cu, :detail d, 1 ord,
                     e || jsonb_build_object('type', 'memo', 'client_uuid', '$memo', 'rank', 1, 'payload', jsonb_build_object(
                       'visit_client_uuid', '$visit', 'outlet_id', o, 'memo_no', '$memoNo', 'memo_kind', 'sale',
                       'committed_at', '2027-01-02T18:01:00.000Z', 'price_list_date', '$BUSINESS_DATE', 'price_type', 'outlet', 'gross_mtk', 9200, 'offer_discount_mtk', 0,
                       'drp_discount_mtk', 0, 'qc_deduction_mtk', 0, 'round_adj_mtk', 0, 'net_mtk', 9200, 'paid_mtk', 0, 'due_mtk', 9200, 'is_credit', true,
                       'line_count', 1, 'discount_line_count', 0, 'qc_line_count', 0, 'rounding_mode', 'half_up_paisa')) p FROM env
              UNION ALL
              SELECT u, r, 'memo_line', '$line'::uuid, 'memo $memo: held with its memo', 2,
                     e || jsonb_build_object('type', 'memo_line', 'client_uuid', '$line', 'rank', 2, 'payload', jsonb_build_object(
                       'memo_client_uuid', '$memo', 'line_no', 1, 'sku_id', s, 'line_kind', 'sale', 'qty_entered', 1, 'unit_entered', 'stick',
                       'pack_factor', 10, 'qty_base', 1, 'price_type', 'outlet', 'price_valid_from', '2026-01-01', 'base_price_mtk', 9200, 'price_per_qty', 1, 'gross_mtk', 9200)) FROM env
               WHERE :withLine)
            INSERT INTO app.sync_quarantine (client_uuid, record_type, code, payload_sha256, payload, detail, user_id, route_id, business_date)
            SELECT cu, t, :code, sha256(convert_to(p::text, 'UTF8')), p, d, u, r, DATE '$BUSINESS_DATE' FROM recs ORDER BY ord RETURNING id
            """.trimIndent(),
        ).bind("detail", detail).bind("withLine", withLine).bind("code", code).mapTo(Long::class.java).list().min() // the memo is inserted first
    }

    override fun close() { wiring.database?.close(); fresh.close() }

    suspend fun login(client: HttpClient, c: Caller): String? {
        val r = client.request("/v1/auth/login") {
            method = HttpMethod.Post; contentType(ContentType.Application.Json); header("X-App-Version", APP_VERSION)
            setBody("""{"username":"${c.user}","password":"$password","client":"${c.client}"${c.device?.let { ",\"device_uuid\":\"$it\"" } ?: ""}}""")
        }
        val text = r.bodyAsText()
        val answer = ContractModel.json.readTree(text).also { logins[c.user] = it }
        return answer.path("access_token").takeIf { it.isTextual }?.asText().also {
            if (it == null) println("ApiWorld: no token for ${c.user}: ${r.status} ${text.take(200)}")
        }
    }

    /**
     * One call of [op] as [c]: path and query parameters that name a resource (`id` after its collection segment,
     * `*_id`, `node_id`) from [pathIds], other parameters and headers from the contract, a generated body whose
     * `device_uuid` is the caller's phone, gzipped when the operation requires `Content-Encoding`. [onInvalidBody] gets
     * the validation messages of a generated body the contract itself rejects; [bodyEdit] may change the body before
     * it is sent. [extraQuery] is appended as is.
     */
    suspend fun call(
        client: HttpClient, contract: ContractModel, op: ContractModel.Operation, c: Caller, token: String?,
        pathIds: Map<String, Long> = ids, extraQuery: String = "", onInvalidBody: (List<String>) -> Unit = {},
        includeOptionalIds: Boolean = false, bodyEdit: ((com.fasterxml.jackson.databind.JsonNode) -> Unit)? = null,
        queryOverrides: Map<String, String> = emptyMap(),
        /** Path parameter values by name (uuids and ids an earlier answer created), before [pathIds] and samples. */
        pathValues: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        /** `X-Device-Proof` from the final method, path, sent bytes and JSON text (null: none); see [deviceProofOf]. */
        proof: ((method: String, path: String, sent: ByteArray, json: String?) -> String)? = null,
        /** A body sent as is (multipart) instead of the generated JSON sample. */
        rawBody: io.ktor.http.content.OutgoingContent.ByteArrayContent? = null,
        /** Sees the JSON body after [bodyEdit] (the contract suite checks a fixture's body against the request schema). */
        afterEdit: ((com.fasterxml.jackson.databind.JsonNode) -> Unit)? = null,
    ): HttpResponse {
        val path = pathFor(contract, op, pathIds, pathValues)
        val query = (
            op.parameters.filter { it.where == "query" && it.required }.map { p -> p.name + "=" + URLEncoder.encode(queryOverrides[p.name] ?: paramValue(p.name, pathIds) ?: contract.sampleText(p.schema, p.name), "UTF-8") } +
                listOfNotNull(extraQuery.ifEmpty { null })
            ).joinToString("&")
        val body = op.requestSchema?.let { s ->
            val sample = contract.sample(s, optional = if (includeOptionalIds) { n -> ID_FIELD.matches(n) } else null)
            contract.validate(s, sample).takeIf { it.isNotEmpty() }?.let(onInvalidBody)
            // The device_uuid of a body is the caller's phone (the server checks it against the token).
            if (c.device != null) (sample as? com.fasterxml.jackson.databind.node.ObjectNode)?.takeIf { it.has("device_uuid") }?.put("device_uuid", c.device)
            bodyEdit?.invoke(sample)
            afterEdit?.invoke(sample)
            ContractModel.json.writeValueAsString(sample)
        }
        val gzip = op.parameters.any { it.where == "header" && it.name == "Content-Encoding" && it.required }
        val sent: ByteArray = rawBody?.bytes() ?: body?.let { if (gzip) gz(it) else it.toByteArray() } ?: ByteArray(0)
        return client.request(path + if (query.isEmpty()) "" else "?$query") {
            method = HttpMethod.parse(op.method)
            if ("X-App-Version" !in headers) header("X-App-Version", APP_VERSION)
            headers.forEach { (k, v) -> header(k, v) }
            proof?.let { header("X-Device-Proof", it(op.method, path, sent, body)) }
            if (token != null && "Authorization" !in headers) header("Authorization", "Bearer $token")
            if ("X-Device-Id" !in headers) c.device?.let { header("X-Device-Id", it) }
            for (p in op.parameters.filter { it.where == "header" && it.required && it.name !in headers }) {
                when (p.name) {
                    "X-Device-Id" -> if (c.device == null) header(p.name, SR_PHONE)
                    "If-Match" -> header(p.name, "\"1\"")
                    "Content-Encoding" -> header(p.name, "gzip")
                    else -> header(p.name, contract.sampleText(p.schema, p.name))
                }
            }
            if (rawBody != null) setBody(rawBody)
            else if (body != null) {
                if (gzip) setBody(io.ktor.http.content.ByteArrayContent(sent, ContentType.Application.Json)) // the bytes the proof hashed
                else { contentType(ContentType.Application.Json); setBody(body) }
            }
        }
    }

    /**
     * Path parameters named by a record uuid or row id (`request_uuid`, `unlock_id`), set by a suite to the other
     * division's rows: used only when the path is built from [otherIds], so the own call keeps its sample value.
     */
    val otherPathValues = HashMap<String, String>()

    /** The value a required query parameter gets for [pathIds] (null when it names no resource). */
    fun queryValue(name: String, pathIds: Map<String, Long>): String? = paramValue(name, pathIds)

    /** The concrete request path of [op] with its parameters filled from [pathIds] (or samples). */
    fun pathFor(contract: ContractModel, op: ContractModel.Operation, pathIds: Map<String, Long>, values: Map<String, String> = emptyMap()): String =
        op.path.replace(Regex("\\{([^}]*)}")) { m -> values[m.groupValues[1]] ?: pathValue(contract, op, m.groupValues[1], pathIds) }

    /** `X-Device-Proof` of the seeded SR phone: ES256 over `aron-proof-v1`, `device`, uuid, `METHOD path`, sha256 hex of the body (empty line without one), nonce bucket. */
    fun deviceProofOf(method: String, path: String, body: ByteArray, device: String = SR_PHONE): String =
        sign(listOf("aron-proof-v1", "device", device, "$method $path", if (body.isEmpty()) "" else com.aktcl.aron.backend.platform.DeviceProof.sha256Hex(body), bucket().toString()).joinToString("\n"))

    /** The `batch` proof of [device] (docs/24 s8.3): sha256 of the gzip body, `batch_uuid`, X-Batch-Attempt 1. */
    fun batchProof(device: String, gz: ByteArray, batchUuid: String): String =
        sign(listOf("aron-proof-v1", "batch", device, com.aktcl.aron.backend.platform.DeviceProof.sha256Hex(gz), batchUuid, "1").joinToString("\n"))

    /** [batchProof] of a gzip batch body, its `batch_uuid` read from the body. */
    fun batchProof(device: String, gz: ByteArray): String =
        batchProof(device, gz, ContractModel.json.readTree(java.util.zip.GZIPInputStream(gz.inputStream()).readBytes()).path("batch_uuid").asText())

    /** The nonce bucket of the harness clock (`floor(unix_s / 300)`). */
    fun bucket(): Long = com.aktcl.aron.backend.platform.DeviceProof.bucket(clock.now().epochSecond)

    /** base64url ES256 (P1363, 64 bytes) by [phoneKey]. */
    fun sign(message: String): String {
        val s = java.security.Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(phoneKey.private); update(message.toByteArray()) }.sign()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s)
    }

    /** A query or path value that names a resource of [pathIds] (or the clock's business date), else null. */
    private fun paramValue(name: String, pathIds: Map<String, Long>): String? = when (name) {
        "business_date", "date" -> BUSINESS_DATE
        "node_type" -> "zone"
        "radius_m" -> "100" // one of cfg.tso.periphery_radius_options_m (50, 100, 300), as the contract says
        "node_id" -> pathIds["zones"]?.toString()
        else -> PARAM_RESOURCE[name]?.let { pathIds[it]?.toString() }
    }

    private fun pathValue(contract: ContractModel, op: ContractModel.Operation, name: String, pathIds: Map<String, Long>): String {
        paramValue(name, pathIds)?.let { return it }
        if (pathIds === otherIds) otherPathValues[name]?.let { return it }
        // Reads of /v1/admin/geo/{level}/{id}: the division level, so the harness's two divisions tell the answers apart.
        val geoRead = op.method == "GET" && op.path.startsWith("/v1/admin/geo/")
        if (geoRead && name == "level") return "division"
        if (geoRead && name == "id") pathIds["divisions"]?.let { return it.toString() }
        if (name == "id") {
            val segs = op.path.split('/')
            pathIds[segs.getOrNull(segs.indexOf("{$name}") - 1)]?.let { return it.toString() }
        }
        val p = op.parameters.firstOrNull { it.where == "path" && it.name == name }
        return if (p == null) "1" else contract.sampleText(p.schema, name)
    }

    companion object {
        const val BUSINESS_DATE = "2027-01-03"
        const val YESTERDAY = "2027-01-02"
        /** The uuid the contract sample gives every `Uuid` (its first example). */
        const val SAMPLE_UUID = "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10"
        /** The seeded second outlet request (pending, by tso1001). */
        const val SECOND_REQUEST = "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c11"
        const val TUTORIAL_ASSET = "00000000-0000-4000-8000-0000000000a2"
        const val PROOF_PHONE = "00000000-0000-4000-8000-00000000000a"
        const val BIND_PHONE = "00000000-0000-4000-8000-00000000000b"
        const val ENROL_PHONE = "00000000-0000-4000-8000-00000000000c"
        const val BIND_USER = "qa9003"
        const val STORED_PHOTO = "6f1c2a3b-4d5e-4f60-8a71-b2c3d4e5f601"
        const val RESOLVED_ITEM = "7a1b2c3d-4e5f-4a60-9b71-c2d3e4f5a602"
        /** The signing certificate digest of the seeded SR release (`sha256('qa-signing-cert')`), which an enrolment attests. */
        val RELEASE_CERT: ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest("qa-signing-cert".toByteArray())
        /** The paper memo the backfill enters for qa9001 (`<username>-<yyMMdd>-<seq>`). */
        const val PAPER_MEMO_NO = "qa9001-270103-001"

        /** A stable UUID v4 for [seed] (version and variant bits set), so a caller gets its own client uuid per operation. */
        fun v4(seed: String): String {
            val u = java.util.UUID.nameUUIDFromBytes(seed.toByteArray())
            val versionMask = 0xF000L.inv() // the version nibble of time_hi_and_version
            return java.util.UUID((u.mostSignificantBits and versionMask) or 0x4000L, (u.leastSignificantBits and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE).toString()
        }
        /** Parameter names that carry a resource id, and the [ids] key of that resource. */
        val PARAM_RESOURCE = mapOf(
            "outlet_id" to "outlets", "route_id" to "routes", "zone_id" to "zones", "cluster_id" to "clusters", "territory_id" to "territories",
            "division_id" to "divisions", "user_id" to "users", "sr_user_id" to "users", "device_id" to "devices",
        )
        /** Body fields the scope probes fill even when optional (single ids and id lists). */
        val ID_FIELD = Regex("^[a-z_]*_ids?$")
        /**
         * effective_from of the harness's config overrides: a fixed instant before every clock the suites use (the fixed
         * test clocks around 2027-01-03, and the real time of the run). `now() - 1 day` stopped activating once the real
         * date passed the fixed clock, because the resolver compares against the API clock (a date bomb).
         */
        const val CFG_FROM = "2026-01-01T00:00:00Z"
        const val SR_PHONE = "00000000-0000-4000-8000-000000000001"
        const val THROWAWAY_PHONE = "00000000-0000-4000-8000-000000000009"
        const val APP_VERSION = "1.0.9+9"
        /** The tag of the scope-leak harness's other division; every code, name and username of it carries [MARK]. */
        const val OTHER = "LKX"
        const val MARK = "lkx"

        /**
         * The seed SQL rewritten for a mirrored division tagged [tag] (three upper-case letters): every code, name,
         * username and phone number moved, so the copy shares nothing with the original but the wing and the products.
         */
        fun mirrored(sql: String, tag: String): String {
            require(tag.matches(Regex("[A-Z]{3}")) && tag != "MIR" && tag != "DHK")
            val low = tag.lowercase(); val cap = tag.lowercase().replaceFirstChar { it.uppercase() }
            return sql
                .replace("'D-DHK'", "'D-$tag'").replace("Dhaka Division", "$cap Division").replace("'T-DHK-N'", "'T-$tag'").replace("Dhaka North", "$cap North")
                .replace("'H-MIR'", "'H-$tag'").replace("'Z-MIR'", "'Z-$tag'").replace("MIR-", "$tag-").replace("Mirpur", "${cap}pur").replace("Pallabi", "${cap}pallabi")
                .replace(Regex("'([a-z]+)1001'")) { "'$low${it.groupValues[1]}1001'" }.replace(Regex("'T-([A-Z]+)-1001'")) { "'T-$tag-${it.groupValues[1]}'" }
                .replace("(Dhaka)", "($cap)").replace("'017'", "'018'").replace("'Test ", "'$cap Test ")
        }

        /** Every route of a routing tree as (method, path template). */
        fun routes(root: RoutingNode): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            fun walk(n: RoutingNode, path: String) {
                var p = path
                when (val s = n.selector) {
                    is HttpMethodRouteSelector -> out += s.method.value to p.ifEmpty { "/" }
                    is PathSegmentConstantRouteSelector -> p += "/" + s.value
                    is PathSegmentParameterRouteSelector -> p += "/{" + s.name + "}"
                    is PathSegmentOptionalParameterRouteSelector -> p += "/{" + s.name + "}"
                    is PathSegmentWildcardRouteSelector -> p += "/*"
                    is PathSegmentTailcardRouteSelector -> p += "/{...}"
                    else -> {} // root, authenticated {}: RouteInventoryTest proves the walker sees every route
                }
                n.children.forEach { walk(it, p) }
            }
            walk(root, "")
            return out.distinct()
        }
    }
}

/**
 * In-memory blob storage for the harness: every SAS and read URL points at `https://blob.test/<path>`, written photo
 * bytes are kept by path. It lets the blob operations answer 2xx so their success bodies are validated too.
 */
class FakeBlobs {
    val stored = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    fun ports() = BlobPorts(
        object : com.aktcl.aron.backend.masterdata.BlobSasIssuer {
            override fun writeSas(blobPath: String, maxBytes: Long, expiresAt: Instant) = "https://blob.test/$blobPath?sp=cw&se=${expiresAt.epochSecond}"
            override fun readUrl(blobPath: String) = "https://blob.test/$blobPath"
        },
        com.aktcl.aron.backend.media.PhotoBlobWriter { path, bytes -> stored[path] = bytes },
        com.aktcl.aron.backend.media.PhotoReadIssuer { path, until -> "https://blob.test/$path?sp=r&se=${until.epochSecond}" },
    )
}

/** The public JWK (`kty` EC, `crv` P-256, base64url `x` and `y`) of an EC key pair. */
fun publicJwk(k: java.security.KeyPair): String {
    val w = (k.public as java.security.interfaces.ECPublicKey).w
    fun b64(v: java.math.BigInteger): String {
        val raw = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }
    return """{"kty":"EC","crv":"P-256","x":"${b64(w.affineX)}","y":"${b64(w.affineY)}"}"""
}
