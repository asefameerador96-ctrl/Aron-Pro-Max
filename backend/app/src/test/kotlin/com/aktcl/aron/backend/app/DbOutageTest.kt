package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.DbErrors
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.NIL_GENERATION
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.backend.platform.retryingTx
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.PrintWriter
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AUD-REL-01 and AUD-REL-02 with a real Hikari pool against a closed port: health never waits on PostgreSQL, the config
 * cache serves its last good value and retries once per back-off window, every database failure is a 503 with a
 * jittered Retry-After (never a 500 the phone would bisect on), transient failures are retried in the request, and a
 * request past the timeout is a 503.
 */
class DbOutageTest {
    private val closedPort = "jdbc:postgresql://127.0.0.1:1/aron?connectTimeout=1"

    private fun keyFile() = File.createTempFile("aron-jwt", ".pem").apply {
        deleteOnExit()
        val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
        writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
    }

    private inline fun ms(block: () -> Unit): Long { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000 }

    @Test
    fun healthAnswersAtOnceAndDatabaseRoutesAre503WhileTheDatabaseIsDown() {
        val w = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to closedPort, "ARON_JWT_SIGNING_KEY_FILE" to keyFile().absolutePath)))
        try {
            testApplication {
                application { aronApi(w) }
                client.get("/v1/health") // warm-up: Ktor and the client, not the database
                repeat(5) {
                    var status = HttpStatusCode.OK
                    val took = ms { status = client.get("/v1/health").status }
                    assertEquals(HttpStatusCode.OK, status)
                    assertTrue(took < 100, "liveness answered in $took ms while the database is down")
                }
                val ready = client.get("/v1/health/ready")
                assertEquals(HttpStatusCode.ServiceUnavailable, ready.status)

                // A route that needs the database: 503 ERR_SERVICE_UNAVAILABLE with Retry-After 5..30, never 500.
                repeat(2) {
                    val r = client.post("/v1/auth/login") {
                        contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
                        setBody("""{"username":"sr1001","password":"whatever-123","client":"web"}""")
                    }
                    assertEquals(HttpStatusCode.ServiceUnavailable, r.status, r.bodyAsText())
                    val b = Json.parseToJsonElement(r.bodyAsText()).jsonObject
                    assertEquals("ERR_SERVICE_UNAVAILABLE", b["code"]!!.jsonPrimitive.content)
                    val after = r.headers["Retry-After"]!!.toInt()
                    assertTrue(after in 5..30, "Retry-After $after")
                    assertEquals(after, b["retry_after_s"]!!.jsonPrimitive.content.toInt())
                }
                // Health is still instant after the failures.
                assertTrue(ms { client.get("/v1/health") } < 100)
            }
        } finally { w.database?.close() }
    }

    /** A DataSource that can be switched between the live database and a dead pool. */
    private class Switch(val live: DataSource, val dead: DataSource) : DataSource {
        val down = AtomicReference(false)
        val borrows = AtomicInteger(0)
        private fun cur() = if (down.get()) dead else live
        override fun getConnection(): Connection { borrows.incrementAndGet(); return cur().connection }
        override fun getConnection(u: String?, p: String?): Connection = cur().getConnection(u, p)
        override fun getLogWriter(): PrintWriter? = live.logWriter
        override fun setLogWriter(out: PrintWriter?) {}
        override fun setLoginTimeout(seconds: Int) {}
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = Logger.getGlobal()
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("no")
        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }

    @Test
    fun theConfigCacheServesItsLastGoodValueAndRetriesOncePerBackoffWindow() {
        FreshDb.create().use { fresh ->
            com.zaxxer.hikari.HikariDataSource(com.zaxxer.hikari.HikariConfig().apply {
                jdbcUrl = closedPort; connectionTimeout = 1_000; initializationFailTimeout = -1; maximumPoolSize = 2
            }).use { deadPool ->
                val sw = Switch(fresh.dataSource, deadPool)
                val now = AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
                val cfg = DbServerConfig(Database(sw), RegistryDefaults(), AronClock { now.get() })
                val version = cfg.configVersion()
                // The database goes away and the 30 s cache goes stale.
                sw.down.set(true)
                now.set(now.get().plus(Duration.ofSeconds(31)))
                assertTrue(ms { assertEquals(version, cfg.peekConfigVersion()) } < 100, "peek never waits")
                val before = sw.borrows.get()
                assertEquals(version, cfg.configVersion(), "the last good value while the database is down")
                // Within the back-off window nobody waits on the database again.
                repeat(20) { assertTrue(ms { assertEquals(version, cfg.configVersion()) } < 100) }
                assertTrue(sw.borrows.get() - before <= 2, "at most one load attempt (and one background peek) per window, was ${sw.borrows.get() - before}")
                // Back up: after the back-off the next read reloads.
                sw.down.set(false)
                Thread.sleep(2_200)
                val b2 = sw.borrows.get()
                cfg.configVersion()
                assertTrue(sw.borrows.get() > b2, "reloads after the back-off")
            }
        }
    }

    @Test
    fun transientFailuresAreClassifiedAndRetriedWithinTheRequest() {
        assertTrue(DbErrors.isTransient(SQLTransientConnectionException("pool timeout")))
        for (s in listOf("08006", "08001", "40001", "40P01", "55P03", "57014", "57P01", "53300")) assertTrue(DbErrors.isTransient(RuntimeException(SQLException("x", s))), s)
        for (s in listOf("23505", "22021", "42P01")) assertFalse(DbErrors.isTransient(RuntimeException(SQLException("x", s))), s)
        assertFalse(DbErrors.isTransient(IllegalStateException("bug")))
        repeat(50) { assertTrue(DbErrors.retryAfterS() in 5..30) }

        FreshDb.create().use { fresh ->
            val tries = AtomicInteger(0)
            val v = fresh.db.retryingTx { h ->
                if (tries.incrementAndGet() < 3) throw SQLException("could not serialize access", "40001")
                h.createQuery("SELECT 7").mapTo(Int::class.java).one()
            }
            assertEquals(7, v); assertEquals(3, tries.get())
            // Bounded: never a fourth attempt; a non-transient failure is not retried at all.
            tries.set(0)
            runCatching { fresh.db.retryingTx<Int> { tries.incrementAndGet(); throw SQLException("deadlock", "40P01") } }
            assertEquals(3, tries.get())
            tries.set(0)
            runCatching { fresh.db.retryingTx<Int> { tries.incrementAndGet(); throw SQLException("dup", "23505") } }
            assertEquals(1, tries.get())
        }
    }

    @Test
    fun aRequestPastTheTimeoutIsA503() = testApplication {
        application {
            installAronPlatform(PlatformContext(config = RegistryDefaults(), generation = { NIL_GENERATION }, requestTimeoutMs = 300))
            routing {
                get("/v1/slow") { delay(5_000); call.respondText("late") }
                get("/v1/fast") { call.respondText("ok") }
            }
        }
        val r = client.get("/v1/slow")
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertTrue(r.headers["Retry-After"]!!.toInt() in 5..30)
        assertEquals(HttpStatusCode.OK, client.get("/v1/fast").status)
    }
}
