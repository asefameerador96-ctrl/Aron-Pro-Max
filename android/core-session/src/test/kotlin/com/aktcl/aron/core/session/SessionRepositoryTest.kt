package com.aktcl.aron.core.session

import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.core.common.DispatcherProvider
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.AuthApi
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.core.network.SyncApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val clock = FakeClock()
    private val cipher = JvmAesCipher()
    private val verifier = JvmPasswordVerifier()
    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private val loginOk: String by lazy {
        ContractYaml.toJson(ContractYaml.node("paths", "/v1/auth/login", "post", "responses", "200", "content", "application/json", "examples", "phoneOk", "value")).toString()
    }
    private val contractAccessToken: String by lazy { Json.parseToJsonElement(loginOk).jsonObject["access_token"]!!.jsonPrimitive.content }
    private val bundleSr: String by lazy { ContractYaml.componentExampleJson("BundleSr") }

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private inner class Phone(storage: File = tmp.root, d: DispatcherProvider = dispatchers, c: SecretCipher = cipher) {
        val identity = DeviceIdentity(storage)
        private val holder = Holder()
        val api = AronApiClient(ApiOrigin.parse(server.url("/").toString(), true), AronApiClient.defaultOkHttp(), ClientIdentity("0.1.0+1") { identity.deviceUuid }, holder)
        val store = SessionStore(File(storage, "session"), c)
        val session = SessionRepository(AuthApi(api), store, verifier, identity, "app_sr", clock, OfflineUnlockPolicy(), d).also { holder.target = it }
        val sync = SyncApi(api)
    }

    private class Holder : com.aktcl.aron.core.network.AccessTokenSource {
        var target: SessionRepository? = null
        override fun currentAccessToken(grant: Grant) = target?.currentAccessToken(grant)
        override suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: ProblemCode?) =
            target?.refreshAfterUnauthorized(grant, rejectedToken, code) ?: false
    }

    private fun api(code: Int, body: String, vararg headers: Pair<String, String>) = MockResponse.Builder().code(code).body(body)
        .addHeader("X-Aron-Api", "1").addHeader("X-Server-Time", "2026-10-05T02:12:44.120Z").also { b -> headers.forEach { b.addHeader(it.first, it.second) } }.build()

    private fun problem(status: Int, code: String) = """{"type":"urn:aron:problem:x","title":"t","status":$status,"code":"$code","request_id":"6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10"}"""

    private fun tokenPair(access: String, refresh: String) =
        """{"access_token":"$access","access_expires_at":"2026-10-05T04:12:44.120Z","refresh_token":"$refresh","refresh_expires_at":"2027-01-02T00:00:00.000Z","scope_version":8,"server_time":"2026-10-05T03:12:44.120Z"}"""

    private suspend fun Phone.logoutOnline() {
        server.enqueue(api(204, ""))
        session.logout()
    }

    private suspend fun Phone.loginOnline(password: String = "secret-1"): LoginOutcome {
        server.enqueue(api(200, loginOk))
        return session.login(" SR334001 ", password)
    }

    /** Holds the first task dispatched (the repository's background restore) and runs everything else in place. */
    private class HoldFirst : CoroutineDispatcher() {
        var held: Runnable? = null
        var threadOfFirst: Thread? = null
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            if (threadOfFirst == null) { threadOfFirst = Thread.currentThread(); held = block } else block.run()
        }
        fun release() { held?.run(); held = null }
    }

    private fun holding(d: HoldFirst) = object : DispatcherProvider {
        override val io: CoroutineDispatcher = d
        override val default: CoroutineDispatcher = d
    }

    @Test
    fun audPerf05_theRestoreIsNotDoneByTheConstructorButBehindRestoring() = runTest {
        Phone().loginOnline()
        val hold = HoldFirst()
        val relaunched = Phone(d = holding(hold))
        assertEquals("the constructor (main thread under Hilt) restores nothing", SessionState.Restoring, relaunched.session.state.value)
        assertTrue("the restore was handed to the IO dispatcher", hold.held != null)
        hold.release()
        assertEquals(1001L, (relaunched.session.state.value as SessionState.Active).user.userId)
    }

    @Test
    fun audPerf05_settledNeverAnswersRestoringAndALaterRestoreNeverOverwritesALogout() = runTest {
        Phone().loginOnline()
        val hold = HoldFirst()
        val relaunched = Phone(d = holding(hold))
        assertTrue(relaunched.session.settled() is SessionState.Active)
        relaunched.logoutOnline()
        hold.release() // the background restore arrives late
        assertEquals(SessionState.LoggedOut, relaunched.session.state.value)
    }

    @Test
    fun audPerf05_aLoginWhileRestoringIsKeptAndTheTokenIsReadyForTheNetworkThread() = runTest {
        val hold = HoldFirst()
        val phone = Phone(d = holding(hold))
        assertTrue(phone.loginOnline() is LoginOutcome.LoggedIn)
        hold.release()
        assertTrue(phone.session.state.value is SessionState.Active)
        assertEquals(contractAccessToken, phone.session.currentAccessToken(Grant.FULL))
    }

    @Test
    fun audPerf05_aKeystoreFailureAtRestoreLeavesTheUserSignedOutNeverACrash() = runTest {
        Phone().loginOnline()
        val broken = object : SecretCipher {
            override fun encrypt(plain: ByteArray) = cipher.encrypt(plain)
            override fun decrypt(blob: ByteArray): ByteArray = throw java.security.GeneralSecurityException("keystore")
        }
        val relaunched = Phone(c = broken)
        assertEquals(SessionState.LoggedOut, relaunched.session.settled())
    }

    @Test
    fun loginAgainstTheContractResponsesThenTheBundleCarriesTheToken() = runTest {
        val phone = Phone()
        val outcome = phone.loginOnline()
        assertTrue(outcome is LoginOutcome.LoggedIn && outcome.mode == UnlockMode.ONLINE)
        val login = server.takeRequest()
        assertEquals("/v1/auth/login", login.url.encodedPath)
        val body = Json.parseToJsonElement(login.body!!.utf8()).jsonObject
        assertEquals("sr334001", body["username"]!!.jsonPrimitive.content)
        assertEquals("app_sr", body["client"]!!.jsonPrimitive.content)
        assertEquals(phone.identity.deviceUuid, body["device_uuid"]!!.jsonPrimitive.content)
        assertEquals(setOf("username", "password", "client", "device_uuid"), body.keys)

        val state = phone.session.state.value as SessionState.Active
        assertEquals(1001L, state.user.userId)
        assertEquals("Testing Banani", state.user.fullName)
        assertEquals(0, state.user.bindOrdinal)
        assertFalse(state.reauthRequired)

        server.enqueue(api(200, bundleSr, "ETag" to "\"2026-10-05:3\""))
        val bundle = phone.sync.bundle() as ApiResult.Success
        assertEquals("2026-10-05:3", bundle.value.head.meta.bundleVersion)
        assertEquals("2026-10-05", bundle.value.head.meta.validForBusinessDate)
        val req = server.takeRequest()
        assertEquals("Bearer $contractAccessToken", req.headers["Authorization"])
        assertEquals(phone.identity.deviceUuid, req.headers["X-Device-Id"])
    }

    @Test
    fun tokensAndVerifierNeverLandInPlaintext() = runTest {
        val phone = Phone()
        phone.loginOnline("secret-1")
        val all = tmp.root.walkTopDown().filter { it.isFile }.map { it.readBytes().toString(Charsets.ISO_8859_1) }.joinToString()
        assertFalse(all.contains(contractAccessToken))
        assertFalse(all.contains("secret-1"))
        assertFalse(all.contains("Testing Banani"))
    }

    @Test
    fun offlineUnlockWorksAfterAnOnlineLoginAndSurvivesARestart() = runTest {
        Phone().loginOnline("secret-1")
        server.close() // no network from here on
        val relaunched = Phone()
        assertTrue("session survives a kill", relaunched.session.state.value is SessionState.Active)
        relaunched.session.logout()
        assertEquals(SessionState.LoggedOut, relaunched.session.state.value)
        val outcome = relaunched.session.login("sr334001", "secret-1")
        assertEquals(UnlockMode.OFFLINE, (outcome as LoginOutcome.LoggedIn).mode)
        assertEquals(UnlockMode.OFFLINE, (relaunched.session.state.value as SessionState.Active).mode)
    }

    @Test
    fun offlineUnlockNeedsAPriorOnlineLoginOnThisPhone() = runTest {
        server.close()
        val outcome = Phone().session.login("sr334001", "secret-1")
        assertEquals(LoginOutcome.OfflineUnavailable(OfflineRefusal.NEVER_ONLINE_ON_THIS_PHONE), outcome)
    }

    @Test
    fun offlineUnlockExpiresSevenDaysAfterTheLastOnlineLogin() = runTest {
        val phone = Phone()
        phone.loginOnline("secret-1")
        phone.logoutOnline()
        server.close()
        clock.now += 7 * 86_400_000L
        assertTrue(phone.session.login("sr334001", "secret-1") is LoginOutcome.LoggedIn)
        phone.session.logout()
        clock.now += 1
        assertEquals(OfflineRefusal.EXPIRED, (phone.session.login("sr334001", "secret-1") as LoginOutcome.OfflineUnavailable).refusal)
    }

    /**
     * F-SYS-052 checker: rebooting every morning and setting the date back to just after the last unlock used to keep the
     * 7-day window open forever. The uptime of each boot now counts, so the window closes after 7 days of use.
     */
    @Test
    fun rebootingAndSettingTheDateBackEveryDayCannotStretchTheOfflineWindow() = runTest {
        val loginAt = clock.now
        clock.elapsed = 3_600_000L; clock.boot = 5
        val phone = Phone()
        phone.loginOnline("secret-1")
        phone.logoutOnline()
        server.close()
        var outcome: LoginOutcome? = null
        for (day in 1..9) {
            clock.boot += 1                         // reboot
            clock.now = loginAt + day * 60_000L      // the date set back to just after the last unlock
            clock.elapsed = 20 * 3_600_000L          // a 20-hour field day of uptime before the next unlock
            outcome = phone.session.login("sr334001", "secret-1")
            if (outcome is LoginOutcome.OfflineUnavailable) break
            phone.session.noteTimePassing()
            phone.session.logout()
        }
        // 9 days of 20 h is 180 h > 168 h: refused by day 9 at the latest, never open for good.
        assertEquals(OfflineRefusal.EXPIRED, (outcome as LoginOutcome.OfflineUnavailable).refusal)
    }

    @Test
    fun anHonestDayOfUseWithRebootsStaysInsideTheWindow() = runTest {
        clock.elapsed = 1_000L; clock.boot = 1
        val phone = Phone()
        phone.loginOnline("secret-1")
        phone.logoutOnline()
        server.close()
        // Two days later, after a reboot, with a true clock: still unlocks (uptime and date agree well within 7 days).
        clock.now += 2 * 86_400_000L; clock.boot = 2; clock.elapsed = 5 * 3_600_000L
        assertTrue(phone.session.login("sr334001", "secret-1") is LoginOutcome.LoggedIn)
    }

    @Test
    fun tenWrongOfflinePasswordsStartADoublingCooldown() = runTest {
        val phone = Phone()
        phone.loginOnline("secret-1")
        phone.logoutOnline()
        server.close()
        repeat(9) {
            assertEquals(OfflineRefusal.WRONG_PASSWORD, (phone.session.login("sr334001", "nope") as LoginOutcome.OfflineUnavailable).refusal)
        }
        val tenth = phone.session.login("sr334001", "nope") as LoginOutcome.OfflineUnavailable
        assertEquals(OfflineRefusal.COOLDOWN, tenth.refusal)
        assertEquals(clock.now + 60_000, tenth.cooldownUntilMs)
        // Even the right password waits for the cool-down.
        assertEquals(OfflineRefusal.COOLDOWN, (phone.session.login("sr334001", "secret-1") as LoginOutcome.OfflineUnavailable).refusal)
        clock.now += 60_000
        val eleventh = phone.session.login("sr334001", "nope") as LoginOutcome.OfflineUnavailable
        assertEquals(clock.now + 120_000, eleventh.cooldownUntilMs)
        clock.now += 120_000
        assertTrue(phone.session.login("sr334001", "secret-1") is LoginOutcome.LoggedIn)
        assertEquals(0, phone.store.profileByUsername("sr334001")!!.offlineFailures)
    }

    @Test
    fun anOnlineWrongPasswordNeverFallsBackToOfflineUnlock() = runTest {
        val phone = Phone()
        phone.loginOnline("secret-1")
        phone.logoutOnline()
        server.enqueue(api(401, problem(401, "ERR_AUTH_INVALID_CREDENTIALS")))
        assertEquals(LoginOutcome.InvalidCredentials, phone.session.login("sr334001", "secret-1"))
        assertEquals(SessionState.LoggedOut, phone.session.state.value)
    }

    @Test
    fun edgePagesServerErrorsAndRateLimitsFallBackToOfflineUnlock() = runTest {
        val phone = Phone()
        phone.loginOnline("secret-1")
        for (answer in listOf(
            MockResponse.Builder().code(200).body("<html>captive portal</html>").build(),
            api(503, problem(503, "ERR_SERVICE_UNAVAILABLE"), "Retry-After" to "30"),
            api(500, "boom"),
            api(429, problem(429, "ERR_RATE_LIMITED"), "Retry-After" to "60"),
        )) {
            if (phone.session.state.value is SessionState.Active) phone.logoutOnline()
            server.enqueue(answer)
            val outcome = phone.session.login("sr334001", "secret-1")
            assertTrue("$answer -> $outcome", outcome is LoginOutcome.LoggedIn && outcome.mode == UnlockMode.OFFLINE)
        }
    }

    @Test
    fun refusalsKeepTheirCodeForTheLocalisedMessage() = runTest {
        val phone = Phone()
        server.enqueue(api(403, problem(403, "ERR_AUTH_ACCOUNT_LOCKED").replace("}", ",\"retry_after_s\":900}")))
        assertEquals(LoginOutcome.Refused("ERR_AUTH_ACCOUNT_LOCKED", 900), phone.session.login("sr334001", "x"))
        server.enqueue(api(403, problem(403, "ERR_DEVICE_NOT_ENROLLED")))
        assertEquals(LoginOutcome.Refused("ERR_DEVICE_NOT_ENROLLED", null), phone.session.login("sr334001", "x"))
        server.enqueue(api(426, problem(426, "ERR_APP_VERSION_UNSUPPORTED")))
        assertEquals(LoginOutcome.Refused("ERR_APP_VERSION_UNSUPPORTED", null), phone.session.login("sr334001", "x"))
        server.enqueue(api(429, problem(429, "ERR_RATE_LIMITED"), "Retry-After" to "60"))
        assertEquals(LoginOutcome.Refused("ERR_RATE_LIMITED", 60), phone.session.login("sr334001", "x"))
    }

    @Test
    fun anOldAppVersionMayStillFinishItsOfflineDay() = runTest {
        val phone = Phone()
        phone.loginOnline("secret-1")
        phone.logoutOnline()
        server.enqueue(api(426, problem(426, "ERR_APP_VERSION_UNSUPPORTED")))
        val outcome = phone.session.login("sr334001", "secret-1") as LoginOutcome.LoggedIn
        assertTrue(outcome.updateRequired)
        assertTrue((phone.session.state.value as SessionState.Active).updateRequired)
    }

    @Test
    fun bindAndPasswordChangeOutcomesOpenNoSession() = runTest {
        val phone = Phone()
        val bind = Json.parseToJsonElement(loginOk).jsonObject.toMutableMap().apply {
            put("status", Json.parseToJsonElement("\"bind_required\""))
            put("access_token", Json.parseToJsonElement("null")); put("refresh_token", Json.parseToJsonElement("null"))
            put("bind_token", Json.parseToJsonElement("\"bind.jwt\""))
        }
        server.enqueue(api(200, kotlinx.serialization.json.JsonObject(bind).toString()))
        assertEquals(LoginOutcome.BindRequired("bind.jwt"), phone.session.login("sr334001", "x"))
        assertEquals(SessionState.LoggedOut, phone.session.state.value)
    }

    @Test
    fun anExpiredTokenIsRefreshedAndTheRotationStored() = runTest {
        val phone = Phone()
        phone.loginOnline()
        server.takeRequest()
        server.enqueue(api(401, problem(401, "ERR_TOKEN_EXPIRED")))
        server.enqueue(api(200, tokenPair("access-2", "r".repeat(43))))
        server.enqueue(api(200, bundleSr))
        assertTrue(phone.sync.bundle() is ApiResult.Success)
        server.takeRequest()
        val refresh = server.takeRequest()
        assertEquals("/v1/auth/refresh", refresh.url.encodedPath)
        val body = Json.parseToJsonElement(refresh.body!!.utf8()).jsonObject
        assertEquals("full", body["grant"]!!.jsonPrimitive.content)
        assertEquals(phone.identity.deviceUuid, refresh.headers["X-Device-Id"])
        assertEquals("Bearer access-2", server.takeRequest().headers["Authorization"])
        assertEquals("r".repeat(43), phone.store.tokens(1001).refreshToken)
    }

    @Test
    fun aRefusedRefreshFlagsReauthButNeverEndsTheLocalSession() = runTest {
        val phone = Phone()
        phone.loginOnline()
        server.enqueue(api(401, problem(401, "ERR_TOKEN_EXPIRED")))
        server.enqueue(api(401, problem(401, "ERR_AUTH_REFRESH_REUSED")))
        val r = phone.sync.bundle()
        assertEquals(401, (r as ApiResult.Failure).httpStatus)
        val state = phone.session.state.value as SessionState.Active
        assertTrue(state.reauthRequired)
        assertEquals(1001L, state.user.userId)
        // The upload grant is untouched so the outbox can still empty.
        assertTrue(phone.store.tokens(1001).uploadRefreshToken != null)
        // An offline refresh failure flags nothing.
        server.close()
        assertFalse(phone.session.refresh(1001, Grant.UPLOAD))
        assertFalse(phone.store.tokens(1001).uploadReauthRequired)
    }

    @Test
    fun logoutKeepsTheUploadGrantAndWorksOffline() = runTest {
        val phone = Phone()
        phone.loginOnline()
        server.close()
        phone.session.logout()
        val tokens = phone.store.tokens(1001)
        assertEquals(null, tokens.refreshToken)
        assertEquals(null, tokens.accessToken)
        assertTrue(tokens.uploadRefreshToken != null)
        assertEquals(null, phone.session.currentAccessToken(Grant.FULL))
    }

    @Test
    fun twoUsersOnOnePhoneKeepSeparateTokens() = runTest {
        val phone = Phone()
        phone.loginOnline("secret-1")
        val second = loginOk.replace("\"user_id\":1001", "\"user_id\":1002").replace("sr334001", "sr334002")
            .replace(contractAccessToken, "access-of-1002")
        server.enqueue(api(200, second))
        assertTrue(phone.session.login("sr334002", "secret-2") is LoginOutcome.LoggedIn)
        assertEquals("access-of-1002", phone.session.currentAccessToken(Grant.FULL))
        assertEquals(contractAccessToken, phone.store.tokens(1001).accessToken)
        phone.logoutOnline()
        assertTrue(phone.store.tokens(1001).refreshToken != null)
        server.close()
        assertTrue(phone.session.login("sr334001", "secret-1") is LoginOutcome.LoggedIn)
    }

    /**
     * F-SYS-052: with user B signed in on the shared phone, A's rows still upload under A's own grant: the upload refresh
     * for A sends A's upload refresh token, stores A's new upload token under A only, and leaves B's session untouched.
     */
    @Test
    fun whileBIsSignedInAsRowsGetAnUploadTokenOfAOnly() = runTest {
        val phone = Phone()
        phone.loginOnline("secret-1")
        val uploadRefreshOfA = Json.parseToJsonElement(loginOk).jsonObject["upload_refresh_token"]!!.jsonPrimitive.content
        val second = loginOk.replace("\"user_id\":1001", "\"user_id\":1002").replace("sr334001", "sr334002")
            .replace(contractAccessToken, "access-of-1002").replace(uploadRefreshOfA, "u".repeat(43))
        server.enqueue(api(200, second))
        assertTrue(phone.session.login("sr334002", "secret-2") is LoginOutcome.LoggedIn)
        server.enqueue(api(200, tokenPair("upload-of-1001", "v".repeat(43))))
        assertTrue(phone.session.refresh(1001, Grant.UPLOAD))
        var refresh = server.takeRequest()
        while (refresh.url.encodedPath != "/v1/auth/refresh") refresh = server.takeRequest() // the two logins first
        val body = Json.parseToJsonElement(refresh.body!!.utf8()).jsonObject
        assertEquals("upload", body["grant"]!!.jsonPrimitive.content)
        assertEquals(uploadRefreshOfA, body["refresh_token"]!!.jsonPrimitive.content)
        assertEquals("upload-of-1001", phone.session.uploadAccessToken(1001))
        assertEquals(null, phone.session.uploadAccessToken(1002))
        assertEquals("access-of-1002", phone.session.currentAccessToken(Grant.FULL))
        assertEquals(1002L, (phone.session.state.value as SessionState.Active).user.userId)
    }

    @Test
    fun theDeviceUuidIsStableAndReplacedByEnrolment() {
        val id = DeviceIdentity(tmp.root)
        val first = id.deviceUuid
        assertEquals(first, DeviceIdentity(tmp.root).deviceUuid)
        id.setEnrolledUuid("6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10")
        assertEquals("6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10", DeviceIdentity(tmp.root).deviceUuid)
    }

    @Test
    fun cooldownPolicyDoublesAndCaps() {
        val p = OfflineUnlockPolicy()
        assertEquals(0, p.cooldownAfter(9))
        assertEquals(60_000, p.cooldownAfter(10))
        assertEquals(120_000, p.cooldownAfter(11))
        assertEquals(3_600_000, p.cooldownAfter(40))
        assertEquals(3_600_000, p.cooldownAfter(Int.MAX_VALUE))
    }

    /** F-SYS-003 (lead #4): a fifth phone is refused with 409 ERR_DEVICE_LIMIT_REACHED; nothing is stored, no session opens. */
    @Test
    fun aBindRefusedForTheDeviceLimitKeepsNothingAndNamesTheCode() = runTest {
        val phone = Phone()
        server.enqueue(api(409, problem(409, "ERR_DEVICE_LIMIT_REACHED")))
        assertEquals(BindOutcome.Failed("ERR_DEVICE_LIMIT_REACHED"), phone.session.bindDevice("bind.jwt", "১২৩৪", "x"))
        val request = server.takeRequest()
        assertEquals("/v1/auth/bind-device", request.url.encodedPath)
        assertEquals("Bearer bind.jwt", request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("1234", body["otp"]!!.jsonPrimitive.content) // Bengali digits normalised
        assertEquals(phone.identity.deviceUuid, body["device_uuid"]!!.jsonPrimitive.content)
        assertEquals(SessionState.LoggedOut, phone.session.state.value)
        assertEquals(null, phone.session.currentAccessToken(Grant.FULL))
    }

    @Test
    fun aBoundPhoneLogsInAndAnUnreachableServerSaysOffline() = runTest {
        val phone = Phone()
        server.enqueue(api(200, loginOk))
        val bound = phone.session.bindDevice("bind.jwt", "1234", "x")
        assertTrue(bound is BindOutcome.Bound)
        val active = phone.session.state.value as SessionState.Active
        assertEquals(contractAccessToken, phone.session.currentAccessToken(Grant.FULL)) // tokens stored as at login
        val expectedOrdinal = Json.parseToJsonElement(loginOk).jsonObject["device"]?.jsonObject?.get("bind_ordinal")?.jsonPrimitive?.content?.toIntOrNull()
        assertEquals(expectedOrdinal, active.user.bindOrdinal)
        server.close()
        assertEquals(BindOutcome.Failed(null, offline = true), Phone().session.bindDevice("bind.jwt", "1234", "x"))
    }

    /** Checker (bind round 1, finding 1): a temporary-password rep is bound by the 200; the screen must not say "failed". */
    @Test
    fun aBindAnsweredWithPasswordChangeIsNotAFailure() = runTest {
        val phone = Phone()
        val answer = Json.parseToJsonElement(loginOk).jsonObject.toMutableMap().apply {
            put("status", Json.parseToJsonElement("\"password_change_required\""))
            put("access_token", Json.parseToJsonElement("null")); put("refresh_token", Json.parseToJsonElement("null"))
        }
        server.enqueue(api(200, kotlinx.serialization.json.JsonObject(answer).toString()))
        assertEquals(BindOutcome.PasswordChangeRequired, phone.session.bindDevice("bind.jwt", "1234", "x"))
    }

    /** Checker (bind round 1): X-Device-Proof is signed over the s8.3 bind string of the normalised OTP. */
    @Test
    fun theBindCarriesTheDeviceProofOverTheNormalisedOtp() = runTest {
        val identity = DeviceIdentity(tmp.root)
        val signed = mutableListOf<String>()
        val client = AronApiClient(ApiOrigin.parse(server.url("/").toString(), true), AronApiClient.defaultOkHttp(), ClientIdentity("0.1.0+1") { identity.deviceUuid }, Holder())
        val now = 1_791_000_000_000L
        val auth = AuthApi(client, { str -> signed += str; "p".repeat(86) }) { now }
        server.enqueue(api(409, problem(409, "ERR_DEVICE_LIMIT_REACHED")))
        auth.bindDevice("bind.jwt", "1234")
        assertEquals(listOf(com.aktcl.aron.core.network.ProofStrings.bind(identity.deviceUuid, "1234", now)), signed)
        assertEquals("p".repeat(86), server.takeRequest().headers["X-Device-Proof"])
    }
}
