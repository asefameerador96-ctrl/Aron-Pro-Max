package com.aktcl.aron.backend.app

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.application.plugin
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * N-014 / AUD-TP-2 (1): the contract conformance suite. Every operation the PRODUCTION wiring serves is driven over HTTP
 * (seeded database, real logins of the seeded SR phone and the web roles) and every answer is checked against
 * contract/openapi.yaml with a JSON Schema 2020-12 validator ([ContractModel]):
 * - every implemented route is an operation of the contract (an endpoint the contract does not know fails);
 * - every answer's status code is declared for its operation (or `default`);
 * - every JSON body (application/json and application/problem+json) validates against the declared schema of that
 *   status; a problem's `code` is therefore one of the contract's ProblemCode values;
 * - every generated request body validates against the request schema first (the generator itself is checked).
 * Callers: sr1001 on the seeded phone (app_sr), and amo1001, tso1001, admin1001, superadmin1001 and support1001 on the
 * web (MFA lifted in the test config). Path ids come from the seed where the resource is known, otherwise a well-formed
 * placeholder, so many answers are 404 problems: those are validated too.
 *
 * Deviations that existed when the suite was introduced are listed in `contract-conformance-baseline.txt` (test
 * resources) as `METHOD shape status kind`; the list may only shrink: a new deviation fails, and a fixed one must be
 * removed from the file (a stale line fails too). Each baseline line is routed to its owning lane as a request.
 * The seeded-mismatch self-tests prove the checker turns red on a wrong body, an undeclared status and an unknown route.
 * The full report is written to build/contract-conformance.txt.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContractConformanceTest {
    private lateinit var world: ApiWorld
    private lateinit var contract: ContractModel
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")

    private val callers = listOf(
        ApiWorld.Caller("sr1001", "app_sr", ApiWorld.SR_PHONE),
        ApiWorld.Caller("amo1001", "web", null),
        ApiWorld.Caller("tso1001", "web", null),
        ApiWorld.Caller("admin1001", "web", null),
        ApiWorld.Caller("superadmin1001", "web", null),
        ApiWorld.Caller("support1001", "web", null),
    )

    private lateinit var endpoints: List<Pair<String, String>>
    private lateinit var findings: List<Finding>
    private var checkedCalls = 0
    private var validatedBodies = 0
    private val statusSeen = sortedMapOf<Int, Int>()

    /** Operations that answered 2xx at least once, and every operation called. */
    private val succeeded = sortedSetOf<String>()
    private val called = sortedSetOf<String>()
    /** Fixture request bodies the contract's request schema refuses. */
    private val fixtureFindings = mutableListOf<Finding>()
    /** Refusals of the operations without a 2xx answer, for the report (which fixture each one needs). */
    private val refusals = HashMap<String, MutableList<String>>()

    /** One deviation; [detail] is part of the key for schema and sample errors (a normalised error, see errorKeys). */
    data class Finding(val method: String, val shape: String, val status: Int, val kind: String, val detail: String, val keyed: Boolean = false) {
        val key: String get() = "$method $shape $status $kind" + if (keyed) " $detail" else ""
    }

    @BeforeAll
    fun setUp() {
        contract = ContractModel.load(repoRoot.resolve("contract/openapi.yaml"))
        world = ApiWorld(repoRoot, conformance = true)
        runSuite()
    }

    @AfterAll
    fun tearDown() { world.close() }

    // ---- the run ---------------------------------------------------------------------------------------------------

    /** Served routes the contract does not have. */
    internal fun notInContract(routes: List<Pair<String, String>>): List<Finding> = routes.filter { (m, p) -> contract.operation(m, p) == null }
        .map { (m, p) -> Finding(m, ContractModel.shape(p), 0, "not-in-contract", "the route is served but the contract has no such operation") }

    private fun runSuite() {
        val out = mutableListOf<Finding>()
        testApplication {
            application { aronApi(world.wiring) }
            startApplication()
            endpoints = ApiWorld.routes(application.plugin(RoutingRoot)).sortedBy { it.second + " " + it.first }
            out += notInContract(endpoints)
            val tokens = HashMap<ApiWorld.Caller, String?>()
            val ctx = Ctx(world)
            suspend fun token(c: ApiWorld.Caller): String? = tokens.getOrPut(c) { world.login(client, c) }
            // A caller that cannot log in would silently skip every guarded call.
            for (c in callers) check(token(c) != null) { "no token for ${c.user}" }
            suspend fun call(op: ContractModel.Operation, c: ApiWorld.Caller, t: String?): io.ktor.client.statement.HttpResponse {
                val fx = FX[op.key] ?: Fx()
                return world.call(
                    client, contract, op, c, t,
                    bodyEdit = { n -> fx.body(n as com.fasterxml.jackson.databind.node.ObjectNode, c, ctx) },
                    queryOverrides = fx.query(c, ctx), pathValues = fx.path(c, ctx), headers = fx.headers(c, ctx),
                    proof = fx.proof.takeIf { c.device == ApiWorld.SR_PHONE }?.let { f -> { m: String, path: String, sent: ByteArray, json: String? -> f(world, m, path, sent, json) } },
                    rawBody = fx.raw?.invoke(c, ctx),
                    // A fixture's body must still be a request the contract allows (a 2xx to an invalid request proves nothing).
                    afterEdit = FX[op.key]?.let { { n -> op.requestSchema?.let { rs -> contract.errorKeys(rs, n).forEach { e -> fixtureFindings += Finding(op.method, ContractModel.shape(op.path), 0, "fixture-invalid", e, keyed = true) } } } },
                ).also { checkedCalls++ }
            }
            // Reads first, then writes; an operation after its producers ([Fx.after]); the session-ending
            // operations last (change-password before logout).
            val last = listOf("POST /v1/auth/change-password", "POST /v1/auth/logout")
            val ops = endpoints.mapNotNull { (m, p) -> contract.operation(m, p) }.distinctBy { it.key }
                .sortedWith(compareBy<ContractModel.Operation>({ last.indexOf(it.key) }, { writePhase(it.key) }, { depth(it.key) }, { it.key }))
            for (op in ops) {
                // The generator's body must satisfy the contract's own request schema (or the schema is unsatisfiable).
                op.requestSchema?.let { rs -> contract.errorKeys(rs, contract.sample(rs)).forEach { e -> out += Finding(op.method, ContractModel.shape(op.path), 0, "sample-invalid", e, keyed = true) } }
                called += op.key
                FX[op.key]?.prepare?.invoke(client, ctx)
                for (c in if (op.public) callers.take(1) else callers) {
                    var t = if (op.public) null else token(c) ?: continue
                    var r = call(op, c, t)
                    if (!op.public && r.status.value == 401 && op.key !in last) { tokens.remove(c); t = token(c) ?: continue; r = call(op, c, t) }
                    val text = r.bodyAsText()
                    if (r.status.value in 200..299) {
                        succeeded += op.key
                        FX[op.key]?.capture?.invoke(c, if (text.isBlank()) com.fasterxml.jackson.databind.node.NullNode.instance else ContractModel.json.readTree(text), ctx)
                    } else refusals.getOrPut(op.key) { mutableListOf() } += "${c.user} ${r.status.value} ${text.take(160).replace('\n', ' ')}"
                    if (op.key == "GET /v1/me" && c == callers[0] && r.status.value == 200) meBody = text
                    out += check(op, r.status.value, r.headers["Content-Type"], text)
                }
            }
        }
        findings = (out + fixtureFindings).distinctBy { it.key }
        File("build").mkdirs()
        File("build/contract-conformance.txt").writeText(
            "calls=$checkedCalls bodies_validated=$validatedBodies statuses=$statusSeen operations=${called.size} with_2xx=${succeeded.size}\n" +
                findings.joinToString("\n") { it.key + if (it.keyed) "" else "  " + it.detail } + "\n" +
                "# operations without any 2xx answer (with each caller's refusal):\n" +
                    (called - succeeded).joinToString("\n") { k -> k + refusals[k].orEmpty().joinToString("") { "\n    $it" } } + "\n",
        )
    }

    /** One answer against the contract; empty when it conforms. */
    internal fun check(op: ContractModel.Operation, status: Int, contentType: String?, body: String): List<Finding> {
        statusSeen.merge(status, 1, Int::plus)
        val shape = ContractModel.shape(op.path)
        val resp = op.responses[status.toString()] ?: op.responses["${status / 100}XX"] ?: op.responses["default"]
            ?: return listOf(Finding(op.method, shape, status, "undeclared-status", body.take(200)))
        if (op.method == "HEAD") return emptyList()
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (body.isEmpty()) {
            // A declared JSON body that does not come is a deviation too (204 and 304 carry none by definition).
            return if (status != 204 && status != 304 && resp.content.keys.any { it.endsWith("json") }) listOf(Finding(op.method, shape, status, "empty-body", "declared ${resp.content.keys}")) else emptyList()
        }
        if (type == null) return listOf(Finding(op.method, shape, status, "no-content-type", body.take(120)))
        if (resp.content.isEmpty()) return listOf(Finding(op.method, shape, status, "undeclared-body", "$type ${body.take(120)}"))
        val schema = resp.content[type] ?: return listOf(Finding(op.method, shape, status, "undeclared-content-type", "$type not in ${resp.content.keys}"))
        if (schema == null || !type.endsWith("json")) return emptyList()
        val node = try { ContractModel.json.readTree(body) } catch (e: Exception) { return listOf(Finding(op.method, shape, status, "invalid-json", body.take(120))) }
        validatedBodies++
        return contract.errorKeys(schema, node).map { Finding(op.method, shape, status, "schema", it, keyed = true) }
    }

    private fun baseline(name: String): List<String> =
        javaClass.getResource("/$name")!!.readText().lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

    /** Fails with the new and the stale lines of a shrinking baseline. */
    private fun shrinking(name: String, now: Map<String, String>) {
        val base = baseline(name)
        assertEquals(emptyList(), base.groupBy { it }.filter { it.value.size > 1 }.keys.toList(), "duplicate lines in $name")
        val fresh = now.keys - base.toSet()
        val stale = base.toSet() - now.keys
        val msg = buildString {
            if (fresh.isNotEmpty()) append("new (fix the code or the contract; never add to $name):\n" + fresh.sorted().joinToString("\n") { "  $it  ${now[it]}" } + "\n")
            if (stale.isNotEmpty()) append("fixed, still listed in $name (remove the lines):\n" + stale.sorted().joinToString("\n") { "  $it" } + "\n")
        }
        assertTrue(msg.isEmpty(), msg)
    }

    // ---- tests -----------------------------------------------------------------------------------------------------

    @Test
    fun theSuiteReallyRan() {
        assertTrue(endpoints.size > 100, "only ${endpoints.size} routes found")
        val ops = endpoints.mapNotNull { (m, p) -> contract.operation(m, p) }.distinctBy { it.key }.size
        assertTrue(checkedCalls >= ops, "calls $checkedCalls < operations $ops")
        assertTrue(succeeded.size * 2 > called.size, "fewer than half of the operations answered 2xx: ${succeeded.size} of ${called.size}")
        assertTrue(validatedBodies > ops, "bodies validated $validatedBodies")
        // The sync paths the field apps live on are validated on their success answer, not only on refusals.
        assertTrue(listOf("POST /v1/sync/batch", "GET /v1/sync/bundle", "POST /v1/auth/login", "GET /v1/me").all { it in succeeded }, "core operations without a 2xx: ${listOf("POST /v1/sync/batch", "GET /v1/sync/bundle", "POST /v1/auth/login", "GET /v1/me") - succeeded}")
    }

    @Test
    fun everyAnswerConformsToTheContractExceptTheShrinkingBaseline() =
        shrinking("contract-conformance-baseline.txt", findings.associate { it.key to (if (it.keyed) "" else it.detail) })

    @Test
    fun everyOperationAnswersSuccessOnceExceptTheShrinkingList() =
        shrinking("contract-no-success-baseline.txt", (called - succeeded).associateWith { "never answered 2xx to any caller" })

    @Test
    fun everySuccessSchemaOfTheContractIsSatisfiable() {
        // A 2xx schema that rejects even its own generated instance cannot be met by any server (e.g. allOf over a
        // closed branch). The generator is not perfect, so a line here is a lead for the contract, listed in a baseline.
        val now = contract.successSchemas().mapNotNull { (k, st, sch) -> contract.errorKeys(sch, contract.sample(sch)).takeIf { it.isNotEmpty() }?.let { "$k $st" to it.take(3).joinToString("; ") } }.toMap()
        shrinking("contract-unsatisfiable-baseline.txt", now)
    }

    @Test
    fun aSeededWrongBodyIsCaught() {
        val op = contract.operation("GET", "/v1/me")!!
        // The real answer conforms...
        assertEquals(emptyList(), check(op, 200, "application/json", meBody()), "the real /v1/me answer")
        // ...and each seeded mismatch is reported.
        val real = ContractModel.json.readTree(meBody()) as com.fasterxml.jackson.databind.node.ObjectNode
        val required = real.fieldNames().asSequence().first { contract.validate(op.responses["200"]!!.content["application/json"]!!, real.deepCopy().apply { remove(it) }).isNotEmpty() }
        val dropped = real.deepCopy().apply { remove(required) }
        assertEquals("schema", check(op, 200, "application/json", dropped.toString()).single().kind, "a dropped required field ($required)")
        val retyped = real.deepCopy().apply { put(required, 12345) }
        assertTrue(check(op, 200, "application/json", retyped.toString()).all { it.kind == "schema" && "/$required" in it.detail }, "a field of the wrong type")
        assertEquals("empty-body", check(op, 200, "application/json", "").single().kind, "a declared body that does not come")
        val problemOp = contract.operation("POST", "/v1/auth/login")!!
        assertTrue(check(problemOp, 401, "application/problem+json", """{"type":"about:blank","title":"x","status":401,"code":"ERR_NOT_A_CONTRACT_CODE"}""").any { it.kind == "schema" && "/code" in it.detail }, "a problem code outside ProblemCode")
        // A new error on an operation already in the baseline has its own key, so it is not hidden by the old one.
        assertTrue(check(op, 200, "application/json", dropped.toString()).single().key != check(op, 200, "application/json", retyped.toString()).first().key)
    }

    @Test
    fun aSeededUndeclaredStatusAndAnUnknownRouteAreCaught() = testApplication {
        val op = contract.operation("GET", "/v1/health")!!
        assertEquals("undeclared-status", check(op, 418, "application/json", "{}").single().kind)
        // A real extra route on the production routing tree is reported by the same walker and check as the run.
        application {
            aronApi(world.wiring)
            routing { get("/v1/qa/not-in-contract/{id}") { call.respondText("{}") } }
        }
        startApplication()
        val missing = notInContract(ApiWorld.routes(application.plugin(RoutingRoot)))
        assertTrue(missing.any { it.key == "GET /v1/qa/not-in-contract/{} 0 not-in-contract" }, "the extra route was not reported: ${missing.map { it.key }}")
    }

    companion object {
        /** A business date 30 days after the harness day, for writes that refuse today or the past. */
        private val FUTURE: String = java.time.LocalDate.parse(ApiWorld.BUSINESS_DATE).plusDays(30).toString()

        /** A write, or a read that needs a write first: runs after every plain read (a read chain stays with the reads). */
        fun writePhase(key: String): Boolean = !(key.startsWith("GET ") || key.startsWith("HEAD ")) || FX[key]?.after.orEmpty().any { writePhase(it) }

        /** Rank of an operation in the run: 0 without a producer, else one more than its deepest producer. */
        fun depth(key: String): Int = FX[key]?.after?.maxOfOrNull { depth(it) + 1 } ?: 0

        private fun obj(n: com.fasterxml.jackson.databind.JsonNode?) = n as? com.fasterxml.jackson.databind.node.ObjectNode

        /**
         * What a generated call cannot get right, per operation: real credentials and codes, a future date, the id or
         * cursor an earlier answer created ([Fx.after] runs the producer first, [Fx.capture] keeps the value in [Ctx]).
         */
        val FX: Map<String, Fx> = mapOf(
            // The seeded phone holds a real key ([ApiWorld.phoneKey]), so its calls carry a device proof.
            "POST /v1/sync/batch" to Fx(proof = BATCH_PROOF),
            "PUT /v1/devices/me/push-token" to Fx(proof = DEVICE_PROOF),
            "POST /v1/auth/login" to Fx(body = { n, c, x ->
                n.put("username", c.user); n.put("password", x.world.password); n.put("client", c.client)
                if (c.device != null) n.put("device_uuid", c.device) else n.remove("device_uuid")
            }),
            // Each caller's bundle: its version, first paged section and delta cursor drive the page and delta reads.
            "GET /v1/sync/bundle" to Fx(capture = { c, b, x -> x.bundles[c.user] = b }),
            "GET /v1/sync/bundle/page" to Fx(after = listOf("GET /v1/sync/bundle"), query = { c, x ->
                val meta = x.bundles[c.user]?.path("meta")
                val paged = meta?.path("paged_sections")?.firstOrNull()
                if (meta == null || paged == null) emptyMap() else mapOf("bundle_version" to meta.path("bundle_version").asText(), "section" to paged.path("section").asText(), "page" to "1")
            }),
            "GET /v1/sync/delta" to Fx(after = listOf("GET /v1/sync/bundle"), query = { c, x -> x.bundles[c.user]?.let { mapOf("since" to it.path("meta").path("cursor").asText()) }.orEmpty() }),
            // Seeded codes (V0027): a generated code is unknown to the code list and refused with 400 unknown_code.
            "POST /v1/feedback" to Fx(body = { n, _, _ -> n.put("category_code", "suggestion") }),
            "POST /v1/leave" to Fx(body = { n, _, _ -> n.put("leave_type_code", "casual") }),
            // Calendar and price writes take effect in the future only (ERR_MASTER_EFFECTIVE_DATE_PAST, finance.back_date).
            "POST /v1/admin/calendar/holidays" to Fx(body = { n, _, _ -> n.put("date", FUTURE) }),
            "POST /v1/admin/prices/preview" to Fx(body = { n, _, _ -> n.put("valid_from", FUTURE) }),
            // Publishing prices needs the preview of the same batch first (ERR_REQUEST_STATE otherwise).
            "POST /v1/admin/prices" to Fx(after = listOf("POST /v1/admin/prices/preview"), body = { n, _, _ -> n.put("valid_from", FUTURE) }),
            // The sample parent is not a node of the parent level; a rename alone is a valid patch.
            "PATCH /v1/admin/product-nodes/{}/{}" to Fx(body = { n, _, _ -> n.remove("parent_id"); n.put("name", "Contract check") }),
            // The sample key is cfg.geo.radius_m, an integer key.
            "POST /v1/admin/config/changes" to Fx(body = { n, _, _ -> obj(n.path("changes").firstOrNull())?.put("value", 150) }),

            // Outlet requests: tso1001 raises an info change on its own outlet, amo1001 verifies, admin1001 approves
            // (separation of duties); the seeded second request is rejected by amo1001.
            "POST /v1/outlet-requests" to Fx(body = { n, _, x ->
                n.put("request_type", "info"); n.put("outlet_id", x.id("outlet_req")); n.putObject("proposed").put("name", "QA Renamed Outlet")
            }),
            "GET /v1/outlet-requests/{}" to Fx(after = listOf("POST /v1/outlet-requests")),
            "POST /v1/outlet-requests/{}/verify" to Fx(after = listOf("POST /v1/outlet-requests"), body = { n, _, x -> n.put("sub_channel_id", x.id("sub_channel")) }),
            "POST /v1/outlet-requests/{}/approve" to Fx(after = listOf("POST /v1/outlet-requests/{}/verify")),
            "POST /v1/outlet-requests/{}/reject" to Fx(path = { _, _ -> mapOf("request_uuid" to ApiWorld.SECOND_REQUEST) }),
            // Feedback triage after tso1001's submit (the sample uuid on both); a real status change.
            "PATCH /v1/feedback/{}" to Fx(after = listOf("POST /v1/feedback"), body = { n, _, _ -> n.put("status", "in_progress") }),
            // The seeded risk signal; each caller reviews with its own review_uuid (a reuse by another user is 409).
            "POST /v1/risk-signals/{}/review" to Fx(path = { _, x -> mapOf("signal_id" to x.id("risk-signals").toString()) }, body = { n, c, _ -> n.put("review_uuid", ApiWorld.v4("review/${c.user}")) }),
            // Tasks for sr1001 (active, in every web caller's reach); amo1001 keeps the sample uuid so cancel targets its task.
            "POST /v1/tasks" to Fx(body = { n, c, x ->
                n.put("assignee_user_id", x.id("sr_user")); n.put("task_type_code", "qa_check")
                if (c.user != "amo1001") n.put("task_uuid", ApiWorld.v4("task/${c.user}"))
            }),
            "POST /v1/tasks/{}/cancel" to Fx(after = listOf("POST /v1/tasks")),
            // Actions open at cfg.day.take_action_after (17:00 Dhaka); the clock is 10:00, so the action is on yesterday's seeded route-day.
            "POST /v1/dashboards/daily-tracking/actions" to Fx(body = { n, c, x ->
                n.put("action_uuid", ApiWorld.v4("action/${c.user}")); n.put("route_id", x.id("sr_route")); n.put("business_date", ApiWorld.YESTERDAY)
            }),
            "POST /v1/day/sales-submit" to Fx(body = { n, c, x ->
                n.put("client_uuid", ApiWorld.v4("sales-submit/${c.user}")); n.put("scope", "route_day"); n.put("route_id", x.id("sr_route"))
            }),
            // Web entry before the zone-day is Final Submitted (final-submit runs after both); per-caller client uuids, and
            // a reason on every save (the 2nd and 3rd callers re-save).
            "POST /v1/web-entry/qc" to Fx(body = { n, c, x ->
                n.put("client_uuid", ApiWorld.v4("qc/${c.user}")); n.put("source", "market"); n.put("zone_id", x.id("zones")); n.put("route_id", x.id("routes"))
                n.putArray("rows").addObject().put("sku_id", x.id("skus")).put("fault_type_code", "damaged_crushed").put("qty_base", 0)
            }),
            "POST /v1/web-entry/route-day" to Fx(body = { n, c, x ->
                n.put("client_uuid", ApiWorld.v4("route-day/${c.user}")); n.put("route_id", x.id("routes")); n.put("change_reason", "contract conformance re-save")
                n.putArray("lines").addObject().put("sku_id", x.id("skus")).put("issue_qty_base", 0).put("return_qty_base", 0).put("memo_count", 0)
            }),
            // Admin content: each PATCH after its create, on the id the create answered (version 1, If-Match "1").
            "PATCH /v1/admin/content/{}" to Fx(after = listOf("POST /v1/admin/content"), path = { _, x -> mapOf("id" to x.need("content_id")) }),
            "POST /v1/admin/content" to Fx(capture = { _, b, x -> if (x["content_id"] == null) x["content_id"] = b.path("content_id").asText() }),
            "PATCH /v1/admin/rubrics/{}" to Fx(after = listOf("POST /v1/admin/rubrics"), path = { _, x -> mapOf("id" to x.need("rubric_id")) }),
            "POST /v1/admin/rubrics" to Fx(capture = { _, b, x -> if (x["rubric_id"] == null) x["rubric_id"] = b.path("rubric_id").asText() }),
            "PATCH /v1/admin/surveys/{}" to Fx(after = listOf("POST /v1/admin/surveys"), path = { _, x -> mapOf("id" to x.need("survey_id")) }),
            "POST /v1/admin/surveys" to Fx(capture = { _, b, x -> if (x["survey_id"] == null) x["survey_id"] = b.path("survey_id").asText() }),
            // A tutorial video needs a tutorial_video asset (seeded; the sample asset is content_av).
            "POST /v1/admin/tutorials" to Fx(
                body = { n, _, _ -> n.put("asset_id", ApiWorld.TUTORIAL_ASSET) },
                capture = { _, b, x -> if (x["tutorial_id"] == null) x["tutorial_id"] = b.path("tutorial_id").asText() },
            ),
            "PATCH /v1/admin/tutorials/{}" to Fx(after = listOf("POST /v1/admin/tutorials"), path = { _, x -> mapOf("id" to x.need("tutorial_id")) }, body = { n, _, _ -> n.put("asset_id", ApiWorld.TUTORIAL_ASSET) }),
            // Sending zone_id at all is a move (409 while the cluster has outlets); a rename is the patch.
            "PATCH /v1/admin/clusters/{}" to Fx(body = { n, _, _ -> n.remove("zone_id"); n.put("name", "Contract Check Cluster") }),
            "POST /v1/admin/skus" to Fx(body = { n, c, x -> n.put("variant_id", x.id("variants")); n.put("code", "QA-${c.user}") }),
            // template_json is a JSON object as a string; effective later, so today's template stays.
            "POST /v1/admin/print-templates" to Fx(body = { n, _, _ -> n.put("template_json", "{\"lines\":[\"{{memo_no}}\"]}"); n.put("effective_from", FUTURE) }),
            // Paper backfill for the throwaway SR (cover on MIR-SR-D): one MaxR-10S stick at the outlet price 9200, on credit.
            "POST /v1/admin/data-entry" to Fx(body = { n, _, x ->
                n.put("client_uuid", "00000000-0000-4000-8000-0000000000e1"); n.put("memo_no", ApiWorld.PAPER_MEMO_NO)
                n.put("user_id", x.id("users")); n.put("route_id", x.id("sr_route")); n.put("outlet_id", x.id("paper_outlet")); n.put("paid_mtk", 0)
                n.putArray("lines").addObject().put("sku_id", x.id("paper_sku")).put("qty_base", 1).put("line_kind", "sale")
            }),
            // The seeded phone memo held as a duplicate of that paper memo; a separate held item is discarded.
            "POST /v1/admin/quarantine/{}/match-backfill" to Fx(after = listOf("POST /v1/admin/data-entry"), path = { _, x -> mapOf("quarantine_id" to x.id("quarantine_match").toString()) }),
            "POST /v1/admin/quarantine/{}/resolve" to Fx(path = { _, x -> mapOf("quarantine_id" to x.id("quarantine_resolve").toString()) }, body = { n, _, _ -> n.put("action", "discard") }),
            // A -100 % change waits for a SUPERADMIN other than the submitter (admin1001): superadmin1001 rejects it.
            "POST /v1/admin/prices/batches/{}/decision" to Fx(after = listOf("POST /v1/admin/prices"), body = { n, _, _ -> n.put("decision", "reject") }),
            // Config: the seeded pending change, version 1 (V0006 always creates it); a TSO names a zone of its reach.
            "GET /v1/admin/config/changes/{}" to Fx(path = { _, x -> mapOf("change_id" to x.id("cfg_change").toString()) }),
            "GET /v1/admin/config/versions/{}" to Fx(path = { _, _ -> mapOf("version" to "1") }),
            "GET /v1/admin/config/reach/{}" to Fx(path = { _, _ -> mapOf("version" to "1") }, query = { _, x -> mapOf("zone_id" to x.id("zones").toString()) }),
            "GET /v1/admin/config/reach/{}/pending" to Fx(path = { _, _ -> mapOf("version" to "1") }, query = { _, x -> mapOf("zone_id" to x.id("zones").toString()) }),
            "POST /v1/admin/config/changes/{}/decision" to Fx(path = { _, x -> mapOf("change_id" to x.id("cfg_change").toString()) }, body = { n, _, _ -> n.put("decision", "reject") }),
            // Version 1 reverts role-scope C2 rows: scheduled, never applied under the fixed clock.
            "POST /v1/admin/config/versions/{}/rollback" to Fx(path = { _, _ -> mapOf("version" to "1") }),
            "PUT /v1/admin/users/{}/scope" to Fx(path = { _, x -> mapOf("id" to x.id("scope_user").toString()) }),
            // A future cover for the throwaway SR (every MIR route has sr1001 as primary; a cover today would take its day);
            // each caller its own window. /end ends that cover, never a seeded assignment.
            "POST /v1/admin/route-assignments" to Fx(
                body = { n, c, x ->
                    val day = if (c.user == "superadmin1001") 12 else 10
                    n.put("route_id", x.id("cover_route")); n.put("user_id", x.id("users")); n.put("kind", "cover")
                    n.put("valid_from", "2027-01-$day"); n.put("valid_to", "2027-01-${day + 1}")
                },
                capture = { _, b, x -> if (x["assignment_id"] == null) x["assignment_id"] = b.path("id").asText() },
            ),
            "POST /v1/admin/route-assignments/{}/end" to Fx(
                after = listOf("POST /v1/admin/route-assignments"),
                path = { _, x -> mapOf("id" to x.need("assignment_id")) }, body = { n, _, _ -> n.put("valid_to", "2027-01-11") },
            ),
            // Device admin on the throwaway SR and phone (OTPs revoke its previous OTP only; upload_first changes no state).
            "POST /v1/admin/device-otps" to Fx(body = { n, _, x -> n.put("user_id", x.id("users")) }),
            "POST /v1/admin/devices/{}/replace" to Fx(body = { n, _, x -> n.put("new_device_user_id", x.id("users")) }),
            // admin1001's token is revoked; superadmin1001's enrols a phone.
            "POST /v1/admin/enrolment-tokens" to Fx(capture = { c, b, x ->
                if (c.user == "admin1001") x["token_id"] = b.path("token").path("token_id").asText()
                if (c.user == "superadmin1001") x["enrol_secret"] = b.path("enrolment_token").asText()
            }),
            "POST /v1/admin/enrolment-tokens/{}/revoke" to Fx(after = listOf("POST /v1/admin/enrolment-tokens"), path = { _, x -> mapOf("token_id" to x.need("token_id")) }),
            // Devices: a proof phone with the harness key (its trust may move; the SR phone's must not).
            "GET /v1/devices/me/policy" to Fx(headers = { _, _ -> mapOf("X-Device-Id" to ApiWorld.PROOF_PHONE) }, proof = deviceProof(ApiWorld.PROOF_PHONE)),
            "POST /v1/devices/nonce" to Fx(headers = { _, _ -> mapOf("X-Device-Id" to ApiWorld.PROOF_PHONE) }, proof = deviceProof(ApiWorld.PROOF_PHONE)),
            "POST /v1/devices/me/status" to Fx(headers = { _, _ -> mapOf("X-Device-Id" to ApiWorld.PROOF_PHONE) }, proof = deviceProof(ApiWorld.PROOF_PHONE)),
            // Enrolment with a dev token (no Google root needed): a new phone and key, a self-built chain attesting the
            // token, the SR package and the seeded release's signing certificate.
            "POST /v1/devices/enrol" to Fx(after = listOf("POST /v1/admin/enrolment-tokens"), body = { n, _, x ->
                val token = x.need("enrol_secret")
                val key = AttestationChains.keyPair()
                val certHex = ApiWorld.RELEASE_CERT.joinToString("") { "%02x".format(it) }
                val desc = AttestationChains.keyDescription(java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray()), "com.aktcl.aron.sr", ApiWorld.RELEASE_CERT)
                n.put("enrolment_token", token); n.put("device_uuid", ApiWorld.ENROL_PHONE); n.put("app_package", "com.aktcl.aron.sr"); n.put("app_version", ApiWorld.APP_VERSION)
                n.put("app_signing_cert_sha256", certHex); n.put("device_owner", false)
                n.set<com.fasterxml.jackson.databind.JsonNode>("public_key", ContractModel.json.readTree(publicJwk(key)))
                n.putArray("key_attestation_chain").apply { AttestationChains.chain(key.public, desc).chainBase64.forEach { add(it) } }
            }),
            // Bind: an unbound SR logs in on an unbound phone (bind_required), an admin issues the OTP the TSO reads out.
            "POST /v1/auth/bind-device" to Fx(
                prepare = { client, x ->
                    x.world.login(client, ApiWorld.Caller(ApiWorld.BIND_USER, "app_sr", ApiWorld.BIND_PHONE))
                    val admin = x.world.login(client, ApiWorld.Caller("admin1001", "web", null))
                    val r = client.request("/v1/admin/device-otps") {
                        method = HttpMethod.Post; contentType(ContentType.Application.Json); header("Authorization", "Bearer $admin"); header("X-App-Version", ApiWorld.APP_VERSION)
                        setBody("""{"user_id":${x.id("bind_user")},"reason":"conformance bind fixture"}""")
                    }
                    x["bind_otp"] = ContractModel.json.readTree(r.bodyAsText()).path("otp").asText()
                },
                headers = { c, x -> if (c.user == "sr1001") mapOf("Authorization" to "Bearer " + x.world.logins[ApiWorld.BIND_USER]?.path("bind_token")?.asText(), "X-Device-Id" to ApiWorld.BIND_PHONE) else emptyMap() },
                body = { n, c, x -> if (c.user == "sr1001") { n.put("device_uuid", ApiWorld.BIND_PHONE); n.put("otp", x.need("bind_otp")) } },
            ),
            // MFA: DMO is MFA-required in this world only; dmo1001's login answers mfa_required, the enrolment's secret gives the TOTP.
            "POST /v1/auth/mfa/enrol" to Fx(
                prepare = { client, x -> x.world.login(client, ApiWorld.Caller("dmo1001", "web", null)) },
                headers = { c, x -> if (c.user == "admin1001") mapOf("Authorization" to "Bearer " + x.world.logins["dmo1001"]?.path("mfa_token")?.asText()) else emptyMap() },
                capture = { c, b, x -> if (c.user == "admin1001") x["mfa_secret"] = Regex("[?&]secret=([A-Z2-7]+)").find(b.path("otpauth_uri").asText())?.groupValues?.get(1).orEmpty() },
            ),
            "POST /v1/auth/mfa/verify" to Fx(after = listOf("POST /v1/auth/mfa/enrol"), body = { n, _, x ->
                n.put("mfa_token", x.world.logins["dmo1001"]?.path("mfa_token")?.asText().orEmpty())
                n.put("code", com.aktcl.aron.backend.auth.Totp.code(base32Decode(x.need("mfa_secret")), com.aktcl.aron.backend.auth.Totp.step(x.world.clock.now())))
            }),
            // Refresh the SR phone's latest login, with its refresh proof.
            "POST /v1/auth/refresh" to Fx(proof = REFRESH_PROOF, body = { n, _, x ->
                n.put("grant", "full"); n.put("refresh_token", x.world.logins["sr1001"]?.path("refresh_token")?.asText().orEmpty()); n.put("device_uuid", ApiWorld.SR_PHONE)
            }),
            "POST /v1/auth/change-password" to Fx(body = { n, _, x -> n.put("current_password", x.world.password); n.put("new_password", "Qz7-Kestrel-Lagoon-91") }),
            "GET /v1/media/{}/read-url" to Fx(path = { _, _ -> mapOf("media_uuid" to ApiWorld.STORED_PHOTO) }),
            "POST /v1/media/upload" to Fx(raw = { c, _ -> photoUpload(ApiWorld.v4("upload/${c.user}")) }),
            "POST /v1/day/final-submit" to Fx(after = listOf("POST /v1/web-entry/qc", "POST /v1/web-entry/route-day")),
        )
    }

    /** The SR's real GET /v1/me answer, kept from the run (before the session-ending operations). */
    private var meBody: String? = null
    private fun meBody(): String = meBody ?: error("the run never got a 200 from GET /v1/me for the SR")
}

/** The `device` proof of docs/24 s8.3 by [device]: `METHOD path` and the sha256 of the sent body. */
fun deviceProof(device: String = ApiWorld.SR_PHONE): (ApiWorld, String, String, ByteArray, String?) -> String = { w, m, path, sent, _ -> w.deviceProofOf(m, path, sent, device) }
val DEVICE_PROOF = deviceProof()

/** The `refresh` proof of the seeded SR phone over the body's refresh token. */
val REFRESH_PROOF: (ApiWorld, String, String, ByteArray, String?) -> String = { w, _, _, _, json ->
    w.sign(com.aktcl.aron.backend.platform.DeviceProof.refreshString(ApiWorld.SR_PHONE, ContractModel.json.readTree(json ?: "{}").path("refresh_token").asText(), w.bucket()))
}

/** RFC 4648 base32 (no padding), the `secret` of an otpauth URI. */
fun base32Decode(s: String): ByteArray {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    var buffer = 0; var bits = 0
    val out = java.io.ByteArrayOutputStream()
    for (ch in s.uppercase().trimEnd('=')) {
        buffer = (buffer shl 5) or alphabet.indexOf(ch).also { require(it >= 0) { "not base32: $ch" } }
        bits += 5
        if (bits >= 8) { bits -= 8; out.write((buffer shr bits) and 0xFF) }
    }
    return out.toByteArray()
}

/** A multipart/form-data body (quoted part names, CRLF) for POST /v1/media/upload: a tiny JPEG and its sha256. */
fun photoUpload(mediaUuid: String): io.ktor.http.content.ByteArrayContent {
    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + "contract conformance".toByteArray()
    val sha = com.aktcl.aron.backend.platform.DeviceProof.sha256Hex(jpeg)
    fun field(name: String, value: String) = "--B\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"
    val head = field("media_uuid", mediaUuid) + field("purpose", "feedback") + field("sha256", sha) +
        "--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"p.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"
    val bytes = head.toByteArray() + jpeg + "\r\n--B--\r\n".toByteArray()
    return io.ktor.http.content.ByteArrayContent(bytes, io.ktor.http.ContentType.parse("multipart/form-data; boundary=B"))
}

/** The `batch` proof: sha256 of the gzip body, `batch_uuid`, X-Batch-Attempt (1). */
val BATCH_PROOF: (ApiWorld, String, String, ByteArray, String?) -> String = { w, _, _, sent, json ->
    w.batchProof(ApiWorld.SR_PHONE, sent, ContractModel.json.readTree(json ?: "{}").path("batch_uuid").asText())
}

/** Values one call cannot know: the world, each caller's bundle, and ids or tokens captured from earlier answers. */
class Ctx(val world: ApiWorld) {
    val bundles = HashMap<String, com.fasterxml.jackson.databind.JsonNode>()
    val vars = HashMap<String, String>()
    operator fun get(k: String): String? = vars[k]
    operator fun set(k: String, v: String) { vars[k] = v }
    /** A value an earlier answer must have given (fails fast: an empty path segment would test nothing). */
    fun need(k: String): String = vars[k]?.takeIf { it.isNotEmpty() } ?: error("no $k captured: its producer never answered 2xx")
    /** A seeded id of the home world ([ApiWorld.ids]). */
    fun id(k: String): Long = world.ids[k] ?: error("no seeded id $k")
}

/** The fixture of one operation (see [ContractConformanceTest.FX]); every part defaults to the generated call. */
class Fx(
    /** Producers that must run before this operation (their [capture] fills the [Ctx] values it reads). */
    val after: List<String> = emptyList(),
    val path: (ApiWorld.Caller, Ctx) -> Map<String, String> = { _, _ -> emptyMap() },
    val query: (ApiWorld.Caller, Ctx) -> Map<String, String> = { _, _ -> emptyMap() },
    val headers: (ApiWorld.Caller, Ctx) -> Map<String, String> = { _, _ -> emptyMap() },
    val body: (com.fasterxml.jackson.databind.node.ObjectNode, ApiWorld.Caller, Ctx) -> Unit = { _, _, _ -> },
    /** `X-Device-Proof` of the seeded SR phone from (world, method, path, sent bytes, JSON text); [DEVICE_PROOF] for the device calls. */
    val proof: ((ApiWorld, String, String, ByteArray, String?) -> String)? = null,
    val raw: ((ApiWorld.Caller, Ctx) -> io.ktor.http.content.OutgoingContent.ByteArrayContent)? = null,
    /** Runs on each 2xx answer (body parsed; NullNode when empty). */
    val capture: ((ApiWorld.Caller, com.fasterxml.jackson.databind.JsonNode, Ctx) -> Unit)? = null,
    /** Runs once before the operation's calls (a login or an issue the calls need). */
    val prepare: (suspend (io.ktor.client.HttpClient, Ctx) -> Unit)? = null,
)
