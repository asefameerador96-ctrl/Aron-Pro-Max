package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.contract.Role
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Checker tests (F-SYS-001, F-API-001, F-SYS-002, F-API-002). Each one fails against the builder's code. */
class AuthReviewTest {
    private val made = mutableListOf<AuthFixture>()
    private fun fixture(overrides: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()) = AuthFixture(overrides).also { made += it }

    @AfterTest
    fun close() { made.forEach { it.close() } }

    /**
     * The lockout key's IP class is read from X-Forwarded-For, which the client writes itself (no trusted-proxy check),
     * so an attacker escapes the (username, device) lock by changing one header.
     */
    @Test
    fun lockoutCannotBeEscapedBySpoofingXForwardedFor() {
        val f = fixture(mapOf("cfg.auth.lockout_attempts" to JsonPrimitive(5)))
        testApplication {
            application { f.application(this) }
            suspend fun attempt(pw: String, xff: String) = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                header("X-Forwarded-For", xff)
                setBody("""{"username":"sr334001","password":"$pw","client":"app_sr","device_uuid":"${f.srDevice}"}""")
            }
            repeat(5) { assertEquals(HttpStatusCode.Unauthorized, attempt("bad", "203.0.113.7").status) }
            // Same phone, same socket; only a client-written header changed.
            val r = attempt("correct horse 1", "198.51.100.7")
            assertEquals(HttpStatusCode.Forbidden, r.status, "lock bypassed via X-Forwarded-For: ${r.bodyAsText()}")
            assertEquals("ERR_AUTH_ACCOUNT_LOCKED", json(r.bodyAsText()).code)
        }
    }

    /**
     * A phone family minted while the device has no row (dev, and every phone in the current production wiring,
     * which uses NoDevices) is not bound to the device_uuid it was issued for: another phone refreshes it and the
     * new access token carries the other phone's dvu.
     */
    @Test
    fun refreshTokenOfOnePhoneIsNotUsableFromAnother() {
        val f = fixture()
        val phoneA = "1b2c3d4e-5f60-4a7b-8c9d-0e1f2a3b4c5d"; val phoneB = "9b0c1d2e-3f40-4b5c-8d6e-7f8091a2b3c4"
        testApplication {
            application { f.application(this) }
            val b = json(client.login("sr334001", "correct horse 1", phoneA).bodyAsText())
            assertEquals("ok", b["status"]!!.jsonPrimitive.content)
            assertEquals(phoneA, SignedJWT.parse(b["access_token"]!!.jsonPrimitive.content).jwtClaimsSet.getStringClaim("dvu"))
            // Builder's fix: a phone the server cannot bind (no device row) gets no refresh or upload grant at all.
            assertEquals(kotlinx.serialization.json.JsonNull, b["refresh_token"])
            assertEquals(kotlinx.serialization.json.JsonNull, b["upload_refresh_token"])
            // And a bound phone's grant presented from another phone is refused (see RefreshTest.grantAndDeviceAreBound).
            val bound = json(client.login("sr334001", "correct horse 1", f.srDevice).bodyAsText())["refresh_token"]!!.jsonPrimitive.content
            val r = client.refresh(bound, phoneB)
            assertEquals(HttpStatusCode.Unauthorized, r.status)
            assertEquals("ERR_DEVICE_PROOF_INVALID", json(r.bodyAsText()).code)
        }
    }

    @Test
    fun aRefusedRefreshDoesNotConsumeTheToken() {
        val f = fixture()
        testApplication {
            application { f.application(this) }
            val rt = json(client.login("sr334001", "correct horse 1", f.srDevice).bodyAsText())["refresh_token"]!!.jsonPrimitive.content
            f.addUser(f.user(1001, "sr334001", Role.SR, status = "disabled"))
            assertEquals("ERR_AUTH_USER_DISABLED", json(client.refresh(rt, f.srDevice).bodyAsText()).code)
            f.addUser(f.user(1001, "sr334001", Role.SR, status = "active"))
            f.clock.advance(120)
            val r = client.refresh(rt, f.srDevice)
            assertEquals(HttpStatusCode.OK, r.status, "refused refresh consumed the token: ${r.bodyAsText()}")
        }
    }

    /**
     * password_change_required returns an aron-api access token; the bearer guard accepts it on every guarded
     * route, so a temporary password gives the full API for 10 minutes instead of only change-password.
     */
    @Test
    fun temporaryPasswordTokenIsRefusedOutsideChangePassword() {
        val f = fixture()
        f.addUser(f.user(1001, "sr334001", Role.SR).copy(mustChangePassword = true))
        testApplication {
            application {
                f.application(this)
                routing { route("/v1") { authenticated(f.guard) { get("/probe-business") { call.respondText("served") } } } }
            }
            val b = json(client.login("sr334001", "correct horse 1", f.srDevice).bodyAsText())
            assertEquals("password_change_required", b["status"]!!.jsonPrimitive.content)
            val token = b["access_token"]!!.jsonPrimitive.content
            val r = client.get("/v1/probe-business") { bearerAuth(token); header("X-Device-Id", f.srDevice) }
            assertEquals(HttpStatusCode.Forbidden, r.status, "temporary-password token served a business route: ${r.bodyAsText()}")
            assertEquals("ERR_AUTH_PASSWORD_CHANGE_REQUIRED", json(r.bodyAsText()).code)
        }
    }
}
