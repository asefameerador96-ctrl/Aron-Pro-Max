package com.aktcl.aron.core.session

import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.core.common.DispatcherProvider
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.AuthApi
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.core.network.ResponseMeta
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalTime

/** Checker (refuter) for F-SYS-049 trusted time anchor. Each test is a reproducible defect. */
class CheckerF049Test {
    @get:Rule val tmp = TemporaryFolder()

    private var elapsed = 5_000_000L
    private var boot = 41
    private var wall = 0L

    private fun clock(file: File = File(tmp.root, "anchors")) = TrustedClockSource(file, { boot }, { elapsed }, { wall })
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun meta(serverTime: String) = ResponseMeta(200, "r", serverTime, 318, null, null, null, null)

    /**
     * D1: a slow response (server stamped X-Server-Time, then the reply sat on a 2G link) becomes the newest anchor and
     * moves trusted time BACKWARDS: the 17:00 gate that was open closes again. Server time + elapsed never over-estimates,
     * so the max over the boot's anchors (or a monotone high-water) is the correct estimate.
     */
    @Test fun aDelayedResponseMustNotMoveTrustedTimeBackwardsAndReCloseTheGate() {
        val c = clock()
        wall = ms("2026-10-05T09:00:05.000Z")
        c.onApiResponse(meta("2026-10-05T11:00:05.000Z")) // 17:00:05 Dhaka, fast reply
        assertTrue(c.isAtOrAfterDhaka(LocalTime.of(17, 0)))
        val before = c.nowMs()
        elapsed += 5_000; wall += 5_000
        c.onApiResponse(meta("2026-10-05T10:59:40.000Z")) // stamped 30 s ago, delivered now
        assertTrue("trusted time went backwards", c.nowMs() >= before)
        assertTrue("gate re-closed", c.isAtOrAfterDhaka(LocalTime.of(17, 0)))
    }

    /**
     * D2: where BOOT_COUNT is unavailable, create() passes 0 forever. After a reboot an old-boot anchor whose elapsed is
     * below the new elapsed is still applied, so trusted time = old server time + new uptime: wrong by the whole previous
     * uptime (here 10 h; the business date flips back a day).
     */
    @Test fun withoutBootCountAnOldBootAnchorMustNotBeAppliedAfterAReboot() {
        boot = 0
        elapsed = 1_000_000L
        wall = ms("2026-10-04T12:00:00.000Z")
        clock().onApiResponse(meta("2026-10-04T12:00:00.000Z"))
        // Phone ran 10 more hours, rebooted, has been up ~33 min; wall clock is right.
        elapsed = 2_000_000L
        wall = ms("2026-10-04T12:00:00.000Z") + 10 * 3_600_000L + 2_000_000L
        val c = clock()
        assertTrue("stale anchor applied: off by ${(wall - c.nowMs()) / 60_000} min", kotlin.math.abs(c.nowMs() - wall) < 10 * 60_000L)
    }

    /**
     * D3: anchors are ordered by (bootCount, elapsedMs). With BOOT_COUNT = 0 a fresh post-reboot anchor (small elapsed)
     * sorts before six old-boot ones and is dropped by takeLast(6): the phone can never anchor again until its uptime
     * passes the old one, and recentAnchors() sends the server only the old boot's anchors.
     */
    @Test fun withoutBootCountAFreshAnchorAfterARebootIsKeptAndSent() {
        boot = 0
        val c = clock()
        repeat(6) { i -> elapsed = 10_000_000L + i * 1000; c.onApiResponse(meta("2026-10-04T12:00:0$i.000Z")) }
        elapsed = 60_000L // rebooted
        c.onApiResponse(meta("2026-10-05T03:00:00.000Z"))
        assertTrue("fresh anchor dropped", c.recentAnchors().any { it.serverTimeMs == ms("2026-10-05T03:00:00.000Z") })
        assertEquals(ms("2026-10-05T03:00:00.000Z"), c.nowMs())
    }

    /** D4: one corrupt line in the anchors file throws inside load() and runCatching discards ALL anchors. */
    @Test fun oneGarbageLineMustNotDiscardTheValidAnchors() {
        val f = File(tmp.root, "anchors")
        f.writeText("41,${ms("2026-10-05T10:00:00.000Z")},5000000\n41,garbage,\n4x,1,2")
        wall = ms("2026-10-05T08:00:00.000Z")
        assertEquals(ms("2026-10-05T10:00:00.000Z"), clock(f).nowMs())
    }

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private class Holder : com.aktcl.aron.core.network.AccessTokenSource {
        var target: SessionRepository? = null
        override fun currentAccessToken(grant: Grant) = target?.currentAccessToken(grant)
        override suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: ProblemCode?) =
            target?.refreshAfterUnauthorized(grant, rejectedToken, code) ?: false
    }

    /**
     * D5 (regression of AC-04 offline unlock): lastOnlineLoginMs is now stamped on TRUSTED time. On a phone whose clock
     * is 2 h behind (the F-SYS-049 acceptance case), a reboot with no network drops the clock back to the wall clock,
     * which is 2 h before the stored login: offline unlock refuses CLOCK_INCONSISTENT and the SR cannot sell that day.
     * Before F-SYS-049 both values were wall time and the unlock worked.
     */
    @Test fun aPhoneTwoHoursBehindCanStillUnlockOfflineAfterARebootWithoutNetwork() = runTest {
        val server = MockWebServer(); server.start()
        val loginOk = ContractYaml.toJson(
            ContractYaml.node("paths", "/v1/auth/login", "post", "responses", "200", "content", "application/json", "examples", "phoneOk", "value"),
        ).toString()
        val tc = TrustedClockSource(File(tmp.root, "time-anchors"), { boot }, { elapsed }, { wall })
        val identity = DeviceIdentity(tmp.root)
        val holder = Holder()
        val api = AronApiClient(ApiOrigin.parse(server.url("/").toString(), true), AronApiClient.defaultOkHttp(),
            ClientIdentity("0.1.0+1") { identity.deviceUuid }, holder) { tc.onApiResponse(it) }
        val session = SessionRepository(AuthApi(api), SessionStore(File(tmp.root, "session"), JvmAesCipher()), JvmPasswordVerifier(),
            identity, "app_sr", tc, OfflineUnlockPolicy(), dispatchers).also { holder.target = it }

        wall = ms("2026-10-05T00:12:44.120Z") // phone 2 h behind
        server.enqueue(MockResponse.Builder().code(200).body(loginOk).addHeader("X-Aron-Api", "1")
            .addHeader("X-Server-Time", "2026-10-05T02:12:44.120Z").build())
        assertTrue(session.login("sr334001", "secret-1") is LoginOutcome.LoggedIn)
        server.close()
        session.logout()

        boot++; elapsed = 90_000L; wall += 20 * 60_000L // reboot 20 min later, offline
        val outcome = session.login("sr334001", "secret-1")
        assertFalse("refused: $outcome", outcome is LoginOutcome.OfflineUnavailable)
    }
}
