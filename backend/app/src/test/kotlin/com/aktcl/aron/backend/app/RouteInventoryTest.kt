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
import io.ktor.server.routing.RootRouteSelector
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
    /** [guard] is the settings of the nearest guard (the innermost route-scoped install wins in Ktor), null when public. */
    data class Endpoint(val method: String, val path: String, val guard: String?) {
        val guarded: Boolean get() = guard != null
        val key: String get() = "$method $path"
        val shape: String get() = "$method ${normalise(path)}"
    }

    private lateinit var wiring: Wiring
    private lateinit var endpoints: List<Endpoint>
    private val oddNodes = mutableListOf<String>()
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
        File("build/route-inventory.txt").writeText(endpoints.joinToString("\n") { "${it.key}${when (it.guard) { null -> "  [public]"; AuthenticatedRouteSelector.DEFAULT -> ""; else -> "  [guard ${it.guard}]" }}" } + "\n")
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
        val pairs = lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { l ->
            val (route, rest) = l.split("->", limit = 2).map { it.trim() }.also { require(it.size == 2) { "bad line: $l" } }
            route to rest
        }
        assertEquals(emptyList(), pairs.groupBy { it.first }.filter { it.value.size > 1 }.keys.toList(), "duplicate scope lines")
        val entries = pairs.toMap()
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
                    val file = testSources.firstOrNull { it.nameWithoutExtension == cls && methodBody(it.readText(), method) != null }
                    // The cited test must at least call this route: its literal path up to the first parameter, in the
                    // method or in a helper of the same test class (several tests post through one helper).
                    val literal = route.substringAfter(' ').removePrefix("/v1").substringBefore("/{").substringBefore("{")
                    when {
                        file == null -> problems += "$route: scope case $ref not found under backend/*/src/test"
                        literal !in file.readText() -> problems += "$route: scope case $ref never names $literal"
                    }
                }
                rest.startsWith("exempt:") -> if (rest.removePrefix("exempt:").trim().length < 10) problems += "$route: an exemption needs a reason"
                rest.startsWith("gap:") -> {}
                else -> problems += "$route: expected `case`, `exempt:` or `gap:`, was `$rest`"
            }
        }
        // The gap list may only shrink: every gap must be in the baseline frozen when the registry was introduced.
        val baseline = javaClass.getResource("/scope-gaps-baseline.txt")!!.readText().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        val newGaps = entries.filter { it.value.startsWith("gap:") }.keys - baseline
        newGaps.sorted().forEach { problems += "new scope gap $it: a new route needs a scope case or a reasoned exemption" }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun theWalkerUnderstandsEveryNode() {
        assertEquals(emptyList(), oddNodes, "routes the inventory cannot see; teach the walker or use get/post/... under a path")
    }

    @Test
    fun theOnlyWidenedGuardsAreThePinnedOnes() {
        val widened = endpoints.filter { it.guarded && it.guard != AuthenticatedRouteSelector.DEFAULT }.associate { it.key to it.guard }
        assertEquals(WIDENED_GUARDS, widened, "a route whose guard accepts other token kinds or skips the scope check must be pinned here deliberately")
    }

    @Test
    fun thePublicSetIsPinnedAndEqualsTheContract() {
        assertEquals(PUBLIC_OPERATIONS, contractPublicOperations(), "a contract edit made an operation public (or private): review it, then update PUBLIC_OPERATIONS")
        assertEquals(contractLines().count { OPERATION_ID.containsMatchIn(it) }, contractOps().size, "the contract parser missed operations")
    }

    @Test
    fun theDeviceProofRoutesRefuseACallWithoutAProof() = testApplication {
        application { aronApi(wiring) }
        val wrong = mutableListOf<String>()
        for (op in DEVICE_PROOF_OPERATIONS) {
            val (m, p) = op.split(' ', limit = 2)
            val r = client.request(p) { method = HttpMethod.parse(m) }
            if (r.status.value != 401) wrong += "$op -> ${r.status.value} ${r.bodyAsText().take(120)}"
        }
        assertEquals(emptyList(), wrong)
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
        fun walk(n: RoutingNode, path: String, guarded: String?) {
            var p = path
            var g = guarded
            when (val s = n.selector) {
                is AuthenticatedRouteSelector -> g = s.guard
                is HttpMethodRouteSelector -> out += Endpoint(s.method.value, p.ifEmpty { "/" }, g)
                is PathSegmentConstantRouteSelector -> p += "/" + s.value
                is PathSegmentParameterRouteSelector -> p += "/{" + s.name + "}"
                is PathSegmentOptionalParameterRouteSelector -> p += "/{" + s.name + "?}"
                is PathSegmentWildcardRouteSelector -> p += "/*"
                is PathSegmentTailcardRouteSelector -> p += "/{...}"
                is RootRouteSelector -> {}
                // A selector the walker does not understand (header, host, parameter, handle {} ...) could hide a route.
                else -> oddNodes += "unknown selector ${s.javaClass.name} at $n"
            }
            if (n.children.isEmpty() && n.selector !is HttpMethodRouteSelector) oddNodes += "leaf without a method: $n"
            n.children.forEach { walk(it, p, g) }
        }
        walk(root, "", null)
        return out
    }

    /** The text of `fun name(` up to the next member at the same indentation, or null. */
    private fun methodBody(src: String, name: String): String? {
        val start = Regex("\\bfun `?" + Regex.escape(name) + "`?\\(").find(src)?.range?.first ?: return null
        val next = Regex("\\n    (?:@Test|fun |private fun |val |private val |@)").find(src, start + 5)?.range?.first ?: src.length
        return src.substring(start, next)
    }

    /** A request path for a route template: every parameter becomes a well-formed UUID (the guard runs before parsing). */
    private fun concrete(path: String): String = path.replace(Regex("\\{[^}]*}"), "00000000-0000-4000-8000-000000000001").replace("*", "x")

    companion object {
        /** Optional-bearer operations that authenticate with the phone's own X-Device-Proof instead (docs/24 s8.3). */
        val DEVICE_PROOF_OPERATIONS = listOf("POST /v1/devices/nonce", "GET /v1/devices/me/policy", "POST /v1/devices/me/status")

        /** Contract operations a caller may use without a bearer token (2026-10-07). Change only with a security review. */
        val PUBLIC_OPERATIONS = setOf(
            "GET /v1/health", "HEAD /v1/health", "GET /v1/health/ready", "GET /v1/config/public", "POST /v1/auth/login",
            "POST /v1/auth/refresh", "POST /v1/auth/mfa/verify", "GET /v1/auth/jwks", "POST /v1/devices/enrol",
        ) + DEVICE_PROOF_OPERATIONS


        /** Guards that accept more than an API token with a current scope version, each on purpose (AuthModule, SyncApi). */
        val WIDENED_GUARDS: Map<String, String> = mapOf(
            "POST /v1/auth/change-password" to "aud=aron-api,aron-pwchange;grace=0;sv=true;pwchange=true",
            "POST /v1/auth/bind-device" to "aud=aron-bind;grace=0;sv=true;pwchange=true",
            "POST /v1/auth/logout" to "aud=aron-api,aron-upload;grace=0;sv=false;pwchange=true",
            // The photo upload grant (contract createMediaUploadUrls: "the upload grant may call this operation"), like the batch.
            "POST /v1/media/sas" to "aud=aron-api,aron-upload;grace=60;sv=false;pwchange=false",
            "POST /v1/sync/batch" to "aud=aron-api,aron-upload;grace=60;sv=false;pwchange=false",
        )

        private val OPERATION_ID = Regex("^      operationId:")

        private val PATH_LINE = Regex("^  (/\\S*):\\s*$")
        private val METHOD_LINE = Regex("^    (get|put|post|delete|patch|head|options):\\s*$")
        private val PUBLIC_LINE = Regex("^      security:\\s*\\[\\s*]\\s*$")
        private val SECURITY_LIST = Regex("^      security:\\s*$")
        private val OPTIONAL_LINE = Regex("^        - \\{\\s*}\\s*$")

        fun normalise(path: String): String = path.replace(Regex("\\{[^}]*}"), "{}").trimEnd('/').ifEmpty { "/" }
    }
}
