package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V0033 (AUD-DA-01): a consumer that reads below app.outbox_horizon() in (tx_id, id) order and stores its position sees
 * every committed event exactly once, even when transactions commit out of id order; dirty keys get a dead letter;
 * deprecated event versions are refused; the catalogue cannot be truncated.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxCommitOrderTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
    }

    @AfterAll
    fun tearDown() = db.close()

    private fun event(n: Int) =
        "INSERT INTO app.domain_event (event_type, aggregate_type, aggregate_id, business_date, payload) VALUES ('memo.created', 'memo', 'w$n', '2026-10-07', '{}')"

    /** One consumer poll: rows below the horizon after the stored position, in commit order; returns their aggregate ids. */
    private fun poll(): List<String> = db.connect().use { c ->
        c.autoCommit = false
        val rows = c.column(
            "SELECT e.aggregate_id || '|' || e.tx_id || '|' || e.id FROM app.domain_event e, app.event_consumer p " +
                "WHERE p.consumer = 'feed' AND e.tx_id < app.outbox_horizon() " +
                "AND (coalesce(e.tx_id, '0'::xid8), e.id) > (coalesce(p.last_tx_id, '0'::xid8), p.last_event_id) ORDER BY e.tx_id, e.id",
        ).filterNotNull()
        rows.lastOrNull()?.split("|")?.let { (_, tx, id) ->
            c.exec("UPDATE app.event_consumer SET last_tx_id = '$tx', last_event_id = $id, updated_at = now() WHERE consumer = 'feed'")
        }
        c.commit()
        rows.map { it.substringBefore("|") }
    }

    @Test
    fun eightWritersWithRandomCommitDelaysAreReadExactlyOnce() {
        db.connect().use { it.exec("INSERT INTO app.event_consumer (consumer) VALUES ('feed')") }
        val writers = 8
        val perWriter = 15
        val pool = Executors.newFixedThreadPool(writers + 1)
        val done = CountDownLatch(writers)
        val seen = mutableListOf<String>()
        repeat(writers) { w ->
            pool.submit {
                try {
                    db.connect().use { c ->
                        c.autoCommit = false
                        repeat(perWriter) { i ->
                            c.exec(event(w * 100 + i))
                            Thread.sleep(Random.nextLong(0, 15))           // hold the id while others commit later ids
                            c.commit()
                        }
                    }
                } finally { done.countDown() }
            }
        }
        while (!done.await(5, TimeUnit.MILLISECONDS)) seen += poll()
        pool.shutdown()
        // Drain with a bounded number of polls instead of a fixed three: on a loaded runner another session's open
        // transaction can hold the horizon back for a while after the writers finish (CI run 37613735145).
        var polls = 0
        while (seen.size < writers * perWriter && polls++ < 3000) {
            seen += poll()
            if (seen.size < writers * perWriter) Thread.sleep(20)
        }
        repeat(2) { seen += poll() }                                       // nothing may arrive twice after the drain
        assertEquals(writers * perWriter, seen.size, "every event once")
        assertEquals(seen.size, seen.toSet().size, "no event twice")
    }

    @Test
    fun anOpenTransactionHoldsTheHorizonBack() = db.connect().use { open ->
        open.autoCommit = false
        open.exec(event(9001))
        val tx = open.scalar("SELECT pg_current_xact_id()::text")!!
        db.connect().use { c ->
            assertEquals("t", c.scalar("SELECT app.outbox_horizon() <= '$tx'::xid8"))
            assertEquals("t", c.scalar("SELECT app.outbox_horizon_lag() >= interval '0'"))
        }
        open.rollback()
    }

    /** V0035: every SECURITY DEFINER function searches pg_temp last, so a caller's temporary object cannot shadow a relation. */
    @Test
    fun securityDefinerFunctionsSearchPgTempLast() = db.connect().use { c ->
        assertEquals(
            emptyList(),
            c.column("SELECT p.oid::regprocedure::text FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname IN ('app', 'dw') AND p.prosecdef " +
                "AND NOT EXISTS (SELECT 1 FROM unnest(p.proconfig) cfg WHERE cfg LIKE 'search_path=%' AND cfg LIKE '%, pg_temp')"),
        )
        // The reproduction of the V0033 check: a temporary view named like the catalog view is ignored.
        c.autoCommit = false
        try {
            c.exec("CREATE TEMP VIEW pg_stat_activity AS SELECT '1'::xid AS backend_xid, 0 AS pid, now() - interval '999 days' AS xact_start")
            assertEquals("t", c.scalar("SELECT app.outbox_horizon_lag() < interval '1 day'"))
        } finally {
            c.rollback()
        }
    }

    @Test
    fun dirtyKeysBackOffDieAndReviveWhenMarkedAgain() = db.connect().use { c ->
        c.exec("SELECT app.mark_dirty('route_day_agg', 1, '2026-10-07', 'test')")
        c.exec("UPDATE app.dirty_key SET attempts = 5, last_error = 'boom', not_before = now() + interval '1 hour', dead_at = now() WHERE subject_id = 1")
        assertEquals("route_day_agg|1", c.scalar("SELECT kind || '|' || dead_keys FROM app.v_dirty_key_dead"))
        c.exec("SELECT app.mark_dirty('route_day_agg', 1, '2026-10-07')")
        assertEquals("0|t|t|boom", c.scalar("SELECT concat_ws('|', attempts, not_before IS NULL, dead_at IS NULL, last_error) FROM app.dirty_key WHERE subject_id = 1"))
        assertEquals("23514", assertFailsWith<SQLException> { c.exec("UPDATE app.dirty_key SET attempts = -1") }.sqlState)
    }

    @Test
    fun deprecatedVersionsAreRefusedAndTheCatalogueCannotBeTruncated() = db.connect().use { c ->
        c.autoCommit = false
        try {
            c.exec("UPDATE app.domain_event_type SET deprecated_at = now() - interval '1 second' WHERE event_type = 'visit.closed' AND payload_version = 1")
            c.exec("SAVEPOINT s")
            val e = assertFailsWith<SQLException> {
                c.exec("INSERT INTO app.domain_event (event_type, aggregate_type, aggregate_id, business_date) VALUES ('visit.closed', 'visit', 'x', '2026-10-07')")
            }
            assertEquals("23514", e.sqlState)
            assertTrue(e.message!!.contains("deprecated"))
            c.exec("ROLLBACK TO SAVEPOINT s")
            assertEquals("42501", assertFailsWith<SQLException> { c.exec("TRUNCATE app.domain_event_type CASCADE") }.sqlState)
        } finally {
            c.rollback()
        }
    }
}
