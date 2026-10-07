package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Independent checker for F-SYS-059 (T1): tries to refute the append-only, hash-chained audit log. */
class AuditCheckerTest {
    private val fresh = FreshDb.create()
    private val db = fresh.db
    private val admin = AronPrincipal(0, "admin1001", Role.ADMIN, 1, Audience.API, null, null, "web", emptyList(), false, listOf("pwd"), "j", Instant.EPOCH)

    @AfterTest fun close() = fresh.close()

    private fun actor(): AronPrincipal {
        val id = db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("INSERT INTO app.app_user (username, full_name, role, must_change_password) VALUES ('admin1001', 'Admin', 'ADMIN', false) RETURNING id").mapTo(Long::class.java).one()
        }
        return admin.copy(userId = id)
    }

    private fun write(h: Handle, p: AronPrincipal?, n: Int) =
        AuditLog.write(h, p, "outlet", n.toString(), "update", buildJsonObject { put("n", n) }, buildJsonObject { put("n", n + 1) }, "r$n", null)

    private fun verify(): Long? = db.jdbi.withHandle<Long?, Exception> { AuditLog.verify(it) }
    private fun seqs(): List<Long> = db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT chain_seq FROM app.audit_log ORDER BY chain_seq").mapTo(Long::class.java).list() }

    @Test
    fun rolledBackWriterLeavesNoGapAndNoFalseBreak() {
        val p = actor()
        db.jdbi.useTransaction<Exception> { write(it, p, 1) }
        runCatching { db.jdbi.useTransaction<Exception> { h -> write(h, p, 2); error("business write fails after the audit insert") } }
        db.jdbi.useTransaction<Exception> { write(it, p, 3) }
        assertEquals(listOf(1L, 2L), seqs())
        assertNull(verify())
    }

    @Test
    fun concurrentWritersWithRandomRollbacksKeepALinearChain() {
        val p = actor()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val committed = java.util.concurrent.atomic.AtomicInteger()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(8) { t ->
            pool.submit {
                start.await()
                val rnd = Random(t)
                repeat(30) { i ->
                    try {
                        db.jdbi.useTransaction<Exception> { h ->
                            write(h, p, t * 1000 + i)
                            if (rnd.nextInt(3) == 0) write(h, p, t * 1000 + i + 500) // two rows in one transaction
                            if (rnd.nextInt(4) == 0) throw IllegalStateException("rollback")
                        }
                        committed.incrementAndGet()
                    } catch (e: IllegalStateException) { /* expected rollback */ } catch (e: Throwable) { errors.add(e) }
                }
            }
        }
        start.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS))
        assertTrue(errors.isEmpty(), "writers failed: ${errors.map { it.message }}")
        val s = seqs()
        assertEquals((1L..s.size.toLong()).toList(), s, "chain_seq is gapless")
        assertNull(verify())
    }

    @Test
    fun multiRowInsertInOneStatementChainsEveryRow() {
        db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.audit_log (via, entity, entity_id, action, row_hash) SELECT 'job', 'x', g::text, 'a', '\\x0000000000000000000000000000000000000000000000000000000000000000'::bytea FROM generate_series(1, 5) g")
        }
        assertEquals((1L..5L).toList(), seqs())
        assertNull(verify())
    }

    @Test
    fun sessionSettingsDoNotChangeTheHash() {
        val p = actor()
        val settings = listOf(
            "SET TimeZone = 'America/New_York'; SET DateStyle = 'ISO, DMY'; SET extra_float_digits = -15; SET IntervalStyle = 'sql_standard'",
            "SET TimeZone = 'Pacific/Kiritimati'; SET DateStyle = 'ISO, YMD'; SET extra_float_digits = 3; SET lc_numeric = 'C'",
            "SET TimeZone = 'UTC'; SET DateStyle = 'ISO, MDY'; SET extra_float_digits = 1; SET bytea_output = 'escape'",
        )
        settings.forEachIndexed { i, s ->
            db.jdbi.useTransaction<Exception> { h ->
                h.execute(s)
                AuditLog.write(h, p, "price", "$i", "update",
                    buildJsonObject { put("z", 0.1); put("a", 1e-7); put("m", 12345678901234.567); put("big", Long.MAX_VALUE); put("neg0", -0.0) },
                    buildJsonObject { put("b", buildJsonArray { add(JsonPrimitive(1.10)); add(JsonPrimitive("নতুন")) }); put("a", JsonPrimitive(3.0e20)) },
                    "r", "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10", ipClass = "10.0.0.0/24")
            }
        }
        settings.forEach { s ->
            db.jdbi.useTransaction<Exception> { h -> h.execute(s); assertNull(AuditLog.verify(h), "verify under: $s") }
        }
    }

    @Test
    fun noApplicationRoleCanChangeOrDisableTheLog() {
        val p = actor()
        db.jdbi.useTransaction<Exception> { write(it, p, 1) }
        val attacks = listOf(
            "UPDATE app.audit_log SET reason = 'x'",
            "DELETE FROM app.audit_log",
            "TRUNCATE app.audit_log",
            "ALTER TABLE app.audit_log DISABLE TRIGGER audit_log_append_only",
            "ALTER TABLE app.audit_log DISABLE TRIGGER ALL",
            "CREATE TRIGGER zz BEFORE INSERT ON app.audit_log FOR EACH ROW EXECUTE FUNCTION app.deny_mutation()",
            "CREATE RULE r AS ON INSERT TO app.audit_log DO INSTEAD NOTHING",
            "SET session_replication_role = replica",
            "DROP TRIGGER audit_log_append_only ON app.audit_log",
            "CREATE OR REPLACE FUNCTION app.deny_mutation() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'",
            "CREATE OR REPLACE FUNCTION app.audit_verify() RETURNS bigint LANGUAGE sql AS 'SELECT NULL::bigint'",
            "ALTER TABLE app.audit_log ALTER COLUMN reason SET DEFAULT 'x'",
        )
        val leaks = mutableListOf<String>()
        for (role in listOf("api_rw", "worker_rw", "jobs_rw", "web_ro", "bi_reader")) {
            for (sql in attacks) {
                val ok = db.jdbi.withHandle<Boolean, Exception> { h ->
                    h.begin()
                    try { h.execute("SET LOCAL ROLE $role"); h.execute(sql); true } catch (e: Exception) { false } finally { h.rollback() }
                }
                if (ok) leaks += "$role: $sql"
            }
        }
        assertTrue(leaks.isEmpty(), "application roles could: $leaks")
        assertNull(verify())
    }

    @Test
    fun apiRoleCanWriteThroughTheWriter() {
        val p = actor()
        db.jdbi.useHandle<Exception> { h ->
            h.useTransaction<Exception> { t -> t.execute("SET LOCAL ROLE api_rw"); write(t, p, 1); write(t, p, 2) }
            h.useTransaction<Exception> { t -> t.execute("SET LOCAL ROLE api_rw"); assertNull(AuditLog.verify(t)) }
        }
    }

    @Test
    fun everyRoleWireIsAValidActorRole() {
        val p = actor()
        db.jdbi.useTransaction<Exception> { h -> Role.entries.forEachIndexed { i, r -> AuditLog.write(h, p.copy(role = r), "x", "$i", "a", null, null, null, null) } }
        assertNull(verify())
    }

    @Test
    fun hostileValuesNeverFailTheWriteAndRoundTrip() {
        val p = actor()
        val big = "x".repeat(5_000_000)
        db.jdbi.useTransaction<Exception> { h ->
            AuditLog.write(h, p, "e".repeat(100), "i".repeat(100), "a".repeat(100), JsonPrimitive("a\u0000b"), buildJsonObject { put("k\u0000", "v\u0000"); put("big", big) },
                "\u0000".repeat(600), "not-a-uuid", ipClass = "i".repeat(100))
        }
        assertNull(verify())
    }

    @Test
    fun aLooseUuidRequestIdDoesNotFailTheBusinessWrite() {
        // KDoc: "an audit row never makes its write fail". java.util.UUID.fromString accepts "1-1-1-1-1"; PostgreSQL does not.
        val p = actor()
        db.jdbi.useTransaction<Exception> { h -> AuditLog.write(h, p, "outlet", "1", "update", null, null, null, "1-1-1-1-1") }
        assertEquals(1, seqs().size)
    }

    @Test
    fun aLiteralBackslashU0000InBeforeOrAfterIsStoredFaithfully() {
        // A value that contains the six characters \u0000 (a backslash, not a NUL) must not be rewritten.
        val p = actor()
        val text = "C:\\u0000dir"
        db.jdbi.useTransaction<Exception> { h -> AuditLog.write(h, p, "outlet", "1", "update", null, buildJsonObject { put("path", text) }, null, null) }
        val stored = db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT after->>'path' FROM app.audit_log").mapTo(String::class.java).one() }
        assertEquals(text, stored, "the audit 'after' must be exactly what was written")
    }

    @Test
    fun aWebAdminRowWithoutAnActorIsRefused() {
        // s8.6: every web write and admin action is written with its actor. A via='web' row with no actor is not an audit row.
        try {
            db.jdbi.useTransaction<Exception> { h -> AuditLog.write(h, null, "outlet", "1", "update", null, null, null, null, via = "web") }
        } catch (e: Exception) { return }
        fail("a via=web audit row with no actor (actor_user_id, actor_username, actor_role all null) was accepted by the writer and the database")
    }

    @Test
    fun repeatableReadWriterAfterAConcurrentCommitFails() {
        // Documented in V0010: a REPEATABLE READ writer whose snapshot misses a concurrent row gets a unique violation (no fork).
        // The concurrent writer runs on another thread: Jdbi reuses the open handle for a nested call on the same thread.
        val p = actor()
        db.jdbi.useHandle<Exception> { a ->
            a.begin(); a.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ"); assertEquals(0, a.createQuery("SELECT count(*) FROM app.audit_log").mapTo(Int::class.java).one())
            val other = Thread { db.jdbi.useTransaction<Exception> { write(it, p, 1) } }; other.start(); other.join()
            val r = runCatching { write(a, p, 2) }
            if (r.isSuccess) a.commit() else a.rollback()
            assertTrue(r.isFailure, "a stale-snapshot writer must be refused")
        }
        assertEquals(listOf(1L), seqs())
        assertNull(verify())
    }
}
