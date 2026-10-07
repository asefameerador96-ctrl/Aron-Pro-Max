package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.notify.PushNotifier
import com.aktcl.aron.backend.notify.PushSender
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.DeviceProof
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.Settings
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent checker for N-037 (push service). Each test states the behaviour the row (and D24-23, docs/24 s8.3) asks
 * for; a failing test is a confirmed defect.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PushCheckerTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private lateinit var keyFile: File
    private val now = java.util.concurrent.atomic.AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
    private val clock = AronClock { now.get() }
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val amoPhone = UUID.randomUUID().toString()
    private val day = "2027-01-03"
    private lateinit var devKeys: KeyPair

    /** (token, data, was the watched task visible to another connection when the push went out). */
    private data class Sent(val token: String, val data: Map<String, String>, val taskVisible: Boolean?)
    private val sent = CopyOnWriteArrayList<Sent>()
    @Volatile private var watchTask: String? = null
    private var sr = 0L
    private var amo = 0L
    private var zone = 0L
    private var minute = 0
    private fun tok(tag: String) = "fcm-chk-$tag-" + UUID.randomUUID().toString().replace("-", "")

    private lateinit var recorder: PushSender

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        devKeys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val jwk = ECKey.Builder(Curve.P_256, devKeys.public as ECPublicKey).build()
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            sr = h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one()
            amo = h.createQuery("SELECT id FROM app.app_user WHERE username = 'amo1001'").mapTo(Long::class.java).one()
            zone = h.createQuery("SELECT id FROM app.zone WHERE code = 'Z-MIR'").mapTo(Long::class.java).one()
            // The dev phone gets a real key, so every devices/me/* call must carry a valid s8.3 proof.
            h.createUpdate("UPDATE app.device SET public_key_jwk = CAST(:k AS jsonb), public_key_thumbprint = :t WHERE device_uuid = CAST(:u AS uuid)")
                .bind("k", jwk.toJSONString()).bind("t", jwk.computeThumbprint().toString()).bind("u", devPhone).execute()
            // An AMO phone (placeholder key, like the seed's dev phone) bound to amo1001: it uploads task records.
            h.createUpdate(
                """
                INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256, zone_id)
                VALUES (CAST(:u AS uuid), 'amo', 'com.aktcl.aron.amo', 'active', false, 'dev', '{"kty":"placeholder"}', :t, decode(repeat('00', 32), 'hex'), :z)
                """.trimIndent(),
            ).bind("u", amoPhone).bind("t", "chk-amo-$amoPhone").bind("z", zone).execute()
            h.execute("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, bound_via) SELECT d.id, $amo, 0, 'support' FROM app.device d WHERE d.device_uuid = '$amoPhone'")
        }
        keyFile = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        recorder = PushSender { token, data ->
            val visible = watchTask?.let { t -> count("SELECT count(*) FROM app.task WHERE client_uuid = '$t'") > 0 }
            sent += Sent(token, data, visible); true
        }
        wiring = Wiring.production(settings(), clock, pushSender = recorder)
    }

    private fun settings(extra: Map<String, String> = emptyMap()) =
        Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to keyFile.absolutePath) + extra)

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun exec(sql: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(sql) }
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private suspend fun HttpClient.login(user: String, client: String, device: String? = null): String {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody(if (device != null) """{"username":"$user","password":"$password","client":"$client","device_uuid":"$device"}""" else """{"username":"$user","password":"$password","client":"web"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content
    }

    private fun sign(keys: KeyPair, msg: String): String {
        val sig = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(keys.private); update(msg.toByteArray()) }.sign()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
    }

    private fun body(fcm: String) = """{"provider":"fcm","token":"$fcm","app_flavour":"sr"}"""
    private fun proofFor(b: String, keys: KeyPair = devKeys, uuid: String = devPhone, path: String = "PUT /v1/devices/me/push-token") =
        sign(keys, listOf("aron-proof-v1", "device", uuid, path, DeviceProof.sha256Hex(b.toByteArray()), DeviceProof.bucket(now.get().epochSecond).toString()).joinToString("\n"))

    private suspend fun HttpClient.register(token: String, fcm: String, proof: String? = proofFor(body(fcm))): HttpResponse = put("/v1/devices/me/push-token") {
        bearerAuth(token); header("X-Device-Id", devPhone); contentType(ContentType.Application.Json)
        proof?.let { header("X-Device-Proof", it) }
        setBody(body(fcm))
    }

    private suspend fun HttpClient.assign(web: String, taskUuid: String = UUID.randomUUID().toString()): HttpResponse = post("/v1/tasks") {
        bearerAuth(web); contentType(ContentType.Application.Json)
        setBody("""{"task_uuid":"$taskUuid","task_type_code":"display_check","assignee_user_id":$sr,"title":"Fix the display"}""")
    }

    private fun taskRecord(cu: String = UUID.randomUUID().toString()) = buildJsonObject {
        minute++
        put("type", "task"); put("client_uuid", cu); put("family_uuid", cu); put("rank", 0); put("schema_version", 1)
        put("business_date", day); put("captured_at", "2027-01-03T03:%02d:00.000Z".format(minute)); put("captured_elapsed_ms", 18330000 + minute); put("boot_count", 412)
        put("clock_offset_ms", 0); put("captured_offline", true); put("bundle_version", "$day:3"); put("bundle_stale", false); put("config_version", 1)
        put("payload", buildJsonObject { put("task_type_code", "follow_up"); put("assignee_user_id", sr); put("title", "control call follow up") })
    }

    private suspend fun HttpClient.upload(token: String, records: List<JsonObject>): List<String> {
        val b = buildJsonObject {
            put("batch_uuid", UUID.randomUUID().toString()); put("device_uuid", amoPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
            put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
            put("time_anchors", JsonArray(emptyList())); put("device_counts", buildJsonObject {}); put("records", JsonArray(records))
        }.toString()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b.toByteArray()) } }.toByteArray()
        val r = post("/v1/sync/batch") {
            bearerAuth(token); header("X-Device-Id", amoPhone); header("X-App-Version", "1.0.9+9"); header("Content-Encoding", "gzip")
            setBody(io.ktor.http.content.ByteArrayContent(gz, ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["acks"]!!.jsonArray.map { a -> a.jsonObject["status"]!!.jsonPrimitive.content + "/" + (a.jsonObject["code"]?.jsonPrimitive?.content ?: "") }
    }

    private fun settle(ms: Long = 700) = Thread.sleep(ms)

    // ------------------------------------------------------------------------------------------------ what should hold

    @Test @Order(1)
    fun proofIsRequiredAndBoundToBodyKeyAndPath() = testApplication {
        application { aronApi(wiring) }
        val phone = client.login("sr1001", "app_sr", devPhone)
        val fcm = tok("proof")
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        assertEquals(HttpStatusCode.Unauthorized, client.register(phone, fcm, proof = null).status, "no proof")
        assertEquals(HttpStatusCode.Unauthorized, client.register(phone, fcm, proof = proofFor(body(tok("x")))).status, "proof over another body")
        assertEquals(HttpStatusCode.Unauthorized, client.register(phone, fcm, proof = proofFor(body(fcm), keys = other)).status, "another device's key")
        assertEquals(HttpStatusCode.Unauthorized, client.register(phone, fcm, proof = proofFor(body(fcm), path = "PUT /v1/devices/me/status")).status, "another path")
        assertEquals(HttpStatusCode.Unauthorized, client.register(phone, fcm, proof = proofFor(body(fcm), uuid = UUID.randomUUID().toString())).status, "another device uuid")
        // Another phone's X-Device-Id with this phone's token.
        assertEquals(HttpStatusCode.Unauthorized, client.put("/v1/devices/me/push-token") {
            bearerAuth(phone); header("X-Device-Id", amoPhone); header("X-Device-Proof", proofFor(body(fcm))); contentType(ContentType.Application.Json); setBody(body(fcm))
        }.status)
        assertEquals(0, count("SELECT count(*) FROM app.push_token WHERE token = '$fcm'"))
        val ok = client.register(phone, fcm)
        assertEquals(HttpStatusCode.NoContent, ok.status, ok.bodyAsText())
        assertEquals("", ok.bodyAsText(), "the token is never echoed")
        // A web (non-phone) session cannot register.
        val web = client.login("amo1001", "web")
        assertEquals(HttpStatusCode.Forbidden, client.put("/v1/devices/me/push-token") { bearerAuth(web); contentType(ContentType.Application.Json); setBody(body(tok("w"))) }.status)
    }

    @Test @Order(2)
    fun aPhoneUploadedTaskNudgesOnceAndAResendNudgesNothing() = testApplication {
        application { aronApi(wiring) }
        val phone = client.login("sr1001", "app_sr", devPhone)
        val fcm = tok("rec")
        assertEquals(HttpStatusCode.NoContent, client.register(phone, fcm).status)
        val amoToken = client.login("amo1001", "app_amo", amoPhone)
        settle(); sent.clear()
        val rec = taskRecord()
        assertEquals("accepted", client.upload(amoToken, listOf(rec)).single().substringBefore('/'))
        settle()
        assertEquals(listOf(fcm), sent.map { it.token })
        assertEquals(mapOf("kind" to "sync_nudge", "reason" to "task_assigned"), sent.single().data)
        sent.clear()
        assertEquals("duplicate", client.upload(amoToken, listOf(rec)).single().substringBefore('/'))
        settle()
        assertEquals(0, sent.size, "a resent task record pushes nothing")
    }

    @Test @Order(3)
    fun settingsToStringHidesTheServiceAccount() {
        val s = runCatching { settings(mapOf("ARON_FCM_SERVICE_ACCOUNT_JSON" to """{"private_key":"chk-secret-pk-123"}""")) }.getOrNull() ?: return
        assertFalse("chk-secret-pk-123" in s.toString())
    }

    // ------------------------------------------------------------------------------------------------ defects

    /** A revoked (lost/stolen, replaced) phone must no longer receive nudges; push_token even has 'device_revoked'. */
    @Test @Order(10)
    fun aRevokedPhoneGetsNoPush() = testApplication {
        application { aronApi(wiring) }
        val phone = client.login("sr1001", "app_sr", devPhone)
        val fcm = tok("revoked")
        assertEquals(HttpStatusCode.NoContent, client.register(phone, fcm).status)
        val web = client.login("amo1001", "web")
        // What POST /v1/admin/devices/{id}/replace does to the old phone (DeviceReplace.kt:55-56).
        exec("UPDATE app.device SET status = 'revoked', status_reason = 'stolen' WHERE device_uuid = '$devPhone'")
        try {
            settle(); sent.clear()
            assertEquals(HttpStatusCode.OK, client.assign(web).status)
            settle()
            assertEquals(emptyList(), sent.map { it.token }, "nudge sent to a revoked phone")
        } finally {
            exec("UPDATE app.device SET status = 'active', status_reason = null WHERE device_uuid = '$devPhone'")
        }
    }

    /** After the SR logs out of the (shared) phone, the phone must not keep getting that SR's task nudges. */
    @Test @Order(11)
    fun aLoggedOutUserGetsNoPush() = testApplication {
        application { aronApi(wiring) }
        val phone = client.login("sr1001", "app_sr", devPhone)
        val fcm = tok("logout")
        assertEquals(HttpStatusCode.NoContent, client.register(phone, fcm).status)
        val out = client.post("/v1/auth/logout") {
            bearerAuth(phone); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); contentType(ContentType.Application.Json); setBody("""{"scope":"all"}""")
        }
        assertTrue(out.status.isSuccess(), "logout ${out.status} ${out.bodyAsText()}")
        val web = client.login("amo1001", "web")
        settle(); sent.clear()
        assertEquals(HttpStatusCode.OK, client.assign(web).status)
        settle()
        println("CHK logout: live tokens=${count("SELECT count(*) FROM app.push_token WHERE user_id = $sr AND revoked_at IS NULL")} sent=${sent.size}")
        assertEquals(emptyList(), sent.map { it.token }, "nudge sent to a phone the SR logged out of")
    }

    /** cfg.notify.task_push_enabled is zone-scoped in the registry; a zone override 'false' must stop that zone's task pushes. */
    @Test @Order(45)
    fun aZoneOverrideOfTheTaskSwitchIsHonoured() = testApplication {
        application { aronApi(wiring) }
        val phone = client.login("sr1001", "app_sr", devPhone)
        val fcm = tok("zone")
        assertEquals(HttpStatusCode.NoContent, client.register(phone, fcm).status)
        exec("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'chk zone push off' FROM app.cfg_version")
        exec("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.notify.task_push_enabled', 'zone', $zone, 'false'::jsonb, now() - interval '1 day', max(config_version), 'chk' FROM app.cfg_version")
        now.updateAndGet { it.plusSeconds(61) } // past every config cache
        val web = client.login("amo1001", "web")
        settle(); sent.clear()
        assertEquals(HttpStatusCode.OK, client.assign(web).status)
        settle()
        assertEquals(emptyList(), sent.map { it.token }, "task push sent although the SR's zone switched it off")
    }

    /** A task record whose savepoint rolls back (acked rejected/server_error, not stored) must not push; its resend pushes once. */
    @Test @Order(30)
    fun aRolledBackTaskRecordSendsNoPushAndItsResendOnlyOne() {
        var failing = true
        val boom = object : RecordHandler {
            override val types = setOf("task")
            override fun afterStored(h: Handle, rec: IngestRecord, serverId: Long?) { if (failing) throw IllegalStateException("chk: later step of the record fails") }
        }
        val w = Wiring.production(settings(), clock, extraRecordHandlers = listOf(boom), pushSender = recorder)
        try {
            testApplication {
                application { aronApi(w) }
                val phone = client.login("sr1001", "app_sr", devPhone)
                val fcm = tok("rollback")
                assertEquals(HttpStatusCode.NoContent, client.register(phone, fcm).status)
                val amoToken = client.login("amo1001", "app_amo", amoPhone)
                settle(); sent.clear()
                val cu = UUID.randomUUID().toString()
                val rec = taskRecord(cu)
                val first = client.upload(amoToken, listOf(rec))
                settle()
                val afterRollback = sent.size
                assertEquals(0, count("SELECT count(*) FROM app.task WHERE client_uuid = '$cu'"), "rolled back: $first")
                failing = false
                assertEquals("accepted", client.upload(amoToken, listOf(rec)).single().substringBefore('/'))
                settle()
                println("CHK rollback: first=$first pushesAfterRollback=$afterRollback pushesTotal=${sent.size}")
                assertEquals(0, afterRollback, "a push went out for a task that was rolled back (not stored)")
                assertEquals(1, sent.size, "one task, one push")
            }
        } finally { w.database?.close() }
    }

    /** The nudge must go out only after the task commits, else the woken phone syncs before the task is there and never gets another nudge. */
    @Test @Order(31)
    fun theRecordPathNudgesOnlyAfterTheTaskCommits() {
        val slow = object : RecordHandler {
            override val types = setOf("task")
            // Any later work of the same family transaction (another handler, outOfBounds, the next records of the family).
            override fun afterStored(h: Handle, rec: IngestRecord, serverId: Long?) { Thread.sleep(1500) }
        }
        val w = Wiring.production(settings(), clock, extraRecordHandlers = listOf(slow), pushSender = recorder)
        try {
            testApplication {
                application { aronApi(w) }
                val phone = client.login("sr1001", "app_sr", devPhone)
                val fcm = tok("commit")
                assertEquals(HttpStatusCode.NoContent, client.register(phone, fcm).status)
                val amoToken = client.login("amo1001", "app_amo", amoPhone)
                settle(); sent.clear()
                val cu = UUID.randomUUID().toString()
                watchTask = cu
                assertEquals("accepted", client.upload(amoToken, listOf(taskRecord(cu))).single().substringBefore('/'))
                settle()
                watchTask = null
                println("CHK commit order: $sent")
                assertEquals(1, sent.size)
                assertEquals(true, sent.single().taskVisible, "the push went out before the task was committed (a sync then would miss it)")
            }
        } finally { watchTask = null; w.database?.close() }
    }

    /** One slow or retrying FCM call (firebase-admin retries 5xx with backoff) must not hold every other user's nudge past 30 s. */
    @Test @Order(40)
    fun oneSlowSendDoesNotHoldOtherUsersNudges() {
        val slowTok = tok("slow"); val fastTok = tok("fast")
        exec("UPDATE app.push_token SET revoked_at = now(), revoke_reason = 'replaced' WHERE revoked_at IS NULL")
        exec("INSERT INTO app.push_token (device_id, user_id, app_flavour, token, token_sha256) SELECT id, $sr, 'sr', '$slowTok', sha256(convert_to('$slowTok','UTF8')) FROM app.device WHERE device_uuid = '$devPhone'")
        exec("INSERT INTO app.push_token (device_id, user_id, app_flavour, token, token_sha256) SELECT id, $amo, 'amo', '$fastTok', sha256(convert_to('$fastTok','UTF8')) FROM app.device WHERE device_uuid = '$amoPhone'")
        val got = CopyOnWriteArrayList<Pair<String, Long>>()
        val t0 = System.nanoTime()
        val n = PushNotifier(fresh.db, RegistryDefaults(), { token, _ -> if (token == slowTok) Thread.sleep(3_000); got += token to (System.nanoTime() - t0) / 1_000_000; true }, clock) { _, _ -> true }
        n.nudge(sr, "task_assigned")
        n.nudge(amo, "task_assigned")
        Thread.sleep(1_000)
        val fastAt = got.firstOrNull { it.first == fastTok }?.second
        n.drain(10_000)
        println("CHK head-of-line: $got")
        assertTrue(fastAt != null, "the other user's nudge waited behind one slow FCM call (single thread, unbounded queue)")
    }

    @Test @Order(50)
    fun theGlobalOpsSwitchStopsEveryPush() = testApplication {
        application { aronApi(wiring) }
        exec("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'chk ops push off' FROM app.cfg_version")
        exec("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.ops.push_enabled', 'global', 0, 'false'::jsonb, now() - interval '1 day', max(config_version), 'chk' FROM app.cfg_version")
        now.updateAndGet { it.plusSeconds(61) }
        val phone = client.login("sr1001", "app_sr", devPhone)
        assertEquals(HttpStatusCode.NoContent, client.register(phone, tok("ops")).status)
        val amoToken = client.login("amo1001", "app_amo", amoPhone)
        settle(); sent.clear()
        client.upload(amoToken, listOf(taskRecord()))
        settle()
        assertEquals(0, sent.size)
    }
}
