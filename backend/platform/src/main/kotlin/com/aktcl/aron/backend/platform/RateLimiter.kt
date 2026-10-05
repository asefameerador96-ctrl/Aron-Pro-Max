package com.aktcl.aron.backend.platform

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Outcome of one counted request. */
data class RateDecision(val allowed: Boolean, val limit: Int, val remaining: Int, val resetS: Int)

/**
 * In-memory fixed-window counter per key (device, user, username), per replica (docs/24 s3.6, D24-58). Keys are never
 * IP addresses: carrier NAT puts thousands of phones behind one address. Memory is bounded: when more than
 * [maxKeys] keys are held, expired windows are dropped first and then the oldest windows, so a flood of distinct
 * keys can never exhaust the heap.
 */
class RateLimiter(
    val limit: Int,
    val windowS: Int,
    private val clock: AronClock = AronClock.SYSTEM,
    private val maxKeys: Int = 200_000,
) {
    private class Window(val startMs: Long) { val count = AtomicInteger(0) }

    private val windows = ConcurrentHashMap<String, Window>()

    fun tryAcquire(key: String): RateDecision {
        val nowMs = clock.now().toEpochMilli()
        val windowMs = windowS * 1000L
        val start = nowMs - Math.floorMod(nowMs, windowMs)
        if (windows.size >= maxKeys) evict(start)
        val w = windows.compute(key) { _, old -> if (old == null || old.startMs != start) Window(start) else old }!!
        val n = w.count.incrementAndGet()
        val resetS = ((start + windowMs - nowMs + 999) / 1000).toInt().coerceAtLeast(1)
        return RateDecision(n <= limit, limit, (limit - n).coerceAtLeast(0), resetS)
    }

    /** Counts without consuming (lockout checks peek before deciding). */
    fun count(key: String): Int {
        val nowMs = clock.now().toEpochMilli()
        val windowMs = windowS * 1000L
        val start = nowMs - Math.floorMod(nowMs, windowMs)
        val w = windows[key] ?: return 0
        return if (w.startMs == start) w.count.get() else 0
    }

    fun size(): Int = windows.size

    /**
     * Drops expired windows first; if the map is still full, drops the least-used keys that are not limited, so a
     * flood of new keys can never hand a key that is already over its limit a fresh quota.
     */
    private fun evict(currentStart: Long) {
        windows.entries.removeIf { it.value.startMs != currentStart }
        if (windows.size < maxKeys) return
        val target = windows.size - maxKeys / 2
        windows.entries.filter { it.value.count.get() <= limit }.sortedBy { it.value.count.get() }.take(target)
            .forEach { windows.remove(it.key, it.value) }
    }
}

/** 429 ERR_RATE_LIMITED with Retry-After and the RateLimit-* headers of the contract. */
fun RateDecision.toProblem(detail: String = "Too many requests"): ApiProblem = ApiProblem(
    code = com.aktcl.aron.contract.ProblemCode.ERR_RATE_LIMITED,
    detail = detail,
    retryAfterS = resetS,
    headers = mapOf(
        "Retry-After" to resetS.toString(),
        "RateLimit-Limit" to limit.toString(),
        "RateLimit-Remaining" to remaining.toString(),
        "RateLimit-Reset" to resetS.toString(),
    ),
)
