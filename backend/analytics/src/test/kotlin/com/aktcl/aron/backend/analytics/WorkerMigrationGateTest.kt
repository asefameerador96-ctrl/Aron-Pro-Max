package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Migrator
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The first aggregation pass waits for the migrate job of the same deploy (lane portal-loyalty-api, push A): no pass while a migration is pending, a failed check is a wait, not a crash. */
class WorkerMigrationGateTest {
    @Test
    fun aFullyMigratedDatabaseHasNothingPending() {
        val fresh = FreshDb.create()
        try { assertEquals(0, Migrator.pendingOrFailed(fresh.dataSource)) } finally { fresh.close() }
    }

    @Test
    fun pendingMigrationsHoldTheFirstPassAndTheAnswerIsCachedOnceSettled() {
        val fresh = FreshDb.create()
        try {
            val w = AggregationWorker(fresh.db)
            var pending = 3
            assertFalse(w.migrationsSettled { pending }, "3 pending: wait")
            pending = 1
            assertFalse(w.migrationsSettled { pending }, "1 pending: wait")
            pending = 0
            assertTrue(w.migrationsSettled { pending })
            assertTrue(w.migrationsSettled { error("not asked again once settled") })
        } finally { fresh.close() }
    }

    @Test
    fun aFailingCheckIsAWaitNotAnException() {
        val fresh = FreshDb.create()
        try { assertFalse(AggregationWorker(fresh.db).migrationsSettled { throw IllegalStateException("db down") }) } finally { fresh.close() }
    }

    @Test
    fun aPassOnAMigratedDatabaseRunsAndDoesNotThrow() {
        val fresh = FreshDb.create()
        try { AggregationWorker(fresh.db).pass() } finally { fresh.close() }
    }

    @Test
    fun aStuckWaitWarnsOncePerIntervalNotPerPassAndRecoversWhenMigrationsFinish() {
        val fresh = FreshDb.create()
        try {
            var now = Instant.parse("2026-01-01T00:00:00Z")
            val w = AggregationWorker(fresh.db, AronClock { now }, waitWarnEvery = Duration.ofMinutes(5))
            var pending = 2
            repeat(50) { assertFalse(w.migrationsSettled { pending }); now = now.plusSeconds(5) }   // 250 s of 5 s passes: under one interval
            assertEquals(0, w.waitWarns, "no WARN inside the first interval")
            repeat(120) { assertFalse(w.migrationsSettled { pending }); now = now.plusSeconds(5) }  // up to 850 s in all
            assertEquals(2, w.waitWarns, "one WARN per 5 minutes (at 300 s and 600 s), not one per pass")
            pending = 0
            assertTrue(w.migrationsSettled { pending }, "the worker resumes the moment nothing is pending")
        } finally { fresh.close() }
    }
}
