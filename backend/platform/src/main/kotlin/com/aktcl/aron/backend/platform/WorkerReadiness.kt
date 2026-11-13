package com.aktcl.aron.backend.platform

import org.slf4j.LoggerFactory
import java.sql.SQLException
import javax.sql.DataSource

/**
 * The worker starts its schedules only after ONE successful `SELECT 1` on its pool (D-LOY-API-21; evidence: the ops diagnosis of revisions --0000205 and --0000206, where the first aggregation pass,
 * initial delay 0, asked Hikari for a connection about 15 s after container start, before the new replica could reach PostgreSQL, and logged an ERROR once per revision).
 *
 * Retries with a short back-off ([firstDelayMs] doubling to [maxDelayMs]). While the database is not reachable: INFO once at the first failure, then at most one WARN per minute (class and SQLState only,
 * never the message, which can carry a host or user), and once [errorAfterMs] has passed one ERROR per [errorEveryMs]. It never gives up and never exits. It reads nothing but `SELECT 1`: no Flyway, no schema.
 * A connection failure in a pass AFTER readiness is not handled here: it stays the job's own ERROR.
 */
class WorkerReadiness(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val sink: (Level, String) -> Unit = { l, m -> val log = LoggerFactory.getLogger(WorkerReadiness::class.java); when (l) { Level.INFO -> log.info(m); Level.WARN -> log.warn(m); Level.ERROR -> log.error(m) } },
    private val firstDelayMs: Long = 500,
    private val maxDelayMs: Long = 5_000,
    private val warnEveryMs: Long = 60_000,
    private val errorAfterMs: Long = 5 * 60_000,
    private val errorEveryMs: Long = 5 * 60_000,
) {
    enum class Level { INFO, WARN, ERROR }

    /** Blocks until `SELECT 1` succeeds on [ds]; returns the number of failed attempts. */
    fun await(ds: DataSource): Int {
        val started = nowMs()
        var failures = 0
        var delay = firstDelayMs
        var lastWarn = Long.MIN_VALUE
        var lastError = Long.MIN_VALUE
        while (true) {
            val failure = try {
                ds.connection.use { c -> c.createStatement().use { s -> s.execute("SELECT 1") } }
                null
            } catch (e: Exception) {
                e
            }
            if (failure == null) {
                if (failures > 0) sink(Level.INFO, "database reachable after $failures failed attempt(s) in ${nowMs() - started} ms; starting the worker schedules")
                return failures
            }
            failures++
            val now = nowMs()
            val what = describe(failure)
            when {
                failures == 1 -> sink(Level.INFO, "database not reachable yet ($what); the worker retries and starts its schedules once it answers")
                now - started >= errorAfterMs -> if (lastError == Long.MIN_VALUE || now - lastError >= errorEveryMs) { lastError = now; sink(Level.ERROR, "database still not reachable after ${(now - started) / 1000} s ($what); the worker keeps retrying") }
                lastWarn == Long.MIN_VALUE || now - lastWarn >= warnEveryMs -> { lastWarn = now; sink(Level.WARN, "database not reachable yet ($what)") }
            }
            if (failures == 1) lastWarn = now   // the INFO counts as the first notice; the first WARN comes a minute later
            sleep(delay)
            delay = minOf(delay * 2, maxDelayMs)
        }
    }

    private fun describe(e: Throwable): String {
        val sql = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
        return "class=${e.javaClass.simpleName}" + (sql?.sqlState?.let { " sqlstate=$it" } ?: "")
    }
}
