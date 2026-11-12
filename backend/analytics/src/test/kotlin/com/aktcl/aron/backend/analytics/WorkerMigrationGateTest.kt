package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Migrator
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
}
