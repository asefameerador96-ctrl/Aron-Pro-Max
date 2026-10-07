package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigApiTest {
    private lateinit var env: SeededConfigDb

    @BeforeAll fun setUp() { env = SeededConfigDb() }
    @AfterAll fun tearDown() = env.close()

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        val clock = TestClock()
        val cfg = RegistryDefaults(overrides = mapOf("cfg.api.rl.user_per_min" to JsonPrimitive(2000)))
        application {
            installAronPlatform(PlatformContext(clock, cfg, { "00000000-0000-0000-0000-000000000000" }))
            val guard = AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, cfg)
            routing { route("/v1") { configAdminRoutes(ConfigDeps(ConfigService(env.fresh.db, ConfigResolver(env.fresh.db, clock), clock), guard, clock)) } }
        }
        block()
    }

    @Test
    fun registryValuesChangesAndResolveOverHttpWithContractShapes() = app {
        val admin = TestTokens.web(env.ids.getValue("admin1001"), Role.ADMIN)
        val kr = client.get("/v1/admin/config/keys?area=geo") { bearerAuth(admin) }.bodyAsText()
        val keys = Json.parseToJsonElement(kr).jsonObject["items"]!!.jsonArray
        assertEquals(true, keys.any { it.jsonObject["key"]!!.jsonPrimitive.content == "cfg.geo.radius_m" })
        val outlet = env.one("SELECT id FROM app.outlet ORDER BY id LIMIT 1")!!
        val r = client.post("/v1/admin/config/changes") {
            bearerAuth(admin); contentType(ContentType.Application.Json)
            setBody("""{"reason":"Dense market, widen the radius","changes":[{"key":"cfg.geo.radius_m","scope_type":"outlet","scope_id":$outlet,"value":135}]}""")
        }
        assertEquals(HttpStatusCode.Created, r.status)
        val changeText = r.bodyAsText()
        val change = Json.parseToJsonElement(changeText).jsonObject
        assertEquals("applied", change["status"]!!.jsonPrimitive.content)
        val res = Json.parseToJsonElement(client.get("/v1/admin/config/resolve?key=cfg.geo.radius_m&node_type=outlet&node_id=$outlet") { bearerAuth(admin) }.bodyAsText()).jsonObject
        assertEquals("outlet", res["scope_type"]!!.jsonPrimitive.content); assertEquals("135", res["value"]!!.jsonPrimitive.content)
        // An unknown field in the body is a 400, a bound breach is ERR_CFG_OUT_OF_BOUNDS, a wrong role is 403.
        val extra = client.post("/v1/admin/config/changes") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody("""{"reason":"Dense market, widen","changes":[],"scope":[1]}""") }
        assertEquals(HttpStatusCode.BadRequest, extra.status)
        val oob = client.post("/v1/admin/config/changes") {
            bearerAuth(admin); contentType(ContentType.Application.Json)
            setBody("""{"reason":"Dense market, widen the radius","changes":[{"key":"cfg.geo.radius_m","scope_type":"outlet","scope_id":$outlet,"value":9000}]}""")
        }
        assertEquals("ERR_CFG_OUT_OF_BOUNDS", Json.parseToJsonElement(oob.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/config/keys") { bearerAuth(TestTokens.web(env.ids.getValue("sr1001"), Role.SR)) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/config/keys").status)
        val versions = Json.parseToJsonElement(client.get("/v1/admin/config/versions?limit=1") { bearerAuth(admin) }.bodyAsText()).jsonObject
        assertEquals(1, versions["items"]!!.jsonArray.size)
    }
}
