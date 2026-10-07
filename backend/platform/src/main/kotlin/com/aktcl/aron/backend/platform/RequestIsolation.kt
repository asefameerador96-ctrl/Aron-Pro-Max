package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.ProblemCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ThreadLocalRandom

/**
 * AUD-PERF-02: route handlers, the auth guard, stores and the reach resolver call JDBI, which blocks. Netty gives the
 * API one call thread per vCPU (one in the test account), so a single handler parked on the Hikari pool (up to 5 s in
 * an outage) would stall every other call, liveness included. Every call except the two health probes therefore runs
 * on [dispatcher], a bounded view of Dispatchers.IO sized to the connection pools, never on the engine's call threads,
 * and gets [timeoutMs] (docs/18 s4.3: 25 s, so the API answers before the edge's 60 s origin timeout).
 *
 * One interceptor covers every lane's routes, so a new route cannot forget it (RequestIsolationTest pins this).
 * The in-flight limiter is admission control (N-056, installed outside this one); this is not a second limiter.
 */
class RequestIsolation(parallelism: Int, val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    val parallelism: Int = parallelism.coerceAtLeast(2)
    val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(this.parallelism)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 25_000L

        /** One thread per pooled connection (write plus a separate read pool): more threads would only queue on Hikari. */
        fun forPools(writePoolMax: Int, readPoolMax: Int?): RequestIsolation = RequestIsolation(writePoolMax + (readPoolMax ?: 0))

        /** Liveness and readiness stay on the call threads: they never block (cached values, readiness pings on IO). */
        fun isProbe(path: String): Boolean = path == "/v1/health" || path == "/v1/health/ready"
    }
}

/** 503 ERR_SERVICE_UNAVAILABLE with a jittered Retry-After of 5..30 s, so refused phones do not all come back together. */
fun serviceUnavailable(detail: String): ApiProblem {
    val wait = ThreadLocalRandom.current().nextInt(5, 31)
    return ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, detail, retryAfterS = wait, headers = mapOf("Retry-After" to wait.toString()))
}

/**
 * Install after the platform (its StatusPages renders the timeout problem) and after admission control, so the call
 * holds its admission slot while it waits here. A call still running at the deadline is cancelled and answered 503
 * unless it has already started its response; a statement in flight ends at the role's statement_timeout (15 s).
 */
fun Application.installRequestIsolation(iso: RequestIsolation) {
    intercept(ApplicationCallPipeline.Plugins) {
        if (RequestIsolation.isProbe(call.request.path())) return@intercept proceed()
        try {
            withTimeout(iso.timeoutMs) { withContext(iso.dispatcher) { proceed() } }
        } catch (e: TimeoutCancellationException) {
            if (call.response.isCommitted) return@intercept
            throw serviceUnavailable("the request took too long; retry later")
        }
    }
}
