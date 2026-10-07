package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F-SYS-059: UPDATE, DELETE and TRUNCATE on app.audit_log are rejected by the database; every write through
 * [AuditLog] adds a hash-chained row with actor, before and after; a tampered row is found by [AuditLog.verify].
 */
class AuditLogTest {
    private val admin = AronPrincipal(0, "admin1001", Role.ADMIN, 1, Audience.API, null, null, "web", emptyList(), false, listOf("pwd"), "j", Instant.EPOCH)

    @Test
    fun theLogIsAppendOnlyChainedAndVerifiable() {
        FreshDb.create().use { fresh ->
            val db = fresh.db
            val actor = db.jdbi.withHandle<Long, Exception> { h ->
                h.createQuery("INSERT INTO app.app_user (username, full_name, role, must_change_password) VALUES ('admin1001', 'Admin', 'ADMIN', false) RETURNING id").mapTo(Long::class.java).one()
            }
            val p = admin.copy(userId = actor)
            db.jdbi.useTransaction<Exception> { h ->
                AuditLog.write(h, p, "outlet", "17", "update", buildJsonObject { put("name", "Old") }, buildJsonObject { put("name", "নতুন দোকান") }, "renamed after a visit", null)
                AuditLog.write(h, p, "route", "3", "assign", null, buildJsonObject { put("user_id", 9) }, null, "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10", ipClass = "10.0.0.0/24")
                AuditLog.write(h, null, "job", "rollover", "run", null, JsonPrimitive("x\u0000y"), "x\u0000".repeat(300), "not-a-uuid", via = "job")
            }
            db.jdbi.useHandle<Exception> { h ->
                val rows = h.createQuery("SELECT chain_seq, prev_hash IS NULL, actor_username, after->>'name' FROM app.audit_log ORDER BY chain_seq")
                    .map { rs, _ -> listOf(rs.getLong(1), rs.getBoolean(2), rs.getString(3), rs.getString(4)) }.list()
                assertEquals(listOf(1L, 2L, 3L), rows.map { it[0] })
                assertEquals(listOf(true, false, false), rows.map { it[1] }, "each row links to the previous one")
                assertEquals("admin1001", rows[0][2]); assertEquals("নতুন দোকান", rows[0][3], "Bangla round trip")
                assertEquals(2, h.createQuery("SELECT count(*) FROM app.audit_log a JOIN app.audit_log b ON b.chain_seq = a.chain_seq + 1 WHERE b.prev_hash = a.row_hash").mapTo(Int::class.java).one(), "prev_hash is the previous row_hash")
                assertNull(AuditLog.verify(h), "the chain is intact")
                assertEquals(500, h.createQuery("SELECT length(reason) FROM app.audit_log WHERE chain_seq = 3").mapTo(Int::class.java).one())
                // The database refuses every change.
                for (sql in listOf("UPDATE app.audit_log SET reason = 'edited' WHERE chain_seq = 1", "DELETE FROM app.audit_log WHERE chain_seq = 3", "TRUNCATE app.audit_log")) {
                    assertFailsWith<Exception>(sql) { h.execute(sql) }
                }
                assertEquals(3, h.createQuery("SELECT count(*) FROM app.audit_log").mapTo(Int::class.java).one())
                // A row changed behind the triggers' back (a superuser) is found.
                h.execute("ALTER TABLE app.audit_log DISABLE TRIGGER audit_log_append_only")
                h.execute("UPDATE app.audit_log SET after = '{\"name\": \"Forged\"}' WHERE chain_seq = 1")
                h.execute("ALTER TABLE app.audit_log ENABLE TRIGGER audit_log_append_only")
                val bad = AuditLog.verify(h)
                assertTrue(bad != null && bad == h.createQuery("SELECT id FROM app.audit_log WHERE chain_seq = 1").mapTo(Long::class.java).one())
            }
        }
    }
}
