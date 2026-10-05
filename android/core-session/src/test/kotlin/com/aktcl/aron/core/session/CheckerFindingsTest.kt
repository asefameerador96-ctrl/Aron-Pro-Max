package com.aktcl.aron.core.session

import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.core.common.DispatcherProvider
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.network.AccessTokenSource
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.AuthApi
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.Grant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Reproductions written by the independent checker of N-001 (each failed on 4769b75) plus the regression tests of the fixes. */
class CheckerFindingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    /** Wall clock and monotonic clock move independently, as on a phone whose user changes the date. */
    private class SplitClock(var wall: Long = 1_759_630_364_120L, var elapsed: Long = 5_000_000L) : WallClock {
        override fun nowMs() = wall
        override fun elapsedRealtimeMs() = elapsed
    }

    private val clock = SplitClock()
    private val cipher = JvmAesCipher()
    private val verifier = JvmPasswordVerifier()
    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }
    private val loginOk: String by lazy {
        ContractYaml.toJson(ContractYaml.node("paths", "/v1/auth/login", "post", "responses", "200", "content", "application/json", "examples", "phoneOk", "value")).toString()
    }

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private inner class Phone {
        val identity = DeviceIdentity(tmp.root)
        private val holder = object : AccessTokenSource {
            var target: SessionRepository? = null
            override fun currentAccessToken(grant: Grant) = target?.currentAccessToken(grant)
            override suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: ProblemCode?) =
                target?.refreshAfterUnauthorized(grant, rejectedToken, code) ?: false
        }
        val api = AronApiClient(ApiOrigin.parse(server.url("/").toString(), true), AronApiClient.defaultOkHttp(), ClientIdentity("0.1.0+1") { identity.deviceUuid }, holder)
        val store = SessionStore(File(tmp.root, "session"), cipher)
        val session = SessionRepository(AuthApi(api), store, verifier, identity, "app_sr", clock, OfflineUnlockPolicy(), dispatchers).also { holder.target = it }
    }

    private fun api(code: Int, body: String) = MockResponse.Builder().code(code).body(body)
        .addHeader("X-Aron-Api", "1").addHeader("X-Server-Time", "2026-10-05T02:12:44.120Z")

    private fun tokenPair(access: String, refresh: String) =
        """{"access_token":"$access","access_expires_at":"2026-10-05T04:12:44.120Z","refresh_token":"$refresh","refresh_expires_at":"2027-01-02T00:00:00.000Z","scope_version":8,"server_time":"2026-10-05T03:12:44.120Z"}"""

    private suspend fun Phone.loginOnlineThenLogoutAndGoOffline() {
        server.enqueue(api(200, loginOk).build())
        assertTrue(session.login("sr334001", "secret-1") is LoginOutcome.LoggedIn)
        server.enqueue(api(204, "").build())
        session.logout()
        server.close()
    }

    /**
     * docs/24 s8.1: offline unlock only for 7 days since the last online login. Rolling the phone's date back to before
     * the last online login keeps the verifier valid forever (age is negative, so `ageMs > 7 days` never trips).
     */
    @Test
    fun rollingTheClockBackDoesNotReopenAnExpiredOfflineUnlock() = runBlocking {
        val phone = Phone()
        phone.loginOnlineThenLogoutAndGoOffline()
        val loginAt = clock.wall
        clock.wall = loginAt + 30L * 86_400_000L
        clock.elapsed += 30L * 86_400_000L
        assertEquals(OfflineRefusal.EXPIRED, (phone.session.login("sr334001", "secret-1") as LoginOutcome.OfflineUnavailable).refusal)
        // The SR sets the date back to the day before the last online login.
        clock.wall = loginAt - 86_400_000L
        val outcome = phone.session.login("sr334001", "secret-1")
        assertFalse("offline unlock reopened by moving the clock back: $outcome", outcome is LoginOutcome.LoggedIn)
    }

    /** The cool-down after 10 offline failures must not end because the wall clock is moved forward (WallClock's own KDoc). */
    @Test
    fun movingTheClockForwardDoesNotEndTheOfflineCooldown() = runBlocking {
        val phone = Phone()
        phone.loginOnlineThenLogoutAndGoOffline()
        repeat(10) { phone.session.login("sr334001", "nope") }
        // Only the wall clock moves (Settings > Date & time); no real time has passed.
        clock.wall += 2 * 3_600_000L
        val outcome = phone.session.login("sr334001", "secret-1")
        assertTrue("cool-down bypassed by moving the clock forward: $outcome",
            outcome is LoginOutcome.OfflineUnavailable && outcome.refusal == OfflineRefusal.COOLDOWN)
    }

    /** The offline verifier (salt and hash) must not reach logs through toString of the state or the login outcome. */
    @Test
    fun theOfflineVerifierNeverAppearsInToString() {
        val p = UserProfile(1001, "sr334001", "Testing Banani", "SR", null, "bn", verifier = "SALT:HASH-SECRET", lastOnlineLoginMs = 0)
        assertFalse(LoginOutcome.LoggedIn(p, UnlockMode.ONLINE).toString().contains("HASH-SECRET"))
        assertFalse(SessionState.Active(p, UnlockMode.ONLINE, false).toString().contains("HASH-SECRET"))
    }

    /**
     * Logout and refresh hold different mutexes. A refresh in flight when the user logs out writes back its stale
     * snapshot plus the rotated pair, so the full grant the user just ended reappears in tokens/u1001.bin.
     */
    @Test
    fun aRefreshInFlightDuringLogoutDoesNotResurrectTheFullGrant() = runBlocking {
        val phone = Phone()
        server.enqueue(api(200, loginOk).build())
        assertTrue(phone.session.login("sr334001", "secret-1") is LoginOutcome.LoggedIn)
        server.enqueue(api(200, tokenPair("access-2", "r".repeat(43))).headersDelay(1, TimeUnit.SECONDS).build())
        server.enqueue(api(204, "").build())
        val refresh = async(Dispatchers.Default) { phone.session.refresh(1001, Grant.FULL) }
        server.takeRequest() // login
        assertEquals("/v1/auth/refresh", server.takeRequest().url.encodedPath) // refresh is now in flight
        phone.session.logout()
        assertNull(phone.store.tokens(1001).refreshToken)
        refresh.await()
        val after = phone.store.tokens(1001)
        assertNull("full refresh token back after logout", after.refreshToken)
        assertNull("full access token back after logout", after.accessToken)
    }

    private fun problem(status: Int, code: String) =
        """{"type":"urn:aron:problem:x","title":"t","status":$status,"code":"$code","request_id":"6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10"}"""

    @Test
    fun aDateBeforeTheLastOnlineLoginIsRefusedAsAWrongClock() = runBlocking {
        val phone = Phone()
        phone.loginOnlineThenLogoutAndGoOffline()
        clock.wall -= 2 * 86_400_000L
        assertEquals(OfflineRefusal.CLOCK_INCONSISTENT, (phone.session.login("sr334001", "secret-1") as LoginOutcome.OfflineUnavailable).refusal)
        clock.wall += 2 * 86_400_000L - 5 * 60_000L // five minutes of NTP drift is tolerated
        assertTrue(phone.session.login("sr334001", "secret-1") is LoginOutcome.LoggedIn)
    }

    @Test
    fun aCooldownEndsAfterARebootOnlyWhenBothClocksAgree() = runBlocking {
        val phone = Phone()
        phone.loginOnlineThenLogoutAndGoOffline()
        repeat(10) { phone.session.login("sr334001", "nope") }
        clock.elapsed = 1_000 // reboot: the monotonic clock restarted
        assertEquals(OfflineRefusal.COOLDOWN, (phone.session.login("sr334001", "secret-1") as LoginOutcome.OfflineUnavailable).refusal)
        clock.wall += 61_000 // the wall-clock end alone is not enough after a reboot...
        assertEquals(OfflineRefusal.COOLDOWN, (phone.session.login("sr334001", "secret-1") as LoginOutcome.OfflineUnavailable).refusal)
        clock.elapsed = 61_000 // ...the full cool-down must also have run since boot
        assertTrue(phone.session.login("sr334001", "secret-1") is LoginOutcome.LoggedIn)
    }

    @Test
    fun onlyFamilyEndingCodesDropTheRefreshToken() = runBlocking {
        val phone = Phone()
        server.enqueue(api(200, loginOk).build())
        phone.session.login("sr334001", "secret-1")
        val original = phone.store.tokens(1001).refreshToken
        for (code in listOf(401 to "ERR_DEVICE_PROOF_INVALID", 403 to "ERR_DEVICE_SUSPENDED", 403 to "ERR_AUTH_ACCOUNT_LOCKED")) {
            server.enqueue(api(code.first, problem(code.first, code.second)).build())
            assertFalse(phone.session.refresh(1001, Grant.FULL))
            assertEquals(code.second, original, phone.store.tokens(1001).refreshToken)
            assertFalse((phone.session.state.value as SessionState.Active).reauthRequired)
        }
        server.enqueue(api(403, problem(403, "ERR_AUTH_USER_DISABLED")).build())
        assertFalse(phone.session.refresh(1001, Grant.UPLOAD))
        assertTrue("upload grant survives user disable", phone.store.tokens(1001).uploadRefreshToken != null)
        server.enqueue(api(401, problem(401, "ERR_PASSWORD_CHANGED")).build())
        assertFalse(phone.session.refresh(1001, Grant.FULL))
        assertNull(phone.store.tokens(1001).refreshToken)
        assertTrue((phone.session.state.value as SessionState.Active).reauthRequired)
    }

    @Test
    fun aLogin401ThatIsNotAWrongPasswordIsNotShownAsOne() = runBlocking {
        val phone = Phone()
        server.enqueue(api(401, problem(401, "ERR_DEVICE_PROOF_INVALID")).build())
        assertEquals(LoginOutcome.Refused("ERR_DEVICE_PROOF_INVALID", null), phone.session.login("sr334001", "x"))
    }

    // ---- second re-check (b35af37) ----
    @Test
    fun rollbackWithoutAnInterveningAttemptDoesNotReopenAnExpiredUnlock() = runBlocking {
        val phone = Phone()
        phone.loginOnlineThenLogoutAndGoOffline()
        val loginAt = clock.wall
        clock.elapsed += 30L * 86_400_000L // 30 real days, phone on, app untouched
        clock.wall = loginAt + 86_400_000L // date set to the day after the login
        val outcome = phone.session.login("sr334001", "secret-1")
        assertFalse("expired offline unlock reopened by a date rollback: $outcome", outcome is LoginOutcome.LoggedIn)
    }
    @Test
    fun rebootPlusClockForwardDoesNotEndTheCooldown() = runBlocking {
        val phone = Phone()
        phone.loginOnlineThenLogoutAndGoOffline()
        repeat(16) { phone.session.login("sr334001", "nope") } // cool-down now at the 1 h cap
        clock.elapsed = 30_000 // rebooted 30 s ago
        clock.wall += 3_600_000L + 60_000L // date moved forward by an hour
        val outcome = phone.session.login("sr334001", "secret-1")
        assertTrue("cool-down bypassed by reboot + clock forward: $outcome",
            outcome is LoginOutcome.OfflineUnavailable && outcome.refusal == OfflineRefusal.COOLDOWN)
    }
    @Test
    fun passwordChangedDoesNotDropTheUploadGrant() = runBlocking {
        val phone = Phone()
        server.enqueue(api(200, loginOk).build())
        phone.session.login("sr334001", "secret-1")
        val upload = phone.store.tokens(1001).uploadRefreshToken
        assertTrue(upload != null)
        server.enqueue(api(401, problem(401, "ERR_PASSWORD_CHANGED")).build())
        phone.session.refresh(1001, Grant.UPLOAD)
        assertEquals("upload grant dropped on password change", upload, phone.store.tokens(1001).uploadRefreshToken)
    }
}
