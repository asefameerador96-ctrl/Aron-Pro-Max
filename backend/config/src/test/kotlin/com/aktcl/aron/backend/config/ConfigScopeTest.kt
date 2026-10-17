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
    private var far = 0L; private var farTerr = 0L; private var farDiv = 0L; private var farRoute = 0L
    private val key = "cfg.geo.radius_m"

    @BeforeAll fun setUp() {
        env = SeededConfigDb()
        env.fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.division (code, name, wing_id) SELECT 'D-FAR', 'Far Division', wing_id FROM app.division WHERE code = 'D-DHK'")
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-FAR', 'Far Territory', id FROM app.division WHERE code = 'D-FAR'")
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-FAR', 'Far Zone', id FROM app.territory WHERE code = 'T-FAR'")
            h.execute("INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'FAR-R1', 'Far route', id, 'sr', 'daily', 127 FROM app.zone WHERE code = 'Z-FAR'")
        }
        fun id(sql: String) = env.one(sql)!!.toLong()
        home = id("SELECT id FROM app.zone WHERE code = 'Z-MIR'"); homeTerr = id("SELECT territory_id FROM app.zone WHERE code = 'Z-MIR'")
        homeDiv = id("SELECT division_id FROM app.territory WHERE id = $homeTerr")
        far = id("SELECT id FROM app.zone WHERE code = 'Z-FAR'"); farTerr = id("SELECT territory_id FROM app.zone WHERE code = 'Z-FAR'")
        farDiv = id("SELECT division_id FROM app.territory WHERE id = $farTerr"); farRoute = id("SELECT id FROM app.route WHERE code = 'FAR-R1'")
        env.fresh.db.jdbi.useHandle<Exception> { h ->
            for ((t, i, v) in listOf(Triple("zone", home, 61), Triple("zone", far, 62), Triple("division", farDiv, 63), Triple("route", farRoute, 64), Triple("territory", homeTerr, 65))) {
                h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT '$key', '$t', $i, '$v'::jsonb, now() - interval '1 day', max(config_version), 'scope test' FROM app.cfg_version")
            }
        }
    }
    @AfterAll fun tearDown() = env.close()

    private fun reachOf(userId: Long, role: Role): Reach = when (role) {
        Role.TSO, Role.DMO -> Reach(userId, role, java.time.LocalDate.now(), false, setOf(home), emptySet(), false, listOf(ReachNode("zone", home)))
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
    fun aTsoAndADmoReadOnlyTheirOwnNodesAndAncestors() = app {
        for ((user, role) in listOf("tso1001" to Role.TSO, "dmo1001" to Role.DMO)) {
            val t = TestTokens.web(env.ids.getValue(user), role)
            suspend fun resolve(type: String, id: Long) = client.get("/v1/admin/config/resolve?key=$key&node_type=$type&node_id=$id") { bearerAuth(t) }.status
            assertEquals(HttpStatusCode.OK, resolve("zone", home), user); assertEquals(HttpStatusCode.OK, resolve("territory", homeTerr), user)
            assertEquals(HttpStatusCode.OK, resolve("division", homeDiv), user); assertEquals(HttpStatusCode.OK, resolve("global", 0), user)
            for ((type, id) in listOf("zone" to far, "territory" to farTerr, "division" to farDiv, "route" to farRoute, "zone" to 999_999L)) {
                assertEquals(HttpStatusCode.Forbidden, resolve(type, id), "$user $type $id")
            }
            suspend fun values(q: String) = client.get("/v1/admin/config/values?key=$key$q") { bearerAuth(t) }
            assertEquals(HttpStatusCode.Forbidden, values("&scope_type=zone&scope_id=$far").status, user)
            assertEquals(HttpStatusCode.OK, values("&scope_type=zone&scope_id=$home").status, user)
            val items = Json.parseToJsonElement(values("").bodyAsText()).jsonObject["items"]!!.jsonArray.map { it.jsonObject }
            val seen = items.map { it["scope_type"]!!.jsonPrimitive.content to it["scope_id"]!!.jsonPrimitive.long }.toSet()
            assertTrue(("zone" to home) in seen && ("territory" to homeTerr) in seen, "$user own rows: $seen")
            assertTrue(seen.none { it == ("zone" to far) || it == ("division" to farDiv) || it == ("route" to farRoute) }, "$user sees another division: $seen")
        }
        // A national reader is unchanged.
        val admin = TestTokens.web(env.ids.getValue("admin1001"), Role.ADMIN)
        assertEquals(HttpStatusCode.OK, client.get("/v1/admin/config/resolve?key=$key&node_type=zone&node_id=$far") { bearerAuth(admin) }.status)
        val all = Json.parseToJsonElement(client.get("/v1/admin/config/values?key=$key") { bearerAuth(admin) }.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertTrue(all.any { it.jsonObject["scope_id"]!!.jsonPrimitive.long == far })
    }
}
