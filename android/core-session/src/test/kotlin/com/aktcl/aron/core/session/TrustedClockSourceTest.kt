package com.aktcl.aron.core.session

import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.ResponseMeta
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalTime

/** F-SYS-049: with the phone clock two hours wrong, the business date and the 17:00 gate follow server time + elapsed. */
class TrustedClockSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private var elapsed = 5_000_000L
    private var boot = 41
    private var wall = 0L

    private fun clock(file: File = File(tmp.root, "anchors")) = TrustedClockSource(file, { boot }, { elapsed }, { wall })

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun meta(serverTime: String) = ResponseMeta(200, "r", serverTime, 318, null, null, null, null)

    @Test fun aPhoneTwoHoursBehindStillCrossesMidnightAndTheGateOnServerTime() {
        val c = clock()
        wall = ms("2026-10-05T08:58:00.000Z") // the phone says 14:58 Dhaka; the server says 16:58
        c.onApiResponse(meta("2026-10-05T10:58:00.000Z"))
        assertFalse(c.isAtOrAfterDhaka(LocalTime.of(17, 0)))
        elapsed += 3 * 60_000; wall += 3 * 60_000 // three minutes later: 17:01 trusted, 15:01 on the phone
        assertTrue(c.isAtOrAfterDhaka(LocalTime.of(17, 0)))
        assertEquals(2 * 3_600_000L, c.clockOffsetMs())

        elapsed += 7 * 3_600_000L + 30 * 60_000; wall += 7 * 3_600_000L + 30 * 60_000 // 00:31 Dhaka trusted, 22:31 on the phone
        assertEquals(LocalDate(2026, 10, 6), c.businessDate())
    }

    @Test fun aPhoneTwoHoursAheadDoesNotOpenTheGateEarly() {
        val c = clock()
        wall = ms("2026-10-05T12:30:00.000Z") // 18:30 on the phone
        c.onApiResponse(meta("2026-10-05T10:30:00.000Z")) // 16:30 in truth
        assertFalse(c.isAtOrAfterDhaka(LocalTime.of(17, 0)))
        assertEquals(ms("2026-10-05T10:30:00.000Z"), c.nowMs())
    }

    @Test fun anchorsSurviveAProcessDeathButNotAReboot() {
        wall = ms("2026-10-05T08:00:00.000Z")
        clock().onApiResponse(meta("2026-10-05T10:00:00.000Z"))
        elapsed += 60_000; wall += 60_000
        assertEquals(ms("2026-10-05T10:01:00.000Z"), clock().nowMs()) // a new process reads the stored anchor
        boot++; elapsed = 30_000 // reboot, no contact yet: wall clock, offset unknown
        assertEquals(wall, clock().nowMs())
        assertNull(clock().clockOffsetMs())
    }

    @Test fun atMostThreeRecentAnchorsTravelAndAGarbageHeaderIsIgnored() {
        val c = clock()
        repeat(5) { i -> elapsed += 1000; c.onApiResponse(meta("2026-10-05T10:00:0$i.000Z")) }
        c.onApiResponse(meta("not a time"))
        c.onApiResponse(ResponseMeta(200, null, null, null, null, null, null, null))
        assertEquals(3, c.recentAnchors().size)
        assertEquals(ms("2026-10-05T10:00:04.000Z"), c.recentAnchors().last().serverTimeMs)
    }

    @Test fun everyApiResponseThroughTheSessionComponentsFeedsTheClock() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(200).addHeader("X-Aron-Api", "1").addHeader("X-Server-Time", "2026-10-05T10:00:00.000Z").build())
        server.start()
        try {
            val components = SessionComponents(
                origin = ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true),
                appVersion = "1.0.3+10003", client = "app_sr", storageDir = tmp.root, cipher = JvmAesCipher(), verifier = JvmPasswordVerifier(),
            )
            assertTrue(components.syncApi.healthy())
            assertNotNull(components.trustedClock.clockOffsetMs())
            assertEquals(1, components.trustedClock.recentAnchors().size)
            assertTrue(components.clock === components.trustedClock)
        } finally {
            server.close()
        }
    }
}
