package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-SYS-005: the scope-leak harness through GET /v1/admin/outlets and, for every role including SR and AMO, GET
 * /v1/outlets (F-API-010, contract listOutletsInReach) themselves (not only the reach functions): 200
 * randomised queries with random users, business dates, narrowing selectors, filters (status, q, cluster_id,
 * updated_since), injected client scope ids and page sizes, every page followed, each answer checked against the
 * independent oracle (rows and refusals).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScopeLeakHttpTest {
    private lateinit var fresh: FreshDb
    private val world = ScopeWorld(77)
    private val readers = setOf(Role.TSO, Role.DMO, Role.WM, Role.TOP, Role.ANALYST, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)

    @BeforeAll
    fun setUp() { fresh = FreshDb.create(); WorldLoader.load(fresh, world) }

    @AfterAll
    fun tearDown() = fresh.close()

    @Test
    fun twoHundredRandomisedHttpQueriesFindZeroCrossScopeRows() = harness("/v1/admin/outlets", readers)

    @Test
    fun theFieldReadForEveryRoleFindsZeroCrossScopeRows() = harness("/v1/outlets", Role.entries.toSet())

    private fun harness(path: String, roles: Set<Role>) {
        var today = LocalDate.parse("2026-10-05")
        val clock = AronClock { today.atTime(6, 0).toInstant(ZoneOffset.UTC) } // 12:00 Dhaka on `today`
        val geo = GeoRepository(fresh.db, clock)
        val deps = OutletsDeps(fresh.db, geo, SqlReachResolver(fresh.db, geo, clock), AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, RegistryDefaults(overrides = mapOf("cfg.api.rl.user_per_min" to kotlinx.serialization.json.JsonPrimitive(2000)))), clock)
        val users = world.users.filter { it.role in roles }
        val problems = mutableListOf<String>()
        var refused = 0
        testApplication {
            application {
                installAronPlatform(PlatformContext(config = RegistryDefaults(), generation = { "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10" }))
                routing { route("/v1") { outletRoutes(deps) } }
            }
            repeat(200) { q ->
                val u = users.random(world.rnd)
                today = world.randomDate()
                val sel = world.randomSelector()
                val cluster = if (world.rnd.nextInt(5) == 0) world.zoneTerritory.keys.random(world.rnd) else null // cluster id == its zone id
                val search = if (world.rnd.nextInt(5) == 0) "outlet 10" else null
                val since = world.rnd.nextInt(5) == 0
                val limit = world.rnd.nextInt(25, 61) // small pages still page several times; the per-user limit is raised for the run
                val base = buildString {
                    append("$path?limit=$limit&scope_ids=1,2,3&zone_ids=${world.zoneTerritory.keys.joinToString(",")}")
                    sel.zoneId?.let { append("&zone_id=$it") }; sel.routeId?.let { append("&route_id=$it") }
                    // wing/division/territory are not query parameters of this operation: they are ignored like any unknown one
                    cluster?.let { append("&cluster_id=$it") }
                    search?.let { append("&q=" + URLEncoder.encode(it, "UTF-8")) }
                    if (since) append("&updated_since=2000-01-01T00:00:00Z")
                    if (world.rnd.nextBoolean()) append("&status=active")
                }
                val effectiveSel = GeoSelector(zoneId = sel.zoneId, routeId = sel.routeId)
                val mustRefuse = world.shouldRefuse(u, today, effectiveSel) || (cluster != null && !world.zoneVisible(u, cluster, today))
                val expected = world.outlets.filter {
                    world.visible(u, it, today) && world.inSelector(it, effectiveSel) && (cluster == null || it.zoneId == cluster) &&
                        (search == null || "Outlet ${it.id}".contains(search, ignoreCase = true))
                }.map { it.id }.toSet()
                val token = TestTokens.web(u.id, u.role)
                val got = mutableSetOf<Long>()
                var cursor: String? = null
                var pages = 0
                do {
                    val r = client.get(base + (cursor?.let { "&cursor=$it" } ?: "")) { bearerAuth(token) }
                    if (r.status == HttpStatusCode.Forbidden) {
                        refused++
                        if (!mustRefuse) problems += "q$q ${u.role} $today $sel cluster=$cluster: refused but in reach"
                        if (!r.bodyAsText().contains("ERR_OUT_OF_SCOPE")) problems += "q$q: 403 without ERR_OUT_OF_SCOPE"
                        break
                    }
                    if (r.status != HttpStatusCode.OK) { problems += "q$q: status ${r.status} ${r.bodyAsText()}"; break }
                    if (mustRefuse) problems += "q$q ${u.role} $today $sel cluster=$cluster: rows where 403 is due"
                    val b = Json.parseToJsonElement(r.bodyAsText()).jsonObject
                    val ids = b["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.long }
                    assertTrue(ids.size <= limit)
                    ids.forEach { if (!got.add(it)) problems += "q$q: duplicate $it across pages" }
                    cursor = b["next_cursor"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
                    pages++
                } while (cursor != null && pages < 500)
                if (!mustRefuse) {
                    (got - expected).takeIf { it.isNotEmpty() }?.let { problems += "q$q ${u.role} $today $sel: LEAKED ${it.take(5)}" }
                    (expected - got).takeIf { it.isNotEmpty() }?.let { problems += "q$q ${u.role} $today $sel: missing ${it.take(5)}" }
                }
            }
        }
        assertEquals(emptyList(), problems)
        assertTrue(refused > 0, "the harness must exercise out-of-scope selectors")
    }
}
