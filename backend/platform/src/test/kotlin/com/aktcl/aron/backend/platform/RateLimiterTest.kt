package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.ProblemCode
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    private val now = AtomicReference(Instant.parse("2026-10-05T01:00:10Z"))
    private val clock = AronClock { now.get() }

    @Test
    fun limitsPerKeyAndResetsWithTheWindow() {
        val rl = RateLimiter(3, 60, clock)
        repeat(3) { assertTrue(rl.tryAcquire("d:a").allowed) }
        val denied = rl.tryAcquire("d:a")
        assertFalse(denied.allowed)
        assertEquals(0, denied.remaining)
        assertEquals(50, denied.resetS)
        assertTrue(rl.tryAcquire("d:b").allowed, "another device is not affected")
        now.set(now.get().plusSeconds(50))
        assertTrue(rl.tryAcquire("d:a").allowed, "new window")
    }

    @Test
    fun problemCarriesRetryAfterAndRateLimitHeaders() {
        val rl = RateLimiter(1, 60, clock)
        rl.tryAcquire("k")
        val p = rl.tryAcquire("k").toProblem()
        assertEquals(ProblemCode.ERR_RATE_LIMITED, p.code)
        assertEquals(429, p.status)
        assertEquals("50", p.headers["Retry-After"])
        assertEquals("1", p.headers["RateLimit-Limit"])
        assertEquals("0", p.headers["RateLimit-Remaining"])
        assertEquals("50", p.headers["RateLimit-Reset"])
        assertEquals(50, p.retryAfterS)
    }

    @Test
    fun memoryStaysBoundedUnderAFloodOfKeys() {
        val rl = RateLimiter(10, 60, clock, maxKeys = 1_000)
        repeat(50_000) { rl.tryAcquire("d:$it") }
        assertTrue(rl.size() <= 1_000, "held ${rl.size()} keys")
    }
}
