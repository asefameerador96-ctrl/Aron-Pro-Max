package com.aktcl.aron.backend.platform

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.request.path
import java.util.concurrent.atomic.AtomicInteger

/**
 * AUD-REL-07: graceful drain on deploy, scale-in or restart. When the engine starts to stop (SIGTERM through Ktor's
 * shutdown hook), [draining] turns readiness 503 at once so the platform stops routing here, and the stop waits up
 * to [maxWaitMs] for the calls in flight (a phone's sync batch mid-commit) before Netty's event loops shut down.
 * Netty's own grace (3 s quiet period per event group, 25 s cap) starts only after this wait, so the worst stop is
 * about 15 + 9 s, inside Container Apps' default 30 s termination grace. While draining, responses carry
 * `Connection: close` so keep-alive clients move to another replica.
 */
class Drain(val maxWaitMs: Long = DEFAULT_MAX_WAIT_MS) {
    private val inFlight = AtomicInteger()

    @Volatile
    var draining: Boolean = false
        private set

    val callsInFlight: Int get() = inFlight.get()

    internal fun enter() { inFlight.incrementAndGet() }
    internal fun exit() { inFlight.decrementAndGet() }

    /** Starts draining and blocks until no call is in flight or [maxWaitMs] passed; true when every call finished. */
    fun drain(): Boolean {
        draining = true
        val deadline = System.nanoTime() + maxWaitMs * 1_000_000
        while (inFlight.get() > 0) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(POLL_MS)
        }
        return true
    }

    companion object {
        const val DEFAULT_MAX_WAIT_MS = 15_000L
        private const val POLL_MS = 20L
    }
}

/** Counts every non-probe call while it runs and drains on ApplicationStopPreparing (raised before the engine stops). */
fun Application.installDrain(drain: Drain) {
    intercept(ApplicationCallPipeline.Plugins) {
        if (RequestIsolation.isProbe(call.request.path())) return@intercept proceed()
        drain.enter()
        try { proceed() } finally { drain.exit() }
    }
    sendPipeline.intercept(ApplicationSendPipeline.Before) {
        if (drain.draining && !call.response.isCommitted) call.response.headers.append("Connection", "close", safeOnly = false)
    }
    monitor.subscribe(ApplicationStopPreparing) {
        val done = drain.drain()
        if (!done) org.slf4j.LoggerFactory.getLogger("aron.main").warn("shutdown: {} call(s) still in flight after {} ms", drain.callsInFlight, drain.maxWaitMs)
    }
}
