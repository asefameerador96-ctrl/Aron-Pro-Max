package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.contract.ProblemCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** What a request is worth under pressure (docs/24 s3.6: dashboards and reports are shed first, the sync batch last). */
enum class LoadClass { INGEST, READ, OTHER }

/**
 * Admission control and backpressure (N-056): a bounded, short buffer in front of the sync batch, and an immediate shed of dashboards and reports
 * whenever ingest is under pressure or the database pool has callers waiting. A refused request is `429` with `Retry-After` and jitter, answered before
 * any work or write, so an accepted batch is never half-done and a phone simply retries later (the batch is idempotent by client uuid anyway).
 *
 * @param ingestCapacity batches in flight per replica (cfg.api.inflight_batches_per_replica, default 64)
 * @param readCapacity concurrent dashboard and report requests per replica
 * @param ingestQueueWaitMs how long a batch may wait for a slot before it is refused (the buffer)
 * @param readShedAtIngestFraction reads are refused once this share of the ingest slots is busy
 * @param dbWaiting callers currently waiting for a database connection (Hikari `threadsAwaitingConnection`); reads are shed when above 0
 */
class AdmissionControl(
    val ingestCapacity: Int = 64, val readCapacity: Int = 16, val ingestQueueWaitMs: Long = 750, val readShedAtIngestFraction: Double = 0.5,
    private val dbWaiting: () -> Int = { 0 },
) {
    private val ingest = Semaphore(ingestCapacity, true)
    private val reads = Semaphore(readCapacity, false)
    val admitted = mapOf(LoadClass.INGEST to AtomicLong(), LoadClass.READ to AtomicLong(), LoadClass.OTHER to AtomicLong())
    val shed = mapOf(LoadClass.INGEST to AtomicLong(), LoadClass.READ to AtomicLong(), LoadClass.OTHER to AtomicLong())

    fun classify(method: String, path: String): LoadClass = when {
        method == "POST" && path == "/v1/sync/batch" -> LoadClass.INGEST
        path.startsWith("/v1/dashboards") || path.startsWith("/v1/reports") || path.startsWith("/v1/report-exports") || path == "/v1/app/home" || path.startsWith("/v1/team/") -> LoadClass.READ
        else -> LoadClass.OTHER
    }

    /** Returns a release function when admitted; throws the 429 problem when not. */
    fun admit(cls: LoadClass): () -> Unit {
        when (cls) {
            LoadClass.OTHER -> { admitted.getValue(cls).incrementAndGet(); return {} }
            LoadClass.INGEST -> {
                val ok = ingest.tryAcquire() || (ingestQueueWaitMs > 0 && ingest.tryAcquire(ingestQueueWaitMs, TimeUnit.MILLISECONDS))
                if (!ok) throw refuse(cls, 5, 60)
                admitted.getValue(cls).incrementAndGet()
                return { ingest.release() }
            }
            LoadClass.READ -> {
                val ingestBusy = ingestCapacity - ingest.availablePermits()
                if (ingestBusy >= ingestCapacity * readShedAtIngestFraction || dbWaiting() > 0 || !reads.tryAcquire()) throw refuse(cls, 30, 60)
                admitted.getValue(cls).incrementAndGet()
                return { reads.release() }
            }
        }
    }

    private fun refuse(cls: LoadClass, minS: Int, maxS: Int): ApiProblem {
        shed.getValue(cls).incrementAndGet()
        val retry = ThreadLocalRandom.current().nextInt(minS, maxS + 1)   // jitter: phones and browsers that were refused together do not return together
        return ApiProblem(ProblemCode.ERR_RATE_LIMITED, "the service is busy; retry after $retry s", retryAfterS = retry, headers = mapOf("Retry-After" to retry.toString()))
    }
}

/** Wraps every call: admitted calls hold their slot until the response is complete. Install after the platform's error handling. */
fun Application.installAdmissionControl(control: AdmissionControl) {
    intercept(ApplicationCallPipeline.Plugins) {
        val release = control.admit(control.classify(call.request.httpMethod.value, call.request.path()))
        try { proceed() } finally { release() }
    }
}
