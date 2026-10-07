package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.Audience
import com.aktcl.aron.contract.Role
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun json(r: String): JsonObject = Json.parseToJsonElement(r).jsonObject
internal val JsonObject.code: String? get() = this["code"]?.jsonPrimitive?.content

internal suspend fun HttpClient.login(
    username: String, password: String, device: String? = null, client: String = "app_sr", ip: String = "103.4.145.10",
): HttpResponse = post("/v1/auth/login") {
    contentType(ContentType.Application.Json)
    header("X-Azure-ClientIP", ip)
    header("X-App-Version", "1.0.0+100")
    setBody("""{"username":"$username","password":"$password","client":"$client"${device?.let { ",\"device_uuid\":\"$it\"" } ?: ""}}""")
}

open class LoginTest {
    private val made = mutableListOf<AuthFixture>()

    /** Builds the fixture; [LoginTestDb] overrides it to run the same tests on PostgreSQL stores. */
    open fun fixture(overrides: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(), hashConcurrency: Int = 4, hashQueue: Int = 32): AuthFixture =
        AuthFixture(overrides, hashConcurrency, hashQueue).also { made += it }

    protected fun track(f: AuthFixture) = f.also { made += it }

    @org.junit.jupiter.api.AfterEach
    fun closeFixtures() { made.forEach { it.close() }; made.clear() }

    @Test
    fun seededSrGetsAnEs256AccessTokenWithRoleAndScopeClaimsAndRefreshTokens() {
        val f = fixture()
        testApplication {
            application { f.application(this) }
            val r = client.login("SR334001", "correct horse 1", f.srDevice)
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val b = json(r.bodyAsText())
            assertEquals("ok", b["status"]!!.jsonPrimitive.content)
            val jwt = SignedJWT.parse(b["access_token"]!!.jsonPrimitive.content)
            assertEquals("ES256", jwt.header.algorithm.name)
            assertEquals("test-1", jwt.header.keyID)
            val c = jwt.jwtClaimsSet
            assertEquals("aron", c.issuer)
            assertEquals(listOf(Audience.API), c.audience)
            assertEquals("1001", c.subject)
            assertEquals("SR", c.getStringClaim("role"))
            assertEquals(7L, c.getLongClaim("sv"))
            assertEquals(501L, c.getLongClaim("did"))
            assertEquals(f.srDevice, c.getStringClaim("dvu"))
            assertEquals("sr", c.getStringClaim("flv"))
            assertEquals(listOf("pwd"), c.getStringListClaim("amr"))
            assertTrue(f.verifier.verify(jwt.serialize(), setOf(Audience.API)).userId == 1001L)
            assertEquals(43, b["refresh_token"]!!.jsonPrimitive.content.length)
            assertEquals(43, b["upload_refresh_token"]!!.jsonPrimitive.content.length)
            assertEquals(7L, b["scope"]!!.jsonObject["scope_version"]!!.jsonPrimitive.long)
            assertEquals("route", b["scope"]!!.jsonObject["nodes"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(0, b["device"]!!.jsonObject["bind_ordinal"]!!.jsonPrimitive.int)
            assertEquals(500, b["device"]!!.jsonObject["memo_seq_block_size"]!!.jsonPrimitive.int)
            assertEquals("sr334001", b["user"]!!.jsonObject["username"]!!.jsonPrimitive.content)
            // Every LoginResponse member is present (required-nullable ones as null).
            assertEquals(setOf("status", "access_token", "access_expires_at", "refresh_token", "refresh_expires_at", "upload_refresh_token",
                "bind_token", "mfa_token", "user", "scope", "device", "config_version", "server_time", "min_app_version_code", "password_change_token"), b.keys)
        }
    }

    @Test
    fun wrongPasswordAndUnknownUserGetTheSameUniformError() {
        val f = fixture()
        testApplication {
            application { f.application(this) }
            val wrong = client.login("sr334001", "nope", f.srDevice)
            val unknown = client.login("nobody99", "nope", f.srDevice)
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
            assertEquals(HttpStatusCode.Unauthorized, unknown.status)
            val a = json(wrong.bodyAsText()); val b = json(unknown.bodyAsText())
            assertEquals("ERR_AUTH_INVALID_CREDENTIALS", a.code)
            for (k in listOf("code", "detail", "title", "message_key", "status")) assertEquals(a[k], b[k], k)
        }
    }

    @Test
    fun repeatedFailuresLockTheUsernameDevicePairOnly() {
        // lockout_attempts lowered to 5 (bounds 3..50) so the per-username rate limit (10 / 15 min) does not mask the pair lock.
        val f = fixture(mapOf("cfg.auth.lockout_attempts" to JsonPrimitive(5)))
        val otherDevice = "7a1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c11"
        f.addDevice(DeviceRecord(502, otherDevice, "active", "sr", null))
        f.bind(1001L, 502L, 1)
        testApplication {
            application { f.application(this) }
            repeat(5) { assertEquals(HttpStatusCode.Unauthorized, client.login("sr334001", "bad", f.srDevice).status) }
            val locked = client.login("sr334001", "correct horse 1", f.srDevice)
            assertEquals(HttpStatusCode.Forbidden, locked.status)
            val lb = json(locked.bodyAsText())
            assertEquals("ERR_AUTH_ACCOUNT_LOCKED", lb.code)
            assertEquals(900, lb["retry_after_s"]!!.jsonPrimitive.int)
            assertEquals("900", locked.headers["Retry-After"])
            // The same user on another phone is not locked.
            assertEquals(HttpStatusCode.OK, client.login("sr334001", "correct horse 1", otherDevice).status)
            // After 15 minutes the pair is free again; the next lock doubles.
            f.clock.advance(901)
            assertEquals(HttpStatusCode.OK, client.login("sr334001", "correct horse 1", f.srDevice).status)
        }
    }

    @Test
    fun theLockoutIpClassComesOnlyFromOurFrontDoor() {
        val f = fixture(mapOf("cfg.auth.lockout_attempts" to JsonPrimitive(3)))
        testApplication {
            application { f.application(this) }
            suspend fun attempt(pw: String, ip: String, fdid: String?) = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                header("X-Azure-ClientIP", ip); fdid?.let { header("X-Azure-FDID", it) }
                setBody("""{"username":"sr334001","password":"$pw","client":"app_sr","device_uuid":"${f.srDevice}"}""")
            }
            repeat(3) { attempt("bad", "203.0.113.7", "fd-test") }
            // Same Front Door client IP: locked.
            assertEquals("ERR_AUTH_ACCOUNT_LOCKED", json(attempt("correct horse 1", "203.0.113.9", "fd-test").bodyAsText()).code)
            // A forged X-Azure-ClientIP without our Front Door id does not count as another network.
            repeat(3) { attempt("bad", "198.51.100.1", null) }
            assertEquals("ERR_AUTH_ACCOUNT_LOCKED", json(attempt("correct horse 1", "192.0.2.77", null).bodyAsText()).code)
            assertEquals("ERR_AUTH_ACCOUNT_LOCKED", json(attempt("correct horse 1", "192.0.2.77", "someone-elses-fd").bodyAsText()).code)
        }
        assertEquals("10.1.2.0/24", LoginService.ipClass("::ffff:10.1.2.3"))
    }

    @Test
    fun lockDoublesOnTheSecondLock() {
        val f = fixture(mapOf("cfg.auth.lockout_attempts" to JsonPrimitive(3)))
        testApplication {
            application { f.application(this) }
            repeat(3) { client.login("sr334001", "bad", f.srDevice) }
            f.clock.advance(901)
            repeat(3) { client.login("sr334001", "bad", f.srDevice) }
            val b = json(client.login("sr334001", "correct horse 1", f.srDevice).bodyAsText())
            assertEquals("ERR_AUTH_ACCOUNT_LOCKED", b.code)
            assertEquals(1800, b["retry_after_s"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun rateLimitedPerUsernameAndPerDeviceNeverPerIp() {
        val f = fixture(mapOf("cfg.auth.lockout_attempts" to JsonPrimitive(50)))
        testApplication {
            application { f.application(this) }
            // 10 per 15 min per username (from any device and IP)
            repeat(10) { assertEquals(HttpStatusCode.Unauthorized, client.login("sr334001", "bad", UUID.randomUUID().toString(), ip = "10.0.$it.1").status) }
            val limited = client.login("sr334001", "correct horse 1", f.srDevice)
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("ERR_RATE_LIMITED", json(limited.bodyAsText()).code)
            assertNotNull(limited.headers["Retry-After"])
            assertEquals("10", limited.headers["RateLimit-Limit"])
            // 30 per 15 min per device, whatever the username
            val dev = UUID.randomUUID().toString()
            repeat(30) { assertEquals(HttpStatusCode.Unauthorized, client.login("user$it", "bad", dev).status) }
            assertEquals(HttpStatusCode.TooManyRequests, client.login("user99", "bad", dev).status)
            // Not per IP: 40 different devices behind one carrier-NAT address are not limited.
            repeat(40) { assertEquals(HttpStatusCode.Unauthorized, client.login("nat$it", "bad", UUID.randomUUID().toString(), ip = "103.4.145.10").status) }
        }
    }

    @Test
    fun underTwoHundredParallelLoginsTheHashLimiterAnswers503WithRetryAfterAndStaysBounded() {
        val f = fixture(hashConcurrency = 4, hashQueue = 16)
        repeat(200) { i -> f.addUser(f.user(10_000L + i, "storm$i", Role.SR)) }
        val heapBefore = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        testApplication {
            application { f.application(this) }
            val results = coroutineScope {
                (0 until 200).map { i ->
                    async { client.login("storm$i", "correct horse 1", UUID.randomUUID().toString(), ip = "103.4.145.10") }
                }.awaitAll()
            }
            val statuses = results.groupingBy { it.status.value }.eachCount()
            assertTrue(statuses.keys.all { it == 200 || it == 503 }, "statuses: $statuses")
            val busy = results.filter { it.status.value == 503 }
            assertTrue(busy.isNotEmpty(), "limiter must shed load: $statuses")
            assertTrue((statuses[200] ?: 0) >= 4, "some logins succeed: $statuses")
            for (r in busy) {
                val s = r.headers["Retry-After"]!!.toInt()
                assertTrue(s in 2..10)
                val b = json(r.bodyAsText())
                assertEquals("ERR_SERVICE_UNAVAILABLE", b.code)
                assertEquals(s, b["retry_after_s"]!!.jsonPrimitive.int)
            }
        }
        assertTrue(f.limiter.peakConcurrent <= 4, "peak ${f.limiter.peakConcurrent}")
        System.gc()
        val heapAfter = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        assertTrue(heapAfter - heapBefore < 256L * 1024 * 1024, "heap grew by ${(heapAfter - heapBefore) / 1_048_576} MiB")
    }

    @Test
    fun deviceStatesAndEnrolment() {
        val f = fixture(mapOf("cfg.device.require_enrolled" to JsonPrimitive(true)))
        val suspended = "8a1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c12"
        f.addDevice(DeviceRecord(503, suspended, "suspended", "sr", null))
        testApplication {
            application { f.application(this) }
            assertEquals("ERR_DEVICE_NOT_ENROLLED", json(client.login("sr334001", "correct horse 1", UUID.randomUUID().toString()).bodyAsText()).code)
            assertEquals("ERR_DEVICE_SUSPENDED", json(client.login("sr334001", "correct horse 1", suspended).bodyAsText()).code)
            val noDevice = client.login("sr334001", "correct horse 1", null)
            assertEquals(HttpStatusCode.BadRequest, noDevice.status)
            assertEquals("/device_uuid", json(noDevice.bodyAsText())["errors"]!!.jsonArray[0].jsonObject["pointer"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun unboundUserGetsABindTokenAndNoRefreshGrant() {
        val f = fixture()
        f.unbindAll()
        testApplication {
            application { f.application(this) }
            val b = json(client.login("sr334001", "correct horse 1", f.srDevice).bodyAsText())
            assertEquals("bind_required", b["status"]!!.jsonPrimitive.content)
            assertNull(b["refresh_token"]!!.jsonPrimitive.contentOrNullSafe())
            val jwt = SignedJWT.parse(b["bind_token"]!!.jsonPrimitive.content)
            assertEquals(listOf(Audience.BIND), jwt.jwtClaimsSet.audience)
            assertEquals(600, (jwt.jwtClaimsSet.expirationTime.time - jwt.jwtClaimsSet.issueTime.time) / 1000)
        }
    }

    @Test
    fun disabledUserIsRefusedOnlyAfterTheRightPassword() {
        val f = fixture()
        f.addUser(f.user(1001, "sr334001", Role.SR, status = "disabled"))
        testApplication {
            application { f.application(this) }
            assertEquals("ERR_AUTH_INVALID_CREDENTIALS", json(client.login("sr334001", "bad", f.srDevice).bodyAsText()).code)
            assertEquals("ERR_AUTH_USER_DISABLED", json(client.login("sr334001", "correct horse 1", f.srDevice).bodyAsText()).code)
        }
    }

    @Test
    fun webAdminGetsAnMfaStepAndOldAppVersionsGet426() {
        val f = fixture(mapOf("cfg.release.min_version_code" to Json.parseToJsonElement("""{"sr":200,"amo":1,"tso":1}""")))
        testApplication {
            application { f.application(this) }
            val web = json(client.login("admin1", "correct horse 1", client = "web").bodyAsText())
            assertEquals("mfa_required", web["status"]!!.jsonPrimitive.content)
            assertEquals(listOf(Audience.MFA), SignedJWT.parse(web["mfa_token"]!!.jsonPrimitive.content).jwtClaimsSet.audience)
            val old = client.login("sr334001", "correct horse 1", f.srDevice)
            assertEquals(426, old.status.value)
            val b = json(old.bodyAsText())
            assertEquals("ERR_APP_VERSION_UNSUPPORTED", b.code)
            assertEquals(200, b["context"]!!.jsonObject["min_version_code"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun clientSentScopeIdsAreNotAccepted() {
        val f = fixture()
        testApplication {
            application { f.application(this) }
            val r = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"sr334001","password":"correct horse 1","client":"app_sr","device_uuid":"${f.srDevice}","zone_ids":[1,2,3]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertEquals("ERR_VALIDATION", json(r.bodyAsText()).code)
        }
    }
}

private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content
