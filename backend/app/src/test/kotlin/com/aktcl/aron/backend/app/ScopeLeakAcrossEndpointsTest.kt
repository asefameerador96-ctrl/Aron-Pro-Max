package com.aktcl.aron.backend.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
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
 * N-014 (scope-leak harness over every endpoint; N-061 widens it): the seed is loaded twice ([ApiWorld] mirror), so a
 * whole second division (D-LKX, every code, name and username marked `lkx`) exists that no scoped seed user may reach.
 * Every operation the production wiring serves is called by the scoped callers (sr1001 on its phone, amo1001, tso1001,
 * dmo1001 on the web) in three ways: with ids of their own world, with path ids of the other division, and with each
 * optional selector query parameter (zone_id, route_id, ...) set to the other division; write operations additionally
 * carry the other division's ids in every `*_id` field of the body. A leak is:
 * - any answer that contains the marker (an outlet, route, user, zone or cluster of the other division), or
 * - a 2xx answer to a write that names the other division's resources.
 * The national admin is the positive control: it must see the other division (the world and the detector both work).
 * Known leaks would go to `scope-leak-baseline.txt` (may only shrink; empty when introduced). The seeded-leak test
 * proves the detector turns red. Report: build/scope-leak-endpoints.txt.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScopeLeakAcrossEndpointsTest {
    private lateinit var world: ApiWorld
    private lateinit var contract: ContractModel
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")

    private val scoped = listOf(
        ApiWorld.Caller("sr1001", "app_sr", ApiWorld.SR_PHONE),
        ApiWorld.Caller("amo1001", "web", null),
        ApiWorld.Caller("tso1001", "web", null),
        ApiWorld.Caller("dmo1001", "web", null),
    )
    private val national = ApiWorld.Caller("admin1001", "web", null)

    data class Leak(val method: String, val shape: String, val caller: String, val variant: String, val detail: String) {
        val key: String get() = "$method $shape $caller $variant"
    }

    private val leaks = mutableListOf<Leak>()
    private val nationalAnswers = mutableMapOf<String, String>()
    private var calls = 0
    private var otherIdCalls = 0
    private var refusedOtherIdCalls = 0

    @BeforeAll
    fun setUp() {
        contract = ContractModel.load(repoRoot.resolve("contract/openapi.yaml"))
        world = ApiWorld(repoRoot, mirrors = listOf(ApiWorld.OTHER))
        check(world.otherIds["outlets"] != null && world.otherIds["zones"] != world.ids["zones"]) { "the mirrored division is missing: ${world.otherIds}" }
        run()
    }

    @AfterAll
    fun tearDown() { world.close() }

    private fun run() = testApplication {
        application { aronApi(world.wiring) }
        startApplication()
        val ops = ApiWorld.routes(application.plugin(RoutingRoot)).mapNotNull { (m, p) -> contract.operation(m, p) }.distinctBy { it.key }
            .filter { !it.public && it.key !in SESSION_ENDING }
            .sortedWith(compareBy({ it.method != "GET" }, { it.key }))
        val tokens = HashMap<ApiWorld.Caller, String?>()
        suspend fun token(c: ApiWorld.Caller): String? = tokens.getOrPut(c) { world.login(client, c) }
        for (c in scoped + national) check(token(c) != null) { "no token for ${c.user}" }

        for (op in ops) {
            val shape = ContractModel.shape(op.path)
            // A POST that only reads (a report query, a digest) answers 2xx with the caller's own rows: the marker rule applies.
            val read = op.method == "GET" || READ_BY_POST.containsMatchIn(op.operationId)
            // The positive control: what a national caller gets back.
            if (read) {
                val r = world.call(client, contract, op, national, token(national))
                nationalAnswers[op.key] = r.bodyAsText()
            }
            for (c in scoped) {
                val t = token(c) ?: continue
                suspend fun probe(variant: String, pathIds: Map<String, Long>, query: String = "", body: ((JsonNode) -> Unit)? = null) {
                    var retargeted = body == null
                    val edit = body?.let { b -> { n: JsonNode -> b(n); retargeted = retarget(n) } }
                    var r = world.call(client, contract, op, c, t, pathIds, query, bodyEdit = edit)
                    if (r.status.value == 401) { tokens.remove(c); r = world.call(client, contract, op, c, token(c), pathIds, query, bodyEdit = edit) }
                    if (!retargeted) return // the body names no resource: nothing to learn beyond the own call
                    calls++
                    val text = r.bodyAsText()
                    marked(text)?.let { leaks += Leak(op.method, shape, c.user, variant, "${r.status.value} …$it…") }
                    if (variant != "own") {
                        otherIdCalls++
                        if (r.status.value in 400..499) refusedOtherIdCalls++
                        if (!read && r.status.value in 200..299) leaks += Leak(op.method, shape, c.user, variant, "${r.status.value} a write naming the other division succeeded: ${text.take(160)}")
                    }
                }
                probe("own", world.ids)
                if (op.path.contains('{') && op.parameters.any { it.where == "path" && (it.name == "id" || it.name == "zone_id") }) probe("other-path", world.otherIds)
                for (p in op.parameters.filter { it.where == "query" && !it.required && it.name in SELECTORS }) {
                    world.otherIds[SELECTORS.getValue(p.name)]?.let { probe("other-${p.name}", world.ids, "${p.name}=$it") }
                }
                if (op.requestSchema != null) probe("other-body", world.ids, body = {})
            }
        }
        File("build").mkdirs()
        File("build/scope-leak-endpoints.txt").writeText(
            "operations=${ops.size} calls=$calls other_id_calls=$otherIdCalls refused=$refusedOtherIdCalls national_marked=${nationalAnswers.count { marked(it.value) != null }}\n" +
                leaks.joinToString("\n") { "${it.key}  ${it.detail}" } + "\n",
        )
    }

    /** Points every resource `*_id` field of a generated body at the other division; true when one was changed. */
    private fun retarget(n: JsonNode): Boolean {
        var changed = false
        if (n is ObjectNode) n.fieldNames().asSequence().toList().forEach { f ->
            val v = n.get(f)
            val key = BODY_IDS[f] ?: f.takeIf { it.endsWith("user_id") }?.let { "users" }
            val other = key?.let { world.otherIds[it] }
            if (other != null && v.isIntegralNumber) { n.put(f, other); changed = true } else if (retarget(v)) changed = true
        } else if (n.isArray) n.forEach { if (retarget(it)) changed = true }
        return changed
    }

    /** The text around the first marker of the other division, or null. */
    private fun marked(text: String): String? {
        val i = text.indexOf(ApiWorld.MARK, ignoreCase = true)
        return if (i < 0) null else text.substring(maxOf(0, i - 40), minOf(text.length, i + 40))
    }

    @Test
    fun noScopedCallerReachesTheOtherDivisionThroughAnyEndpoint() {
        val baseline = javaClass.getResource("/scope-leak-baseline.txt")!!.readText().lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()
        val now = leaks.associateBy { it.key }
        val fresh = now.keys - baseline
        val stale = baseline - now.keys
        assertTrue(fresh.isEmpty() && stale.isEmpty(), buildString {
            if (fresh.isNotEmpty()) append("cross-scope reads or writes:\n" + fresh.sorted().joinToString("\n") { "  $it  ${now[it]!!.detail}" } + "\n")
            if (stale.isNotEmpty()) append("fixed leaks still in scope-leak-baseline.txt (remove them):\n" + stale.sorted().joinToString("\n") { "  $it" })
        })
    }

    @Test
    fun theHarnessReallyProbedTheOtherDivision() {
        assertTrue(calls > 400, "calls $calls")
        assertTrue(otherIdCalls > 100, "other-division calls $otherIdCalls")
        assertTrue(refusedOtherIdCalls > 50, "other-division calls refused $refusedOtherIdCalls")
        // The positive control: the national admin sees the other division through the list and detail reads.
        val seen = nationalAnswers.filterValues { marked(it) != null }.keys
        assertTrue(seen.size >= 5 && "GET /v1/admin/outlets" in seen, "the national admin saw the other division only in $seen")
    }

    @Test
    fun aSeededCrossScopeReadIsCaught() {
        // The national answers re-labelled as a TSO's: every one that shows the other division must be reported.
        val seeded = nationalAnswers.filterValues { marked(it) != null }
        assertTrue(seeded.isNotEmpty())
        val caught = seeded.mapNotNull { (k, text) -> marked(text)?.let { k } }
        assertEquals(seeded.keys, caught.toSet())
    }

    companion object {
        val SESSION_ENDING = setOf("POST /v1/auth/logout", "POST /v1/auth/change-password")
        /** Query selectors a client may send (the server must refuse or ignore a value outside the caller's reach). */
        val SELECTORS = mapOf(
            "zone_id" to "zones", "route_id" to "routes", "outlet_id" to "outlets", "cluster_id" to "clusters", "territory_id" to "territories",
            "division_id" to "divisions", "user_id" to "users", "sr_user_id" to "users", "node_id" to "zones",
        )
        val READ_BY_POST = Regex("^(query|search|list|get|preview|check|validate|compute|digest)", RegexOption.IGNORE_CASE)
        val BODY_IDS = mapOf("outlet_id" to "outlets", "route_id" to "routes", "zone_id" to "zones", "cluster_id" to "clusters", "territory_id" to "territories", "division_id" to "divisions")
    }
}
