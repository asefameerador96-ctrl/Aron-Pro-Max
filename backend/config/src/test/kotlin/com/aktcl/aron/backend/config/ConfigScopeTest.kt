package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachNode
import com.aktcl.aron.backend.platform.ReachResolver
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** qa scope ledger 2026-10-17: a TSO or DMO reads only the configuration of its own scope nodes, their ancestors and the nodes under them. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigScopeTest {
    private lateinit var env: SeededConfigDb
    private var home = 0L; private var homeTerr = 0L; private var homeDiv = 0L
    private var far = 0L; private var farTerr = 0L; private var farDiv = 0L; private var farRoute = 0L; private var farRoute2 = 0L
    private val key = "cfg.geo.radius_m"

    @BeforeAll fun setUp() {
        env = SeededConfigDb()
        env.fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.division (code, name, wing_id) SELECT 'D-FAR', 'Far Division', wing_id FROM app.division WHERE code = 'D-DHK'")
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-FAR', 'Far Territory', id FROM app.division WHERE code = 'D-FAR'")
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-FAR', 'Far Zone', id FROM app.territory WHERE code = 'T-FAR'")
            h.execute("INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'FAR-R1', 'Far route', id, 'sr', 'daily', 127 FROM app.zone WHERE code = 'Z-FAR'")
            h.execute("INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'FAR-R2', 'Far cover route', id, 'sr', 'daily', 127 FROM app.zone WHERE code = 'Z-FAR'")
        }
        fun id(sql: String) = env.one(sql)!!.toLong()
        home = id("SELECT id FROM app.zone WHERE code = 'Z-MIR'"); homeTerr = id("SELECT territory_id FROM app.zone WHERE code = 'Z-MIR'")
        homeDiv = id("SELECT division_id FROM app.territory WHERE id = $homeTerr")
        far = id("SELECT id FROM app.zone WHERE code = 'Z-FAR'"); farTerr = id("SELECT territory_id FROM app.zone WHERE code = 'Z-FAR'")
        farDiv = id("SELECT division_id FROM app.territory WHERE id = $farTerr"); farRoute = id("SELECT id FROM app.route WHERE code = 'FAR-R1'")
        farRoute2 = id("SELECT id FROM app.route WHERE code = 'FAR-R2'")
        env.fresh.db.jdbi.useHandle<Exception> { h ->
            for ((t, i, v) in listOf(Triple("zone", home, 61), Triple("zone", far, 62), Triple("division", farDiv, 63), Triple("route", farRoute, 64), Triple("territory", homeTerr, 65))) {
                h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT '$key', '$t', $i, '$v'::jsonb, now() - interval '1 day', max(config_version), 'scope test' FROM app.cfg_version")
            }
        }
    }
    @AfterAll fun tearDown() = env.close()

    private fun reachOf(userId: Long, role: Role): Reach = when (role) {
        // Checker (T1 scope): the TSO also holds one cover route in a zone outside its own zones.
        Role.TSO -> Reach(userId, role, java.time.LocalDate.now(), false, setOf(home), setOf(farRoute2), false, listOf(ReachNode("zone", home)))
        Role.DMO, Role.WM -> Reach(userId, role, java.time.LocalDate.now(), false, setOf(home), emptySet(), false, listOf(ReachNode("zone", home)))
        else -> Reach(userId, role, java.time.LocalDate.now(), true, setOf(home, far), emptySet(), false, listOf(ReachNode("national", 0)))
    }

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        val clock = TestClock()
        val cfg = RegistryDefaults(overrides = mapOf("cfg.api.rl.user_per_min" to JsonPrimitive(2000)))
        application {
            installAronPlatform(PlatformContext(clock, cfg, { "00000000-0000-0000-0000-000000000000" }))
            val guard = AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, cfg)
            val reach = ReachResolver { u, r, _, _ -> reachOf(u, r) }
            routing { route("/v1") { configAdminRoutes(ConfigDeps(ConfigService(env.fresh.db, ConfigResolver(env.fresh.db, clock), clock), guard, clock, reach)) } }
        }
        block()
    }

    @Test
    fun aTsoResolvesOnlyItsOwnNodesAndAncestorsAndNoOtherNonNationalRoleReadsConfig() = app {
        val t = TestTokens.web(env.ids.getValue("tso1001"), Role.TSO)
        suspend fun resolve(type: String, id: Long, tok: String = t) = client.get("/v1/admin/config/resolve?key=$key&node_type=$type&node_id=$id") { bearerAuth(tok) }.status
        assertEquals(HttpStatusCode.OK, resolve("zone", home)); assertEquals(HttpStatusCode.OK, resolve("territory", homeTerr))
        assertEquals(HttpStatusCode.OK, resolve("division", homeDiv)); assertEquals(HttpStatusCode.OK, resolve("global", 0))
        for ((type, id) in listOf("zone" to far, "territory" to farTerr, "division" to farDiv, "route" to farRoute, "zone" to 999_999L)) {
            assertEquals(HttpStatusCode.Forbidden, resolve(type, id), "tso $type $id")
        }
        // Q2 (docs/24 s8.5): the TSO's only config read is that resolve (web entry classes); values, changes, versions are ADMIN and SUPERADMIN.
        for (path in listOf("/v1/admin/config/values?key=$key", "/v1/admin/config/values?key=$key&scope_type=zone&scope_id=$home", "/v1/admin/config/changes", "/v1/admin/config/versions", "/v1/admin/config/keys")) {
            assertEquals(HttpStatusCode.Forbidden, client.get(path) { bearerAuth(t) }.status, "tso $path")
        }
        for ((user, role) in listOf("dmo1001" to Role.DMO, "tso1001" to Role.WM)) {
            val o = TestTokens.web(env.ids.getValue(user), role)
            assertEquals(HttpStatusCode.Forbidden, resolve("zone", home, o), "$role resolve")
            assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/config/values?key=$key") { bearerAuth(o) }.status, "$role values")
        }
        // A national reader is unchanged.
        val admin = TestTokens.web(env.ids.getValue("admin1001"), Role.ADMIN)
        assertEquals(HttpStatusCode.OK, client.get("/v1/admin/config/resolve?key=$key&node_type=zone&node_id=$far") { bearerAuth(admin) }.status)
        val all = Json.parseToJsonElement(client.get("/v1/admin/config/values?key=$key") { bearerAuth(admin) }.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertTrue(all.any { it.jsonObject["scope_id"]!!.jsonPrimitive.long == far })
    }

    /** AZ-3: the config lists follow the contract Limit, 1..500 (default 100). */
    @Test
    fun configListsAcceptTheContractLimitRange() = app {
        val admin = TestTokens.web(env.ids.getValue("admin1001"), Role.ADMIN)
        for (path in listOf("/v1/admin/config/values?key=$key&", "/v1/admin/config/changes?", "/v1/admin/config/versions?")) {
            assertEquals(HttpStatusCode.OK, client.get("${path}limit=500") { bearerAuth(admin) }.status, path)
            assertEquals(HttpStatusCode.OK, client.get("${path}limit=201") { bearerAuth(admin) }.status, path)
            assertEquals(HttpStatusCode.BadRequest, client.get("${path}limit=501") { bearerAuth(admin) }.status, path)
            assertEquals(HttpStatusCode.BadRequest, client.get("${path}limit=0") { bearerAuth(admin) }.status, path)
        }
    }

    /** Checker (T1 scope, 8a571fa1): a cover route outside the caller's zones is visible, and resolving it returns the foreign zone's row. Expected to FAIL until fixed. */
    @Test
    fun aCoverRouteDoesNotRevealTheForeignZoneRow() = app {
        val t = TestTokens.web(env.ids.getValue("tso1001"), Role.TSO)
        val r = client.get("/v1/admin/config/resolve?key=$key&node_type=route&node_id=$farRoute2") { bearerAuth(t) }
        assertTrue(r.status == HttpStatusCode.OK || r.status == HttpStatusCode.Forbidden, "status ${r.status}")
        if (r.status == HttpStatusCode.OK) {
            val o = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            val leaked = o["scope_type"]!!.jsonPrimitive.content to o["scope_id"]?.jsonPrimitive?.long
            assertTrue(leaked != ("zone" to far) && leaked != ("division" to farDiv), "cover route resolve leaks the foreign node row: $leaked value=${o["value"]}")
        }
    }
}
