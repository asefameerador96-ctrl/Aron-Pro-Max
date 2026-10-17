package com.aktcl.aron.backend.app

import io.ktor.client.statement.bodyAsText
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
    /** Refusals of the operations without a 2xx answer, for the report (which fixture each one needs). */
    private val refusals = HashMap<String, MutableList<String>>()

    /** One deviation; [detail] is part of the key for schema and sample errors (a normalised error, see errorKeys). */
    data class Finding(val method: String, val shape: String, val status: Int, val kind: String, val detail: String, val keyed: Boolean = false) {
        val key: String get() = "$method $shape $status $kind" + if (keyed) " $detail" else ""
    }

    @BeforeAll
    fun setUp() {
        contract = ContractModel.load(repoRoot.resolve("contract/openapi.yaml"))
        world = ApiWorld(repoRoot)
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
            suspend fun token(c: ApiWorld.Caller): String? = tokens.getOrPut(c) { world.login(client, c) }
            // A caller that cannot log in would silently skip every guarded call.
            for (c in callers) check(token(c) != null) { "no token for ${c.user}" }
            suspend fun call(op: ContractModel.Operation, c: ApiWorld.Caller, t: String?) = world.call(
                client, contract, op, c, t,
                bodyEdit = FIXTURES[op.key]?.let { f -> { n: com.fasterxml.jackson.databind.JsonNode -> f(n as com.fasterxml.jackson.databind.node.ObjectNode, c, world.password) } },
            ).also { checkedCalls++ }
            // Reads first, then writes; the session-ending operations last (change-password before logout).
            val last = listOf("POST /v1/auth/change-password", "POST /v1/auth/logout")
            // Publishing prices needs the preview of the same batch first (ERR_REQUEST_STATE otherwise).
            val late = listOf("POST /v1/admin/prices") + last
            val ops = endpoints.mapNotNull { (m, p) -> contract.operation(m, p) }.distinctBy { it.key }
                .sortedWith(compareBy<ContractModel.Operation>({ late.indexOf(it.key) }, { it.method != "GET" && it.method != "HEAD" }, { it.key }))
            for (op in ops) {
                // The generator's body must satisfy the contract's own request schema (or the schema is unsatisfiable).
                op.requestSchema?.let { rs -> contract.errorKeys(rs, contract.sample(rs)).forEach { e -> out += Finding(op.method, ContractModel.shape(op.path), 0, "sample-invalid", e, keyed = true) } }
                called += op.key
                for (c in if (op.public) callers.take(1) else callers) {
                    var t = if (op.public) null else token(c) ?: continue
                    var r = call(op, c, t)
                    if (!op.public && r.status.value == 401 && op.key !in last) { tokens.remove(c); t = token(c) ?: continue; r = call(op, c, t) }
                    val text = r.bodyAsText()
                    if (r.status.value in 200..299) succeeded += op.key
                    else refusals.getOrPut(op.key) { mutableListOf() } += "${c.user} ${r.status.value} ${text.take(160).replace('\n', ' ')}"
                    if (op.key == "GET /v1/me" && c == callers[0] && r.status.value == 200) meBody = text
                    out += check(op, r.status.value, r.headers["Content-Type"], text)
                }
            }
        }
        findings = out.distinctBy { it.key }
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
        /** Request bodies a generated sample cannot get right (real credentials), filled per caller. */
        /** A business date 30 days after the harness day, for writes that refuse today or the past. */
        private val FUTURE: String = java.time.LocalDate.parse(ApiWorld.BUSINESS_DATE).plusDays(30).toString()

        val FIXTURES: Map<String, (com.fasterxml.jackson.databind.node.ObjectNode, ApiWorld.Caller, String) -> Unit> = mapOf(
            "POST /v1/auth/login" to { n, c, pw ->
                n.put("username", c.user); n.put("password", pw); n.put("client", c.client)
                if (c.device != null) n.put("device_uuid", c.device) else n.remove("device_uuid")
            },
            // Seeded codes (V0027): a generated code is unknown to the code list and refused with 400 unknown_code.
            "POST /v1/feedback" to { n, _, _ -> n.put("category_code", "suggestion") },
            "POST /v1/leave" to { n, _, _ -> n.put("leave_type_code", "casual") },
            // Calendar and price writes take effect in the future only (ERR_MASTER_EFFECTIVE_DATE_PAST, finance.back_date).
            "POST /v1/admin/calendar/holidays" to { n, _, _ -> n.put("date", FUTURE) },
            "POST /v1/admin/prices" to { n, _, _ -> n.put("valid_from", FUTURE) },
            "POST /v1/admin/prices/preview" to { n, _, _ -> n.put("valid_from", FUTURE) },
            // The sample parent is not a node of the parent level; a rename alone is a valid patch.
            "PATCH /v1/admin/product-nodes/{}/{}" to { n, _, _ -> n.remove("parent_id"); n.put("name", "Contract check") },
            // The sample key is cfg.geo.radius_m, an integer key.
            "POST /v1/admin/config/changes" to { n, _, _ -> (n.path("changes").firstOrNull() as? com.fasterxml.jackson.databind.node.ObjectNode)?.put("value", 150) },
        )
    }

    /** The SR's real GET /v1/me answer, kept from the run (before the session-ending operations). */
    private var meBody: String? = null
    private fun meBody(): String = meBody ?: error("the run never got a 200 from GET /v1/me for the SR")
}
