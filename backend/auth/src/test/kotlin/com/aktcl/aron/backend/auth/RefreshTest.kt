package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.Audience
import com.aktcl.aron.backend.platform.DeviceProof
import com.aktcl.aron.contract.Role
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal suspend fun HttpClient.refresh(token: String, device: String?, grant: String = "full", proof: String? = null): HttpResponse =
    post("/v1/auth/refresh") {
        contentType(ContentType.Application.Json)
        device?.let { header("X-Device-Id", it) }
        proof?.let { header("X-Device-Proof", it) }
        setBody("""{"grant":"$grant","refresh_token":"$token"}""")
    }

open class RefreshTest {
    private val made = mutableListOf<AuthFixture>()

    /** Builds the fixture; [RefreshTestDb] overrides it to run the same tests on PostgreSQL stores. */
    open fun fixture(overrides: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(), hashConcurrency: Int = 4, hashQueue: Int = 32): AuthFixture =
        AuthFixture(overrides, hashConcurrency, hashQueue).also { made += it }

    protected fun track(f: AuthFixture) = f.also { made += it }

    @org.junit.jupiter.api.AfterEach
    fun closeFixtures() { made.forEach { it.close() }; made.clear() }

    private suspend fun HttpClient.loginTokens(f: AuthFixture): Pair<String, String> {
        val b = json(login("sr334001", "correct horse 1", f.srDevice).bodyAsText())
        return b["refresh_token"]!!.jsonPrimitive.content to b["upload_refresh_token"]!!.jsonPrimitive.content
    }

    @Test
    fun rotatesOnUseAndAReplayWithinSixtySecondsGetsTheSameResult() {
        val f = fixture()
        testApplication {
            application { f.application(this) }
            val (rt, _) = client.loginTokens(f)
            val first = client.refresh(rt, f.srDevice)
            assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
            val a = json(first.bodyAsText())
            val rt2 = a["refresh_token"]!!.jsonPrimitive.content
            assertNotEquals(rt, rt2)
            assertEquals(Audience.API, SignedJWT.parse(a["access_token"]!!.jsonPrimitive.content).jwtClaimsSet.audience.single())
            f.clock.advance(59)
            val replay = json(client.refresh(rt, f.srDevice).bodyAsText())
            assertEquals(rt2, replay["refresh_token"]!!.jsonPrimitive.content, "grace replay returns the same replacement")
            assertEquals(a["refresh_expires_at"], replay["refresh_expires_at"])
            assertEquals(a["scope_version"], replay["scope_version"])
            // The replacement keeps working.
            assertEquals(HttpStatusCode.OK, client.refresh(rt2, f.srDevice).status)
        }
    }

    @Test
    fun aUsedTokenReplayedAfterSixtySecondsRevokesTheGrant() {
        val f = fixture()
        testApplication {
            application { f.application(this) }
            val (rt, upload) = client.loginTokens(f)
            val rt2 = json(client.refresh(rt, f.srDevice).bodyAsText())["refresh_token"]!!.jsonPrimitive.content
            f.clock.advance(61)
            val reused = client.refresh(rt, f.srDevice)
            assertEquals(HttpStatusCode.Unauthorized, reused.status)
            assertEquals("ERR_AUTH_REFRESH_REUSED", json(reused.bodyAsText()).code)
            // The whole family is revoked: the legitimate replacement no longer works either.
            assertEquals("ERR_AUTH_REFRESH_REUSED", json(client.refresh(rt2, f.srDevice).bodyAsText()).code)
            // The upload grant is a separate family: captured rows still reach the server.
            val up = client.refresh(upload, f.srDevice, grant = "upload")
            assertEquals(HttpStatusCode.OK, up.status)
            assertEquals(Audience.UPLOAD, SignedJWT.parse(json(up.bodyAsText())["access_token"]!!.jsonPrimitive.content).jwtClaimsSet.audience.single())
        }
    }

    @Test
    fun concurrentUseOfOneTokenRotatesOnce() {
        val f = fixture()
        testApplication {
            application { f.application(this) }
            val (rt, _) = client.loginTokens(f)
            val got = coroutineScope { (1..20).map { async { client.refresh(rt, f.srDevice) } }.awaitAll() }
            assertTrue(got.all { it.status == HttpStatusCode.OK }, got.map { it.status }.toString())
            assertEquals(1, got.map { json(it.bodyAsText())["refresh_token"]!!.jsonPrimitive.content }.toSet().size)
        }
    }

    @Test
    fun grantAndDeviceAreBound() {
        val f = fixture()
        val other = "9b0c1d2e-3f40-4b5c-8d6e-7f8091a2b3c4"
        f.addDevice(DeviceRecord(777, other, "active", "sr", null))
        testApplication {
            application { f.application(this) }
            val (rt, upload) = client.loginTokens(f)
            assertEquals("ERR_AUTH_REFRESH_INVALID", json(client.refresh(rt, f.srDevice, grant = "upload").bodyAsText()).code)
            assertEquals("ERR_AUTH_REFRESH_INVALID", json(client.refresh(upload, f.srDevice, grant = "full").bodyAsText()).code)
            assertEquals("ERR_DEVICE_PROOF_INVALID", json(client.refresh(rt, other).bodyAsText()).code)
            assertEquals("ERR_DEVICE_PROOF_INVALID", json(client.refresh(rt, null).bodyAsText()).code)
            assertEquals("ERR_AUTH_REFRESH_INVALID", json(client.refresh("A".repeat(43), f.srDevice).bodyAsText()).code)
            // None of the refused attempts consumed the token.
            assertEquals(HttpStatusCode.OK, client.refresh(rt, f.srDevice).status)
        }
    }

    @Test
    fun aDeviceWithAKeyMustProveIt() {
        val f = fixture()
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val jwk = ECKey.Builder(Curve.P_256, kp.public as ECPublicKey).build().toJSONString()
        f.addDevice(DeviceRecord(501, f.srDevice, "active", "sr", jwk))
        fun proof(token: String, at: Instant): String {
            val msg = DeviceProof.refreshString(f.srDevice, token, DeviceProof.bucket(at.epochSecond))
            val sig = Signature.getInstance("SHA256withECDSAinP1363Format").run { initSign(kp.private); update(msg.toByteArray()); sign() }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
        }
        testApplication {
            application { f.application(this) }
            val (rt, _) = client.loginTokens(f)
            assertEquals("ERR_DEVICE_PROOF_INVALID", json(client.refresh(rt, f.srDevice).bodyAsText()).code, "missing proof")
            assertEquals("ERR_DEVICE_PROOF_INVALID", json(client.refresh(rt, f.srDevice, proof = proof("other-token-x", f.clock.now())).bodyAsText()).code)
            assertEquals("ERR_DEVICE_PROOF_INVALID", json(client.refresh(rt, f.srDevice, proof = proof(rt, f.clock.now().minusSeconds(900))).bodyAsText()).code, "stale bucket")
            assertEquals(HttpStatusCode.OK, client.refresh(rt, f.srDevice, proof = proof(rt, f.clock.now().minusSeconds(300))).status, "previous bucket accepted")
        }
    }

    @Test
    fun disabledUserKeepsOnlyTheUploadGrant() {
        val f = fixture()
        testApplication {
            application { f.application(this) }
            val (rt, upload) = client.loginTokens(f)
            f.addUser(f.user(1001, "sr334001", Role.SR, status = "disabled"))
            assertEquals("ERR_AUTH_USER_DISABLED", json(client.refresh(rt, f.srDevice).bodyAsText()).code)
            assertEquals(HttpStatusCode.OK, client.refresh(upload, f.srDevice, grant = "upload").status)
        }
    }

    @Test
    fun slidingAndAbsoluteExpiry() {
        val f = fixture()
        testApplication {
            application { f.application(this) }
            val b = json(client.login("sr334001", "correct horse 1", f.srDevice).bodyAsText())
            val abs = Instant.parse(b["refresh_expires_at"]!!.jsonPrimitive.content)
            val days = Duration.between(f.clock.now(), abs).toDays()
            assertTrue(days in 75..105, "absolute $days days")
            val rt = b["refresh_token"]!!.jsonPrimitive.content
            f.clock.advance(31L * 86_400)
            assertEquals("ERR_AUTH_REFRESH_INVALID", json(client.refresh(rt, f.srDevice).bodyAsText()).code, "idle 31 days")
        }
    }

    @Test
    fun scopeChangeAnswers401AndARefreshRecovers() {
        val f = fixture()
        testApplication {
            application { f.application(this) }
            val b = json(client.login("sr334001", "correct horse 1", f.srDevice).bodyAsText())
            val access = b["access_token"]!!.jsonPrimitive.content
            suspend fun me(t: String) = client.get("/v1/me") { bearerAuth(t); header("X-Device-Id", f.srDevice) }
            assertEquals(HttpStatusCode.OK, me(access).status)
            assertEquals("ERR_DEVICE_PROOF_INVALID", json(client.get("/v1/me") { bearerAuth(access); header("X-Device-Id", "9b0c1d2e-3f40-4b5c-8d6e-7f8091a2b3c4") }.bodyAsText()).code)
            f.addUser(f.users.findById(1001)!!.copy(scopeVersion = 8))
            val stale = me(access)
            assertEquals(HttpStatusCode.Unauthorized, stale.status)
            assertEquals("ERR_SCOPE_CHANGED", json(stale.bodyAsText()).code)
            val fresh = json(client.refresh(b["refresh_token"]!!.jsonPrimitive.content, f.srDevice).bodyAsText())
            assertEquals(8L, fresh["scope_version"]!!.jsonPrimitive.long)
            assertEquals(HttpStatusCode.OK, me(fresh["access_token"]!!.jsonPrimitive.content).status)
        }
    }

    @Test
    fun accessTokenLifetimesAreJitteredForPhonesAndFifteenMinutesOnTheWeb() {
        val f = fixture()
        val sr = f.users.findById(1001)!!
        val ttls = (1..60).map {
            val a = f.issuer.access(TokenSubject(sr, 501, f.srDevice, "sr"))
            Duration.between(f.clock.now(), a.expiresAt).seconds
        }
        assertTrue(ttls.all { it in 3600..4200 }, "phone ttl range $ttls")
        assertTrue(ttls.toSet().size > 30, "jitter spreads expiries")
        val web = f.issuer.access(TokenSubject(f.users.findById(9001)!!, null, null, "web"))
        assertEquals(900, Duration.between(f.clock.now(), web.expiresAt).seconds)
    }

    @Test
    fun jwksPublishesTheVerificationKey() {
        val f = fixture()
        testApplication {
            application { f.application(this) }
            val k = json(client.get("/v1/auth/jwks").bodyAsText())["keys"]!!.let { it as kotlinx.serialization.json.JsonArray }.single().jsonObject
            assertEquals("EC", k["kty"]!!.jsonPrimitive.content)
            assertEquals("P-256", k["crv"]!!.jsonPrimitive.content)
            assertEquals("test-1", k["kid"]!!.jsonPrimitive.content)
            assertEquals(43, k["x"]!!.jsonPrimitive.content.length)
            val pub = ECKey.parse(k.toString()).toECPublicKey()
            assertEquals(f.keys.publicKey.w, pub.w)
        }
    }
}
