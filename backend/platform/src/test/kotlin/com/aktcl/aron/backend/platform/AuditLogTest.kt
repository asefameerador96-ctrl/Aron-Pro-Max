package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun personalTextIsRedactedAtEveryDepthAndCoordinatesOutsideAnOutletPin() {
        // D-AUDIT-PII-01: objects in objects, arrays of objects, arrays in arrays; names in any case; null stays null; a
        // personal member holding an object or array is replaced whole; look-alike names are left to the read mask.
        val image = Json.parseToJsonElement(
            """{"full_name":"Rahim","Phone":"01711111111","EMAIL":null,"name":"Shop A","lat":23.7,"lng":90.4,"n":1,
               "scope":{"owner_name":"Karim","address":{"line":"Mirpur 10"},"deep":[{"nid":"1234567890","tin":"55","ok":true},[{"contact_number":"017"}]]},
               "items":[{"pda_contact_no":"0199","dep_name":"Depot boss","trade_license":"TL-9","provisional_lat":23.1,"provisional_lng":90.1}],
               "owner_phone":"01700000000","home_lat":23.5,"phone_memo_uuid":"m-1"}""",
        )
        val r = AuditLog.redact("user", image).jsonObject
        val R = AuditLog.REDACTED
        assertEquals(R, r.s("full_name")); assertEquals(R, r.s("Phone")); assertEquals(JsonNull, r["EMAIL"])
        assertEquals("Shop A", r.s("name")); assertEquals("1", r.s("n"))
        assertEquals(R, r.s("lat")); assertEquals(R, r.s("lng"))
        val scope = r["scope"]!!.jsonObject
        assertEquals(R, scope.s("owner_name")); assertEquals(R, scope.s("address"))
        val deep = scope["deep"]!!.jsonArray
        assertEquals(R, deep[0].jsonObject.s("nid")); assertEquals(R, deep[0].jsonObject.s("tin")); assertEquals("true", deep[0].jsonObject.s("ok"))
        assertEquals(R, deep[1].jsonArray[0].jsonObject.s("contact_number"))
        val item = r["items"]!!.jsonArray[0].jsonObject
        for (k in listOf("pda_contact_no", "dep_name", "trade_license", "provisional_lat", "provisional_lng")) assertEquals(R, item.s(k), k)
        // Exact names only (the narrow list): these stay in clear in the row and are masked by listAudit.
        assertEquals("01700000000", r.s("owner_phone")); assertEquals("23.5", r.s("home_lat")); assertEquals("m-1", r.s("phone_memo_uuid"))
        // An outlet pin keeps its coordinates at any depth (pin moves, location history); its personal text is still redacted.
        val outlet = AuditLog.redact("outlet", image).jsonObject
        assertEquals("23.7", outlet.s("lat")); assertEquals("90.4", outlet.s("lng")); assertEquals("23.1", outlet["items"]!!.jsonArray[0].jsonObject.s("provisional_lat"))
        assertEquals(R, outlet.s("full_name")); assertEquals(R, outlet["scope"]!!.jsonObject.s("owner_name"))
        // A scalar image (cfg_change status) passes through.
        assertEquals(JsonPrimitive("applied"), AuditLog.redact("cfg_change", JsonPrimitive("applied")))
    }

    @Test
    fun theChainVerifiesAcrossOldClearRowsAndNewRedactedRows() {
        FreshDb.create().use { fresh ->
            val db = fresh.db
            val actor = db.jdbi.withHandle<Long, Exception> { h ->
                h.createQuery("INSERT INTO app.app_user (username, full_name, role, must_change_password) VALUES ('admin1001', 'Admin', 'ADMIN', false) RETURNING id").mapTo(Long::class.java).one()
            }
            val p = admin.copy(userId = actor)
            val img = buildJsonObject { put("full_name", "Rahim Uddin"); put("phone", "01711111111"); put("role", "SR") }
            db.jdbi.useTransaction<Exception> { h ->
                // Rows as written before the rule (personal text in clear), interleaved with rows through the writer.
                fun old(id: String) = h.createUpdate("INSERT INTO app.audit_log (actor_user_id, via, entity, entity_id, action, after) VALUES (:u, 'web', 'user', :e, 'create', CAST(:a AS jsonb))")
                    .bind("u", actor).bind("e", id).bind("a", img.toString()).execute()
                old("1"); AuditLog.write(h, p, "user", "2", "create", null, img, null, null); old("3"); AuditLog.write(h, p, "user", "4", "update", img, img, null, null)
            }
            db.jdbi.useHandle<Exception> { h ->
                val names = h.createQuery("SELECT coalesce(before->>'full_name', '-') || '/' || (after->>'full_name') || '/' || (after->>'phone') || '/' || (after->>'role') FROM app.audit_log ORDER BY chain_seq").mapTo(String::class.java).list()
                assertEquals(
                    listOf("-/Rahim Uddin/01711111111/SR", "-/[redacted]/[redacted]/SR", "-/Rahim Uddin/01711111111/SR", "[redacted]/[redacted]/[redacted]/SR"), names,
                    "old rows keep their text, new rows never had it",
                )
                assertNull(AuditLog.verify(h), "app.audit_verify() passes on the mix")
            }
        }
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
}
