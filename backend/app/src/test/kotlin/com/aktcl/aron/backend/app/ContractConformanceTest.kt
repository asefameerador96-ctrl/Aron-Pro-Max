package com.aktcl.aron.backend.app

import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.plugin
import io.ktor.server.routing.RoutingRoot
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

    data class Finding(val method: String, val shape: String, val status: Int, val kind: String, val detail: String) {
        val key: String get() = "$method $shape $status $kind"
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

    private fun runSuite() {
        val out = mutableListOf<Finding>()
        testApplication {
            application { aronApi(world.wiring) }
            startApplication()
            endpoints = ApiWorld.routes(application.plugin(RoutingRoot)).sortedBy { it.second + " " + it.first }
            for ((m, p) in endpoints) if (contract.operation(m, p) == null) out += Finding(m, ContractModel.shape(p), 0, "not-in-contract", "the route is served but the contract has no such operation")
            val tokens = HashMap<ApiWorld.Caller, String?>()
            suspend fun token(c: ApiWorld.Caller): String? = tokens.getOrPut(c) { world.login(client, c) }
            suspend fun call(op: ContractModel.Operation, c: ApiWorld.Caller, t: String?) = world.call(client, contract, op, c, t) { problems ->
                println("ContractConformanceTest: generated body for ${op.key} is not valid (${problems.take(2)}); sent anyway")
            }.also { checkedCalls++ }
            // Reads first, then writes; the session-ending operations last, so the tokens live as long as possible.
            val last = setOf("POST /v1/auth/logout", "POST /v1/auth/change-password")
            val ops = endpoints.mapNotNull { (m, p) -> contract.operation(m, p) }.distinctBy { it.key }
                .sortedWith(compareBy<ContractModel.Operation>({ it.key in last }, { it.method != "GET" && it.method != "HEAD" }, { it.key }))
            for (op in ops) {
                for (c in if (op.public) callers.take(1) else callers) {
                    var t = if (op.public) null else token(c) ?: continue
                    var r = call(op, c, t)
                    if (!op.public && r.status.value == 401 && op.key !in last) { tokens.remove(c); t = token(c) ?: continue; r = call(op, c, t) }
                    val text = r.bodyAsText()
                    if (op.key == "GET /v1/me" && c == callers[0] && r.status.value == 200) meBody = text
                    out += check(op, r.status.value, r.headers["Content-Type"], text)
                }
            }
        }
        findings = out.distinctBy { it.key }
        File("build").mkdirs()
        File("build/contract-conformance.txt").writeText(
            "calls=$checkedCalls bodies_validated=$validatedBodies statuses=$statusSeen\n" + findings.joinToString("\n") { "${it.key}  ${it.detail}" } + "\n",
        )
    }

    /** One answer against the contract; empty when it conforms. */
    internal fun check(op: ContractModel.Operation, status: Int, contentType: String?, body: String): List<Finding> {
        statusSeen.merge(status, 1, Int::plus)
        val shape = ContractModel.shape(op.path)
        val resp = op.responses[status.toString()] ?: op.responses["${status / 100}XX"] ?: op.responses["default"]
            ?: return listOf(Finding(op.method, shape, status, "undeclared-status", body.take(200)))
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (body.isEmpty() || op.method == "HEAD") return emptyList()
        if (type == null) return listOf(Finding(op.method, shape, status, "no-content-type", body.take(120)))
        if (resp.content.isEmpty()) return listOf(Finding(op.method, shape, status, "undeclared-body", "$type ${body.take(120)}"))
        val schema = resp.content[type] ?: return listOf(Finding(op.method, shape, status, "undeclared-content-type", "$type not in ${resp.content.keys}"))
        if (schema == null || !type.endsWith("json")) return emptyList()
        val node = try { ContractModel.json.readTree(body) } catch (e: Exception) { return listOf(Finding(op.method, shape, status, "invalid-json", body.take(120))) }
        validatedBodies++
        val errors = contract.validate(schema, node)
        return if (errors.isEmpty()) emptyList() else listOf(Finding(op.method, shape, status, "schema", errors.take(4).joinToString("; ")))
    }

    // ---- tests -----------------------------------------------------------------------------------------------------

    @Test
    fun theSuiteReallyRan() {
        assertTrue(endpoints.size > 100, "only ${endpoints.size} routes found")
        val ops = endpoints.mapNotNull { (m, p) -> contract.operation(m, p) }.distinctBy { it.key }.size
        assertTrue(checkedCalls >= ops, "calls $checkedCalls < operations $ops")
        // Real successes were validated, not only refusals: every status class the API answers with is present.
        assertTrue((statusSeen.filterKeys { it in 200..299 }.values.sum()) > 40, "too few 2xx answers: $statusSeen")
        assertTrue(validatedBodies > ops, "bodies validated $validatedBodies")
    }

    @Test
    fun everyAnswerConformsToTheContractExceptTheShrinkingBaseline() {
        val baseline = javaClass.getResource("/contract-conformance-baseline.txt")!!.readText().lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        assertEquals(emptyList(), baseline.groupBy { it }.filter { it.value.size > 1 }.keys.toList(), "duplicate baseline lines")
        val now = findings.associateBy { it.key }
        val fresh = now.keys - baseline.toSet()
        val stale = baseline.toSet() - now.keys
        val msg = buildString {
            if (fresh.isNotEmpty()) append("new contract deviations (fix the code or the contract; never add to the baseline):\n" + fresh.sorted().joinToString("\n") { "  $it  ${now[it]!!.detail}" } + "\n")
            if (stale.isNotEmpty()) append("fixed deviations still in contract-conformance-baseline.txt (remove them):\n" + stale.sorted().joinToString("\n") { "  $it" } + "\n")
        }
        assertTrue(msg.isEmpty(), msg)
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
        assertEquals("schema", check(op, 200, "application/json", retyped.toString()).single().kind, "a field of the wrong type")
        val problemOp = contract.operation("POST", "/v1/auth/login")!!
        assertEquals("schema", check(problemOp, 401, "application/problem+json", """{"type":"about:blank","title":"x","status":401,"code":"ERR_NOT_A_CONTRACT_CODE"}""").single().kind, "a problem code outside ProblemCode")
    }

    @Test
    fun aSeededUndeclaredStatusAndAnUnknownRouteAreCaught() {
        val op = contract.operation("GET", "/v1/health")!!
        assertEquals("undeclared-status", check(op, 418, "application/json", "{}").single().kind)
        assertEquals(null, contract.operation("GET", "/v1/qa/not-in-contract"))
        assertTrue(endpoints.all { (m, p) -> contract.operation(m, p) != null || findings.any { it.kind == "not-in-contract" && it.method == m && it.shape == ContractModel.shape(p) } })
    }

    /** The SR's real GET /v1/me answer, kept from the run (before the session-ending operations). */
    private var meBody: String? = null
    private fun meBody(): String = meBody ?: error("the run never got a 200 from GET /v1/me for the SR")
}
