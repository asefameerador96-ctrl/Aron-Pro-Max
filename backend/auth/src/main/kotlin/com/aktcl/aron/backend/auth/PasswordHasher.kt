package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.contract.ProblemCode
import de.mkammerer.argon2.Argon2Factory
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Argon2id password hashes in PHC string form (`$argon2id$v=19$m=...,t=...,p=...$salt$hash`). Server parameters:
 * m = 19 MiB, t = 2, p = 1 (OWASP minimum for Argon2id); verification reads the parameters from the stored string,
 * so seeded hashes with other parameters still verify.
 */
class PasswordHasher(private val memoryKiB: Int = 19 * 1024, private val iterations: Int = 2, private val parallelism: Int = 1) {
    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id, 16, 32)

    fun hash(password: String): String {
        val chars = password.toCharArray()
        try { return argon2.hash(iterations, memoryKiB, parallelism, chars) } finally { argon2.wipeArray(chars) }
    }

    fun verify(phc: String, password: String): Boolean {
        val chars = password.toCharArray()
        return try { argon2.verify(phc, chars) } catch (e: Exception) { false } finally { argon2.wipeArray(chars) }
    }

    /** A valid hash of a random secret: verified when the username is unknown so timing does not reveal it. */
    val dummyHash: String by lazy { hash("dummy-" + Random.nextLong()) }
}

/**
 * Bounds Argon2id work per replica so a login storm (8,500 phones at 07:00) cannot exhaust memory or starve the
 * event loop: at most [concurrency] hashes run at once on a dedicated pool (each takes ~19 MiB native memory),
 * at most [queueMax] callers wait, each for at most [maxWaitMs]. Everyone else gets 503 ERR_SERVICE_UNAVAILABLE
 * with a jittered Retry-After, so phones back off instead of piling up (docs/24 s3.6).
 */
class HashLimiter(
    val concurrency: Int = 4,
    val queueMax: Int = 32,
    private val maxWaitMs: Long = 3_000,
    private val retryAfterRange: IntRange = 2..10,
) : AutoCloseable {
    private val semaphore = Semaphore(concurrency)
    private val waiting = AtomicInteger(0)
    private val running = AtomicInteger(0)
    private val peak = AtomicInteger(0)
    private val pool: ExecutorCoroutineDispatcher =
        Executors.newFixedThreadPool(concurrency) { r -> Thread(r, "aron-argon2").apply { isDaemon = true } }.asCoroutineDispatcher()

    /** Highest number of hashes observed running at once (tests and metrics). */
    val peakConcurrent: Int get() = peak.get()

    suspend fun <T> run(block: () -> T): T {
        if (waiting.incrementAndGet() > queueMax + concurrency) {
            waiting.decrementAndGet()
            throw busy()
        }
        val acquired = try { withTimeoutOrNull(maxWaitMs) { semaphore.acquire(); true } ?: false } finally { waiting.decrementAndGet() }
        if (!acquired) throw busy()
        try {
            val n = running.incrementAndGet()
            peak.accumulateAndGet(n, ::maxOf)
            return withContext(pool) { block() }
        } finally {
            running.decrementAndGet()
            semaphore.release()
        }
    }

    private fun busy(): ApiProblem {
        val s = retryAfterRange.random()
        return ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "login capacity reached; retry later", retryAfterS = s, headers = mapOf("Retry-After" to s.toString()))
    }

    override fun close() = pool.close()
}
