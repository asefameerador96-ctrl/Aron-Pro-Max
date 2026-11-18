package com.aktcl.aron.backend.platform

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLTransientConnectionException
import java.sql.Statement
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The worker start-up gate (D-LOY-API-21): no schedule before one successful SELECT 1, quiet logs while waiting, never gives up. Virtual clock: no real sleep. */
class WorkerReadinessTest {
    private class Clock { var t = 0L }

    private fun dataSource(failUntilMs: () -> Boolean, attempts: IntArray, executed: MutableList<String>): DataSource {
        val stmt = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Statement::class.java)) { _, m, a ->
            when (m.name) { "execute" -> { executed += a!![0] as String; true }; else -> null }
        } as Statement
        val conn = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, m, _ ->
            when (m.name) { "createStatement" -> stmt; else -> null }
        } as Connection
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java)) { _, m, _ ->
            if (m.name == "getConnection") {
                attempts[0]++
                if (failUntilMs()) throw SQLTransientConnectionException("secret-host.example: connection refused user=aron", "08001")
                conn
            } else null
        } as DataSource
    }

    private fun gate(c: Clock, log: MutableList<Pair<WorkerReadiness.Level, String>>, slept: MutableList<Long>) =
        WorkerReadiness(nowMs = { c.t }, sleep = { slept += it; c.t += it }, sink = { l, m -> log += l to m })

    @Test
    fun noScheduleStartsBeforeTheFirstSuccessfulSelectOneAndTheWaitIsQuiet() {
        val c = Clock(); val log = mutableListOf<Pair<WorkerReadiness.Level, String>>(); val slept = mutableListOf<Long>(); val attempts = intArrayOf(0); val executed = mutableListOf<String>()
        val ds = dataSource({ attempts[0] <= 3 }, attempts, executed)   // fails three times, then succeeds
        val failed = gate(c, log, slept).await(ds)
        assertEquals(3, failed); assertEquals(4, attempts[0])
        assertEquals(listOf("SELECT 1"), executed, "exactly one statement, nothing else is read")
        assertEquals(listOf(500L, 1000L, 2000L), slept, "short back-off")
        assertEquals(listOf(WorkerReadiness.Level.INFO, WorkerReadiness.Level.INFO), log.map { it.first }, "INFO once when it starts waiting, INFO when ready; no WARN or ERROR within a minute")
        assertTrue(log.all { "class=SQLTransientConnectionException" in it.second || "reachable after" in it.second })
        assertFalse(log.any { "secret-host" in it.second || "user=aron" in it.second }, "the driver message (host, user) is never logged")
        assertTrue(log.first().second.contains("sqlstate=08001"))
    }

    @Test
    fun aLongOutageLogsOneWarnAMinuteThenOneErrorEveryFiveMinutesAndNeverGivesUp() {
        val c = Clock(); val log = mutableListOf<Pair<WorkerReadiness.Level, String>>(); val slept = mutableListOf<Long>(); val attempts = intArrayOf(0)
        val failed = gate(c, log, slept).await(dataSource({ c.t < 12 * 60_000 }, attempts, mutableListOf()))   // down for 12 virtual minutes
        assertTrue(failed > 100, "it kept retrying")
        val byLevel = log.groupBy { it.first }
        assertEquals(1, byLevel.getValue(WorkerReadiness.Level.INFO).size - 1, "one INFO at the start (the other INFO is the ready line)")
        val warns = byLevel.getValue(WorkerReadiness.Level.WARN).size
        assertTrue(warns in 3..4, "at most one WARN a minute and none once the ERRORs begin: $warns")
        assertEquals(2, byLevel.getValue(WorkerReadiness.Level.ERROR).size, "ERROR at about 5 and 10 minutes, none in between")
        assertEquals(WorkerReadiness.Level.INFO, log.last().first)
    }
}
