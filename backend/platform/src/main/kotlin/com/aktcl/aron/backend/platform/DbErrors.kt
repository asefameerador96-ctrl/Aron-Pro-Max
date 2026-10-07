package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.ProblemCode
import org.jdbi.v3.core.Handle
import java.sql.SQLException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException
import java.util.concurrent.ThreadLocalRandom

/**
 * Database and dependency failures that are not the request's fault (AUD-REL-02, docs/18 s4.3, s5.3): a pool timeout,
 * a lost or refused connection, a failover, a serialization failure, a deadlock, a lock or statement timeout. They are
 * answered 503 ERR_SERVICE_UNAVAILABLE with a jittered Retry-After, never 500 ERR_INTERNAL, because the phone bisects
 * its batch and counts a family failure on a 500 (docs/17) but simply waits on a 503.
 */
object DbErrors {
    /** SQLSTATEs that are transient: connection class 08, resources class 53, admin shutdown and recovery 57P01..57P05,
     * serialization 40001, deadlock 40P01, lock not available 55P03, statement cancelled (timeout) 57014. */
    private val STATES = setOf("40001", "40P01", "55P03", "57014", "57P01", "57P02", "57P03", "57P04", "57P05")

    fun isTransient(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(16).any { t ->
        when (t) {
            is SQLTransientException, is SQLRecoverableException -> true // includes Hikari's SQLTransientConnectionException
            is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException -> true
            is SQLException -> t.sqlState?.let { s -> s in STATES || s.startsWith("08") || s.startsWith("53") } ?: false
            else -> false
        }
    }

    /** A jittered Retry-After in seconds, 5..30 (spreads the reconnect of every phone after a failover). */
    fun retryAfterS(): Int = ThreadLocalRandom.current().nextInt(5, 31)

    fun unavailable(detail: String = "the database is unavailable, retry later"): ApiProblem {
        val s = retryAfterS()
        return ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, detail, retryAfterS = s, headers = mapOf("Retry-After" to s.toString()))
    }
}

/**
 * One transaction with a bounded in-request retry of transient failures (AUD-REL-02, docs/18 s4.3): at most [attempts]
 * tries, 20..100 ms jitter between them, no new try after [budgetMs] in total. Only for idempotent bodies (every
 * attempt runs in a fresh transaction; a failed one is rolled back whole). A non-transient failure is thrown at once.
 */
fun <T> Database.retryingTx(attempts: Int = 3, budgetMs: Long = 10_000, body: (Handle) -> T): T {
    val start = System.nanoTime()
    var n = 0
    while (true) {
        n++
        try {
            return jdbi.inTransaction<T, Exception> { h -> body(h) }
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            if (!DbErrors.isTransient(e) || n >= attempts || elapsedMs >= budgetMs) throw e
            Thread.sleep(ThreadLocalRandom.current().nextLong(20, 101))
        }
    }
}
