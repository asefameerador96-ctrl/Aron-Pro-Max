package com.aktcl.aron.backend.auth

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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D-ROLES-02 (docs/34 s1, backend-authz wave 4 row 1): SR and AMO have no portal login. The web login refuses them with
 * 403 ERR_AUTH_PORTAL_NOT_ALLOWED only after the password matched (a wrong password stays the common 401) and a web
 * refresh family of theirs never refreshes, so no web token is minted for them; their phone apps are unchanged.
 */
class PortalLevelLoginTest {
    private val made = mutableListOf<AuthFixture>()
    private fun fixture() = AuthFixture().also { made += it }

    @AfterTest fun close() { made.forEach { it.close() } }

    @Test
    fun srAndAmoAreRefusedOnTheWebAfterThePasswordButKeepTheirApps() {
        val f = fixture()
        f.addUser(f.user(1002, "amo3001", Role.AMO))
        testApplication {
            application { f.application(this) }
            for (u in listOf("sr334001", "amo3001")) {
                val web = client.login(u, "correct horse 1", client = "web")
                assertEquals(HttpStatusCode.Forbidden, web.status, web.bodyAsText())
                assertEquals("ERR_AUTH_PORTAL_NOT_ALLOWED", json(web.bodyAsText()).code)
                // A wrong password never reveals that the username is a field rep.
                assertEquals("ERR_AUTH_INVALID_CREDENTIALS", json(client.login(u, "wrong password", client = "web").bodyAsText()).code)
            }
            val app = client.login("sr334001", "correct horse 1", f.srDevice)
            assertEquals(HttpStatusCode.OK, app.status, app.bodyAsText())
            val tso = client.login("tso5012", "correct horse 1", client = "web")
            assertEquals(HttpStatusCode.OK, tso.status, tso.bodyAsText())
        }
    }

    @Test
    fun nationalAndBrandUsersLogInOnTheWebAndTheRetiredTopStillWorksUntilMigrated() {
        val f = fixture()
        f.addUser(f.user(3001, "national1", Role.NATIONAL))
        f.addUser(f.user(3002, "brand1", Role.BRAND))
        f.addUser(f.user(3003, "top1", Role.TOP))
        testApplication {
            application { f.application(this) }
            for (u in listOf("national1", "brand1", "top1")) {
                val r = client.login(u, "correct horse 1", client = "web")
                assertEquals(HttpStatusCode.OK, r.status, "$u: ${r.bodyAsText()}")
                val token = json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content
                assertEquals(HttpStatusCode.OK, client.get("/v1/me") { bearerAuth(token) }.status, u)
            }
        }
    }

    @Test
    fun aWebFamilyOfAnSrIsRefused() {
        val f = fixture()
        val family = f.refresh.issue(1001, null, Grant.FULL, "web")
        testApplication {
            application { f.application(this) }
            val r = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody("""{"refresh_token":"${family.token}","grant":"full"}""") }
            assertEquals(HttpStatusCode.Forbidden, r.status, r.bodyAsText())
            assertEquals("ERR_AUTH_PORTAL_NOT_ALLOWED", json(r.bodyAsText()).code)
        }
    }

    /** Opus checker B1: a role change must not ride an old phone grant (TSO to BRAND would keep a tso app token with national reach). */
    @Test
    fun afterARoleChangeThePhoneFullGrantNoLongerRefreshesAndOldPhoneTokensAreRefused() {
        val f = fixture()
        val full = f.refresh.issue(1001, 501, Grant.FULL, "sr")
        val upload = f.refresh.issue(1001, 501, Grant.UPLOAD, "sr")
        f.addUser(f.user(1001, "sr334001", Role.BRAND).copy(scopeVersion = 8))
        val brand = f.users.findById(1001)!!
        val phoneToken = f.issuer.access(TokenSubject(brand, 501, f.srDevice, "sr")).token
        testApplication {
            application { f.application(this) }
            suspend fun refresh(token: String, grant: String) = client.post("/v1/auth/refresh") {
                contentType(ContentType.Application.Json); header("X-Device-Id", f.srDevice)
                setBody("""{"refresh_token":"$token","grant":"$grant"}""")
            }
            val r = refresh(full.token, "full")
            assertEquals(HttpStatusCode.Forbidden, r.status, r.bodyAsText())
            assertEquals("ERR_FORBIDDEN", json(r.bodyAsText()).code)
            // Captured rows still reach the server: the upload grant survives, as it survives a disable.
            assertEquals(HttpStatusCode.OK, refresh(upload.token, "upload").status)
            val me = client.get("/v1/me") { bearerAuth(phoneToken); header("X-Device-Id", f.srDevice) }
            assertEquals(HttpStatusCode.Forbidden, me.status, me.bodyAsText())
            // A web login of the new role works.
            assertEquals(HttpStatusCode.OK, client.login("sr334001", "correct horse 1", client = "web").status)
        }
    }
}
