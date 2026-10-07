package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.JdbiLockoutStore
import com.aktcl.aron.backend.auth.LockoutLimits
import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AUD-SEC-02 on the production wiring: an anonymous flood never takes the hash capacity of an enrolled phone, a web
 * account is never hard-locked through the BFF's shared address, a lock never exceeds 2 h, failing usernames never
 * grow app.auth_lockout without bound, and /auth/refresh is rate limited per device (docs/21 s2.4, s6.1).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginHardeningTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val now = AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
    private val clock = AronClock { now.get() }
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h -> h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute() }
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock)
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    private suspend fun HttpClient.webLogin(user: String, pw: String): HttpResponse =
        post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$pw","client":"web"}""") }

    private suspend fun HttpClient.phoneLogin(): HttpResponse = post("/v1/auth/login") {
        contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
        setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
    }

    @Test
    fun anAnonymousWebFloodNeverTakesTheCapacityOfAnEnrolledPhone() = testApplication {
        application { aronApi(wiring) }
        coroutineScope {
            val flood = (1..200).map { i -> async(Dispatchers.IO) { client.webLogin("nobody${UUID.randomUUID().toString().take(8)}$i", "wrong-password-$i").status } }
            val phones = (1..3).map { async(Dispatchers.IO) { client.phoneLogin() } }
            phones.awaitAll().forEach { r -> assertEquals(HttpStatusCode.OK, r.status, "an enrolled phone during the flood: ${r.bodyAsText()}") }
            val statuses = flood.awaitAll()
            assertTrue(statuses.all { it == HttpStatusCode.Unauthorized || it == HttpStatusCode.ServiceUnavailable || it == HttpStatusCode.TooManyRequests }, statuses.toString())
        }
    }

    @Test
    fun aWebAdministratorIsNeverHardLockedByBadPasswords() = testApplication {
        application { aronApi(wiring) }
        val start = now.get()
        try {
            repeat(12) { client.webLogin("admin1001", "not-the-password") }
            assertEquals(0L, fresh.db.jdbi.withHandle<Long, Exception> { h ->
                h.createQuery("SELECT count(*) FROM app.auth_lockout WHERE lock_key LIKE 'admin1001%' AND locked_until IS NOT NULL").mapTo(Long::class.java).one()
            }, "no hard lock on a web account")
            // The per-username throttle (10 per 15 min) holds the burst back; after it the real admin logs in.
            now.set(start.plus(Duration.ofMinutes(16)))
            val ok = client.webLogin("admin1001", password)
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        } finally { now.set(start) }
    }

    @Test
    fun aLockNeverExceedsTwoHoursAndIdleCountersArePurged() {
        val store = JdbiLockoutStore(fresh.db)
        val t = Instant.parse("2027-02-01T00:00:00Z")
        var until = t
        store.recordFailure("cap-test|x", t, Duration.ofMinutes(15))
        repeat(12) { until = store.lock("cap-test|x", until, Duration.ofMinutes(15)) .also { assertTrue(Duration.between(until, it).seconds <= LockoutLimits.MAX_LOCK_S, "lock ${Duration.between(until, it)}") } }
        // 1,000 failing unknown usernames, then the window passes: one purge leaves none of them.
        repeat(1_000) { i -> store.recordFailure("ghost$i|web", t, Duration.ofMinutes(15)) }
        val later = t.plus(Duration.ofMinutes(16))
        store.purgeIdle(later, Duration.ofMinutes(15))
        assertEquals(0L, fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT count(*) FROM app.auth_lockout WHERE lock_key LIKE 'ghost%'").mapTo(Long::class.java).one() })
    }

    @Test
    fun refreshIsRateLimitedPerDeviceBeforeAnyLookup() = testApplication {
        application { aronApi(wiring) }
        val device = UUID.randomUUID().toString()
        val codes = (1..7).map {
            client.post("/v1/auth/refresh") {
                contentType(ContentType.Application.Json); header("X-Device-Id", device)
                setBody("""{"grant":"full","refresh_token":"junk-${UUID.randomUUID()}-padding"}""")
            }.status
        }
        assertEquals(List(5) { HttpStatusCode.Unauthorized }, codes.take(5))
        assertEquals(List(2) { HttpStatusCode.TooManyRequests }, codes.drop(5))
    }
}
