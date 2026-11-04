package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.platform.AronClock
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.server.testing.TestApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D-TSO-MAN-49 (cloud lead ruling; D-55, D-579, CLAUDE.md constraint 1: a captured sale is never refused): with NO config
 * row for `cfg.day.late_sync_after_final_policy` (the shipped default, `accept_and_flag`) an SR sale captured after the
 * zone-day's Final Submit lock is accepted, stored and counted late, never refused `day_final_submitted` and never
 * held. The quarantine and reject switches are tested in [LateAfterFinalTest], [DayApiTest] and [DayCloseRaceTest],
 * which switch them on in their own set-up.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LateAfterFinalDefaultPolicyTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private val tag = "LAD"
    private val day = "2027-01-03"
    private val lockAt = Instant.parse("2027-01-03T06:00:00Z")
    private val now = AtomicReference(lockAt)
    private val clock = AronClock { now.get() }
    private lateinit var world: ApiWorld
    private lateinit var phone: SyncPhone
    private var zone = 0L

    @BeforeAll
    fun setUp() {
        world = ApiWorld(repoRoot, mirrors = listOf(tag), clock = clock)
        zone = world.fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.zone WHERE code = 'Z-$tag'").mapTo(Long::class.java).one() }
        phone = world.phoneFor(tag, 0, day)
    }

    @AfterAll
    fun tearDown() { world.close() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private val instant = Regex("\"(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z)\"")

    @Test
    fun theShippedDefaultAcceptsAndFlagsAnSrSaleCapturedAfterTheLock() = runBlocking {
        val app = TestApplication { application { aronApi(world.wiring) } }
        app.start()
        try {
            val c: HttpClient = app.client
            val policyRows = world.fresh.db.jdbi.withHandle<Int, Exception> { h -> h.createQuery("SELECT count(*) FROM app.cfg_value WHERE key = 'cfg.day.late_sync_after_final_policy'").mapTo(Int::class.java).one() }
            assertEquals(0, policyRows, "no override: this test runs on the shipped default")
            assertEquals("\"accept_and_flag\"", world.fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT default_value::text FROM app.cfg_key WHERE key = 'cfg.day.late_sync_after_final_policy'").mapTo(String::class.java).one() })
            assertEquals("false", world.fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT default_value::text FROM app.cfg_key WHERE key = 'cfg.day.auto_final_submit_enabled'").mapTo(String::class.java).one() })

            suspend fun sync(records: List<JsonObject>): List<JsonObject> {
                phone.token = world.login(c, ApiWorld.Caller(phone.username, "app_sr", phone.deviceUuid)) ?: error("no token")
                val r = c.post("/v1/sync/batch") {
                    bearerAuth(phone.token); header("X-Device-Id", phone.deviceUuid); header("X-App-Version", ApiWorld.APP_VERSION); header("Content-Encoding", "gzip")
                    setBody(ByteArrayContent(gz(phone.batch(records)), ContentType.Application.Json))
                }
                assertEquals(200, r.status.value, r.bodyAsText())
                return json(r.bodyAsText())["acks"]!!.jsonArray.map { it.jsonObject }
            }

            assertEquals(setOf("accepted"), sync(phone.captureDay(Random(1))).map { it["status"]!!.jsonPrimitive.content }.toSet())
            val tso = world.login(c, ApiWorld.Caller("${tag.lowercase()}tso1001", "web", null)) ?: error("no tso token")
            val close = c.post("/v1/day/final-submit") {
                bearerAuth(tso); contentType(ContentType.Application.Json); header("X-App-Version", ApiWorld.APP_VERSION)
                setBody("""{"client_uuid":"${UUID.randomUUID()}","zone_id":$zone,"business_date":"$day"}""")
            }
            assertEquals(200, close.status.value, close.bodyAsText())

            // A whole sale family captured 16 h after the lock (so captured AFTER it, trusted clock): under quarantine or reject
            // this is refused day_final_submitted; under the default it is accepted, stored and counted late.
            now.set(Instant.parse("2027-01-03T17:30:00Z"))
            val after = phone.captureDay(Random(3)).map { r ->
                json(r.toString().replace(instant) { "\"" + Instant.parse(it.groupValues[1]).plus(16, ChronoUnit.HOURS) + "\"" })
            }
            val acks = sync(after)
            assertEquals(setOf("accepted"), acks.map { it["status"]!!.jsonPrimitive.content }.toSet(), acks.toString())
            val memos = after.filter { it["type"]!!.jsonPrimitive.content == "memo" }.map { it["client_uuid"]!!.jsonPrimitive.content }
            val stored = world.fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT count(*) FROM app.memo WHERE client_uuid::text = ANY(:u)").bindArray("u", String::class.java, memos).mapTo(Long::class.java).one() }
            assertEquals(memos.size.toLong(), stored, "the late sale is stored")
            val late = world.fresh.db.jdbi.withHandle<Int, Exception> { h -> h.createQuery("SELECT late_rows_after_final FROM app.route_day WHERE route_id = :r AND business_date = DATE '$day'").bind("r", phone.routeId).mapTo(Int::class.java).one() }
            assertEquals(after.size, late, "every stored late row is flagged and counted late")
            val held = world.fresh.db.jdbi.withHandle<Int, Exception> { h -> h.createQuery("SELECT count(*) FROM app.sync_quarantine WHERE code = 'after_final_submit'").mapTo(Int::class.java).one() }
            assertEquals(0, held, "nothing is held for review under the default")
        } finally { app.stop() }
    }
}
