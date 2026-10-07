package com.aktcl.aron.backend.platform

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AUD-SEC-03 durable sink: events reach `app.security_event` (V0054) off the request path, as `auth_rw` (INSERT only,
 * no RETURNING), with a detail under the 2000-byte CHECK; the log dedupe holds for the table; a full queue drops
 * without blocking; close() flushes what is queued.
 */
class JdbiSecurityEventsTest {
    private val at = Instant.parse("2026-10-07T04:00:00Z")
    private val device = "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10"
    private val request = "0b7e5a52-3c1d-4f6e-8a9b-1c2d3e4f5a6b"

    private fun rows(db: Database): List<Map<String, Any?>> = db.jdbi.withHandle<List<Map<String, Any?>>, Exception> { h ->
        h.createQuery("SELECT kind, at, user_id, device_uuid::text AS device_uuid, request_id::text AS request_id, detail::text AS detail FROM app.security_event ORDER BY id")
            .mapToMap().list()
    }

    /** A pool whose connections run as `auth_rw`, the login path's role. */
    private fun asAuthRw(fresh: FreshDb): Database =
        Database(HikariDataSource(HikariConfig().apply { jdbcUrl = fresh.url; maximumPoolSize = 2; connectionInitSql = "SET ROLE auth_rw" }))

    @Test
    fun eventsAreStoredAsAuthRwWithEveryColumn() {
        FreshDb.create().use { fresh ->
            asAuthRw(fresh).use { authDb ->
                val sink = JdbiSecurityEvents(authDb, startWriter = false)
                sink.record(SecurityEvent(SecurityEventKind.LOCKOUT, at, 42, device, request, mapOf("failures" to "5", "client" to "phone")))
                sink.record(SecurityEvent(SecurityEventKind.REFRESH_REUSE, at, 7, detail = mapOf("family" to "19")))
                sink.record(SecurityEvent(SecurityEventKind.DEVICE_PROOF_INVALID, at, null, "not-a-uuid", "also-not"))
                assertEquals(3, sink.drainNow())
                assertEquals(0, sink.dropped.get())
            }
            val r = rows(fresh.db)
            assertEquals(listOf("lockout", "refresh_reuse", "device_proof_invalid"), r.map { it["kind"] })
            assertEquals(42L, r[0]["user_id"]); assertEquals(device, r[0]["device_uuid"]); assertEquals(request, r[0]["request_id"])
            assertEquals("5", Json.parseToJsonElement(r[0]["detail"] as String).jsonObject["failures"]!!.jsonPrimitive.content)
            assertEquals(at, (r[0]["at"] as java.sql.Timestamp).toInstant())
            assertNull(r[2]["device_uuid"], "a malformed device id is stored as unknown, not refused")
            assertNull(r[2]["request_id"])
            assertEquals("{}", r[2]["detail"])
        }
    }

    @Test
    fun anOverLongDetailLosesKeysNotTheEvent() {
        val big = (1..40).associate { "k%02d".format(it) to "অ".repeat(300) } // 40 x 200 chars x 3 bytes: far over 2000
        val json = JdbiSecurityEvents.detailJson(big)
        assertTrue(json.toByteArray().size <= JdbiSecurityEvents.DETAIL_MAX_BYTES, "capped: ${json.toByteArray().size}")
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("true", obj["_truncated"]!!.jsonPrimitive.content)
        assertEquals(200, obj["k01"]!!.jsonPrimitive.content.length, "values cut to 200 characters")
        FreshDb.create().use { fresh ->
            val sink = JdbiSecurityEvents(fresh.db, startWriter = false)
            sink.record(SecurityEvent(SecurityEventKind.SCOPE_CHANGED, at, 1, detail = big))
            sink.record(SecurityEvent(SecurityEventKind.SCOPE_CHANGED, at, 2, detail = mapOf("route" to "/v1/sync/batch", "code" to "a\u0000b")))
            sink.drainNow()
            assertEquals(0, sink.dropped.get(), "the CHECK (<= 2000 bytes as jsonb text) accepts the capped detail")
            assertEquals(listOf(1L, 2L), rows(fresh.db).map { it["user_id"] })
        }
    }

    @Test
    fun theLogDedupeHoldsForTheTable() {
        FreshDb.create().use { fresh ->
            val sink = JdbiSecurityEvents(fresh.db, startWriter = false)
            val lines = mutableListOf<String>()
            val events = LogSecurityEvents(then = sink) { lines += it }
            val failure = SecurityEvent(SecurityEventKind.LOGIN_FAILURE, at, null, device, detail = mapOf("username_hash" to SecurityEvents.usernameHash("sr1001")))
            repeat(5) { events.safely(failure) }
            events.safely(SecurityEvent(SecurityEventKind.LOGIN_FAILURE, at.plusSeconds(60), null, device, detail = failure.detail))
            sink.drainNow()
            assertEquals(2, lines.size)
            assertEquals(2, rows(fresh.db).size, "one row per account and device per minute, like the log")
        }
    }

    @Test
    fun aFullQueueDropsWithoutBlockingAndADownDatabaseDropsTheBatch() {
        FreshDb.create().use { fresh ->
            val sink = JdbiSecurityEvents(fresh.db, capacity = 2, startWriter = false)
            repeat(5) { sink.record(SecurityEvent(SecurityEventKind.PASSWORD_CHANGE, at, it.toLong())) }
            assertEquals(3, sink.dropped.get())
            fresh.dataSource.close() // the database "goes away"
            sink.drainNow()
            assertEquals(5, sink.dropped.get(), "the queued pair is dropped, not retried row by row")
        }
    }

    @Test
    fun closeFlushesWhatTheWriterThreadHasQueued() {
        FreshDb.create().use { fresh ->
            val sink = JdbiSecurityEvents(fresh.db, closeWaitMs = 60_000) // a bound, not a timing assertion
            repeat(250) { sink.record(SecurityEvent(SecurityEventKind.FORCE_LOGOUT, at, it.toLong())) }
            sink.close()
            sink.record(SecurityEvent(SecurityEventKind.FORCE_LOGOUT, at, 999)) // after close: dropped, never queued
            assertEquals(250, rows(fresh.db).size)
            assertEquals(1, sink.dropped.get())
        }
    }
}
