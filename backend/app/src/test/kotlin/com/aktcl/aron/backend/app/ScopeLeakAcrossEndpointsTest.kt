package com.aktcl.aron.backend.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.aktcl.aron.backend.analytics.AggregationWorker
import com.aktcl.aron.backend.analytics.Aggregator
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.server.application.plugin
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * N-014 (scope-leak harness over every endpoint; N-061 widens it). The seed is loaded twice ([ApiWorld] mirror): a whole
 * second division (D-LKX: zone, routes, 60 outlets, users, all marked `lkx`) that no scoped seed user may reach. Both
 * divisions get a phone and a day of real sales through `POST /v1/sync/batch` (visits, memos, lines, collections,
 * voids), so lists, reports and dashboards have rows on both sides.
 *
 * Every operation the production wiring serves is called by the scoped callers (sr1001 on its phone, amo1001, tso1001,
 * dmo1001 on the web): with their own ids, with the other division's ids in every resource path parameter, with each
 * optional selector query parameter set to the other division, and (writes) with the other division's ids in every id
 * field of the body, optional ones included. An answer leaks when it carries the other division by
 * - text: the marker, or a client_uuid of one of its records, or
 * - id: one of its outlet, route, user, zone, cluster, territory, division or device ids under a key of that kind
 *   (`outlet_id`, `route_ids`, ..., and `id` inside a collection of that resource);
 * and a write leaks when it answers 2xx while naming the other division. The positive control is per endpoint: the
 * national admin must see the other division on each read, or the read is in `scope-blind-baseline.txt` (a read the
 * harness cannot judge yet; the list may only shrink). Known leaks would go to `scope-leak-baseline.txt` (empty).
 * The seeded-leak test runs the same probes with the national token swapped in for a TSO on chosen operations (a
 * server that ignores the token's reach) and requires the leaks to be reported through the same baseline check.
 * Report: build/scope-leak-endpoints.txt.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScopeLeakAcrossEndpointsTest {
    private lateinit var world: ApiWorld
    private lateinit var contract: ContractModel
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private val day = ApiWorld.BUSINESS_DATE

    private val scoped = listOf(
        ApiWorld.Caller("sr1001", "app_sr", ApiWorld.SR_PHONE),
        ApiWorld.Caller("amo1001", "app_amo", ApiWorld.AMO_PHONE),
        ApiWorld.Caller("tso1001", "web", null),
        ApiWorld.Caller("dmo1001", "web", null),
    )
    private val national = ApiWorld.Caller("admin1001", "web", null)

    /** The top node of each scoped supervisor in the seed (level, ids key): its dashboards must show exactly that node. */
    private val topNode = mapOf("amo1001" to ("zone" to "zones"), "tso1001" to ("territory" to "territories"), "dmo1001" to ("division" to "divisions"))

    /** National numbers of each dashboard read for each supervisor's top node: `<op> <user>` to numbers. */
    private val nationalTop = HashMap<String, List<String>>()

    data class Leak(val method: String, val shape: String, val caller: String, val variant: String, val detail: String) {
        val key: String get() = "$method $shape $caller $variant"
    }

    /** The other division: ids per resource kind, and the client_uuids of its records. */
    private val otherIds = HashMap<String, Set<Long>>()
    private val otherUuids = HashSet<String>()

    private lateinit var ops: List<ContractModel.Operation>
    private val leaks = mutableListOf<Leak>()
    private val nationalSees = sortedMapOf<String, Boolean>()
    /** Counters of one probe run (the main run's are kept apart from the seeded test's). */
    class Stats {
        var calls = 0; var otherIdCalls = 0; var refusedOtherIdCalls = 0; var scopeRefusedOtherIdCalls = 0; val scopedSuccess = sortedMapOf<String, Int>()
        /** Per operation and caller, every variant's status (`own:200 other-path:403`): the ledger in build/scope-ledger.tsv. */
        val ledger = sortedMapOf<String, java.util.TreeMap<String, MutableList<String>>>()
    }
    private val stats = Stats()

    /** National answers' numbers per `<op> <selector>`: (own division, other division), for the aggregate rule. */
    private val nationalNumbers = HashMap<String, Pair<List<String>, List<String>>>()

    @BeforeAll
    fun setUp() {
        contract = ContractModel.load(repoRoot.resolve("contract/openapi.yaml"))
        world = ApiWorld(repoRoot, mirrors = listOf(ApiWorld.OTHER))
        world.fresh.db.jdbi.useHandle<Exception> { seedTsoLevels(it) }
        val own = world.fresh.db.jdbi.withHandle<SyncPhone, Exception> { h ->
            fun id(sql: String) = h.createQuery(sql).mapTo(Long::class.java).one()
            val route = id("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            val skus = h.createQuery("SELECT s.id, p.amount_mtk FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' WHERE p.amount_mtk > 0 ORDER BY s.id LIMIT 2").map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
            SyncPhone(
                day, "MIR", ApiWorld.SR_PHONE, id("SELECT id FROM app.device WHERE device_uuid = '${ApiWorld.SR_PHONE}'"), id("SELECT id FROM app.app_user WHERE username = 'sr1001'"), "sr1001", route,
                h.createQuery("SELECT id FROM app.outlet WHERE route_id = :r ORDER BY id").bind("r", route).mapTo(Long::class.java).list(), skus,
            )
        }
        val other = world.phoneFor(ApiWorld.OTHER, 50, day)
        world.otherIds["devices"] = other.deviceId
        testApplication {
            application { aronApi(world.wiring) }
            client.sellADay(own, 7); client.sellADay(other, 11) // different days, so the two divisions' aggregates differ
            // Rows of other kinds in the other division (outlet requests, feedback, tasks, ...), generated from the
            // contract's payload schemas, so those reads are judged too (N-061). Types the server refuses stay blind.
            otherRecords = client.sendGenerated(other)
        }
        // The dw projector and the route-day aggregates, as the worker role runs them, so dashboards and reports have
        // both divisions (dashboards resolve their node through dw.dim_geo).
        AggregationWorker(world.fresh.db, world.clock).also { w ->
            w.runUntilIdle(); world.fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }; w.requestRebuild(java.time.LocalDate.parse(day)); w.runUntilIdle()
        }
        world.fresh.db.jdbi.useHandle<Exception> { h ->
            fun ids(sql: String) = h.createQuery(sql).mapTo(Long::class.java).set()
            val zones = "SELECT id FROM app.zone WHERE code = 'Z-LKX'"
            otherIds["outlets"] = ids("SELECT id FROM app.outlet WHERE zone_id IN ($zones)")
            otherIds["routes"] = ids("SELECT id FROM app.route WHERE zone_id IN ($zones)")
            otherIds["zones"] = ids(zones)
            otherIds["clusters"] = ids("SELECT id FROM app.cluster WHERE zone_id IN ($zones)")
            otherIds["territories"] = ids("SELECT territory_id FROM app.zone WHERE code = 'Z-LKX'")
            otherIds["divisions"] = ids("SELECT id FROM app.division WHERE code = 'D-LKX'")
            otherIds["users"] = ids("SELECT id FROM app.app_user WHERE username LIKE 'lkx%'")
            otherIds["devices"] = setOf(other.deviceId)
            fun pin(outlet: Long) = h.createQuery("SELECT lat::text || ',' || lng::text FROM app.outlet WHERE id = :o").bind("o", outlet).mapTo(String::class.java).one()
                .split(',').let { mapOf("lat" to it[0], "lng" to it[1], "radius_m" to "300") }
            ownPin = pin(own.outlets.first()); otherPin = pin(other.outlets.first())
            otherUuids += h.createQuery("SELECT client_uuid::text FROM app.visit WHERE user_id = :u UNION SELECT client_uuid::text FROM app.memo WHERE user_id = :u UNION SELECT client_uuid::text FROM app.due_collection WHERE user_id = :u")
                .bind("u", other.userId).mapTo(String::class.java).list()
            otherUuids += seedOtherRows(h, other)
            otherUuids += seedOtherTicket(h) // tso-manual B1
            // getFlaggedMemos (contract 1.8.0) reads only flagged memos: one stored memo of each division carries a flag.
            h.execute("UPDATE app.memo SET server_flags = '{price_mismatch}' WHERE id IN (SELECT max(id) FROM app.memo WHERE user_id IN (${own.userId}, ${other.userId}) GROUP BY user_id)")
            // Record paths named by uuid or row id are aimed at the other division's rows (the other-path variant).
            world.otherPathValues["request_uuid"] = otherRequestUuid
            world.otherPathValues["task_uuid"] = otherTaskUuid
            for ((param, type) in listOf("leave_uuid" to "leave_application", "exception_uuid" to "day_exception", "feedback_uuid" to "feedback")) {
                generatedUuids[type]?.let { world.otherPathValues[param] = it }
            }
            fun rowId(sql: String) = h.createQuery(sql).mapTo(Long::class.java).findOne().orElse(null)?.toString()
            rowId("SELECT max(id) FROM app.entry_unlock WHERE reason LIKE 'LKX%'")?.let { world.otherPathValues["unlock_id"] = it }
            rowId("SELECT max(id) FROM app.dues_adjustment WHERE reason LIKE 'LKX%'")?.let { world.otherPathValues["adjustment_id"] = it }
            // A missing value would silently drop the other-path probe of its writes.
            val aimedAt = setOf("request_uuid", "task_uuid", "leave_uuid", "feedback_uuid", "unlock_id", "adjustment_id")
            check(world.otherPathValues.keys.containsAll(aimedAt)) { "other-division rows missing for ${aimedAt - world.otherPathValues.keys}" }
        }
        check(otherIds.values.all { it.isNotEmpty() } && otherUuids.isNotEmpty()) { "the other division is incomplete: $otherIds, ${otherUuids.size} records" }
        // Ids of the two divisions never coincide per kind, so an id hit is never the caller's own row.
        check(world.ids.entries.none { (k, v) -> otherIds[k]?.contains(v) == true })
        run()
    }

    @AfterAll
    fun tearDown() { world.close() }

    /** A pin of each division (lat, lng): `outlets/nearby` is probed at the other division's outlets. */
    private lateinit var ownPin: Map<String, String>
    private lateinit var otherPin: Map<String, String>

    /** Record types generated from the contract and sent by the other division's phone, with the server's answers. */
    private var otherRecords: Map<String, String> = emptyMap()

    /** Sends one generated record of each [GENERATED_TYPES] from [p] (ids pointed at its own division); type -> ack status. */
    private suspend fun HttpClient.sendGenerated(p: SyncPhone): Map<String, String> {
        val out = sortedMapOf<String, String>()
        val outlet = p.outlets.first()
        for (type in GENERATED_TYPES) {
            val schema = contract.component(type.split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Payload")
            if (schema.isMissingNode) { out[type] = "no payload schema"; continue }
            val payload = contract.sample(schema, optional = { it == "outlet_id" || it == "route_id" }) as ObjectNode
            fun point(n: JsonNode) {
                if (n is ObjectNode) n.fieldNames().asSequence().toList().forEach { f ->
                    when (f) { "outlet_id" -> n.put(f, outlet); "route_id" -> n.put(f, p.routeId); else -> point(n.get(f)) }
                } else if (n.isArray) n.forEach(::point)
            }
            point(payload)
            val cu = java.util.UUID.randomUUID().toString()
            val env = Json.parseToJsonElement(
                """{"type":"$type","client_uuid":"$cu","family_uuid":"$cu","rank":0,"schema_version":1,"business_date":"$day","captured_at":"${day}T03:00:00Z",
                   "captured_elapsed_ms":18330000,"boot_count":412,"clock_offset_ms":0,"captured_offline":true,"route_id":${p.routeId},"bundle_version":"$day:3",
                   "bundle_stale":false,"config_version":1,"payload":${ContractModel.json.writeValueAsString(payload)}}""",
            ).jsonObject
            val body = gz(p.batch(listOf(env)))
            val r = post("/v1/sync/batch") {
                bearerAuth(p.token); header("X-Device-Id", p.deviceUuid); header("X-App-Version", ApiWorld.APP_VERSION); header("Content-Encoding", "gzip")
                header("X-Device-Proof", world.batchProof(p.deviceUuid, body)) // the phones hold the harness key (ApiWorld.phoneKey)
                setBody(ByteArrayContent(body, ContentType.Application.Json))
            }
            val ack = runCatching { Json.parseToJsonElement(r.bodyAsText()).jsonObject["acks"]!!.jsonArray.single().jsonObject }.getOrNull()
            if (ack?.get("status")?.jsonPrimitive?.content == "accepted") generatedUuids[type] = cu
            out[type] = ack?.let { it["status"]!!.jsonPrimitive.content + (it["code"]?.jsonPrimitive?.content?.let { c -> " $c" } ?: "") } ?: "http ${r.status.value}"
        }
        return out
    }

    /**
     * Rows of the other division that no phone record creates (admin, web and device tables), inserted directly so
     * their reads are judged (N-061: the debt lines of `scope-blind-baseline.txt`). Every text field carries the marker;
     * returns the uuids of the rows, which count as the other division's records.
     */
    private fun seedOtherRows(h: org.jdbi.v3.core.Handle, p: SyncPhone): List<String> {
        fun uuid() = java.util.UUID.randomUUID().toString()
        val task = uuid(); val request = uuid(); val plan = uuid(); val planOutlet = uuid()
        val entry = uuid(); val directive = uuid(); val adjustment = uuid()
        otherRequestUuid = request; otherTaskUuid = task
        val outlet = p.outlets.first()
        val zone = h.createQuery("SELECT zone_id FROM app.outlet WHERE id = :o").bind("o", outlet).mapTo(Long::class.java).one()
        val at = "${day}T04:00:00Z"
        // The sync-shaped tables' guards (client_uuid_once and friends) expect ingest; these rows are fixtures.
        h.execute("SET session_replication_role = replica")
        try {
            val base = "client_uuid, family_uuid, business_date, user_id, captured_at, config_version"
            fun vals(u: String) = "'$u'::uuid, '$u'::uuid, '$day'::date, ${p.userId}, '$at'::timestamptz, 1"
            h.execute("INSERT INTO app.task ($base, task_type_code, assignee_user_id, title, route_id) VALUES (${vals(task)}, 'lkx_check', ${p.userId}, 'LKX seeded task', ${p.routeId})")
            h.execute("INSERT INTO app.outlet_change_request ($base, request_type, outlet_id, route_id, proposed) VALUES (${vals(request)}, 'info', $outlet, ${p.routeId}, '{\"note\":\"LKX seeded request\"}'::jsonb)")
            h.execute("INSERT INTO app.visit_plan ($base, plan_date) VALUES (${vals(plan)}, '$day'::date)")
            h.execute("INSERT INTO app.visit_plan_outlet ($base, plan_client_uuid, outlet_id) VALUES (${vals(planOutlet)}, '$plan'::uuid, $outlet)")
            h.execute("INSERT INTO app.web_entry_route_day (client_uuid, route_id, business_date, successful_calls, target_outlets, entered_by) VALUES ('$entry'::uuid, ${p.routeId}, '$day'::date, 3, 9, ${p.userId})")
            h.execute("INSERT INTO app.device_directive (directive_id, device_id, type, sig, expires_at) VALUES ('$directive'::uuid, ${p.deviceId}, 'send_status', '${"A".repeat(86)}', now() + interval '1 hour')")
            h.execute(
                """INSERT INTO app.device_status_report (source, device_id, business_date, reported_at, app_version, device_owner, lockdown_level_applied, blocking_active,
                   location_enabled, dev_options_enabled, adb_enabled, auto_time_enabled, pending_rows, battery_pct, report)
                   VALUES ('online', ${p.deviceId}, '$day'::date, '$at'::timestamptz, '${ApiWorld.APP_VERSION}', true, 'dev', false, true, false, false, true, 0, 80, '{"note":"LKX seeded status"}'::jsonb)""",
            )
            h.execute("INSERT INTO app.dues_adjustment (client_uuid, outlet_id, kind, amount_mtk, reason, created_by) VALUES ('$adjustment'::uuid, $outlet, 'correction', 1000, 'LKX seeded adjustment', ${p.userId})")
            h.execute("INSERT INTO app.entry_unlock (scope_type, scope_id, from_date, to_date, reason, expires_at, created_by) VALUES ('zone', $zone, '$day'::date, '$day'::date, 'LKX seeded unlock', now() + interval '1 day', ${p.userId})")
            // DMO and WM read enrolment tokens of their zones (D-AUTHZ-ENROL-READ): one of the other division's zone.
            h.execute("INSERT INTO app.enrolment_token (token_sha256, token_prefix, flavour, lockdown_level, max_uses, zone_id, expires_at, created_by) VALUES (sha256(convert_to('$entry', 'UTF8')), 'LKXtok', 'sr', 'dev', 1, $zone, now() + interval '1 day', ${p.userId})")
        } finally {
            h.execute("SET session_replication_role = origin")
        }
        return listOf(task, request, plan, planOutlet, entry, directive, adjustment)
    }

    /**
     * tso-manual B1: a support ticket of the other division's TSO (lkxtso1001), its title marked; the ticket paths of the
     * other-path probes name it. The national desk (admin1001) sees it; a scoped caller lists only its own tickets.
     */
    private fun seedOtherTicket(h: org.jdbi.v3.core.Handle): List<String> {
        val t = java.util.UUID.randomUUID().toString()
        h.execute(
            "INSERT INTO app.support_ticket (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, issue_type_code, title, description, source) " +
                "SELECT '$t'::uuid, '$t'::uuid, '$day'::date, id, '${day}T04:00:00Z'::timestamptz, 1, 'others', 'LKX seeded ticket', 'LKX seeded ticket text', 'manual' FROM app.app_user WHERE username = 'lkxtso1001'",
        )
        h.execute("INSERT INTO app.support_ticket_state (ticket_uuid, requester_user_id, requester_role, priority) SELECT '$t'::uuid, id, 'TSO', 'p1' FROM app.app_user WHERE username = 'lkxtso1001'")
        world.otherPathValues["ticket_uuid"] = t
        return listOf(t)
    }

    private lateinit var otherRequestUuid: String
    private lateinit var otherTaskUuid: String
    /** Client uuids of the generated records the server accepted, by type. */
    private val generatedUuids = HashMap<String, String>()

    /** Logs [p] in and delivers one selling day until every record is acknowledged. */
    private suspend fun HttpClient.sellADay(p: SyncPhone, seed: Int) {
        p.token = world.login(this, ApiWorld.Caller(p.username, "app_sr", p.deviceUuid)) ?: error("no token for ${p.username}")
        var pending = p.captureDay(Random(seed))
        repeat(5) {
            if (pending.isEmpty()) return
            val body = gz(p.batch(pending))
            val r = post("/v1/sync/batch") {
                bearerAuth(p.token); header("X-Device-Id", p.deviceUuid); header("X-App-Version", ApiWorld.APP_VERSION); header("Content-Encoding", "gzip")
                // The phones hold the harness key (ApiWorld.phoneKey; the mirrored phone is a copy of the seeded one), so a batch carries its proof.
                header("X-Device-Proof", world.batchProof(p.deviceUuid, body))
                setBody(ByteArrayContent(body, ContentType.Application.Json))
            }
            val acked = (Json.parseToJsonElement(r.bodyAsText()).jsonObject["acks"] ?: error("${p.username}: ${r.status} ${r.bodyAsText().take(300)}")).jsonArray.map { it.jsonObject }
                .filter { it["status"]!!.jsonPrimitive.content in setOf("accepted", "duplicate") }.map { it["client_uuid"]!!.jsonPrimitive.content }.toSet()
            pending = pending.filter { it.uuid() !in acked }
        }
        check(pending.isEmpty()) { "${p.username}: ${pending.size} records never accepted" }
    }

    // ---- detection -------------------------------------------------------------------------------------------------

    /** What in [text] shows the other division (null when nothing does). [resource] is the collection `id` belongs to. */
    internal fun shows(text: String, resource: String?): String? {
        val i = text.indexOf(ApiWorld.MARK, ignoreCase = true)
        if (i >= 0) return "text …" + text.substring(maxOf(0, i - 40), minOf(text.length, i + 40)) + "…"
        otherUuids.firstOrNull { it in text }?.let { return "a record of the other division ($it)" }
        val node = runCatching { ContractModel.json.readTree(text) }.getOrNull() ?: return null
        return idHit(node, resource)
    }

    private fun kindOf(key: String, resource: String?): String? = when {
        key == "id" -> resource
        key == "node_id" || key == "home_zone_id" -> "zones"
        key.endsWith("user_id") || key.endsWith("user_ids") -> "users"
        else -> ApiWorld.PARAM_RESOURCE[key] ?: ApiWorld.PARAM_RESOURCE[key.removeSuffix("s")]
    }

    private fun idHit(n: JsonNode, resource: String?): String? {
        if (n.isArray) { n.forEach { idHit(it, resource)?.let { h -> return h } }; return null }
        if (!n.isObject) return null
        for ((k, v) in n.properties()) {
            val kind = kindOf(k, resource)
            val set = kind?.let { otherIds[it] }
            if (set != null) {
                val values = if (v.isArray) v.toList() else listOf(v)
                values.firstOrNull { it.isIntegralNumber && it.asLong() in set }?.let { return "$k=${it.asLong()} is a ${kind.removeSuffix("s")} of the other division" }
            }
            if (v.isContainerNode) idHit(v, resource)?.let { return it }
        }
        return null
    }

    /** The collection an operation's `id` belongs to (`/v1/admin/outlets/{id}` and `/v1/admin/outlets` are outlets). */
    private fun resourceOf(op: ContractModel.Operation): String? =
        op.path.split('/').lastOrNull { it.isNotEmpty() && !it.startsWith("{") }?.let { seg -> seg.takeIf { it in otherIds.keys } }

    /**
     * Report queries choose their geography with `geo: {zone: [..], division: [..], ...}` (docs/24 s12.3), keys without
     * an `_id` suffix: a body whose schema has `geo` gets the other division's zone, territory and division there.
     */
    private fun aimGeo(op: ContractModel.Operation, n: JsonNode): Boolean {
        val schema = op.requestSchema?.let { r -> r.get("\$ref")?.asText()?.let { contract.component(it.substringAfterLast('/')) } ?: r } ?: return false
        if (n !is ObjectNode || !schema.path("properties").has("geo")) return false
        val geo = n.putObject("geo")
        for ((key, kind) in listOf("zone" to "zones", "territory" to "territories", "division" to "divisions")) {
            world.otherIds[kind]?.let { geo.putArray(key).add(it) }
        }
        return true
    }

    /** Points every resource id field of a body (single or list) at the other division; true when one changed. */
    private fun retarget(n: JsonNode): Boolean {
        var changed = false
        if (n is ObjectNode) n.fieldNames().asSequence().toList().forEach { f ->
            val v = n.get(f)
            val other = kindOf(f, null)?.let { world.otherIds[it] ?: otherIds[it]?.first() }
            when {
                other != null && v.isIntegralNumber -> { n.put(f, other); changed = true }
                other != null && v.isArray && v.size() > 0 && v.all { it.isIntegralNumber } -> { n.putArray(f).add(other); changed = true }
                retarget(v) -> changed = true
            }
        } else if (n.isArray) n.forEach { if (retarget(it)) changed = true }
        return changed
    }

    // ---- the probes ------------------------------------------------------------------------------------------------

    /**
     * Calls every operation of [which] as every scoped caller in every variant and returns the leaks; [tokenFor] gives
     * the bearer of a caller (the seeded-leak test swaps in the national one).
     */
    private suspend fun HttpClient.probe(which: List<ContractModel.Operation>, st: Stats, callers: List<ApiWorld.Caller> = scoped, tokenFor: suspend (ApiWorld.Caller) -> String?): List<Leak> {
        val found = mutableListOf<Leak>()
        for (op in which) {
            val shape = ContractModel.shape(op.path)
            val read = op.method == "GET" || READ_BY_POST.containsMatchIn(op.operationId)
            val resource = resourceOf(op)
            val ownNumbers = HashMap<String, List<String>>()
            for (c in callers) {
                suspend fun once(variant: String, pathIds: Map<String, Long>, query: String = "", body: Boolean = false, pins: Map<String, String> = ownPin) {
                    var retargeted = !body
                    val edit: ((JsonNode) -> Unit)? = if (body) { n -> retargeted = retarget(n) or aimGeo(op, n) } else null
                    val r = world.call(this, contract, op, c, tokenFor(c), pathIds, query, includeOptionalIds = body, bodyEdit = edit, queryOverrides = pins)
                    if (!retargeted) return // the body names no resource: nothing to learn beyond the own call
                    st.calls++
                    st.ledger.getOrPut(op.key) { java.util.TreeMap() }.getOrPut(c.user) { mutableListOf() } += "$variant:${r.status.value}"
                    // A refusal echoes the asked path in its problem `instance` (the caller's own words): not a leak. Only that
                    // field is blanked, only on a refusal.
                    val text = r.bodyAsText().let { t -> if (r.status.value >= 400) t.replace(INSTANCE, "\"instance\":\"<asked>\"") else t }
                    if (variant == "own" && r.status.value in 200..299) st.scopedSuccess.merge(op.key, 1, Int::plus)
                    shows(text, resource)?.let { found += Leak(op.method, shape, c.user, variant, "${r.status.value} $it") }
                    // An aggregate carries no ids: a scoped answer to an other-division selector that equals the national
                    // view of the other division (and that view differs from the caller's own division) is a leak.
                    // Dashboards (a `level` selector): the own answer must be exactly the national view of the caller's top
                    // node, and an other-division selector must be refused or ignored (the own answer again). This catches
                    // an answer that mixes the divisions, which neither text nor ids show (checker N-014 A).
                    if (isDashboard(op) && r.status.value in 200..299) {
                        val n = numbers(text)
                        if (variant == "own") {
                            ownNumbers[c.user] = n
                            nationalTop["${op.key} ${c.user}"]?.let { want -> if (n != want) found += Leak(op.method, shape, c.user, variant, "${r.status.value} the numbers differ from the national view of the caller's own node") }
                        } else if (variant.startsWith("other-") && variant != "other-body") {
                            ownNumbers[c.user]?.let { mine -> if (n != mine) found += Leak(op.method, shape, c.user, variant, "${r.status.value} an other-division selector changed the numbers (not refused, not ignored)") }
                        }
                    }
                    if (read && r.status.value in 200..299 && variant.startsWith("other-") && variant != "other-path" && variant != "other-body") {
                        nationalNumbers["${op.key} ${variant.removePrefix("other-")}"]?.let { (ownN, otherN) ->
                            if (otherN.isNotEmpty() && otherN != ownN && numbers(text) == otherN) found += Leak(op.method, shape, c.user, variant, "${r.status.value} the answer equals the national view of the other division")
                        }
                    }
                    if (variant != "own") {
                        st.otherIdCalls++
                        if (r.status.value == 403 || r.status.value == 404) st.refusedOtherIdCalls++
                        // A refusal for scope, not for role: out of scope, or not found (the row is hidden).
                        if (r.status.value == 404 || text.contains("ERR_OUT_OF_SCOPE")) st.scopeRefusedOtherIdCalls++
                        if (!read && r.status.value in 200..299) found += Leak(op.method, shape, c.user, variant, "${r.status.value} a write naming the other division succeeded: ${text.take(160)}")
                    }
                }
                once("own", world.ids)
                // The other division's ids (and seeded record uuids) in the path and in every required id query parameter.
                if (aimed(op)) once("other-path", world.otherIds)
                for ((name, kind) in selectorsOf(op)) world.otherIds[kind]?.let { once("other-$name", world.ids, selector(op, name, it)) }
                if (op.requestSchema != null) once("other-body", world.ids, body = true)
                if (op.parameters.any { it.where == "query" && it.name == "lat" }) once("other-coords", world.ids, pins = otherPin)
            }
        }
        return found
    }

    /** Optional query selectors that choose a division (name, id kind): resource ids, a node, or a config scope. */
    private fun selectorsOf(op: ContractModel.Operation): List<Pair<String, String>> = op.parameters.filter { it.where == "query" && !it.required }.mapNotNull { p ->
        when (p.name) { "node_id", "scope_id" -> p.name to "zones"; else -> ApiWorld.PARAM_RESOURCE[p.name]?.let { p.name to it } }
    }

    /** True when the other division's ids change the request: in the path, or in a required id query parameter. */
    private fun aimed(op: ContractModel.Operation): Boolean =
        world.pathFor(contract, op, world.ids) != world.pathFor(contract, op, world.otherIds) ||
            op.parameters.any { it.where == "query" && it.required && world.queryValue(it.name, world.ids) != world.queryValue(it.name, world.otherIds) }

    private fun isDashboard(op: ContractModel.Operation) = op.method == "GET" && op.parameters.any { it.name == "level" } && op.parameters.any { it.name == "node_id" }

    /** The query that selects [id] by [param] (a dashboard's node_id goes with its `level`, others with `node_type`). */
    private fun selector(op: ContractModel.Operation, param: String, id: Long): String {
        if (param == "scope_id") return "scope_type=zone&scope_id=$id"
        if (param != "node_id") return "$param=$id"
        val names = op.parameters.map { it.name }
        return "node_id=$id" + when { "level" in names -> "&level=zone"; "node_type" in names && op.parameters.none { it.name == "node_type" && it.required } -> "&node_type=zone"; else -> "" }
    }

    /** The numeric leaves of a JSON answer (times, versions and cursors left out), as a comparable fingerprint. */
    internal fun numbers(text: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: JsonNode, key: String) {
            when {
                n.isObject -> n.properties().forEach { (k, v) -> walk(v, k) }
                n.isArray -> n.forEach { walk(it, key) }
                n.isNumber && !TIMEISH.containsMatchIn(key) -> out += "$key=${n.asText()}"
            }
        }
        runCatching { ContractModel.json.readTree(text) }.getOrNull()?.let { walk(it, "") }
        return out.sorted()
    }

    private fun run() = testApplication {
        application { aronApi(world.wiring) }
        startApplication()
        ops = ApiWorld.routes(application.plugin(RoutingRoot)).mapNotNull { (m, p) -> contract.operation(m, p) }.distinctBy { it.key }
            .filter { !it.public && it.key !in SESSION_ENDING }
            .sortedWith(compareBy({ it.method != "GET" }, { it.key }))
        val tokens = HashMap<String, String?>()
        suspend fun token(c: ApiWorld.Caller): String? = tokens.getOrPut(c.user) { world.login(client, c) }
        for (c in scoped + national) check(token(c) != null) { "no token for ${c.user}" }
        // The positive control: does the national admin see the other division on this read (own or other ids)?
        for (op in ops.filter { it.method == "GET" }) {
            // Only a 2xx counts as seeing: a refusal echoes the asked path in its problem `instance`.
            suspend fun seen(r: io.ktor.client.statement.HttpResponse) = r.status.value in 200..299 && shows(r.bodyAsText(), resourceOf(op)) != null
            var sees = seen(world.call(client, contract, op, national, token(national), queryOverrides = ownPin))
            if (!sees && op.parameters.any { it.where == "query" && it.name == "lat" }) {
                sees = seen(world.call(client, contract, op, national, token(national), queryOverrides = otherPin))
            }
            if (!sees && aimed(op)) sees = seen(world.call(client, contract, op, national, token(national), world.otherIds))
            // Reads whose division is chosen by a query selector: the national view of each division, for the aggregate rule.
            for ((name, kind) in selectorsOf(op)) {
                val ownId = world.ids[kind] ?: continue
                val otherId = world.otherIds[kind] ?: continue
                val ownR = world.call(client, contract, op, national, token(national), extraQuery = selector(op, name, ownId))
                val otherR = world.call(client, contract, op, national, token(national), extraQuery = selector(op, name, otherId))
                if (ownR.status.value == 200 && otherR.status.value == 200) {
                    val pair = numbers(ownR.bodyAsText()) to numbers(otherR.bodyAsText())
                    nationalNumbers["${op.key} $name"] = pair
                    if (pair.second.isNotEmpty() && pair.first != pair.second) sees = true
                }
            }
            nationalSees[op.key] = sees
            if (isDashboard(op)) for ((user, top) in topNode) {
                val id = world.ids[top.second] ?: continue
                val r2 = world.call(client, contract, op, national, token(national), extraQuery = "level=${top.first}&node_id=$id")
                if (r2.status.value == 200) nationalTop["${op.key} $user"] = numbers(r2.bodyAsText())
            }
        }
        leaks += client.probe(ops, stats) { c -> token(c) }
        File("build").mkdirs()
        // The ledger: per operation, whether the national control judges it, and every caller's variants and statuses.
        File("build/scope-ledger.tsv").writeText(
            "operation\tjudged\t" + scoped.joinToString("\t") { it.user } + "\n" + stats.ledger.entries.joinToString("") { (k, byCaller) ->
                val judged = when (nationalSees[k]) { true -> "judged"; false -> "blind"; null -> "write" }
                "$k\t$judged\t" + scoped.joinToString("\t") { c -> byCaller[c.user]?.joinToString(" ") ?: "" } + "\n"
            },
        )
        File("build/scope-leak-endpoints.txt").writeText(
            "operations=${ops.size} calls=${stats.calls} other_id_calls=${stats.otherIdCalls} refused_403_404=${stats.refusedOtherIdCalls} refused_for_scope=${stats.scopeRefusedOtherIdCalls} " +
                "other_division_records=$otherRecords national_sees=${nationalSees.count { it.value }}/${nationalSees.size} aggregate_pairs=${nationalNumbers.size} dashboard_top_views=${nationalTop.size} scoped_2xx_ops=${stats.scopedSuccess.size}\n" +
                leaks.joinToString("\n") { "${it.key}  ${it.detail}" } + "\n# reads the national control cannot see the other division in:\n" +
                nationalSees.filterValues { !it }.keys.joinToString("\n") + "\n",
        )
    }

    private fun baseline(name: String) = javaClass.getResource("/$name")!!.readText().lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()

    /** The verdict over a set of leaks: empty when it matches the baseline, else the message the test fails with. */
    private fun verdict(found: List<Leak>): String {
        val base = baseline("scope-leak-baseline.txt")
        val now = found.associateBy { it.key }
        val fresh = now.keys - base
        val stale = base - now.keys
        return buildString {
            if (fresh.isNotEmpty()) append("cross-scope reads or writes:\n" + fresh.sorted().joinToString("\n") { "  $it  ${now[it]!!.detail}" } + "\n")
            if (stale.isNotEmpty()) append("fixed leaks still in scope-leak-baseline.txt (remove them):\n" + stale.sorted().joinToString("\n") { "  $it" })
        }
    }

    // ---- tests -----------------------------------------------------------------------------------------------------

    @Test
    fun noScopedCallerReachesTheOtherDivisionThroughAnyEndpoint() {
        val msg = verdict(leaks)
        assertTrue(msg.isEmpty(), msg)
    }

    @Test
    fun theHarnessReallyProbedTheOtherDivision() {
        assertTrue(stats.calls > 500, "calls ${stats.calls}")
        assertTrue(stats.otherIdCalls > 200, "other-division calls ${stats.otherIdCalls}")
        assertTrue(stats.refusedOtherIdCalls > 50, "other-division calls refused with 403/404: ${stats.refusedOtherIdCalls}")
        // Role refusals alone would pass the line above: the scope checks themselves must have refused calls too.
        assertTrue(stats.scopeRefusedOtherIdCalls > 30, "other-division calls refused for scope (out of scope or 404): ${stats.scopeRefusedOtherIdCalls}")
        assertTrue(stats.scopedSuccess.size > 30, "own-scope reads answered 2xx on only ${stats.scopedSuccess.size} operations")
        // The aggregate surface is judged: the dashboards answer the national control for both divisions.
        val dashboards = nationalSees.filterKeys { it.startsWith("GET /v1/dashboards/") }
        assertTrue(dashboards.count { it.value } >= 3, "dashboards the national control can tell the divisions apart on: $dashboards")
        // A scoped supervisor reads its own dashboard (the reads are not refused for everyone).
        assertTrue((stats.scopedSuccess["GET /v1/dashboards/summary"] ?: 0) >= 1, "no scoped caller got its own dashboard summary")
        assertTrue(nationalTop.size >= 10, "national views of the supervisors' own nodes: ${nationalTop.keys}")
    }

    @Test
    fun everyReadIsJudgedByThePositiveControlExceptTheShrinkingBlindList() {
        // A read where even the national admin shows nothing of the other division cannot reveal a leak (empty lists,
        // per-caller data). Each is listed with its reason; a new blind read needs other-division data or a reason.
        val blind = nationalSees.filterValues { !it }.keys
        val base = baseline("scope-blind-baseline.txt")
        val msg = buildString {
            (blind - base).sorted().takeIf { it.isNotEmpty() }?.let { append("reads the harness cannot judge (seed other-division data or list them with a reason):\n  " + it.joinToString("\n  ") + "\n") }
            (base - blind).sorted().takeIf { it.isNotEmpty() }?.let { append("judged now, remove from scope-blind-baseline.txt:\n  " + it.joinToString("\n  ")) }
        }
        assertTrue(msg.isEmpty(), msg)
        assertTrue(nationalSees.count { it.value } >= 15, "the national control sees the other division on only ${nationalSees.count { it.value }} reads")
    }

    @Test
    fun aSeededCrossScopeReadIsCaught() = testApplication {
        // A server that ignores the token's reach on these reads: the TSO's calls carry the national admin's token.
        application { aronApi(world.wiring) }
        val tokens = HashMap<String, String?>()
        val nationalToken = world.login(client, national)!!
        val seeded = listOf("GET /v1/admin/outlets", "GET /v1/admin/route-assignments", "GET /v1/dashboards/summary").map { k -> ops.single { it.key == k } }
        val found = client.probe(seeded, Stats()) { c -> if (c.user == "tso1001") nationalToken else tokens.getOrPut(c.user) { world.login(client, c) } }
        val msg = verdict(found)
        assertTrue("GET /v1/admin/outlets tso1001 own" in msg, "a seeded outlet leak (marker) must fail the test: $msg")
        assertTrue("GET /v1/admin/route-assignments tso1001 own" in msg, "a seeded assignment leak (ids only) must fail the test: $msg")
        assertTrue("GET /v1/dashboards/summary tso1001 other-node_id" in msg, "a seeded aggregate leak (numbers only) must fail the test: $msg")
        assertTrue("GET /v1/dashboards/summary tso1001 own" in msg, "a TSO served the national view on its own read must fail the test: $msg")
        assertTrue(found.none { it.caller != "tso1001" }, "only the seeded caller leaks: $found")
    }

    // ---- D-DASH-TSO-HOUSE-SCOPE-01: a TSO scoped by a house node, and TSOs holding route assignments only ------------

    private val houseTso = ApiWorld.Caller("qahousetso1001", "web", null)
    private val routeTso = ApiWorld.Caller("qaroutetso1001", "web", null)
    private val partialTso = ApiWorld.Caller("qapartialtso1001", "web", null)
    /** Ids of the fixtures below: `house_mir`, `zone_sib` (the sibling house's zone), `route_sib`, and the seed's `route_mir` (MIR-SR-D). */
    private val levelIds = HashMap<String, Long>()

    /**
     * A sibling house H-SIB under the seed's territory T-DHK-N with its own zone Z-SIB and one route planned today (so the
     * territory's figures differ from H-MIR's); its name is H-MIR's in other case, so `dw.dim_geo` folds both houses into
     * H-MIR (V0093 duplicates): the house TSO must still read its own house's zones only. Three TSOs: qahousetso1001 scoped by the house H-MIR,
     * qaroutetso1001 covering SIB-SR-D (every route of Z-SIB) and MIR-SR-D (one of the routes of Z-MIR), and
     * qapartialtso1001 covering MIR-SR-D only (part of one zone).
     */
    private fun seedTsoLevels(h: org.jdbi.v3.core.Handle) {
        h.execute(
            """
            INSERT INTO app.house (code, name, territory_id) SELECT 'H-SIB', 'mirpur distribution house', id FROM app.territory WHERE code = 'T-DHK-N';
            INSERT INTO app.zone (code, name, name_bn, territory_id, house_id, dep_name)
              SELECT 'Z-SIB', 'Sibling', 'সিবলিং', t.id, hs.id, 'Sibling DEP' FROM app.territory t, app.house hs WHERE t.code = 'T-DHK-N' AND hs.code = 'H-SIB';
            INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no)
              SELECT 'SIB-SR-D', 'Sibling Daily', 'Daily', id, 'sr', 'daily', 127, 1 FROM app.zone WHERE code = 'Z-SIB';
            INSERT INTO app.route_day (route_id, business_date, planned) SELECT id, DATE '$day', true FROM app.route WHERE code = 'SIB-SR-D';
            """.trimIndent(),
        )
        for ((u, grant) in listOf(
            houseTso.user to "INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, reason) SELECT u.id, 'house', hs.id, DATE '2026-01-01', 'qa house scope' FROM app.app_user u, app.house hs WHERE u.username = '${houseTso.user}' AND hs.code = 'H-MIR'",
            routeTso.user to "INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason) SELECT r.id, u.id, 'cover', DATE '2026-01-01', 'qa route-only TSO' FROM app.route r, app.app_user u WHERE r.code IN ('SIB-SR-D', 'MIR-SR-D') AND u.username = '${routeTso.user}'",
            partialTso.user to "INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason) SELECT r.id, u.id, 'cover', DATE '2026-01-01', 'qa route-only TSO' FROM app.route r, app.app_user u WHERE r.code = 'MIR-SR-D' AND u.username = '${partialTso.user}'",
        )) {
            h.execute(
                "INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password, password_hash) " +
                    "SELECT '$u', 'QA TSO', 'TSO', 'QA', upper('$u'), 'en', home_zone_id, true, false, password_hash FROM app.app_user WHERE username = 'admin1001'",
            )
            h.execute(grant)
        }
        fun one(sql: String) = h.createQuery(sql).mapTo(Long::class.java).one()
        levelIds["house_mir"] = one("SELECT id FROM app.house WHERE code = 'H-MIR'")
        levelIds["zone_sib"] = one("SELECT id FROM app.zone WHERE code = 'Z-SIB'")
        levelIds["route_sib"] = one("SELECT id FROM app.route WHERE code = 'SIB-SR-D'")
        levelIds["route_mir"] = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
    }

    /** The node-scoped dashboard reads (a `level` and a `node_id` selector). */
    private fun dashboards() = ops.filter(::isDashboard).also { d -> check(d.size >= 5) { "dashboard reads: ${d.map { it.key }}" } }

    /** The numbers of a dashboard answer without its own node label and its children (their level depends on the node's level). */
    private fun figures(text: String): List<String> {
        val n = ContractModel.json.readTree(text)
        (n as? ObjectNode)?.remove(listOf("node", "children"))
        return numbers(ContractModel.json.writeValueAsString(n))
    }

    private suspend fun HttpClient.status(op: ContractModel.Operation, c: ApiWorld.Caller, token: String, query: String) =
        world.call(this, contract, op, c, token, extraQuery = query).status.value

    @Test
    fun aHouseScopedTsoReadsItsOwnHouseByDefaultAndNeverTheSiblingHouse() = testApplication {
        application { aronApi(world.wiring) }
        val token = world.login(client, houseTso)!!
        val admin = world.login(client, national)!!
        val mir = world.ids["zones"]!!
        val ops = dashboards()
        assertTrue(ops.map { it.path }.containsAll(listOf("/v1/dashboards/summary", "/v1/dashboards/daily-tracking")), "${ops.map { it.key }}")
        for (op in ops) {
            val own = world.call(client, contract, op, houseTso, token)
            assertEquals(200, own.status.value, "${op.key} default: ${own.bodyAsText().take(300)}")
            ContractModel.json.readTree(own.bodyAsText()).get("node")?.let {
                assertEquals("house" to levelIds["house_mir"], it.path("type").asText() to it.path("id").asLong(), "${op.key}: the default node is the TSO's house")
            }
            // H-MIR holds the zone Z-MIR only: its default view is the national view of Z-MIR (never the sibling's Z-SIB).
            val view = world.call(client, contract, op, national, admin, extraQuery = "level=zone&node_id=$mir")
            assertEquals(figures(view.bodyAsText()), figures(own.bodyAsText()), "${op.key}: the house view is the national view of its zone")
            assertEquals(200, client.status(op, houseTso, token, "level=zone&node_id=$mir"), "${op.key}: own zone")
            for (q in listOf("level=zone&node_id=${levelIds["zone_sib"]}", "level=territory&node_id=${world.ids["territories"]}", "level=zone&node_id=${world.otherIds["zones"]}")) {
                assertEquals(403, client.status(op, houseTso, token, q), "${op.key} $q: outside the house")
            }
            assertEquals(400, client.status(op, houseTso, token, "level=house&node_id=${levelIds["house_mir"]}"), "${op.key}: level=house stays 400 (contract GeoLevelQuery)")
        }
        // The control: the sibling house's zone has figures, so a view that took it in would differ.
        val summary = ops.single { it.path == "/v1/dashboards/summary" }
        val territory = world.call(client, contract, summary, national, admin, extraQuery = "level=territory&node_id=${world.ids["territories"]}").bodyAsText()
        val zone = world.call(client, contract, summary, national, admin, extraQuery = "level=zone&node_id=$mir").bodyAsText()
        assertTrue(figures(territory) != figures(zone), "the sibling zone Z-SIB adds nothing to T-DHK-N: the test cannot tell the houses apart")
        val msg = verdict(client.probe(ops, Stats(), listOf(houseTso)) { token })
        assertTrue(msg.isEmpty(), msg)
    }

    /** Every number under [key] in [n] (a scalar or an array of them), at any depth. */
    private fun valuesOf(n: JsonNode, key: String): Set<Long> = buildSet {
        fun walk(x: JsonNode) {
            if (x.isArray) x.forEach(::walk)
            if (!x.isObject) return
            x.fieldNames().forEach { k ->
                val v = x.get(k)
                if (k == key) { if (v.isArray) v.forEach { if (it.isNumber) add(it.asLong()) } else if (v.isNumber) add(v.asLong()) }
                walk(v)
            }
        }
        walk(n)
    }

    @Test
    fun aRouteOnlyTsoReadsItsOwnRoutesByDefaultAndNothingElse() = testApplication {
        application { aronApi(world.wiring) }
        val token = world.login(client, routeTso)!!
        val partial = world.login(client, partialTso)!!
        val admin = world.login(client, national)!!
        val ops = dashboards()
        assertTrue(ops.map { it.path }.containsAll(listOf("/v1/dashboards/summary", "/v1/dashboards/daily-tracking")), "${ops.map { it.key }}")
        val mir = world.ids["zones"]!!
        val mirRoute = levelIds["route_mir"]!!   // MIR-SR-D, where sr1001 sold today
        val ownRoutes = mapOf(routeTso.user to setOf(mirRoute, levelIds["route_sib"]!!), partialTso.user to setOf(mirRoute))
        val ownZones = mapOf(routeTso.user to setOf(mir, levelIds["zone_sib"]!!), partialTso.user to setOf(mir))
        // D-BC-DASH-ROUTE-KEYED-01 (replaces the 403 of D-BC-DASH-ROUTE-ONLY-01): the default call reads the caller's routes by route
        // (dw.v_route_day and the route-grain splits), never a zone's figures; an explicit zone, territory or the other division stays 403.
        for (op in ops) {
            for ((c, t) in listOf(routeTso to token, partialTso to partial)) {
                val res = world.call(client, contract, op, c, t)
                val text = res.bodyAsText()
                assertEquals(200, res.status.value, "${op.key} ${c.user} default: ${text.take(300)}")
                val body = ContractModel.json.readTree(text)
                body.get("node")?.let { assertEquals("route" to true, it.path("type").asText() to (it.path("id").asLong() in ownRoutes.getValue(c.user)), "${op.key} ${c.user}: the default node is an own route") }
                val routes = valuesOf(body, "route_id") + valuesOf(body, "route_ids") +
                    body.path("children").filter { it.path("node").path("type").asText() == "route" }.map { it.path("node").path("id").asLong() }
                assertTrue(ownRoutes.getValue(c.user).containsAll(routes), "${op.key} ${c.user}: routes $routes outside ${ownRoutes[c.user]}")
                val zones = valuesOf(body, "zone_id")
                assertTrue(ownZones.getValue(c.user).containsAll(zones), "${op.key} ${c.user}: zones $zones outside ${ownZones[c.user]}")
            }
            for (q in listOf("level=zone&node_id=${levelIds["zone_sib"]}", "level=zone&node_id=$mir", "level=territory&node_id=${world.ids["territories"]}", "level=zone&node_id=${world.otherIds["zones"]}")) {
                assertEquals(403, client.status(op, routeTso, token, q), "${op.key} $q")
                assertEquals(403, client.status(op, partialTso, partial, q), "${op.key} $q")
            }
        }
        // The partial TSO's summary is the national zone view's row for MIR-SR-D, and not Z-MIR's (its other planned routes count there).
        val summary = ops.single { it.path == "/v1/dashboards/summary" }
        val zoneView = ContractModel.json.readTree(world.call(client, contract, summary, national, admin, extraQuery = "level=zone&node_id=$mir").bodyAsText())
        val zoneFields = listOf("zones_with_target_routes", "zones_final_submitted", "final_submit_pct")
        val row = (zoneView.path("children").single { it.path("node").path("id").asLong() == mirRoute }.path("kpis") as ObjectNode).also { it.remove(zoneFields) }
        val mine = ContractModel.json.readTree(world.call(client, contract, summary, partialTso, partial).bodyAsText())
        val mineKpis = (mine.path("kpis").deepCopy<JsonNode>() as ObjectNode).also { it.remove(zoneFields) }
        assertEquals(row, mineKpis, "the route's figures")
        assertTrue(mineKpis.path("gross_mtk").asLong() > 0, "MIR-SR-D sold today: ${mine.path("kpis")}")
        assertEquals(1, mine.path("kpis").path("zones_with_target_routes").asInt(), "the final-submit unit stays zones: Z-MIR")
        // Control (the seed day plans no other Z-MIR route, so a zone read would equal it here; RouteKeyedDashboardTest shows the
        // difference): the TSO holding SIB-SR-D too reads one more target route, planned in Z-SIB, and one more zone.
        val both = ContractModel.json.readTree(world.call(client, contract, summary, routeTso, token).bodyAsText()).path("kpis")
        assertEquals(mineKpis.path("target_routes").asInt() + 1 to 2, both.path("target_routes").asInt() to both.path("zones_with_target_routes").asInt(), "SIB-SR-D adds a target route and Z-SIB: $both")
        assertEquals(mineKpis.path("gross_mtk").asLong(), both.path("gross_mtk").asLong(), "SIB-SR-D sold nothing")
        // A territory TSO's numbers are unchanged: its default view is still the national view of its territory.
        val tso = ApiWorld.Caller("tso1001", "web", null)
        val tsoToken = world.login(client, tso)!!
        for (op in ops) {
            val view = world.call(client, contract, op, national, admin, extraQuery = "level=territory&node_id=${world.ids["territories"]}")
            assertEquals(figures(view.bodyAsText()), figures(world.call(client, contract, op, tso, tsoToken).bodyAsText()), "${op.key}: tso1001 default = its territory")
        }
        val msg = verdict(client.probe(ops, Stats(), listOf(routeTso, partialTso)) { c -> if (c == routeTso) token else partial })
        assertTrue(msg.isEmpty(), msg)
    }

    @Test
    fun theIdDetectorSeesBareIds() {
        val outlet = otherIds["outlets"]!!.first()
        val route = otherIds["routes"]!!.first()
        assertEquals(null, shows("""{"items":[{"id":${world.ids["outlets"]},"route_id":${world.ids["routes"]}}]}""", "outlets"))
        assertTrue(shows("""{"items":[{"id":$outlet}]}""", "outlets") != null, "an outlet id in an outlet list")
        assertTrue(shows("""{"rows":[{"route_ids":[$route]}]}""", null) != null, "a route id in a list field")
        assertTrue(shows("""{"rows":[{"x":"${otherUuids.first()}"}]}""", null) != null, "a record uuid of the other division")
    }

    companion object {
        val SESSION_ENDING = setOf("POST /v1/auth/logout", "POST /v1/auth/change-password")
        val TIMEISH = Regex("(_at|time|generation|version|cursor|_ms|_s|as_of)$")
        /** Record types the other division's phone also sends (generated from their payload schemas). */
        private val INSTANCE = Regex("\"instance\"\\s*:\\s*\"[^\"]*\"")
        val GENERATED_TYPES = listOf("outlet_change_request", "feedback", "task", "leave_application", "visit_plan", "day_exception", "risk_review", "survey_response", "sale_abort", "app_error")
        val READ_BY_POST = Regex("^(query|search|list|get|preview|check|validate|compute|digest)", RegexOption.IGNORE_CASE)
    }
}
