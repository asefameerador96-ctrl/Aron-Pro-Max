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

    private fun get(port: Int, path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(40)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun getAsync(port: Int, path: String): CompletableFuture<HttpResponse<String>> =
        http.sendAsync(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(40)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun timed(block: () -> HttpResponse<String>): Pair<HttpResponse<String>, Long> {
        val t0 = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - t0) / 1_000_000
    }

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

    @Test
    fun livenessAnswersWhileHandlersAreBlockedOnTheStarvedPool() {
        val w = outageWiring { wr ->
            // A handler that calls JDBI directly in the route lambda, as most routes do.
            get("/stall-probe/db") { call.respondText(wr.database!!.jdbi.withHandle<Int, Exception> { h -> h.createQuery("SELECT 1").mapTo(Int::class.java).one() }.toString()) }
        }
        assertTrue(w.isolation != null, "production wiring isolates requests")
        val port = start(w)
        val t0 = System.nanoTime()
        val blocked = (1..4).map { getAsync(port, "/v1/stall-probe/db") }
        Thread.sleep(300)   // the four handlers are now parked on the pool
        repeat(5) {
            val (r, ms) = timed { get(port, "/v1/health") }
            assertEquals(200, r.statusCode())
            assertTrue(ms < 200, "liveness took $ms ms while handlers were blocked on the pool")
        }
        val (ready, readyMs) = timed { get(port, "/v1/health/ready") }
        assertEquals(503, ready.statusCode())
        assertTrue(readyMs < 3_000, "readiness took $readyMs ms")
        blocked.forEach { f ->
            val r = f.get()
            assertEquals(503, r.statusCode(), r.body())
            assertTrue("ERR_SERVICE_UNAVAILABLE" in r.body())
            assertTrue(r.headers().firstValue("Retry-After").get().toInt() in 5..30)
        }
        // Each waits Hikari's 5 s side by side (after up to 10 s of cold config and generation loads, which also wait
        // 5 s each); on the call thread they would queue: 4 x 5 s plus those loads, 30 s and more.
        val allMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(allMs < 24_000, "the blocked calls took $allMs ms: they ran one after another")
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

    @Test
    fun aCallOverItsTimeoutIs503WithRetryAfter() {
        val port = start(
            plainWiring(RequestIsolation(4, timeoutMs = 300)) {
                get("/slow") { delay(5_000); call.respondText("late") }
                get("/blocking") { Thread.sleep(1_000); call.respondText("late") }
                get("/inner-timeout") { kotlinx.coroutines.withTimeout(10) { delay(1_000) }; call.respondText("never") }
                get("/fast") { call.respondText("ok") }
            },
        )
        val (r, ms) = timed { get(port, "/v1/slow") }
        assertEquals(503, r.statusCode(), r.body())
        assertTrue("ERR_SERVICE_UNAVAILABLE" in r.body())
        assertTrue(r.headers().firstValue("Retry-After").get().toInt() in 5..30)
        assertTrue(ms < 3_000, "timeout answered after $ms ms")
        assertEquals("ok", get(port, "/v1/fast").body())
        // A handler blocked in JDBC-like work cannot be interrupted: it answers when the blocking call ends (the timeout
        // plus one statement), and then with a whole 503, never a response cut after its headers.
        val (b, bms) = timed { get(port, "/v1/blocking") }
        assertEquals(503, b.statusCode(), b.body())
        assertTrue("ERR_SERVICE_UNAVAILABLE" in b.body())
        assertTrue(bms in 900..3_000, "blocking handler answered after $bms ms")
        // A handler's own inner timeout is a server error of that handler, not the request timeout.
        val inner = get(port, "/v1/inner-timeout")
        assertEquals(500, inner.statusCode(), inner.body())
    }

    @Test
    fun aStopWaitsForTheCallInFlightAndReadinessTurns503AtOnce() {
        val drain = Drain(maxWaitMs = 10_000)
        val w = plainWiring(RequestIsolation(4), drain) {
            get("/slow-commit") { Thread.sleep(1_500); call.respondText("committed") }
        }
        val port = ServerSocket(0).use { it.localPort }
        val s = apiServer(w, port, callThreads = 1)
        s.start(wait = false)
        val inFlight = getAsync(port, "/v1/slow-commit")
        Thread.sleep(300)
        assertEquals(1, drain.callsInFlight)
        val stopped = CompletableFuture.runAsync { s.stop(3_000, 25_000) }   // what Ktor's SIGTERM hook does
        Thread.sleep(200)
        assertTrue(drain.draining)
        val ready = runCatching { get(port, "/v1/health/ready").statusCode() }.getOrNull()
        assertTrue(ready == null || ready == 503, "readiness during drain was $ready")
        val r = inFlight.get()
        assertEquals(200, r.statusCode())
        assertEquals("committed", r.body())
        assertEquals("close", r.headers().firstValue("Connection").orElse(null), "a response finished while draining closes its connection")
        stopped.get()
        assertEquals(0, drain.callsInFlight)
    }

    @Test
    fun aDrainWithNothingInFlightReturnsAtOnce() {
        val d = Drain(maxWaitMs = 5_000)
        val t0 = System.nanoTime()
        assertTrue(d.drain(), "nothing in flight drains at once")
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 50)
    }
}
