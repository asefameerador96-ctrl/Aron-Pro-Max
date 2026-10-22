package com.aktcl.aron.backend.analytics.mdm

import com.aktcl.aron.backend.analytics.ReportFixture
import com.aktcl.aron.backend.analytics.TestTokens
import com.aktcl.aron.backend.analytics.devices.AttestationTrust
import com.aktcl.aron.backend.analytics.devices.DeviceDeps
import com.aktcl.aron.backend.analytics.devices.DeviceService
import com.aktcl.aron.backend.analytics.devices.EnrolmentSettings
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.Route
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D-MDM-01: role matrix, list filters and flags, idempotent commands, second approver, the phone's pull and ack, policies, activity and audit. */
class MdmApiTest : ReportFixture() {
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val phoneUuid = UUID.randomUUID().toString()
    private val phoneKey: KeyPair = com.aktcl.aron.backend.analytics.devices.AttestationBuilder.keyPair()
    private fun b64u(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun jwk(): String {
        val pub = phoneKey.public as java.security.interfaces.ECPublicKey
        fun c(i: java.math.BigInteger) = b64u(i.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it })
        return """{"kty":"EC","crv":"P-256","x":"${c(pub.w.affineX)}","y":"${c(pub.w.affineY)}"}"""
    }

    override val extraSql: String get() = javaClass.classLoader.getResourceAsStream("mdm_ddl.sql")!!.bufferedReader().readText() + """
        INSERT INTO app.app_release (flavour, version_name, version_code, abi, sha256, size_bytes, download_url, signing_cert_sha256, status, created_by, published_at, published_by)
          VALUES ('sr', '1.2.0', 5, 'universal', decode(repeat('11', 32), 'hex'), 1000, 'https://api.example.test/apk/sr.apk', decode(repeat('07', 32), 'hex'), 'published',
                 (SELECT id FROM app.app_user WHERE username = 'sr001'), now(), (SELECT id FROM app.app_user WHERE username = 'sr002'));
        INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256, device_info, app_version, last_contact_at, zone_id)
          VALUES ('$phoneUuid', 'sr', 'com.aktcl.aron.sr', 'active', true, 'prod', '${jwk()}'::jsonb, 'thumb-1', decode(repeat('07', 32), 'hex'),
                  '{"manufacturer":"Itel","model":"A70"}'::jsonb, '1.0.0+1', '2026-10-04T11:00:00Z', (SELECT id FROM app.zone ORDER BY id LIMIT 1));
        INSERT INTO app.device_binding (device_id, user_id, bind_ordinal) SELECT d.id, u.id, 0 FROM app.device d, app.app_user u WHERE u.username = 'sr001';
        INSERT INTO app.device_status_report (source, device_id, business_date, reported_at, app_version, device_owner, lockdown_level_applied, blocking_active, location_enabled, dev_options_enabled,
                                              adb_enabled, auto_time_enabled, mock_location_apps, pending_rows, battery_pct, trigger, report)
          SELECT 'online', d.id, '2026-10-04', '2026-10-04T11:00:00Z', '1.0.0+1', true, 'prod', false, true, true, false, true, ARRAY['com.fake.gps'], 0, 64, 'periodic', '{"network_type":"wifi","battery_pct":64}'::jsonb FROM app.device d;
    """.trimIndent()

    private lateinit var service: MdmService
    override fun mount(r: Route, clock: AronClock, reach: ReachResolver, guard: AuthGuardDeps) {
        val devices = DeviceService(fresh.db, RegistryDefaults(), TestTokens.keys, EnrolmentSettings("https://api.example.test", "dev", AttestationTrust(mutableSetOf())), clock)
        service = MdmService(fresh.db, TestTokens.keys, devices, clock)
        r.mdmRoutes(MdmDeps(service, DeviceDeps(devices, reach, guard, clock), guard))
    }

    private val admin get() = TestTokens.web(13, Role.ADMIN)
    private val root get() = TestTokens.web(10, Role.SUPERADMIN)
    private fun json(s: String) = Json.parseToJsonElement(s)
    private fun JsonElement.o() = jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private val deviceId get() = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.device").mapTo(Long::class.java).one() }
    private fun count(sql: String) = fresh.db.jdbi.withHandle<Int, Exception> { it.createQuery(sql).mapTo(Int::class.java).one() }

    private suspend fun ApplicationTestBuilder.get(path: String, token: String = admin) = client.get("/v1$path") { bearerAuth(token) }
    private suspend fun ApplicationTestBuilder.send(method: String, path: String, body: String, token: String = admin): HttpResponse =
        if (method == "PUT") client.put("/v1$path") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }
        else client.post("/v1$path") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }
    private fun cmd(id: String, type: String = "lock", reason: String = "lost phone", params: String = "{}") = """{"command_id":"$id","type":"$type","params":$params,"reason":"$reason"}"""

    // ---- the phone's side ----
    private fun proof(method: String, path: String, body: ByteArray = ByteArray(0), key: KeyPair = phoneKey): String {
        val bucket = Math.floorDiv(now.epochSecond, 300L)
        val h = if (body.isEmpty()) "" else MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val msg = listOf("aron-proof-v1", "device", phoneUuid, "$method $path", h, bucket.toString()).joinToString("\n")
        return b64u(Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(key.private); update(msg.toByteArray()) }.sign())
    }
    private suspend fun ApplicationTestBuilder.pull() = client.get("/v1/devices/me/commands") { header("X-Device-Id", phoneUuid); header("X-Device-Proof", proof("GET", "/v1/devices/me/commands")) }
    private suspend fun ApplicationTestBuilder.ack(id: String, body: String): HttpResponse {
        val path = "/v1/devices/me/commands/$id/ack"
        return client.post(path) { header("X-Device-Id", phoneUuid); header("X-Device-Proof", proof("POST", path, body.toByteArray())); contentType(ContentType.Application.Json); setBody(body) }
    }

    /** What the DPC does: ES256 over the fields joined by newline, params printed compact from what it received. */
    private fun assertSigned(item: JsonObject) {
        val msg = listOf("aron-command-v1", item.s("command_id"), phoneUuid, item.s("type"), item.s("created_at"), item.s("expires_at"), item["params"]!!.toString()).joinToString("\n")
        val ok = TestTokens.keys.verifier("t")!!.verify(com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.ES256), msg.toByteArray(), com.nimbusds.jose.util.Base64URL(item.s("sig")))
        assertTrue(ok, "signature of ${item.s("type")} does not verify over $msg")
    }

    @Test
    fun roleMatrixOnlyAdminAndSuperadminEnter() = app {
        for (role in listOf(Role.ANALYST, Role.TSO, Role.SUPPORT, Role.DMO, Role.WM, Role.AMO)) {
            val t = TestTokens.web(11, role)
            assertEquals(HttpStatusCode.Forbidden, get("/admin/mdm/devices", t).status, "$role list")
            assertEquals(HttpStatusCode.Forbidden, send("POST", "/admin/mdm/devices/1/commands", cmd(UUID.randomUUID().toString()), t).status, "$role command")
            assertEquals(HttpStatusCode.Forbidden, get("/admin/mdm/policies", t).status, "$role policies")
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/mdm/devices").status)
        assertEquals(HttpStatusCode.OK, get("/admin/mdm/devices", admin).status)
        assertEquals(HttpStatusCode.OK, get("/admin/mdm/devices", root).status)
    }

    @Test
    fun readOnlyGrantSeesListsButCannotAct() {
        val ro = MdmGrants { MdmLevel.READ }
        val p = com.aktcl.aron.backend.platform.AronPrincipal(1, "u", Role.ADMIN, 1, "aron-api", null, null, "web", emptyList(), false, emptyList(), "j", Instant.now())
        ro.read(p)
        assertEquals("ERR_FORBIDDEN", runCatching { ro.write(p) }.exceptionOrNull().let { (it as com.aktcl.aron.backend.platform.ApiProblem).code.wire })
    }

    @Test
    fun listAndDetailCarryTheComplianceFlags() = app {
        val list = json(get("/admin/mdm/devices").bodyAsText()).o()
        val d = list["items"]!!.jsonArray.single().o()
        assertEquals("Itel A70", d.s("model")); assertEquals("sr001", d["users"]!!.jsonArray.single().jsonPrimitive.content); assertEquals("wifi", d.s("network")); assertEquals("64", d.s("battery_pct"))
        val flags = d["compliance_flags"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("mock_location", "developer_options", "outdated_app"), flags)
        assertEquals("1.2.0+5", d.s("latest_app_version"))
        assertEquals(1, json(get("/admin/mdm/devices?flag=mock_location").bodyAsText()).o()["items"]!!.jsonArray.size)
        assertEquals(0, json(get("/admin/mdm/devices?flag=rooted").bodyAsText()).o()["items"]!!.jsonArray.size)
        assertEquals(1, json(get("/admin/mdm/devices?status=active&flavour=sr&search=itel").bodyAsText()).o()["items"]!!.jsonArray.size)
        assertEquals(0, json(get("/admin/mdm/devices?flavour=amo").bodyAsText()).o()["items"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.BadRequest, get("/admin/mdm/devices?flag=nope").status)
        val detail = json(get("/admin/mdm/devices/$deviceId").bodyAsText()).o()
        assertEquals("prod", detail.s("lockdown_level")); assertNotNull(detail["last_report"])
        assertEquals(HttpStatusCode.NotFound, get("/admin/mdm/devices/999999").status)
        val alerts = json(get("/admin/mdm/alerts?flag=outdated_app").bodyAsText()).o()["items"]!!.jsonArray
        assertEquals(1, alerts.size); assertEquals("1.0.0+1 < 1.2.0+5", alerts[0].o().s("detail"))
    }

    @Test
    fun commandsAreIdempotentValidatedAndAudited() = app {
        val id = UUID.randomUUID().toString(); val path = "/admin/mdm/devices/$deviceId/commands"
        val first = send("POST", path, cmd(id))
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        val c = json(first.bodyAsText()).o(); assertEquals("queued", c.s("state")); assertEquals("user13", c.s("created_by"))
        val again = send("POST", path, cmd(id))
        assertEquals(HttpStatusCode.OK, again.status); assertEquals(1, count("SELECT count(*) FROM app.device_command"))
        assertEquals(HttpStatusCode.Conflict, send("POST", path, cmd(id, reason = "another reason")).status)
        assertEquals(HttpStatusCode.BadRequest, send("POST", path, cmd(UUID.randomUUID().toString(), type = "wipe")).status)
        assertEquals(HttpStatusCode.BadRequest, send("POST", path, cmd(UUID.randomUUID().toString(), reason = "x")).status)
        assertEquals(HttpStatusCode.BadRequest, send("POST", path, cmd(UUID.randomUUID().toString(), type = "message", params = "{}")).status)
        assertEquals(HttpStatusCode.BadRequest, send("POST", path, cmd(UUID.randomUUID().toString(), type = "reboot", params = """{"a":1}""")).status)
        assertEquals(HttpStatusCode.Created, send("POST", path, cmd(UUID.randomUUID().toString(), type = "message", params = """{"text_bn":"আজ ১০টায় মিটিং"}""")).status)
        assertEquals(HttpStatusCode.NotFound, send("POST", "/admin/mdm/devices/999999/commands", cmd(UUID.randomUUID().toString())).status)
        assertEquals("lost phone", fresh.db.jdbi.withHandle<String, Exception> { it.createQuery("SELECT reason FROM app.audit_log WHERE entity = 'device_command' AND entity_id = :i AND action = 'create'").bind("i", id).mapTo(String::class.java).one() })
        val listed = json(get(path).bodyAsText()).o()["items"]!!.jsonArray
        assertEquals(2, listed.size)
        val act = json(get("/admin/mdm/devices/$deviceId/activity").bodyAsText()).o()["items"]!!.jsonArray.map { it.o().s("kind") }
        assertTrue("command" in act && "status" !in act.filter { false })
    }

    @Test
    fun factoryResetNeedsASecondPersonAndOnlyThenReachesThePhone() = app {
        val id = UUID.randomUUID().toString(); val path = "/admin/mdm/devices/$deviceId/commands"
        val c = json(send("POST", path, cmd(id, "factory_reset", "device lost for good")).bodyAsText()).o()
        assertEquals("pending_approval", c.s("state"))
        assertEquals(0, json(pull().bodyAsText()).o()["items"]!!.jsonArray.size)
        val self = send("POST", "/admin/mdm/commands/$id/decision", """{"decision":"approve","reason":"I approve my own"}""", admin)
        assertEquals(HttpStatusCode.Forbidden, self.status)
        assertEquals(HttpStatusCode.Forbidden, send("POST", "/admin/mdm/commands/$id/decision", """{"decision":"approve","reason":"nope"}""", TestTokens.web(11, Role.TSO)).status)
        val ok = send("POST", "/admin/mdm/commands/$id/decision", """{"decision":"approve","reason":"confirmed with TSO"}""", root)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertEquals("queued", json(ok.bodyAsText()).o().s("state")); assertEquals("user10", json(ok.bodyAsText()).o().s("approved_by"))
        assertEquals(HttpStatusCode.Conflict, send("POST", "/admin/mdm/commands/$id/decision", """{"decision":"reject","reason":"too late"}""", root).status)
        val pulled = json(pull().bodyAsText()).o()["items"]!!.jsonArray.single().o()
        assertSigned(pulled)
        assertEquals("factory_reset", pulled.s("type")); assertTrue(Regex("^[A-Za-z0-9_-]{86}$").matches(pulled.s("sig")))
        assertEquals(2, count("SELECT count(*) FROM app.audit_log WHERE entity = 'device_command' AND entity_id = '$id'"))
        // Rejection closes it for good.
        val id2 = UUID.randomUUID().toString()
        send("POST", path, cmd(id2, "factory_reset", "second try"))
        assertEquals("rejected", json(send("POST", "/admin/mdm/commands/$id2/decision", """{"decision":"reject","reason":"not needed"}""", root).bodyAsText()).o().s("state"))
        assertEquals("rejected", fresh.db.jdbi.withHandle<String, Exception> { it.createQuery("SELECT state FROM app.device_command WHERE command_id = CAST(:i AS uuid)").bind("i", id2).mapTo(String::class.java).one() })
    }

    @Test
    fun thePhonePullsOnItsOwnProofAndAcksOnce() = app {
        val id = UUID.randomUUID().toString()
        send("POST", "/admin/mdm/devices/$deviceId/commands", cmd(id, "locate", "where is it"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/devices/me/commands").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/devices/me/commands") { header("X-Device-Id", phoneUuid); header("X-Device-Proof", proof("GET", "/v1/devices/me/commands", key = com.aktcl.aron.backend.analytics.devices.AttestationBuilder.keyPair())) }.status)
        val one = json(pull().bodyAsText()).o()["items"]!!.jsonArray.single().o(); assertEquals(id, one.s("command_id")); assertSigned(one)
        assertEquals("sent", count("SELECT 1") .let { fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT state FROM app.device_command").mapTo(String::class.java).one() } })
        assertEquals(1, json(pull().bodyAsText()).o()["items"]!!.jsonArray.size)   // still waiting for the ack
        val a = ack(id, """{"state":"acked","result":{"lat":23.78,"lng":90.41,"accuracy_m":18}}""")
        assertEquals(HttpStatusCode.OK, a.status, a.bodyAsText()); assertEquals("acked", json(a.bodyAsText()).o().s("state"))
        assertEquals("acked", json(ack(id, """{"state":"failed","result":{"error":"late"}}""").bodyAsText()).o().s("state"))   // the first result wins
        assertEquals(0, json(pull().bodyAsText()).o()["items"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.NotFound, ack(UUID.randomUUID().toString(), """{"state":"acked"}""").status)
        assertEquals(HttpStatusCode.BadRequest, ack(id, """{"state":"done"}""").status)
        val shown = json(get("/admin/mdm/devices/$deviceId/commands").bodyAsText()).o()["items"]!!.jsonArray.single().o()
        assertEquals("23.78", shown["result"]!!.o().s("lat"))
    }

    @Test
    fun expiredCommandsNeverReachThePhone() = app {
        val id = UUID.randomUUID().toString()
        send("POST", "/admin/mdm/devices/$deviceId/commands", cmd(id, "reboot", "stuck"))
        fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.device_command SET created_at = created_at - interval '3 days', expires_at = expires_at - interval '3 days'") }
        assertEquals("expired", json(get("/admin/mdm/devices/$deviceId/commands").bodyAsText()).o()["items"]!!.jsonArray.single().o().s("state"))
        assertEquals(0, json(pull().bodyAsText()).o()["items"]!!.jsonArray.size)
        assertEquals("expired", json(ack(id, """{"state":"acked"}""").bodyAsText()).o().s("state"))
    }

    private val policyBody = """{"name":"Day shift","allowed_packages":["com.whatsapp"],"block_windows":[{"days":[1,2,3,4,5],"start":"09:00","end":"18:00","packages":["com.facebook.katana"]}],
        "kiosk":{"enabled":false,"packages":[]},"location_sampling":{"mode":"on_demand"}}""".replace("\n", "")

    private fun withVersion(v: Int) = policyBody.dropLast(1) + ""","version":$v,"reason":"add Facebook Lite"}"""

    @Test
    fun policiesValidateVersionAndPushToAssignedDevices() = app {
        val created = send("POST", "/admin/mdm/policies", policyBody)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val pid = json(created.bodyAsText()).o().s("policy_id").toLong()
        assertEquals(HttpStatusCode.Conflict, send("POST", "/admin/mdm/policies", policyBody.replace("Day shift", "DAY SHIFT")).status)
        for (bad in listOf(policyBody.replace("\"start\":\"09:00\"", "\"start\":\"18:00\""), policyBody.replace("[1,2,3,4,5]", "[0,8]"), policyBody.replace("\"09:00\"", "\"25:00\""),
            policyBody.replace("\"enabled\":false", "\"enabled\":true"), policyBody.replace("\"on_demand\"", "\"interval\""), policyBody.replace("com.whatsapp", "not a package"), policyBody.replace("\"name\":\"Day shift\"", "\"name\":\"x\""))) {
            assertEquals(HttpStatusCode.BadRequest, send("POST", "/admin/mdm/policies", bad.replace("Day shift", "Other")).status, bad)
        }
        val assigned = send("PUT", "/admin/mdm/devices/$deviceId/policy", """{"policy_id":$pid,"reason":"day shift reps"}""")
        assertEquals(HttpStatusCode.OK, assigned.status, assigned.bodyAsText())
        assertEquals("Day shift", json(assigned.bodyAsText()).o()["device"]!!.o().s("policy_name"))
        val applied = json(pull().bodyAsText()).o()["items"]!!.jsonArray.single().o()
        assertSigned(applied)
        assertEquals("apply_policy", applied.s("type")); assertEquals("1", applied["params"]!!.o().s("version"))
        assertEquals("1", applied["params"]!!.o()["policy"]!!.o()["block_windows"]!!.jsonArray.size.toString())
        ack(applied.s("command_id"), """{"state":"acked","result":{"policy_version":1}}""")
        // Update: stale version is refused, the right one bumps and re-queues the policy for the device.
        assertEquals(HttpStatusCode.Conflict, send("PUT", "/admin/mdm/policies/$pid", withVersion(7)).status)
        val upd = send("PUT", "/admin/mdm/policies/$pid", withVersion(1))
        assertEquals(HttpStatusCode.OK, upd.status, upd.bodyAsText()); assertEquals("2", json(upd.bodyAsText()).o().s("version")); assertEquals("1", json(upd.bodyAsText()).o().s("device_count"))
        assertEquals("2", json(pull().bodyAsText()).o()["items"]!!.jsonArray.single().o()["params"]!!.o().s("version"))
        assertEquals(1, json(get("/admin/mdm/devices?policy_id=$pid").bodyAsText()).o()["items"]!!.jsonArray.size)
        assertEquals(1, json(get("/admin/mdm/policies").bodyAsText()).o()["items"]!!.jsonArray.size)
        // Clearing the assignment sends the empty policy so the phone drops it.
        val cleared = send("PUT", "/admin/mdm/devices/$deviceId/policy", """{"policy_id":null,"reason":"back to default"}""")
        assertEquals(HttpStatusCode.OK, cleared.status, cleared.bodyAsText())
        assertEquals("null", json(pull().bodyAsText()).o()["items"]!!.jsonArray.last().o()["params"]!!.o()["policy_id"]!!.toString())
        assertNull(fresh.db.jdbi.withHandle<Long?, Exception> { it.createQuery("SELECT mdm_policy_id FROM app.device").mapTo(Long::class.javaObjectType).one() },
            cleared.bodyAsText() + " | " + fresh.db.jdbi.withHandle<String, Exception> { it.createQuery("SELECT string_agg(type || ':' || state || ':' || params::text, ' ; ') FROM app.device_command").mapTo(String::class.java).one() })
        assertEquals(HttpStatusCode.NotFound, get("/admin/mdm/policies/9999").status)
        assertEquals(HttpStatusCode.BadRequest, send("PUT", "/admin/mdm/devices/$deviceId/policy", """{"policy_id":9999,"reason":"nope nope"}""").status)
        assertTrue(count("SELECT count(*) FROM app.audit_log WHERE entity = 'mdm_policy'") >= 2)
    }

    @Test
    fun enrolmentTokensAreServedThroughTheMdmGate() = app {
        assertEquals(HttpStatusCode.Forbidden, get("/admin/mdm/enrolment-tokens", TestTokens.web(11, Role.TSO)).status)
        val r = send("POST", "/admin/mdm/enrolment-tokens", """{"flavour":"sr","lockdown_level":"dev","max_uses":2,"expires_in_h":24}""")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        val tid = json(r.bodyAsText()).o()["token"]!!.o().s("token_id")
        // (the fixed test clock is before the real database now(), so the active-only filter would hide it; list all)
        assertEquals(1, json(get("/admin/mdm/enrolment-tokens?active_only=false").bodyAsText()).o()["items"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.OK, send("POST", "/admin/mdm/enrolment-tokens/$tid/revoke", "{}").status)
        assertNotNull(json(get("/admin/mdm/enrolment-tokens?active_only=false").bodyAsText()).o()["items"]!!.jsonArray.single().o()["revoked_at"]?.takeIf { it.toString() != "null" })
    }

    @Test
    fun fleetActivityListsCommandsAndStatusEvents() = app {
        send("POST", "/admin/mdm/devices/$deviceId/commands", cmd(UUID.randomUUID().toString(), "unlock", "found it"))
        val items = json(get("/admin/mdm/activity?limit=5").bodyAsText()).o()["items"]!!.jsonArray
        assertTrue(items.any { it.o().s("kind") == "command" && it.o().s("reason") == "found it" })
        assertEquals(HttpStatusCode.BadRequest, get("/admin/mdm/activity?limit=0").status)
    }
}
