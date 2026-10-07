package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.backend.platform.isTransientDbFailure
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AUD-REL-01/02: the production wiring (real Hikari pool, 5 s connection timeout) against a PostgreSQL port that
 * refuses connections. Liveness answers at once and keeps answering (it never waits on the database); a request that
 * needs the database answers 503 ERR_SERVICE_UNAVAILABLE with Retry-After 5..30, never a 500 the phone would bisect on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseOutageTest {
    private lateinit var wiring: Wiring
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }

    @BeforeAll
    fun setUp() {
        // A port nobody listens on: bind, read the number, close.
        val port = ServerSocket(0).use { it.localPort }
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(
            Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to "jdbc:postgresql://127.0.0.1:$port/aron?user=aron&password=x&connectTimeout=1", "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)),
            clock,
        )
    }

    @AfterAll
    fun tearDown() { wiring.database?.close() }

    @Test
    fun livenessAnswersAtOnceWhileTheDatabaseIsDown() = testApplication {
        application { aronApi(wiring) }
        repeat(5) {
            val t0 = System.nanoTime()
            val r = client.get("/v1/health")
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(ms < 1_000, "liveness took $ms ms (probe timeout is 1 s)")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/v1/health/ready").status)
    }

    @Test
    fun aRequestThatNeedsTheDatabaseIs503WithRetryAfterNot500() = testApplication {
        application { aronApi(wiring) }
        val r = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"whatever-123","client":"web"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status, r.bodyAsText())
        assertTrue("ERR_SERVICE_UNAVAILABLE" in r.bodyAsText())
        val wait = r.headers["Retry-After"]!!.toInt()
        assertTrue(wait in 5..30)
        // The back-off: requests after the first failure do not each wait for the pool's connection timeout on config.
        val t0 = System.nanoTime()
        client.get("/v1/health")
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000)
    }

    @Test
    fun theTransientClassifierCoversPoolAndSqlStates() {
        assertTrue(isTransientDbFailure(java.sql.SQLTransientConnectionException("pool timeout")))
        assertTrue(isTransientDbFailure(RuntimeException(java.sql.SQLException("x", "57P01"))))
        assertTrue(isTransientDbFailure(java.sql.SQLException("x", "08006")))
        assertTrue(isTransientDbFailure(java.sql.SQLException("lock", "55P03")))
        assertTrue(!isTransientDbFailure(java.sql.SQLException("dup", "23505")))
        assertTrue(!isTransientDbFailure(IllegalStateException("bug")))
    }
}
