package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-API-026 through the production wiring on the seed: GET and POST /v1/tasks and POST /v1/tasks/{uuid}/cancel
 * assign, list and cancel tasks idempotently and in scope; through the batch a `task` record is scope-checked and
 * `task_event` records resolve and reopen a task by capture order, and a cancelled task stays cancelled.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TasksTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val day = "2027-01-03"
    private var sr = 0L
    private var outsider = 0L
    private var minute = 0

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            sr = h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one()
            // An SR in another territory: outside the TSO's reach.
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-OTHER', 'Other', division_id FROM app.territory WHERE code = 'T-DHK-N'")
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-OTHER', 'Other zone', id FROM app.territory WHERE code = 'T-OTHER'")
            outsider = h.createQuery("INSERT INTO app.app_user (username, full_name, role, home_zone_id, must_change_password) SELECT 'sr9009', 'Other SR', 'SR', id, false FROM app.zone WHERE code = 'Z-OTHER' RETURNING id")
                .mapTo(Long::class.java).one()
        }
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock)
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun uuid() = UUID.randomUUID().toString()

    private val tokens = HashMap<String, String>()
    private suspend fun HttpClient.token(user: String, phone: Boolean = false): String = tokens.getOrPut(user) {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody(if (phone) """{"username":"$user","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""" else """{"username":"$user","password":"$password","client":"web"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.create(token: String, taskUuid: String, assignee: Long, title: String = "Check the display"): HttpResponse = post("/v1/tasks") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody("""{"task_uuid":"$taskUuid","task_type_code":"display_check","assignee_user_id":$assignee,"title":"$title"}""")
    }

    private suspend fun HttpClient.list(token: String, phone: Boolean = false, query: String = ""): List<JsonObject> {
        val r = get("/v1/tasks$query") { bearerAuth(token); if (phone) header("X-Device-Id", devPhone) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["items"]!!.jsonArray.map { it.jsonObject }
    }

    private fun record(type: String, payload: JsonObject) = buildJsonObject {
        val cu = uuid(); minute++
        put("type", type); put("client_uuid", cu); put("family_uuid", cu); put("rank", 0); put("schema_version", 1)
        put("business_date", day); put("captured_at", "2027-01-03T03:%02d:00.000Z".format(minute)); put("captured_elapsed_ms", 18330000 + minute); put("boot_count", 412)
        put("clock_offset_ms", 0); put("captured_offline", true); put("bundle_version", "$day:3"); put("bundle_stale", false); put("config_version", 1)
        put("payload", payload)
    }

    private suspend fun HttpClient.send(token: String, records: List<JsonObject>): List<String> {
        val body = buildJsonObject {
            put("batch_uuid", uuid()); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
            put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
            put("time_anchors", JsonArray(emptyList())); put("device_counts", buildJsonObject {}); put("records", JsonArray(records))
        }.toString()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(body.toByteArray()) } }.toByteArray()
        val r = post("/v1/sync/batch") {
            bearerAuth(token); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); header("Content-Encoding", "gzip")
            setBody(io.ktor.http.content.ByteArrayContent(gz, ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["acks"]!!.jsonArray.map { it.jsonObject["status"]!!.jsonPrimitive.content }
    }

    private fun event(task: String, event: String) = record("task_event", buildJsonObject { put("task_uuid", task); put("event", event); put("note", "done at the outlet") })

    @Test
    fun aTsoAssignsTheSrSeesAndResolvesThroughTheBatch() = testApplication {
        application { aronApi(wiring) }
        val tso = client.token("tso1001")
        val t = uuid()
        val first = client.create(tso, t, sr)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val body = json(first.bodyAsText())
        assertEquals("ongoing", body["status"]!!.jsonPrimitive.content); assertEquals("web", body["source"]!!.jsonPrimitive.content)
        // Idempotent by task_uuid; another task under the same uuid is a conflict; outside the reach is refused.
        assertEquals(body, json(client.create(tso, t, sr).bodyAsText()))
        assertEquals(HttpStatusCode.Conflict, client.create(tso, t, sr, title = "Something else").status)
        assertEquals(HttpStatusCode.Forbidden, client.create(tso, uuid(), outsider).status)

        // The SR sees its task; the outsider's reach does not include it; the TSO lists by assignee.
        val srToken = client.token("sr1001", phone = true)
        assertTrue(client.list(srToken, phone = true).any { it["task_uuid"]!!.jsonPrimitive.content == t })
        assertTrue(client.list(tso, query = "?assignee_user_id=$sr&status=ongoing").any { it["task_uuid"]!!.jsonPrimitive.content == t })

        // Resolved on the phone; a reopen captured earlier but uploaded later does not undo it; a replay changes nothing.
        val resolved = event(t, "resolved")
        val earlierReopen = buildJsonObject { resolved.forEach { (k, v) -> put(k, v) }; put("client_uuid", uuid()); put("captured_at", "2027-01-03T02:00:00.000Z"); put("payload", buildJsonObject { put("task_uuid", t); put("event", "reopened") }) }
        assertEquals(listOf("accepted"), client.send(srToken, listOf(resolved)))
        assertEquals(listOf("accepted"), client.send(srToken, listOf(earlierReopen)))
        assertEquals(listOf("duplicate"), client.send(srToken, listOf(resolved)))
        val now = client.list(tso).single { it["task_uuid"]!!.jsonPrimitive.content == t }
        assertEquals("completed", now["status"]!!.jsonPrimitive.content)
        assertEquals("done at the outlet", now["resolution_note"]!!.jsonPrimitive.content)
        assertTrue(now["resolved_at"] != JsonNull)
        // A later reopen does reopen it.
        client.send(srToken, listOf(event(t, "reopened")))
        assertEquals("ongoing", client.list(tso).single { it["task_uuid"]!!.jsonPrimitive.content == t }["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun cancelIsForTheCreatorTheZonesTsoOrAnAdminAndIsFinal() = testApplication {
        application { aronApi(wiring) }
        val tso = client.token("tso1001")
        val amo = client.token("amo1001")
        val t = uuid()
        assertEquals(HttpStatusCode.OK, client.create(amo, t, sr).status)
        val srToken = client.token("sr1001", phone = true)
        suspend fun cancel(token: String, phone: Boolean = false) = client.post("/v1/tasks/$t/cancel") {
            bearerAuth(token); if (phone) header("X-Device-Id", devPhone); contentType(ContentType.Application.Json); setBody("""{"reason":"the outlet closed for good"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, cancel(srToken, phone = true).status, "the assignee does not cancel")
        val c = cancel(tso)
        assertEquals(HttpStatusCode.OK, c.status, c.bodyAsText())
        assertEquals("cancelled", json(c.bodyAsText())["status"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, cancel(amo).status, "idempotent")
        // F-SYS-059: the create and the cancel each added a hash-chained audit row with actor, before and after.
        val audit = fresh.db.jdbi.withHandle<List<String>, Exception> { h ->
            h.createQuery("SELECT action || ':' || actor_username || ':' || COALESCE(before->>'status', '-') || ':' || (after->>'status') || ':' || COALESCE(reason, '-') FROM app.audit_log WHERE entity = 'task' AND entity_id = '$t' ORDER BY chain_seq")
                .mapTo(String::class.java).list()
        }
        assertEquals(listOf("create:amo1001:-:ongoing:-", "cancel:tso1001:ongoing:cancelled:the outlet closed for good"), audit)
        assertEquals(null, fresh.db.jdbi.withHandle<Long?, Exception> { h -> com.aktcl.aron.backend.platform.AuditLog.verify(h) })
        // A late resolve from the phone is stored but the task stays cancelled.
        assertEquals(listOf("accepted"), client.send(srToken, listOf(event(t, "resolved"))))
        assertEquals("cancelled", client.list(tso).single { it["task_uuid"]!!.jsonPrimitive.content == t }["status"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/tasks/${uuid()}/cancel") { bearerAuth(tso); contentType(ContentType.Application.Json); setBody("""{"reason":"the outlet closed for good"}""") }.status)
    }

    @Test
    fun aTaskRecordForSomeoneOutsideTheReachIsQuarantined() = testApplication {
        application { aronApi(wiring) }
        val srToken = client.token("sr1001", phone = true)
        val mine = record("task", buildJsonObject { put("task_type_code", "follow_up"); put("assignee_user_id", sr); put("title", "Call back tomorrow") })
        val theirs = record("task", buildJsonObject { put("task_type_code", "follow_up"); put("assignee_user_id", outsider); put("title", "Not mine") })
        assertEquals(listOf("accepted", "quarantined"), client.send(srToken, listOf(mine, theirs)))
        assertEquals("app", client.list(srToken, phone = true).single { it["task_uuid"]!!.jsonPrimitive.content == mine["client_uuid"]!!.jsonPrimitive.content }["source"]!!.jsonPrimitive.content)
    }
}
