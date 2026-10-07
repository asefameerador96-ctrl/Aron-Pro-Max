package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.FreshDb
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AUD-SEC-02: web logins hash in their own small pool, so a web flood never takes a phone's hash slot; a global web
 * bucket stops web logins before any hashing; a lock is capped at 2 h (docs/21 s2.4, D-102); auth_lockout stays
 * bounded; refresh is limited per phone (docs/21 s6.1).
 */
class LoginAbuseTest {
    @Test
    fun aBusyWebHashPoolDoesNotStopAPhoneLogin() = runBlocking {
        val f = AuthFixture()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        // Occupy every web hash slot: a web login then waits out the limiter's queue time and is answered 503.
        val holders = (0 until f.webLimiter.concurrency).map {
            CoroutineScope(Dispatchers.Default).launch { runCatching { f.webLimiter.run { started.complete(Unit); runBlocking { release.await() } } } }
        }
        started.await()
        try {
            testApplication {
                application { f.application(this) }
                val web = client.login("tso5012", "correct horse 1", client = "web")
                assertEquals(HttpStatusCode.ServiceUnavailable, web.status, web.bodyAsText())
                val phone = client.login("sr334001", "correct horse 1", f.srDevice)
                assertEquals(HttpStatusCode.OK, phone.status, phone.bodyAsText())
            }
        } finally {
            release.complete(Unit); holders.forEach { it.join() }; f.close()
        }
    }

    @Test
    fun webLoginsAreBoundedBeforeAnyHashing() {
        val f = AuthFixture()
        try {
            testApplication {
                application { f.application(this) }
                val statuses = (1..121).map { i -> client.login("nobody$i", "wrong password $i", client = "web").status }
                assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
                assertTrue(statuses.dropLast(1).none { it == HttpStatusCode.TooManyRequests })
            }
        } finally { f.close() }
    }

    @Test
    fun aRefreshFloodFromOnePhoneIsLimited() {
        val f = AuthFixture()
        try {
            testApplication {
                application { f.application(this) }
                val junk = "x".repeat(43)
                val statuses = (1..21).map { client.refresh(junk, f.srDevice).status }
                assertTrue(statuses.take(20).none { it == HttpStatusCode.TooManyRequests }, statuses.toString())
                assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
            }
        } finally { f.close() }
    }

    @Test
    fun aLockIsCappedAtTwoHoursAndIdleRowsArePurged() {
        val fresh = FreshDb.create()
        try {
            val store = JdbiLockoutStore(fresh.db)
            var now = Instant.parse("2027-01-03T04:00:00Z")
            repeat(12) {
                store.recordFailure("u|-|x", now, Duration.ofMinutes(15))
                val until = store.lock("u|-|x", now, Duration.ofMinutes(15))
                assertTrue(Duration.between(now, until) <= Duration.ofHours(2), "lock ${Duration.between(now, until)}")
                now = until.plusSeconds(1)
            }
            // 100 failing unknown usernames, then a day later: each new failure drops idle rows, the table shrinks.
            repeat(100) { store.recordFailure("nobody$it|-|x", now, Duration.ofMinutes(15)) }
            val later = now.plus(Duration.ofDays(2))
            repeat(10) { store.recordFailure("late$it|-|x", later, Duration.ofMinutes(15)) }
            val left = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT count(*) FROM app.auth_lockout").mapTo(Long::class.java).one() }
            assertTrue(left < 100, "idle rows are purged: $left left")
        } finally { fresh.close() }
    }
}
