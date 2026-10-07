package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthenticatedRouteSelector
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.PathSegmentWildcardRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AUD-SEC-08 and AUD-TP-3: structural checks over the PRODUCTION routing tree (Wiring.production), so a lane cannot
 * ship an endpoint that is unguarded or has no scope case:
 * - every route under /v1 is inside `authenticated {}` unless the contract declares the operation public
 *   (`security: []`, or `{}` among the alternatives, in contract/openapi.yaml); the allow-list is the contract;
 * - every guarded route answers 401 ERR_UNAUTHENTICATED without a token at runtime (the guard really runs);
 * - every guarded route has a line in `scope-cases.txt` (test resources): the test that proves its scope, an
 *   exemption with its reason, or a known gap (debt the lead tracks; the gap list may only shrink).
 * The inventory is written to build/route-inventory.txt.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouteInventoryTest {
    data class Endpoint(val method: String, val path: String, val guarded: Boolean) {
        val key: String get() = "$method $path"
        val shape: String get() = "$method ${normalise(path)}"
    }

    private lateinit var wiring: Wiring
    private lateinit var endpoints: List<Endpoint>
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")

    @BeforeAll
    fun setUp() {
        val port = ServerSocket(0).use { it.localPort }
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(
            Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to "jdbc:postgresql://127.0.0.1:$port/aron?user=aron&password=x&connectTimeout=1", "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)),
            AronClock { Instant.parse("2027-01-03T04:00:00Z") },
        )
        var found: List<Endpoint> = emptyList()
        testApplication {
            application { aronApi(wiring) }
            startApplication()
            found = collect(application.plugin(RoutingRoot))
        }
        endpoints = found.sortedBy { it.key }
        File("build").mkdirs()
        File("build/route-inventory.txt").writeText(endpoints.joinToString("\n") { "${it.key}${if (it.guarded) "" else "  [public]"}" } + "\n")
    }

    @AfterAll
    fun tearDown() { wiring.database?.close() }

    @Test
    fun theInventoryIsNotEmptyAndCoversTheKnownGroups() {
        assertTrue(endpoints.size > 50, "only ${endpoints.size} routes found: the walker is broken")
        listOf("POST /v1/sync/batch", "GET /v1/sync/bundle", "POST /v1/auth/login", "GET /v1/health").forEach { k ->
            assertTrue(endpoints.any { it.key == k }, "$k missing from the inventory")
        }
        assertTrue(endpoints.single { it.key == "POST /v1/sync/batch" }.guarded)
    }

    @Test
    fun everyRouteIsGuardedUnlessTheContractDeclaresItPublic() {
        val publicOps = contractPublicOperations()
        assertTrue("GET /v1/health" in publicOps && "POST /v1/auth/login" in publicOps, "the contract parser found $publicOps")
        val unguarded = endpoints.filter { !it.guarded && it.shape !in publicOps }
        if (unguarded.isNotEmpty()) {
            fail("routes outside authenticated {} that the contract does not declare public (security: []):\n" + unguarded.joinToString("\n") { "  " + it.key })
        }
        // The other direction: an operation the contract protects must not be served without the guard.
        val contractGuarded = contractOperations() - publicOps
        val leaked = endpoints.filter { !it.guarded && it.shape in contractGuarded }
        assertEquals(emptyList(), leaked.map { it.key })
    }

    @Test
    fun everyGuardedRouteRefusesACallWithoutAToken() = testApplication {
        application { aronApi(wiring) }
        val wrong = mutableListOf<String>()
        for (e in endpoints.filter { it.guarded }) {
            val r = client.request(concrete(e.path)) { method = HttpMethod.parse(e.method) }
            if (r.status.value != 401 || "ERR_UNAUTHENTICATED" !in r.bodyAsText()) wrong += "${e.key} -> ${r.status.value}"
        }
        assertEquals(emptyList(), wrong, "guarded routes that did not answer 401 ERR_UNAUTHENTICATED without a token")
    }

    @Test
    fun everyGuardedRouteHasAScopeCaseAnExemptionOrAKnownGap() {
        val lines = javaClass.getResource("/scope-cases.txt")?.readText()?.lines() ?: fail("scope-cases.txt missing")
        val entries = lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.associate { l ->
            val (route, rest) = l.split("->", limit = 2).map { it.trim() }.also { require(it.size == 2) { "bad line: $l" } }
            route to rest
        }
        val guarded = endpoints.filter { it.guarded }.map { it.key }.toSet()
        val missing = guarded - entries.keys
        val stale = entries.keys - guarded
        val problems = mutableListOf<String>()
        missing.sorted().forEach { problems += "no scope line for $it (add `$it -> case <TestClass>#<method>`, `exempt: <reason>` or `gap: <lane>`)" }
        stale.sorted().forEach { problems += "scope line for a route that no longer exists: $it" }
        val testSources = repoRoot.resolve("backend").walkTopDown().filter { it.isFile && it.extension == "kt" && "/src/test/" in it.path }.toList()
        for ((route, rest) in entries) {
            when {
                rest.startsWith("case ") -> {
                    val ref = rest.removePrefix("case ").trim()
                    val (cls, method) = ref.split("#").also { if (it.size != 2) problems += "$route: case must be Class#method" }.let { it[0] to it.getOrElse(1) { "" } }
                    val ok = testSources.any { f -> f.nameWithoutExtension == cls && f.readText().let { t -> "fun $method(" in t || "fun `$method`(" in t } }
                    if (!ok) problems += "$route: scope case $ref not found under backend/*/src/test"
                }
                rest.startsWith("exempt:") -> if (rest.removePrefix("exempt:").trim().length < 10) problems += "$route: an exemption needs a reason"
                rest.startsWith("gap:") -> {}
                else -> problems += "$route: expected `case`, `exempt:` or `gap:`, was `$rest`"
            }
        }
        val gaps = entries.count { it.value.startsWith("gap:") }
        assertTrue(gaps <= KNOWN_GAPS_MAX, "the scope gap list grew to $gaps (max $KNOWN_GAPS_MAX): a new route needs a scope case or a reasoned exemption")
        assertEquals(emptyList(), problems)
    }

    private fun contractLines(): List<String> = repoRoot.resolve("contract/openapi.yaml").readLines()

    /** Every (method, path) of the contract, as shapes ("GET /v1/x/{}"). */
    private fun contractOperations(): Set<String> = contractOps().map { it.first }.toSet()

    private fun contractPublicOperations(): Set<String> = contractOps().filter { it.second }.map { it.first }.toSet()

    /** Line-based walk of `paths:`: a path at two spaces, a method at four, `security: []` at six marks it public. */
    private fun contractOps(): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        var inPaths = false
        var path: String? = null
        var op: String? = null
        var public = false
        var inSecurity = false
        fun flush() { op?.let { out += "$it ${normalise(path!!)}" to public }; op = null; public = false }
        for (l in contractLines()) {
            if (!l.startsWith(" ") && l.isNotBlank()) { flush(); inPaths = l.startsWith("paths:"); continue }
            if (!inPaths) continue
            PATH_LINE.matchEntire(l)?.let { flush(); path = it.groupValues[1]; return@let }
            METHOD_LINE.matchEntire(l)?.let { if (path != null) { flush(); op = it.groupValues[1].uppercase() } }
            if (op != null && PUBLIC_LINE.matches(l)) public = true
            // `security: [bearerAuth, {}]`: the bearer token is optional (the device-proof routes, docs/24 s8.3).
            if (op != null && SECURITY_LIST.matches(l)) inSecurity = true
            else if (op != null && inSecurity && OPTIONAL_LINE.matches(l)) public = true
            else if (!l.startsWith("        ")) inSecurity = false
        }
        flush()
        return out
    }

    private fun collect(root: RoutingNode): List<Endpoint> {
        val out = mutableListOf<Endpoint>()
        fun walk(n: RoutingNode, path: String, guarded: Boolean) {
            var p = path
            var g = guarded
            when (val s = n.selector) {
                is AuthenticatedRouteSelector -> g = true
                is HttpMethodRouteSelector -> out += Endpoint(s.method.value, p.ifEmpty { "/" }, g)
                is PathSegmentConstantRouteSelector -> p += "/" + s.value
                is PathSegmentParameterRouteSelector -> p += "/{" + s.name + "}"
                is PathSegmentOptionalParameterRouteSelector -> p += "/{" + s.name + "?}"
                is PathSegmentWildcardRouteSelector -> p += "/*"
                is PathSegmentTailcardRouteSelector -> p += "/{...}"
                else -> {}
            }
            n.children.forEach { walk(it, p, g) }
        }
        walk(root, "", false)
        return out
    }

    /** A request path for a route template: every parameter becomes a well-formed UUID (the guard runs before parsing). */
    private fun concrete(path: String): String = path.replace(Regex("\\{[^}]*}"), "00000000-0000-4000-8000-000000000001").replace("*", "x")

    companion object {
        /** The routes without a scope test when the registry was introduced (2026-10-07); lower it as cases land. */
        const val KNOWN_GAPS_MAX = 53

        private val PATH_LINE = Regex("^  (/\\S*):\\s*$")
        private val METHOD_LINE = Regex("^    (get|put|post|delete|patch|head|options):\\s*$")
        private val PUBLIC_LINE = Regex("^      security:\\s*\\[\\s*]\\s*$")
        private val SECURITY_LIST = Regex("^      security:\\s*$")
        private val OPTIONAL_LINE = Regex("^        - \\{\\s*}\\s*$")

        fun normalise(path: String): String = path.replace(Regex("\\{[^}]*}"), "{}").trimEnd('/').ifEmpty { "/" }
    }
}
