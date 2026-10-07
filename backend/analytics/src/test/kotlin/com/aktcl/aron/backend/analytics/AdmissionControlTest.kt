package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.installAronPlatform
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** N-056: a 3 x burst gets 429 with Retry-After instead of errors, nothing accepted is lost, dashboards are shed before ingest. */
class AdmissionControlTest {
    private fun app(control: AdmissionControl, work: AtomicInteger, block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            installAronPlatform(PlatformContext(config = RegistryDefaults(), generation = { "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10" }))
            installAdmissionControl(control)
            routing {
                route("/v1") {
                    post("/sync/batch") { delay(300); work.incrementAndGet(); call.respondText("{}") }
                    get("/dashboards/summary") { delay(300); call.respondText("{}") }
                    get("/health") { call.respondText("{}") }
                }
            }
        }
        block()
    }

    @Test
    fun aThreeTimesBurstIsAnsweredWith429NotErrorsAndAcceptedWorkIsNeverLost() {
        val control = AdmissionControl(ingestCapacity = 8, readCapacity = 4, ingestQueueWaitMs = 400)
        val processed = AtomicInteger()
        app(control, processed) {
            val ingest: List<HttpResponse>; val dash: List<HttpResponse>
            coroutineScope {
                val a = (1..24).map { async { client.post("/v1/sync/batch") } }       // 3 x the 8 slots
                val b = (1..12).map { async { client.get("/v1/dashboards/summary") } }
                ingest = a.awaitAll(); dash = b.awaitAll()
            }
            val all = ingest + dash
            assertTrue(all.none { it.status.value >= 500 }, "no server errors under burst")
            assertTrue(all.all { it.status == HttpStatusCode.OK || it.status == HttpStatusCode.TooManyRequests })
            val refused = all.filter { it.status == HttpStatusCode.TooManyRequests }
            assertTrue(refused.isNotEmpty() && refused.all { it.headers["Retry-After"]?.toIntOrNull() in 5..60 }, "every refusal carries Retry-After")
            assertTrue(refused.map { it.headers["Retry-After"] }.toSet().size > 1, "Retry-After is jittered")
            // Accepted ingest ran exactly once each: the handler count equals the number of 200s.
            assertEquals(ingest.count { it.status == HttpStatusCode.OK }, processed.get())
            // Dashboards shed before ingest does: a larger share of them was refused.
            val ingestRefused = ingest.count { it.status == HttpStatusCode.TooManyRequests }
            val dashRefused = dash.count { it.status == HttpStatusCode.TooManyRequests }
            assertTrue(ingest.count { it.status == HttpStatusCode.OK } >= 8, "the buffer admits at least the full capacity")
            assertTrue(dashRefused * ingest.size > ingestRefused * dash.size, "dashboards ($dashRefused of ${dash.size}) must be shed at a higher rate than ingest ($ingestRefused of ${ingest.size})")
            assertEquals(ingestRefused.toLong(), control.shed.getValue(LoadClass.INGEST).get())
        }
    }

    @Test
    fun readsAreShedAsSoonAsTheDatabasePoolHasWaitersAndOtherCallsAreNeverRefused() {
        var waiting = 0
        val control = AdmissionControl(ingestCapacity = 8, readCapacity = 4, dbWaiting = { waiting })
        app(control, AtomicInteger()) {
            assertEquals(HttpStatusCode.OK, client.get("/v1/dashboards/summary").status)
            waiting = 3
            val r = client.get("/v1/dashboards/summary")
            assertEquals(HttpStatusCode.TooManyRequests, r.status); assertTrue(r.headers["Retry-After"]!!.toInt() in 30..60)
            assertEquals(HttpStatusCode.OK, client.get("/v1/health").status)           // health is never shed
            assertEquals(HttpStatusCode.OK, client.post("/v1/sync/batch").status)     // ingest is still served while reads are shed
        }
    }

    @Test
    fun slotsAreReleasedWhenACallEnds() {
        val control = AdmissionControl(ingestCapacity = 1, readCapacity = 1, ingestQueueWaitMs = 0)
        app(control, AtomicInteger()) {
            repeat(5) { assertEquals(HttpStatusCode.OK, client.post("/v1/sync/batch").status) }
            repeat(5) { assertEquals(HttpStatusCode.OK, client.get("/v1/dashboards/summary").status) }
        }
    }

    // ---- checker (N-056) ----

    /** A variant spelling of the batch path that Ktor routing still delivers to the batch handler must be admitted as INGEST, not waved through as OTHER. */
    @Test
    fun checker_variantBatchPathsReachTheHandlerButBypassIngestAdmission() {
        val control = AdmissionControl(ingestCapacity = 1, readCapacity = 4, ingestQueueWaitMs = 0)
        val processed = AtomicInteger()
        val held = kotlinx.coroutines.runBlocking { control.admit(LoadClass.INGEST) }   // the only ingest slot is busy: every further batch must be refused
        val bypassed = mutableListOf<String>()
        app(control, processed) {
            for (p in listOf("/v1/sync/batch/", "/v1/sync/%62atch", "/v1//sync/batch", "/v1/sync/batch/.", "/v1/sync/./batch")) {
                val before = processed.get()
                client.post(p)
                if (processed.get() > before) bypassed += p
            }
        }
        held()
        assertTrue(bypassed.isEmpty(), "batch handler ran with no ingest slot free via: $bypassed")
    }

    /** The ingest buffer wait is a blocking Semaphore.tryAcquire inside a coroutine interceptor: it parks the carrier thread, so every other call on it (health included) stalls. */
    @Test
    fun checker_ingestBufferWaitBlocksTheThreadAndStallsHealthOnIt() {
        val control = AdmissionControl(ingestCapacity = 1, readCapacity = 4, ingestQueueWaitMs = 800)
        val held = kotlinx.coroutines.runBlocking { control.admit(LoadClass.INGEST) }
        val single = java.util.concurrent.Executors.newSingleThreadExecutor()
        val dispatcher = single.asCoroutineDispatcher()
        try {
            val healthLatencyMs = kotlinx.coroutines.runBlocking(dispatcher) {
                val waiter = launch { runCatching { control.admit(LoadClass.INGEST) } }   // a queued batch
                val t0 = System.nanoTime()
                val health = async { control.admit(LoadClass.OTHER)(); (System.nanoTime() - t0) / 1_000_000 }
                val ms = health.await(); waiter.join(); ms
            }
            assertTrue(healthLatencyMs < 200, "a /health on the same thread waited $healthLatencyMs ms behind a queued batch")
        } finally { held(); dispatcher.close() }
    }

    /** A failing pool probe must not turn the dashboards into 500s; it should fail open or shed with 429. */
    @Test
    fun checker_aThrowingDbWaitingProbeTurnsDashboardsInto500() {
        val control = AdmissionControl(ingestCapacity = 8, readCapacity = 4, dbWaiting = { throw IllegalStateException("pool not started") })
        app(control, AtomicInteger()) {
            val s = client.get("/v1/dashboards/summary").status
            assertTrue(s == HttpStatusCode.OK || s == HttpStatusCode.TooManyRequests, "dashboard answered $s")
        }
    }
}
