package com.aktcl.aron.db

import org.junit.jupiter.api.AfterEach
import java.io.File
import java.sql.Connection
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D-DB-DIM-REKEY-02 (deploys #308/#309): the retried, lock-safe steps of V0144 v2, V0146 and V0147 under a concurrent
 * session (the AggregationWorker). The database is migrated to V0143, then the four files are run by hand, as a second
 * session, while a "worker" session holds locks:
 *  - V0144's lock block takes all its locks or none and sleeps holding nothing (the worker can still read dw.dim_geo while
 *    the migration waits for dw.dim_geo_version: the order inversion of the round-1 blocker),
 *  - V0146 retries a dimension whose rekey meets a lock timeout (5 s) and finishes after the lock is released,
 *  - V0147 skips a fact row another transaction holds and finishes the restamp after it is released.
 */
class MigrationLockSafetyTest {
    private lateinit var db: TestDatabase
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private val dir = File(System.getProperty("aron.migrations"))

    /** A throwaway database migrated to V0143 and seeded, then the named files run in order (the state the next step starts from). */
    private fun prepare(vararg before: String) {
        db = TestPostgres.createDatabase()
        db.flyway().let { org.flywaydb.core.Flyway.configure().configuration(it.configuration).target("143").load().migrate() }
        db.connect().use { SeedLoader.load(it) }
        before.forEach { runFile(it) }
    }

    @AfterEach
    fun tearDown() {
        pool.shutdownNow()
        if (::db.isInitialized) db.close()
    }

    private fun sql(prefix: String) = dir.listFiles()!!.single { it.name.startsWith(prefix) }.readText()

    /** Runs a migration file as one transaction on its own connection (as Flyway would), returning the elapsed milliseconds. */
    private fun runFile(prefix: String): Long {
        val t0 = System.nanoTime()
        db.connect().use { c ->
            c.autoCommit = false
            try { c.createStatement().use { it.execute(sql(prefix)) }; c.commit() } catch (e: Exception) { c.rollback(); throw e }
        }
        return (System.nanoTime() - t0) / 1_000_000
    }

    private fun <T> concurrently(block: () -> T): java.util.concurrent.Future<T> =
        pool.submit(Callable { block() })

    @Test
    fun v0144WaitsHoldingNothingThenFinishesAfterTheWorkerReleases() {
        prepare()
        db.connect().use { worker ->
            worker.autoCommit = false
            worker.exec("SELECT count(*) FROM dw.dim_geo_version")                    // the worker reads the version table first ...
            val migration = concurrently { runFile("V0144__") }
            // Wait until the migration has met the worker's lock and sleeps in its retry loop (a failed NOWAIT, then pg_sleep).
            val deadline = System.nanoTime() + 20_000_000_000L
            // (pg_stat_activity is a per-transaction snapshot, and the worker session is inside a transaction: clear it each time.)
            while (worker.also { it.exec("SELECT pg_stat_clear_snapshot()") }.scalar("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event = 'PgSleep' AND pid <> pg_backend_pid()") == "0") {
                check(System.nanoTime() < deadline) { "the migration never reached its retry sleep" }
                Thread.sleep(100)
            }
            // ... and then dw.dim_geo: it must not queue behind a half-taken lock set of the migration.
            worker.exec("SET LOCAL lock_timeout = '1s'")
            worker.exec("SELECT count(*) FROM dw.dim_geo")
            assertEquals(false, migration.isDone, "the migration is still waiting for dw.dim_geo_version")
            worker.commit()
            val ms = migration.get(30, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(ms >= 400, "it waited for the worker (at least one retry sleep): $ms ms")
        }
        db.connect().use { c -> assertEquals("1", c.scalar("SELECT count(*) FROM pg_proc WHERE proname = 'zone_house_follow'")) }
        runFile("V0145__")
    }

    @Test
    fun v0146RetriesARekeyThatMeetsALockTimeout() {
        prepare("V0144__", "V0145__")
        db.connect().use { worker ->
            worker.autoCommit = false
            // The rekey of dim_geo takes this advisory lock first; held here, it waits lock_timeout (5 s), fails 55P03, sleeps 5 s, retries.
            worker.exec("SELECT pg_advisory_xact_lock(hashtext('dw.rekey_dim_versions'), hashtext('dim_geo'))")
            val migration = concurrently { runFile("V0146__") }
            Thread.sleep(7_000)
            assertEquals(false, migration.isDone, "the rekey is retrying, not failed")
            worker.commit()
            val ms = migration.get(40, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(ms >= 5_000, "one lock timeout happened first: $ms ms")
        }
    }

    @Test
    fun v0147SkipsAHeldFactRowAndFinishesWhenItIsReleased() {
        prepare("V0144__", "V0145__", "V0146__")
        db.connect().use { c ->
            c.exec("""
                INSERT INTO dw.fact_memo (business_date, memo_client_uuid, memo_no, user_id, route_id, outlet_id, status, committed_at, line_count, gross_mtk,
                    offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, net_mtk, paid_mtk, due_mtk, is_credit, captured_offline, received_at, last_event_id, geo_key)
                SELECT app.dhaka_date(now()), gen_random_uuid(), 'T-LOCK', (SELECT min(id) FROM app.app_user), (SELECT min(id) FROM app.route),
                       (SELECT min(id) FROM app.outlet), 'active', now(), 1, 0, 0, 0, 0, 0, 0, 0, false, false, now(), 0, 987654""")
        }
        db.connect().use { worker ->
            worker.autoCommit = false
            worker.exec("SELECT 1 FROM dw.fact_memo WHERE memo_no = 'T-LOCK' FOR UPDATE")
            val migration = concurrently { runFile("V0147__") }
            Thread.sleep(7_000)
            assertEquals(false, migration.isDone, "the restamp skipped the held row and waits for it in its completeness loop")
            worker.commit()
            migration.get(30, java.util.concurrent.TimeUnit.SECONDS)
        }
        db.connect().use { c ->
            val route = c.scalar("SELECT route_id FROM dw.fact_memo WHERE memo_no = 'T-LOCK'")
            assertEquals(c.scalar("SELECT dw.geo_key_on($route, business_date)::text FROM dw.fact_memo WHERE memo_no = 'T-LOCK'"),
                c.scalar("SELECT geo_key::text FROM dw.fact_memo WHERE memo_no = 'T-LOCK'"), "the key follows the versions")
        }
    }
}
