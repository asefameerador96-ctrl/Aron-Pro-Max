package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Drain
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.RequestIsolation
import com.aktcl.aron.backend.platform.Settings
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AUD-PERF-02 and AUD-REL-07 on a real Netty engine with ONE call thread (production runs one per vCPU, 1 vCPU in the
 * test account): a handler blocked on JDBC must not stall liveness, every route runs on the bounded request
 * dispatcher, a call over its timeout is 503 with Retry-After, and a stop waits for the calls in flight.
 */
class RequestIsolationTest {
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val closers = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() {
        servers.forEach { runCatching { it.stop(0, 1_000) } }
        closers.forEach { runCatching { it.close() } }
    }

    private fun start(w: Wiring): Int {
        val port = ServerSocket(0).use { it.localPort }
        val s = apiServer(w, port, callThreads = 1)
        s.start(wait = false)
        servers += s
        return port
    }

    private fun get(port: Int, path: String, timeoutS: Long = 40): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(timeoutS)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun getAsync(port: Int, path: String): CompletableFuture<HttpResponse<String>> =
        http.sendAsync(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(40)).GET().build(), HttpResponse.BodyHandlers.ofString())

    /** The production wiring against a port that refuses connections: every pool borrow waits Hikari's 5 s. */
    private fun outageWiring(extra: Route.(Wiring) -> Unit): Wiring {
        val port = ServerSocket(0).use { it.localPort }
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        val w = Wiring.production(
            Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to "jdbc:postgresql://127.0.0.1:$port/aron?user=aron&password=x&connectTimeout=1", "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)),
            clock,
        )
        w.database?.let { closers += it }
        return w.copyWith(mount = { w.mount(this); extra(w) })
    }

    private fun Wiring.copyWith(
        mount: Route.() -> Unit = this.mount,
        isolation: RequestIsolation? = this.isolation,
        drain: Drain = this.drain,
    ) = Wiring(clock, config, database, generation, build, mount, frontDoorId, admission, cachedGeneration, isolation, drain)

    private fun plainWiring(isolation: RequestIsolation, drain: Drain = Drain(), routes: Route.() -> Unit) =
        Wiring(clock, RegistryDefaults(), null, { "8b1c5f8e-3c55-4f1f-9d0a-0d1f6a2b7c11" }, "test-build", routes, isolation = isolation, drain = drain)

    /**
     * Deterministic (no sleeps, no time budgets): four handlers block their thread the way JDBC does on a starved pool
     * (a latch stands in for Hikari's wait). With isolation all four are inside at once and liveness still answers;
     * without it the one call thread holds the first handler, the others never start, and liveness never answers.
     */
    @Test
    fun livenessAnswersWhileHandlersAreBlockedOnTheStarvedPool() {
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val port = start(
            plainWiring(RequestIsolation(6)) {
                get("/stall-probe") { entered.countDown(); release.await(60, TimeUnit.SECONDS); call.respondText("done") }
            },
        )
        val blocked = (1..4).map { getAsync(port, "/v1/stall-probe") }
        try {
            // Hang guards only: a correct server reaches these at once.
            assertTrue(entered.await(30, TimeUnit.SECONDS), "${4 - entered.count} of 4 handlers started: they queued on the call thread")
            repeat(5) { assertEquals(200, get(port, "/v1/health", timeoutS = 15).statusCode(), "liveness while 4 handlers block") }
        } finally {
            release.countDown()
        }
        blocked.forEach { assertEquals("done", it.get(60, TimeUnit.SECONDS).body()) }
    }

    @Test
    fun theProductionWiringIsolatesRequests() {
        val w = outageWiring { }
        val iso = w.isolation
        assertTrue(iso != null, "production wiring isolates requests")
        assertEquals(RequestIsolation.DEFAULT_TIMEOUT_MS, iso.timeoutMs)
    }

    @Test
    fun everyRouteRunsOnTheRequestDispatcherAndProbesDoNot() {
        val iso = RequestIsolation(4)
        val port = start(
            plainWiring(iso) {
                get("/where") { call.respondText((currentCoroutineContext()[ContinuationInterceptor] === iso.dispatcher).toString()) }
            },
        )
        assertEquals("true", get(port, "/v1/where").body())
        assertEquals(200, get(port, "/v1/health").statusCode())
        assertTrue(!RequestIsolation.isProbe("/v1/healthy") && RequestIsolation.isProbe("/v1/health/ready"))
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    @Test
    fun aCallOverItsTimeoutIs503WithRetryAfter() {
        val cancelled = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val port = start(
            plainWiring(RequestIsolation(4, timeoutMs = 300)) {
                get("/slow") { delay(120_000); call.respondText("late") }
                // Blocks its thread like a JDBC call; signals when the request deadline cancels it, and only then unblocks.
                get("/blocking") {
                    currentCoroutineContext()[kotlinx.coroutines.Job]!!.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cancelled.countDown() }
                    unblock.await(60, TimeUnit.SECONDS)
                    call.respondText("late")
                }
                get("/inner-timeout") { kotlinx.coroutines.withTimeout(10) { delay(120_000) }; call.respondText("never") }
                get("/fast") { call.respondText("ok") }
            },
        )
        val r = get(port, "/v1/slow")
        assertEquals(503, r.statusCode(), r.body())
        assertTrue("ERR_SERVICE_UNAVAILABLE" in r.body())
        assertTrue(r.headers().firstValue("Retry-After").get().toInt() in 5..30)
        assertEquals("ok", get(port, "/v1/fast").body())
        // A handler blocked in JDBC-like work cannot be interrupted: it answers when the blocking call ends (the timeout
        // plus one statement), and then with a whole 503, never a response cut after its headers.
        val b = getAsync(port, "/v1/blocking")
        assertTrue(cancelled.await(30, TimeUnit.SECONDS), "the deadline never cancelled the blocked call")
        unblock.countDown()
        val br = b.get(60, TimeUnit.SECONDS)
        assertEquals(503, br.statusCode(), br.body())
        assertTrue("ERR_SERVICE_UNAVAILABLE" in br.body())
        // A handler's own inner timeout is a server error of that handler, not the request timeout.
        val inner = get(port, "/v1/inner-timeout")
        assertEquals(500, inner.statusCode(), inner.body())
    }

    @Test
    fun aStopWaitsForTheCallInFlightAndReadinessTurns503AtOnce() {
        val drain = Drain(maxWaitMs = 30_000)
        val entered = CountDownLatch(1)
        val commit = CountDownLatch(1)
        val w = plainWiring(RequestIsolation(4), drain) {
            get("/slow-commit") { entered.countDown(); commit.await(60, TimeUnit.SECONDS); call.respondText("committed") }
        }
        val port = ServerSocket(0).use { it.localPort }
        val s = apiServer(w, port, callThreads = 1)
        s.start(wait = false)
        val inFlight = getAsync(port, "/v1/slow-commit")
        assertTrue(entered.await(30, TimeUnit.SECONDS))
        assertEquals(1, drain.callsInFlight)
        val stopped = CompletableFuture.runAsync { s.stop(3_000, 25_000) }   // what Ktor's SIGTERM hook does
        awaitTrue { drain.draining }
        val ready = runCatching { get(port, "/v1/health/ready", timeoutS = 15).statusCode() }.getOrNull()
        assertTrue(ready == null || ready == 503, "readiness during drain was $ready")
        assertTrue(!stopped.isDone, "the stop waits for the call in flight")
        commit.countDown()
        val r = inFlight.get(60, TimeUnit.SECONDS)
        assertEquals(200, r.statusCode())
        assertEquals("committed", r.body())
        assertEquals("close", r.headers().firstValue("Connection").orElse(null), "a response finished while draining closes its connection")
        stopped.get(60, TimeUnit.SECONDS)
        assertEquals(0, drain.callsInFlight)
    }

    @Test
    fun aDrainWithNothingInFlightReturnsAtOnce() {
        assertTrue(Drain(maxWaitMs = 60_000).drain(), "nothing in flight drains at once")
    }

    /** Polls a condition that a correct server reaches at once; the bound is a hang guard, not a time budget. */
    private fun awaitTrue(cond: () -> Boolean) {
        val until = System.nanoTime() + 30_000_000_000L
        while (!cond()) {
            check(System.nanoTime() < until) { "condition not reached" }
            Thread.sleep(5)
        }
    }
}
