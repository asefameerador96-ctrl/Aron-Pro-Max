package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals

/** The release write routes check the role before the body or If-Match is read: a refused role gets 403, never 400 (AZ-7). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReleaseRoutesTest {
    private lateinit var env: SeededConfigDb

    @BeforeAll fun setUp() { env = SeededConfigDb() }
    @AfterAll fun tearDown() = env.close()

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        val clock = TestClock()
        val cfg = RegistryDefaults(overrides = mapOf("cfg.api.rl.user_per_min" to JsonPrimitive(2000)))
        application {
            installAronPlatform(PlatformContext(clock, cfg, { "00000000-0000-0000-0000-000000000000" }))
            val guard = AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, cfg)
            routing { route("/v1") { releaseAdminRoutes(ReleaseAdminDeps(ReleaseAdmin(env.fresh.db, ConfigResolver(env.fresh.db, clock), clock), guard)) } }
        }
        block()
    }

    @Test
    fun aRefusedRoleWithAnInvalidBodyOrNoIfMatchGets403AndAWriterGets400() = app {
        suspend fun post(token: String) = client.post("/v1/admin/releases") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("{}") }.status
        suspend fun patch(token: String) = client.patch("/v1/admin/releases/1") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("{}") }.status
        for ((user, role) in listOf("sr1001" to Role.SR, "tso1001" to Role.TSO, "support1001" to Role.SUPPORT)) {
            val token = TestTokens.web(env.ids.getValue(user), role)
            assertEquals(HttpStatusCode.Forbidden, post(token), "POST as $role")
            assertEquals(HttpStatusCode.Forbidden, patch(token), "PATCH as $role")
        }
        val admin = TestTokens.web(env.ids.getValue("admin1001"), Role.ADMIN)
        assertEquals(HttpStatusCode.BadRequest, post(admin), "a writer with an invalid body")
        assertEquals(HttpStatusCode.BadRequest, patch(admin), "a writer without If-Match")
    }
}
