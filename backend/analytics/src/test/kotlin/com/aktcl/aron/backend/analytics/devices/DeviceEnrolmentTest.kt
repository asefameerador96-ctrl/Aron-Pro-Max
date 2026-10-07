package com.aktcl.aron.backend.analytics.devices

import com.aktcl.aron.backend.analytics.ReportFixture
import com.aktcl.aron.backend.analytics.TestTokens
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.contract.Role
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
import io.ktor.server.routing.Route
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** N-031: tokens work once and expire; the attestation, package and certificate are checked; the registry shows model, Android, policy version and the last status; revoke blocks. */
class DeviceEnrolmentTest : ReportFixture() {
    private val certDigest = ByteArray(32) { (it + 7).toByte() }
    private val certHex = certDigest.joinToString("") { "%02x".format(it) }
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private lateinit var trustedRoots: MutableSet<String>
    private lateinit var service: DeviceService

    override val extraSql = """
        INSERT INTO app.app_release (flavour, version_name, version_code, abi, sha256, size_bytes, download_url, signing_cert_sha256, status, created_by, published_at, published_by)
          VALUES ('sr', '1.0.0', 1, 'universal', decode(repeat('11', 32), 'hex'), 1000, 'https://api.example.test/apk/sr-1.0.0.apk', decode('${(0 until 32).joinToString("") { "%02x".format((it + 7) and 0xff) }}', 'hex'), 'published',
                 (SELECT id FROM app.app_user WHERE username = 'sr001'), now(), (SELECT id FROM app.app_user WHERE username = 'sr002'));
    """.trimIndent()

    override fun mount(r: Route, clock: AronClock, reach: ReachResolver, guard: AuthGuardDeps) {
        trustedRoots = mutableSetOf()
        service = DeviceService(fresh.db, RegistryDefaults(), TestTokens.keys, EnrolmentSettings("https://api.example.test", "dev", AttestationTrust(trustedRoots)), clock)
        r.deviceRoutes(DeviceDeps(service, reach, guard, clock))
    }

    // ---- helpers ----
    private fun b64u(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private class Phone(val uuid: String = UUID.randomUUID().toString(), val key: KeyPair = AttestationBuilder.keyPair())

    private fun jwk(k: KeyPair): String {
        val pub = k.public as java.security.interfaces.ECPublicKey
        fun c(i: java.math.BigInteger) = b64u(i.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it })
        return """{"kty":"EC","crv":"P-256","x":"${c(pub.w.affineX)}","y":"${c(pub.w.affineY)}"}"""
    }

    private val info = """{"manufacturer":"Itel","model":"A70","os_api_level":33,"os_version":"13","abi":"arm64-v8a","ram_mb":2048}"""

    private fun status(policy: Long? = 1, owner: Boolean = true, mock: String = "", extra: String = "") =
        """{"reported_at":"2026-10-04T11:59:00.000Z","trigger":"periodic","app_version":"1.0.0+1","device_info":$info,"device_owner":$owner,"lockdown_level_applied":"prod","policy_version_applied":${policy ?: "null"},"blocking_active":false,"location_enabled":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_location_apps":[$mock],"pending_rows":4,"battery_pct":81$extra}"""

    private fun enrolBody(token: String, phone: Phone, chain: AttestationBuilder.Built, pkg: String = "com.aktcl.aron.sr", owner: Boolean = true, cert: String = certHex, withStatus: Boolean = false) =
        """{"enrolment_token":"$token","device_uuid":"${phone.uuid}","app_package":"$pkg","app_version":"1.0.0+1","app_signing_cert_sha256":"$cert","device_owner":$owner,"public_key":${jwk(phone.key)},"key_attestation_chain":[${chain.chainBase64.joinToString(",") { "\"$it\"" }}],"device_info":$info${if (withStatus) ""","status":${status()}""" else ""}}"""

    private fun goodChain(phone: Phone, token: String, pkg: String = "com.aktcl.aron.sr", digest: ByteArray = certDigest, challenge: ByteArray = sha(token.toByteArray()), locked: Boolean = true, boot: Int = 0, level: Int = 1, key: java.security.PublicKey = phone.key.public) =
        AttestationBuilder.chain(key, AttestationBuilder.keyDescription(challenge, pkg, digest, level, locked, boot))

    private suspend fun ApplicationTestBuilder.mintToken(lockdown: String = "dev", maxUses: Int = 1, uid: Long = 13, role: Role = Role.ADMIN): JsonObject {
        val r = client.post("/v1/admin/enrolment-tokens") { bearerAuth(TestTokens.web(uid, role)); contentType(ContentType.Application.Json); setBody("""{"flavour":"sr","lockdown_level":"$lockdown","max_uses":$maxUses,"expires_in_h":24,"zone_id":$z1}""") }
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject
    }

    private suspend fun ApplicationTestBuilder.enrol(body: String): HttpResponse = client.post("/v1/devices/enrol") { contentType(ContentType.Application.Json); setBody(body) }

    private fun proof(phone: Phone, method: String, path: String, body: ByteArray = ByteArray(0), bucketShift: Long = 0): String {
        val bucket = Math.floorDiv(now.epochSecond, 300L) + bucketShift
        val msg = listOf("aron-proof-v1", "device", phone.uuid, "$method $path", if (body.isEmpty()) "" else sha(body).joinToString("") { "%02x".format(it) }, bucket.toString()).joinToString("\n")
        val sig = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(phone.key.private); update(msg.toByteArray()) }.sign()
        return b64u(sig)
    }

    private suspend fun ApplicationTestBuilder.deviceGet(phone: Phone, path: String, proofOf: Phone = phone, ifNoneMatch: String? = null) =
        client.get(path) { header("X-Device-Id", phone.uuid); header("X-Device-Proof", proof(proofOf, "GET", path)); ifNoneMatch?.let { header("If-None-Match", it) } }

    private suspend fun ApplicationTestBuilder.devicePost(phone: Phone, path: String, body: String) =
        client.post(path) { header("X-Device-Id", phone.uuid); header("X-Device-Proof", proof(phone, "POST", path, body.toByteArray())); contentType(ContentType.Application.Json); setBody(body) }

    private fun deviceId(phone: Phone) = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.device WHERE device_uuid = CAST(:u AS uuid)").bind("u", phone.uuid).mapTo(Long::class.java).one() }
    private fun count(sql: String) = fresh.db.jdbi.withHandle<Int, Exception> { it.createQuery(sql).mapTo(Int::class.java).one() }
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    // ---- tests ----

    @Test
    fun aTokenWorksOnceAndTheDeviceRecordShowsModelAndroidAndPolicy() = app {
        val t = mintToken(); val secret = t.s("enrolment_token")
        assertEquals(43, secret.length)
        assertTrue(t["qr_payload"]!!.jsonObject["android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"]!!.jsonPrimitive.content == "com.aktcl.aron.sr/com.aktcl.aron.dpc.AronDeviceAdminReceiver")
        assertEquals(b64u(certDigest), t["qr_payload"]!!.jsonObject["android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"]!!.jsonPrimitive.content)
        assertEquals(secret, t["qr_payload"]!!.jsonObject["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"]!!.jsonObject.s("aron.enrolment_token"))
        // The secret is stored only as a hash, and never listed.
        assertEquals(0, count("SELECT count(*) FROM app.enrolment_token WHERE token_sha256 = convert_to('$secret', 'UTF8')"))
        assertFalse(client.get("/v1/admin/enrolment-tokens") { bearerAuth(TestTokens.web(13, Role.ADMIN)) }.bodyAsText().contains(secret))

        val phone = Phone()
        val ok = enrol(enrolBody(secret, phone, goodChain(phone, secret), withStatus = true))
        assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
        val res = Json.parseToJsonElement(ok.bodyAsText()).jsonObject
        assertEquals("dev", res.s("lockdown_level")); assertEquals("normal", res.s("trust_level"))
        assertEquals(RegistryDefaults().configVersion(), res["policy"]!!.jsonObject["policy_version"]!!.jsonPrimitive.content.toLong())
        // A lost response: the same phone and key repeating the call gets the same device and uses no further slot.
        val again = Json.parseToJsonElement(enrol(enrolBody(secret, phone, goodChain(phone, secret))).bodyAsText()).jsonObject
        assertEquals(res.s("device_id"), again.s("device_id")); assertEquals(1, count("SELECT used_count FROM app.enrolment_token"))
        // Another phone on the used-up token is refused.
        val other = Phone()
        val used = enrol(enrolBody(secret, other, goodChain(other, secret)))
        assertEquals(HttpStatusCode.Forbidden, used.status); assertTrue(used.bodyAsText().contains("ERR_ENROLMENT_TOKEN_EXHAUSTED"))
        assertEquals(1, count("SELECT count(*) FROM app.device"))
        // The registry: model, Android version, app version, policy version, last status report.
        val dev = Json.parseToJsonElement(client.get("/v1/admin/devices/${deviceId(phone)}") { bearerAuth(TestTokens.web(13, Role.ADMIN)) }.bodyAsText()).jsonObject
        assertEquals("A70", dev["device_info"]!!.jsonObject.s("model")); assertEquals("13", dev["device_info"]!!.jsonObject.s("os_version")); assertEquals("1.0.0+1", dev.s("app_version"))
        assertEquals("enrolled", dev.s("status")); assertEquals("true", dev.s("hardware_backed_key")); assertEquals("81", dev["last_status"]!!.jsonObject.s("battery_pct"))
    }

    @Test
    fun anExpiredRevokedOrUnknownTokenIsRefusedAndNothingIsStored() = app {
        val phone = Phone()
        val t = mintToken(); val secret = t.s("enrolment_token")
        fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.enrolment_token SET created_at = TIMESTAMPTZ '2026-10-03 11:00Z', expires_at = TIMESTAMPTZ '2026-10-04 11:00Z'") }
        val expired = enrol(enrolBody(secret, phone, goodChain(phone, secret)))
        assertEquals(HttpStatusCode.Forbidden, expired.status); assertTrue(expired.bodyAsText().contains("ERR_ENROLMENT_TOKEN_EXPIRED"))
        val t2 = mintToken(); val s2 = t2.s("enrolment_token")
        val id2 = t2["token"]!!.jsonObject.s("token_id")
        assertEquals(HttpStatusCode.OK, client.post("/v1/admin/enrolment-tokens/$id2/revoke") { bearerAuth(TestTokens.web(13, Role.ADMIN)) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/v1/admin/enrolment-tokens/$id2/revoke") { bearerAuth(TestTokens.web(13, Role.ADMIN)) }.status)   // idempotent
        val revoked = enrol(enrolBody(s2, phone, goodChain(phone, s2)))
        assertEquals(HttpStatusCode.Forbidden, revoked.status); assertTrue(revoked.bodyAsText().contains("ERR_ENROLMENT_TOKEN_INVALID"))
        val unknown = "A".repeat(43)
        assertTrue(enrol(enrolBody(unknown, phone, goodChain(phone, unknown))).bodyAsText().contains("ERR_ENROLMENT_TOKEN_INVALID"))
        assertEquals(0, count("SELECT count(*) FROM app.device"))
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/admin/enrolment-tokens") { bearerAuth(TestTokens.web(11, Role.TSO)); contentType(ContentType.Application.Json); setBody("""{"flavour":"sr","lockdown_level":"dev","max_uses":1,"expires_in_h":1}""") }.status)
    }

    @Test
    fun theAttestationIsCheckedAgainstTheTokenThePackageTheCertificateAndTheKey() = app {
        val t = mintToken(maxUses = 20); val secret = t.s("enrolment_token")
        suspend fun attempt(name: String, mutate: (Phone) -> Pair<AttestationBuilder.Built, String>) {
            val p = Phone(); val (chain, body) = mutate(p)
            val r = enrol(body.ifEmpty { enrolBody(secret, p, chain) })
            assertEquals(HttpStatusCode.Forbidden, r.status, name); assertTrue(r.bodyAsText().contains("ERR_ENROLMENT_ATTESTATION_FAILED"), name + ": " + r.bodyAsText())
        }
        attempt("wrong challenge") { p -> goodChain(p, secret, challenge = sha("another".toByteArray())) to "" }
        attempt("wrong attested package") { p -> goodChain(p, secret, pkg = "com.aktcl.aron.tso") to "" }
        attempt("digest of another certificate") { p -> goodChain(p, secret, digest = ByteArray(32) { 9 }) to "" }
        attempt("attested key is not the enrolment key") { p -> goodChain(p, secret, key = AttestationBuilder.keyPair().public) to "" }
        attempt("package of the wrong flavour") { p -> val c = goodChain(p, secret); c to enrolBody(secret, p, c, pkg = "com.aktcl.aron.tso") }
        attempt("claimed certificate not of any release") { p -> val c = goodChain(p, secret, digest = ByteArray(32) { 1 }); c to enrolBody(secret, p, c, cert = "01".repeat(32)) }
        attempt("a chain whose links are not signed by each other") { p -> val a = goodChain(p, secret); val b = goodChain(p, secret); AttestationBuilder.Built(listOf(a.chainBase64[0], b.chainBase64[1], b.chainBase64[2]), a.rootSha256, a.rootKey) to "" }
        assertEquals(0, count("SELECT count(*) FROM app.device")); assertEquals(0, count("SELECT used_count FROM app.enrolment_token"))
        // A genuine chain on the same token still works afterwards: failures consume nothing.
        val good = Phone(); assertEquals(HttpStatusCode.Created, enrol(enrolBody(secret, good, goodChain(good, secret))).status)
    }

    @Test
    fun productionDemandsATrustedRootAnOwnerAndAnIntactBoot() = app {
        val t = mintToken(lockdown = "prod", maxUses = 20); val secret = t.s("enrolment_token")
        suspend fun status(owner: Boolean, locked: Boolean, boot: Int, level: Int, root: Boolean): HttpResponse {
            val p = Phone(); val c = goodChain(p, secret, locked = locked, boot = boot, level = level)
            trustedRoots.clear(); if (root) trustedRoots += c.rootSha256
            return enrol(enrolBody(secret, p, c, owner = owner))
        }
        assertEquals(HttpStatusCode.Created, status(owner = true, locked = true, boot = 0, level = 1, root = true).status)
        assertTrue(status(owner = true, locked = true, boot = 0, level = 1, root = false).bodyAsText().contains("ERR_ENROLMENT_ATTESTATION_FAILED"))     // not a trusted root (and none configured)
        assertTrue(status(owner = false, locked = true, boot = 0, level = 1, root = true).bodyAsText().contains("ERR_ENROLMENT_ATTESTATION_FAILED"))
        assertTrue(status(owner = true, locked = false, boot = 0, level = 1, root = true).bodyAsText().contains("ERR_ENROLMENT_ATTESTATION_FAILED"))     // unlocked bootloader
        assertTrue(status(owner = true, locked = true, boot = 2, level = 1, root = true).bodyAsText().contains("ERR_ENROLMENT_ATTESTATION_FAILED"))      // self-signed boot state
        assertTrue(status(owner = true, locked = true, boot = 0, level = 0, root = true).bodyAsText().contains("ERR_ENROLMENT_ATTESTATION_FAILED"))      // software-only key
        assertEquals(1, count("SELECT count(*) FROM app.device"))
    }

    @Test
    fun thePolicyNeedsTheDevicesOwnProofAndHonoursItsEtag() = app {
        val t = mintToken(); val secret = t.s("enrolment_token"); val phone = Phone(); val other = Phone()
        enrol(enrolBody(secret, phone, goodChain(phone, secret)))
        val p = deviceGet(phone, "/v1/devices/me/policy")
        assertEquals(HttpStatusCode.OK, p.status); val etag = p.headers["ETag"]!!
        val body = Json.parseToJsonElement(p.bodyAsText()).jsonObject
        assertEquals("dev", body.s("lockdown_level")); assertEquals("false", body["user_restrictions"]!!.jsonObject.s("no_install_apps")); assertEquals("blocklist", body["app_control"]!!.jsonObject.s("mode"))
        assertTrue(body["app_control"]!!.jsonObject["blocked_packages"]!!.jsonArray.any { it.jsonPrimitive.content == "com.facebook.katana" })
        assertEquals(HttpStatusCode.NotModified, deviceGet(phone, "/v1/devices/me/policy", ifNoneMatch = etag).status)
        // No proof, a proof of another phone's key, a stale bucket, an unknown phone: all 401, the same answer.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/devices/me/policy") { header("X-Device-Id", phone.uuid) }.status)
        assertEquals(HttpStatusCode.Unauthorized, deviceGet(phone, "/v1/devices/me/policy", proofOf = other).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/devices/me/policy") { header("X-Device-Id", phone.uuid); header("X-Device-Proof", proof(phone, "GET", "/v1/devices/me/policy", bucketShift = -2)) }.status)
        assertEquals(HttpStatusCode.Unauthorized, deviceGet(Phone(), "/v1/devices/me/policy").status)
        // A proof is for one path: the policy proof does not open the nonce endpoint.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/devices/nonce") { header("X-Device-Id", phone.uuid); header("X-Device-Proof", proof(phone, "GET", "/v1/devices/me/policy")) }.status)
        val n = devicePost(phone, "/v1/devices/nonce", "")
        assertEquals(HttpStatusCode.OK, n.status); assertEquals(43, Json.parseToJsonElement(n.bodyAsText()).jsonObject.s("nonce").length)
        assertEquals(1, count("SELECT count(*) FROM app.device_nonce"))
    }

    @Test
    fun statusReportsFeedTheComplianceViewDirectivesAndTrust() = app {
        val t = mintToken(lockdown = "prod", maxUses = 5); val secret = t.s("enrolment_token"); val phone = Phone()
        val chain = goodChain(phone, secret); trustedRoots += chain.rootSha256
        assertEquals(HttpStatusCode.Created, enrol(enrolBody(secret, phone, chain)).status)
        val id = deviceId(phone)
        // An admin asks for a status; the phone gets it once with its next report and acknowledges it.
        val dir = client.post("/v1/admin/devices/$id/directives") { bearerAuth(TestTokens.web(13, Role.ADMIN)); contentType(ContentType.Application.Json); setBody("""{"type":"send_status","ttl_h":2}""") }
        assertEquals(HttpStatusCode.Created, dir.status); assertEquals(86, Json.parseToJsonElement(dir.bodyAsText()).jsonObject.s("sig").length)
        val ack = Json.parseToJsonElement(devicePost(phone, "/v1/devices/me/status", status(policy = 1)).bodyAsText()).jsonObject
        assertEquals("normal", ack["trust"]!!.jsonObject.s("trust_level")); assertEquals(1, ack["directives"]!!.jsonArray.size)
        assertEquals(0, Json.parseToJsonElement(devicePost(phone, "/v1/devices/me/status", status(policy = 1)).bodyAsText()).jsonObject["directives"]!!.jsonArray.size)
        devicePost(phone, "/v1/devices/me/status", status(policy = 1).replace("\"periodic\"", "\"directive\""))
        assertEquals(1, count("SELECT count(*) FROM app.device_directive WHERE acked_at IS NOT NULL"))
        // The registry shows what the phone said; a phone that lost device-owner or reports mock-location apps drops to low trust.
        val dev = Json.parseToJsonElement(client.get("/v1/admin/devices/$id") { bearerAuth(TestTokens.web(13, Role.ADMIN)) }.bodyAsText()).jsonObject
        assertEquals("1", dev.s("policy_version_applied")); assertEquals("4", dev.s("pending_rows_reported"))
        val low = Json.parseToJsonElement(devicePost(phone, "/v1/devices/me/status", status(mock = "\"com.fake.gps\"")).bodyAsText()).jsonObject
        assertEquals("low", low["trust"]!!.jsonObject.s("trust_level"))
        assertEquals(1, count("SELECT count(*) FROM app.device WHERE trust_level = 'low'"))
        assertEquals(4, Json.parseToJsonElement(client.get("/v1/admin/devices/$id/status-history") { bearerAuth(TestTokens.web(13, Role.ADMIN)) }.bodyAsText()).jsonObject["items"]!!.jsonArray.size)
        // Strict body: an unknown member is 400; so is a Play Integrity token with an "unavailable" marker beside it.
        assertEquals(HttpStatusCode.BadRequest, devicePost(phone, "/v1/devices/me/status", status(extra = ""","zone_ids":[1]""")).status)
        assertEquals(HttpStatusCode.BadRequest, devicePost(phone, "/v1/devices/me/status", status(extra = ""","play_integrity":{"token":"x","nonce":"${"n".repeat(43)}"},"play_integrity_unavailable":{"reason":"offline"}""")).status)
    }

    @Test
    fun revokingBlocksTheDeviceAtOnceAndEveryStateChangeIsAudited() = app {
        val t = mintToken(maxUses = 5); val secret = t.s("enrolment_token"); val phone = Phone()
        enrol(enrolBody(secret, phone, goodChain(phone, secret)))
        val id = deviceId(phone)
        fun state(action: String, reason: String = "lost phone, reported by the SR", uid: Long = 13, role: Role = Role.ADMIN) = kotlinx.coroutines.runBlocking { null as Any? }
        suspend fun change(action: String, reason: String = "lost phone, reported by the SR", uid: Long = 13, role: Role = Role.ADMIN) =
            client.post("/v1/admin/devices/$id/state") { bearerAuth(TestTokens.web(uid, role)); contentType(ContentType.Application.Json); setBody("""{"action":"$action","reason":"$reason"}""") }
        assertEquals(HttpStatusCode.OK, change("suspend").status); assertEquals("suspended", fresh.db.jdbi.withHandle<String, Exception> { it.createQuery("SELECT status FROM app.device WHERE id = $id").mapTo(String::class.java).one() })
        assertEquals(HttpStatusCode.OK, deviceGet(phone, "/v1/devices/me/policy").status)     // a suspended phone may still fetch policy and report
        assertEquals(HttpStatusCode.OK, change("reactivate").status)
        assertEquals(HttpStatusCode.OK, change("revoke").status)
        assertEquals(HttpStatusCode.OK, change("revoke").status)                              // the same decision again changes nothing
        assertEquals(HttpStatusCode.Conflict, change("reactivate").status)                    // a revoked phone is enrolled again, not reactivated
        assertEquals(HttpStatusCode.Forbidden, deviceGet(phone, "/v1/devices/me/policy").status)
        assertEquals(HttpStatusCode.BadRequest, change("revoke", reason = "short").status)
        assertEquals(HttpStatusCode.BadRequest, change("mark_replaced").status)
        assertEquals(HttpStatusCode.Forbidden, change("revoke", uid = 11, role = Role.TSO).status)
        assertEquals(3, count("SELECT count(*) FROM app.audit_log WHERE entity = 'device' AND entity_id = '$id' AND action IN ('suspend', 'reactivate', 'revoke')"))
        // Reach: a zone-1 TSO reads zone-1 devices; a zone-2 TSO cannot see it (404, no existence leak) and lists none.
        assertEquals(HttpStatusCode.OK, client.get("/v1/admin/devices/$id") { bearerAuth(TestTokens.web(11, Role.TSO)) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/admin/devices/$id") { bearerAuth(TestTokens.web(14, Role.TSO)) }.status)
        assertEquals(0, Json.parseToJsonElement(client.get("/v1/admin/devices") { bearerAuth(TestTokens.web(14, Role.TSO)) }.bodyAsText()).jsonObject["items"]!!.jsonArray.size)
        assertEquals(1, Json.parseToJsonElement(client.get("/v1/admin/devices?status=revoked&search=A70") { bearerAuth(TestTokens.web(10, Role.ANALYST)) }.bodyAsText()).jsonObject["items"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/devices") { bearerAuth(TestTokens.web(12, Role.AMO)) }.status)
        assertNotEquals(0, count("SELECT count(*) FROM app.device_policy"))
    }

    // ---- T1 checker (N-031): each test below failed against 5eaf33ce ----

    @Test
    fun checkerAChainExtendedByAnAttestedNonCaKeyIsRefused() = app {
        // An attacker with any genuine phone creates an attested Keystore key K1 (non-CA leaf), then uses K1 to sign a forged leaf for a software key
        // with whatever challenge, package, signer and boot state it likes. All links verify and the root is Google's; the server must still refuse.
        val t = mintToken(lockdown = "prod", maxUses = 5); val secret = t.s("enrolment_token")
        val attacker = AttestationBuilder.keyPair()
        val genuine = AttestationBuilder.chain(attacker.public, AttestationBuilder.keyDescription(sha("unrelated".toByteArray()), "com.example.any", ByteArray(32) { 3 }))
        trustedRoots += genuine.rootSha256
        val phone = Phone()
        val forged = AttestationBuilder.extendChain(genuine, attacker, phone.key.public, AttestationBuilder.keyDescription(sha(secret.toByteArray()), "com.aktcl.aron.sr", certDigest))
        val r = enrol(enrolBody(secret, phone, forged))
        assertEquals(HttpStatusCode.Forbidden, r.status, r.bodyAsText()); assertTrue(r.bodyAsText().contains("ERR_ENROLMENT_ATTESTATION_FAILED"))
        assertEquals(0, count("SELECT count(*) FROM app.device"))
    }

    @Test
    fun checkerAMalformedDeviceIdIsTheSame401NotA500() = app {
        val r = client.get("/v1/devices/me/policy") { header("X-Device-Id", "not-a-uuid"); header("X-Device-Proof", "A".repeat(86)) }
        assertEquals(HttpStatusCode.Unauthorized, r.status, r.bodyAsText())
    }

    @Test
    fun checkerAnAppVersionOutsideTheContractPatternIs400NotA500() = app {
        // app_version must be <versionName>+<versionCode> (contract AppVersionString; the device table CHECK enforces it).
        val t = mintToken(); val secret = t.s("enrolment_token"); val p = Phone()
        val r = enrol(enrolBody(secret, p, goodChain(p, secret)).replace("\"app_version\":\"1.0.0+1\"", "\"app_version\":\"1.0.0\""))
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
    }

    @Test
    fun checkerAnAttestationChainAboveTheContractsSixCertificatesIs400() = app {
        val t = mintToken(); val secret = t.s("enrolment_token"); val p = Phone(); val c = goodChain(p, secret)
        val long = AttestationBuilder.Built(c.chainBase64 + List(5) { c.chainBase64.last() }, c.rootSha256, c.rootKey)
        val r = enrol(enrolBody(secret, p, long))
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
    }

    @Test
    fun checkerAStatusReportOutsideTheEnumsIs400NotA500() = app {
        val t = mintToken(); val secret = t.s("enrolment_token"); val ok = Phone()
        assertEquals(HttpStatusCode.Created, enrol(enrolBody(secret, ok, goodChain(ok, secret))).status)
        val badTrigger = devicePost(ok, "/v1/devices/me/status", status().replace("\"periodic\"", "\"whenever\""))
        assertEquals(HttpStatusCode.BadRequest, badTrigger.status, badTrigger.bodyAsText())
        val badLevel = devicePost(ok, "/v1/devices/me/status", status().replace("\"lockdown_level_applied\":\"prod\"", "\"lockdown_level_applied\":\"kiosk\""))
        assertEquals(HttpStatusCode.BadRequest, badLevel.status, badLevel.bodyAsText())
    }

    @Test
    fun checkerTheSameKeyUnderASecondDeviceUuidIsAConflictNotA500() = app {
        val t = mintToken(maxUses = 5); val secret = t.s("enrolment_token")
        val phone = Phone(); val chain = goodChain(phone, secret)
        assertEquals(HttpStatusCode.Created, enrol(enrolBody(secret, phone, chain)).status)
        val clone = Phone(key = phone.key)
        val r = enrol(enrolBody(secret, clone, chain))
        assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText())
    }

    @Test
    fun checkerAGzipBodyIsAcceptedAsDocs24Section3AllowsForBodiesAbove4KiB() = app {
        val t = mintToken(); val secret = t.s("enrolment_token"); val phone = Phone()
        val body = enrolBody(secret, phone, goodChain(phone, secret), withStatus = true).toByteArray()
        val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(body) } }.toByteArray()
        val r = client.post("/v1/devices/enrol") { contentType(ContentType.Application.Json); header("Content-Encoding", "gzip"); setBody(gz) }
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
    }

    @Test
    fun checkerTopAndAnalystHaveNoDeviceAccessPerTheDocs24PermissionMatrix() = app {
        // docs/24 s8.5: "Devices, enrolment tokens, device OTPs" is — for TOP and ANALYST.
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/devices") { bearerAuth(TestTokens.web(10, Role.ANALYST)) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/devices") { bearerAuth(TestTokens.web(10, Role.TOP)) }.status)
    }
    @Test
    fun checkerARevokedPhoneRepeatingItsEnrolmentIsNotToldItIsEnrolled() = app {
        val t = mintToken(maxUses = 5); val secret = t.s("enrolment_token"); val phone = Phone(); val chain = goodChain(phone, secret)
        assertEquals(HttpStatusCode.Created, enrol(enrolBody(secret, phone, chain)).status)
        val id = deviceId(phone)
        assertEquals(HttpStatusCode.OK, client.post("/v1/admin/devices/$id/state") { bearerAuth(TestTokens.web(13, Role.ADMIN)); contentType(ContentType.Application.Json); setBody("""{"action":"revoke","reason":"lost phone, reported by the SR"}""") }.status)
        val again = enrol(enrolBody(secret, phone, chain))
        assertNotEquals(HttpStatusCode.Created, again.status, again.bodyAsText())
    }
}
