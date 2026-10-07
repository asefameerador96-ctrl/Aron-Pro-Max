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
class TasksCheckerTest {
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
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[]'::jsonb, now() - interval '1 day', max(config_version), 'test: admins log in without MFA' FROM app.cfg_version")
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

    private fun event(task: String, event: String, at: String? = null, note: String = "done at the outlet"): JsonObject {
        val r = record("task_event", buildJsonObject { put("task_uuid", task); put("event", event); put("note", note) })
        return if (at == null) r else buildJsonObject { r.forEach { (k, v) -> put(k, v) }; put("captured_at", at) }
    }
    private suspend fun HttpClient.cancel(token: String, t: String, phone: Boolean = false) = post("/v1/tasks/$t/cancel") {
        bearerAuth(token); if (phone) header("X-Device-Id", devPhone); contentType(ContentType.Application.Json); setBody("""{"reason":"the outlet closed for good"}""")
    }
    private fun ids(l: List<JsonObject>) = l.map { it["task_uuid"]!!.jsonPrimitive.content }
    private fun st(l: List<JsonObject>, t: String) = l.single { it["task_uuid"]!!.jsonPrimitive.content == t }["status"]!!.jsonPrimitive.content
    private fun exec(sql: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(sql) }
    private fun farOutlet(): Long = fresh.db.jdbi.withHandle<Long, Exception> { h ->
        h.createQuery("SELECT id FROM app.outlet WHERE code = 'O-FAR'").mapTo(Long::class.java).findOne().orElseGet {
            h.createQuery("INSERT INTO app.outlet (code, name, owner_name, zone_id, cluster_id, channel) SELECT 'O-FAR','Far','x', z.id, (SELECT id FROM app.cluster LIMIT 1), 'GT' FROM app.zone z WHERE z.code='Z-OTHER' RETURNING id")
                .mapTo(Long::class.java).one()
        }
    }

    @Test
    fun eventBeforeTaskParksThenAppliesOnResend() = testApplication {
        application { aronApi(wiring) }
        val tso = client.token("tso1001"); val srToken = client.token("sr1001", phone = true)
        val t = uuid()
        val ev = event(t, "resolved")
        assertEquals(listOf("rejected"), client.send(srToken, listOf(ev)), "event for unknown task (parked = retryable rejected)")
        assertEquals(HttpStatusCode.OK, client.create(tso, t, sr).status)
        assertEquals(listOf("accepted"), client.send(srToken, listOf(ev)))
        assertEquals("completed", st(client.list(tso), t))
    }

    @Test
    fun otherSrCannotResolveSomeoneElsesTask() = testApplication {
        application { aronApi(wiring) }
        val srToken = client.token("sr1001", phone = true); val admin = client.token("admin1001")
        val t2 = uuid()
        assertEquals(HttpStatusCode.OK, client.create(admin, t2, outsider).status)
        val acks = client.send(srToken, listOf(event(t2, "resolved")))
        println("XSR-ACKS $acks"); assertTrue(acks != listOf("accepted"), "SR resolved a task assigned to another SR: $acks")
        assertEquals("ongoing", st(client.list(admin), t2))
    }

    @Test
    fun listScope() = testApplication {
        application { aronApi(wiring) }
        val tso = client.token("tso1001"); val srToken = client.token("sr1001", phone = true); val admin = client.token("admin1001"); val amo = client.token("amo1001")
        val mine = uuid(); val theirs = uuid()
        assertEquals(HttpStatusCode.OK, client.create(tso, mine, sr).status)
        assertEquals(HttpStatusCode.OK, client.create(admin, theirs, outsider).status)
        assertTrue(theirs !in ids(client.list(srToken, phone = true)))
        assertTrue(theirs !in ids(client.list(tso)), "TSO sees outside-zone task")
        assertTrue(theirs !in ids(client.list(amo)), "AMO sees outside-zone task")
        assertTrue(theirs in ids(client.list(admin)))
        assertTrue(client.list(tso, query = "?assignee_user_id=$outsider").isEmpty())
        assertTrue(client.list(srToken, phone = true, query = "?assignee_user_id=$outsider").isEmpty())
        val zOther = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.zone WHERE code='Z-OTHER'").mapTo(Long::class.java).one() }
        for (u in listOf(tso, amo)) {
            val r = client.get("/v1/tasks?zone_id=$zOther") { bearerAuth(u) }
            assertEquals(HttpStatusCode.Forbidden, r.status, r.bodyAsText())
        }
        val r = client.get("/v1/tasks?zone_id=$zOther") { bearerAuth(srToken); header("X-Device-Id", devPhone) }
        assertEquals(HttpStatusCode.Forbidden, r.status, "SR zone filter outside reach: " + r.bodyAsText())
        assertTrue(theirs in ids(client.list(admin, query = "?zone_id=$zOther")))
    }

    @Test
    fun paginationStableNoDupNoGap() = testApplication {
        application { aronApi(wiring) }
        val tso = client.token("tso1001")
        val made = (1..7).map { uuid().also { u -> assertEquals(HttpStatusCode.OK, client.create(tso, u, sr, "T$it").status) } }.toSet()
        val seen = ArrayList<String>(); var cursor: String? = null; var pages = 0
        do {
            val r = client.get("/v1/tasks?limit=3&assignee_user_id=$sr" + (cursor?.let { "&cursor=$it" } ?: "")) { bearerAuth(tso) }
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val b = json(r.bodyAsText()); seen += b["items"]!!.jsonArray.map { it.jsonObject["task_uuid"]!!.jsonPrimitive.content }
            cursor = b["next_cursor"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
            if (pages == 0) client.create(tso, uuid(), sr, "midwalk")
        } while (cursor != null && ++pages < 50)
        assertEquals(seen.size, seen.toSet().size, "duplicates")
        assertTrue(seen.containsAll(made), "gap")
    }

    @Test
    fun badQueryParameters() = testApplication {
        application { aronApi(wiring) }
        val tso = client.token("tso1001")
        for (bad in listOf("zzz", "MA", "LTE", "9999999999999999999999", "MQ==x")) {
            val r = client.get("/v1/tasks?cursor=$bad") { bearerAuth(tso) }
            assertEquals(HttpStatusCode.BadRequest, r.status, "cursor '$bad': " + r.bodyAsText())
        }
        for (l in listOf("0", "201", "-1", "x")) assertEquals(HttpStatusCode.BadRequest, client.get("/v1/tasks?limit=$l") { bearerAuth(tso) }.status, "limit $l")
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/tasks?status=done") { bearerAuth(tso) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/tasks?from=2027-13-01") { bearerAuth(tso) }.status)
    }

    @Test
    fun filtersAndShapeAndBangla() = testApplication {
        application { aronApi(wiring) }
        val tso = client.token("tso1001")
        val t = uuid(); val title = "দোকানের প্রদর্শন যাচাই করুন ✓"
        val body = """{"task_uuid":"${t.uppercase()}","task_type_code":"display_check","assignee_user_id":$sr,"title":"$title","description":"বিবরণ","due_date":"2027-01-09"}"""
        val r = client.post("/v1/tasks") { bearerAuth(tso); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val b = json(r.bodyAsText())
        assertEquals(setOf("task_uuid","task_type_code","title","description","assignee_user_id","assigned_by_user_id","route_id","outlet_id","due_date","status","created_at","resolved_at","resolution_note","source"), b.keys, "members")
        assertEquals(title, b["title"]!!.jsonPrimitive.content); assertEquals(t, b["task_uuid"]!!.jsonPrimitive.content)
        assertEquals("2027-01-09", b["due_date"]!!.jsonPrimitive.content)
        assertTrue(b["resolved_at"] is JsonNull && b["resolution_note"] is JsonNull && b["route_id"] is JsonNull && b["outlet_id"] is JsonNull)
        assertEquals(title, client.list(tso, query = "?assignee_user_id=$sr").single { it["task_uuid"]!!.jsonPrimitive.content == t }["title"]!!.jsonPrimitive.content)
        val again = client.post("/v1/tasks") { bearerAuth(tso); contentType(ContentType.Application.Json); setBody(body.replace(t.uppercase(), t)) }
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(b, json(again.bodyAsText()))
        assertTrue(t in ids(client.list(tso, query = "?from=2027-01-03&to=2027-01-03&status=ongoing")))
        assertTrue(t !in ids(client.list(tso, query = "?from=2027-01-04")))
        assertTrue(t !in ids(client.list(tso, query = "?to=2027-01-02")))
        assertTrue(t !in ids(client.list(tso, query = "?status=completed")))
        assertTrue(t !in ids(client.list(tso, query = "?status=cancelled")))
    }

    @Test
    fun replayWithDifferentOtherFieldsIsConflict() = testApplication {
        application { aronApi(wiring) }
        val tso = client.token("tso1001")
        val t = uuid()
        assertEquals(HttpStatusCode.OK, client.create(tso, t, sr).status)
        val r = client.post("/v1/tasks") { bearerAuth(tso); contentType(ContentType.Application.Json)
            setBody("""{"task_uuid":"$t","task_type_code":"display_check","assignee_user_id":$sr,"title":"Check the display","description":"changed","due_date":"2027-02-01"}""") }
        assertEquals(HttpStatusCode.Conflict, r.status, "same uuid, different description/due_date silently replayed: " + r.bodyAsText())
    }

    @Test
    fun createEdgeCases() = testApplication {
        application { aronApi(wiring) }
        val admin = client.token("admin1001")
        val dis = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("INSERT INTO app.app_user (username, full_name, role, home_zone_id, must_change_password, status, disabled_at) SELECT 'sr9011','Dis','SR', home_zone_id, false, 'disabled', now() FROM app.app_user WHERE id = $sr RETURNING id").mapTo(Long::class.java).one() }
        assertTrue(client.create(admin, uuid(), dis).status != HttpStatusCode.OK, "task for disabled user created")
        assertTrue(client.create(admin, uuid(), 987654321).status.value in 400..499, "unknown assignee")
        val r = client.post("/v1/tasks") { bearerAuth(admin); contentType(ContentType.Application.Json)
            setBody("""{"task_uuid":"${uuid()}","task_type_code":"display_check","assignee_user_id":$sr,"title":"x","outlet_id":987654321}""") }
        assertTrue(r.status.value in 400..499, "unknown outlet -> ${r.status} " + r.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/tasks") { bearerAuth(admin); contentType(ContentType.Application.Json)
            setBody("""{"task_uuid":"${uuid()}","task_type_code":"display_check","assignee_user_id":$sr,"title":"x","status":"completed"}""") }.status)
    }

    @Test
    fun outletOutsideReach() = testApplication {
        application { aronApi(wiring) }
        val srToken = client.token("sr1001", phone = true)
        val o = farOutlet()
        val rec = record("task", buildJsonObject { put("task_type_code", "follow_up"); put("assignee_user_id", sr); put("title", "far outlet"); put("outlet_id", o) })
        val acks = client.send(srToken, listOf(rec))
        println("OUTLET-ACKS $acks"); assertTrue(acks != listOf("accepted"), "task record with outlet in another zone accepted: $acks")
        val r = client.post("/v1/tasks") { bearerAuth(client.token("tso1001")); contentType(ContentType.Application.Json)
            setBody("""{"task_uuid":"${uuid()}","task_type_code":"display_check","assignee_user_id":$sr,"title":"x","outlet_id":$o}""") }
        assertEquals(HttpStatusCode.Forbidden, r.status, r.bodyAsText())
    }

    @Test
    fun cancelStates() = testApplication {
        application { aronApi(wiring) }
        val tso = client.token("tso1001"); val amo = client.token("amo1001"); val admin = client.token("admin1001"); val srToken = client.token("sr1001", phone = true)
        val a = uuid(); client.create(tso, a, sr)
        client.send(srToken, listOf(event(a, "resolved")))
        assertEquals("completed", st(client.list(tso), a))
        assertEquals(HttpStatusCode.Conflict, client.cancel(tso, a).status)
        val b = uuid(); client.create(tso, b, sr)
        assertEquals(HttpStatusCode.OK, client.cancel(admin, b).status)
        client.send(srToken, listOf(event(b, "reopened"), event(b, "resolved")))
        assertEquals("cancelled", st(client.list(tso), b))
        assertEquals(HttpStatusCode.BadRequest, client.cancel(tso, "not-a-uuid").status)
        val short = client.post("/v1/tasks/$b/cancel") { bearerAuth(tso); contentType(ContentType.Application.Json); setBody("""{"reason":"x"}""") }
        assertEquals(HttpStatusCode.BadRequest, short.status)
        val d = uuid(); client.create(admin, d, outsider)
        assertEquals(HttpStatusCode.Forbidden, client.cancel(tso, d).status)
        assertEquals(HttpStatusCode.Forbidden, client.cancel(amo, d).status)
    }

    @Test
    fun cancelOfDisabledAssigneesTaskByZoneTso() = testApplication {
        application { aronApi(wiring) }
        val tso = client.token("tso1001"); val admin = client.token("admin1001")
        val extra = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("INSERT INTO app.app_user (username, full_name, role, home_zone_id, must_change_password) SELECT 'sr9010','Extra','SR', home_zone_id, false FROM app.app_user WHERE id = $sr RETURNING id").mapTo(Long::class.java).one() }
        val t = uuid(); assertEquals(HttpStatusCode.OK, client.create(admin, t, extra).status)
        exec("UPDATE app.app_user SET status='disabled', disabled_at=now() WHERE id = $extra")
        val r = client.cancel(tso, t)
        assertEquals(HttpStatusCode.OK, r.status, "zone TSO cannot cancel a disabled assignee's task: " + r.bodyAsText())
    }

    @Test
    fun eventOrdering() = testApplication {
        application { aronApi(wiring) }
        val tso = client.token("tso1001"); val srToken = client.token("sr1001", phone = true)
        val t = uuid(); client.create(tso, t, sr)
        val at = "2027-01-03T03:30:00.000Z"
        client.send(srToken, listOf(event(t, "resolved", at), event(t, "reopened", at)))
        println("TIE-RESULT resolved-then-reopened same captured_at => " + st(client.list(tso), t))
        val u = uuid(); client.create(tso, u, sr)
        client.send(srToken, listOf(event(u, "reopened", "2027-01-03T03:50:00.000Z"), event(u, "resolved", "2027-01-03T03:40:00.000Z")))
        assertEquals("ongoing", st(client.list(tso), u))
        val l = client.list(tso).single { it["task_uuid"]!!.jsonPrimitive.content == u }
        assertTrue(l["resolved_at"] is JsonNull && l["resolution_note"] is JsonNull, "resolved fields after reopen: $l")
        val v = uuid(); client.create(tso, v, sr)
        client.send(srToken, listOf(event(v, "resolved", note = "সম্পন্ন হয়েছে")))
        assertEquals("সম্পন্ন হয়েছে", client.list(tso).single { it["task_uuid"]!!.jsonPrimitive.content == v }["resolution_note"]!!.jsonPrimitive.content)
    }

    @Test
    fun voidedTaskHidden() = testApplication {
        application { aronApi(wiring) }
        val tso = client.token("tso1001")
        val t = uuid(); client.create(tso, t, sr)
        exec("UPDATE app.task SET voided_at = now() WHERE client_uuid = CAST('$t' AS uuid)")
        assertTrue(t !in ids(client.list(tso)))
        assertEquals(HttpStatusCode.NotFound, client.cancel(tso, t).status)
    }
}
