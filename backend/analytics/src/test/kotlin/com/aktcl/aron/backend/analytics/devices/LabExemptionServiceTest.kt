package com.aktcl.aron.backend.analytics.devices

import com.aktcl.aron.backend.analytics.ReportFixture
import com.aktcl.aron.backend.analytics.TestTokens
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.Route
import io.ktor.server.testing.ApplicationTestBuilder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server side of the lab exemption (D-OWNER-MDM-02 iii): a row, at most 14 days, and a grant signed ONCE for the device uuid; no signing key means no grant, and the
 * admin route is for Super Admin / a granted Admin, audited without the token. Keys are generated here for the test, never stored.
 */
class LabExemptionServiceTest : ReportFixture() {
    private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val signer = LabGrantSigner(Base64.getEncoder().encodeToString(kp.private.encoded))
    private var now = Instant.parse("2026-10-04T12:00:00Z")
    private val clock = AronClock { now }
    private lateinit var service: DeviceService
    private var withKey = true

    override val successfulLogin = false

    override fun mount(r: Route, clock: AronClock, reach: ReachResolver, guard: AuthGuardDeps) {
        val lab = LabExemptionService(fresh.db, if (withKey) signer else LabGrantSigner(null), this.clock)
        service = DeviceService(fresh.db, RegistryDefaults(), TestTokens.keys, EnrolmentSettings("https://api.example.test", "dev", AttestationTrust(emptySet())), this.clock, lab = lab)
        r.deviceRoutes(DeviceDeps(service, reach, guard, this.clock))
    }

    private fun device(zone: Long = z1, status: String = "active"): Pair<Long, String> {
        val uuid = UUID.randomUUID().toString()
        val id = fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery(
                "INSERT INTO app.device (device_uuid, flavour, app_package, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256, zone_id, status) " +
                    "VALUES (CAST(:u AS uuid), 'sr', 'com.aktcl.aron.sr', false, 'prod', CAST('{}' AS jsonb), :t, decode(repeat('ab', 32), 'hex'), :z, :s) RETURNING id",
            ).bind("u", uuid).bind("t", "thumb-" + UUID.randomUUID()).bind("z", zone).bind("s", status).mapTo(Long::class.java).one()
        }
        return id to uuid
    }

    private fun verifies(token: String, deviceUuid: String): Boolean {
        val (p, s) = token.split('.'); val d = Base64.getUrlDecoder()
        val ok = Signature.getInstance("SHA256withECDSA").apply { initVerify(kp.public); update(d.decode(p)) }.verify(d.decode(s))
        return ok && String(d.decode(p)).contains("\"d\":\"$deviceUuid\"")
    }

    private fun storedToken(id: Long) = fresh.db.jdbi.withHandle<String?, Exception> { it.createQuery("SELECT grant_token FROM app.device_lab_exemption WHERE device_id = $id").mapTo(String::class.java).findOne().orElse(null) }
    private fun count(sql: String) = fresh.db.jdbi.withHandle<Int, Exception> { it.createQuery(sql).mapTo(Int::class.java).one() }

    // ---- the service ----

    @Test fun aGrantIsSignedOnceForTheDeviceUuidAndStoredWithTheRow() {
        val svc = LabExemptionService(fresh.db, signer, clock); val (id, uuid) = device()
        assertNull(svc.current(id), "no row, no exemption"); assertNull(storedToken(id))
        val exp = svc.grant(id, 13, 3, "ADB walk")
        assertEquals(now.plusSeconds(3 * 86_400L), exp)
        val t = assertNotNull(storedToken(id)); assertTrue(verifies(t, uuid))
        val payload = String(Base64.getUrlDecoder().decode(t.substringBefore('.')))
        assertTrue(payload.contains("\"exp\":${exp.toEpochMilli()}") && payload.contains("\"iat\":${now.toEpochMilli()}"), payload)
        assertEquals(t, storedToken(id), "reading never re-signs: the bundle carrying it stays the same bytes")
        now = now.plusSeconds(3 * 86_400L + 1); assertNull(svc.current(id), "expired")
        now = Instant.parse("2026-10-04T12:00:00Z")
    }

    @Test fun theCapAndTheNoteAreEnforcedAndRevokeWithdrawsTheRow() {
        val svc = LabExemptionService(fresh.db, signer, clock); val (id, _) = device()
        assertFailsWith<IllegalArgumentException> { svc.grant(id, 13, 15, null) }
        assertFailsWith<IllegalArgumentException> { svc.grant(id, 13, 0, null) }
        assertFailsWith<IllegalArgumentException> { svc.grant(id, 13, 1, "x".repeat(301)) }
        svc.grant(id, 13, 14, null); assertNotNull(svc.current(id))
        assertTrue(svc.revoke(id)); assertNull(svc.current(id)); assertNull(storedToken(id)); assertFalse(svc.revoke(id))
    }

    @Test fun aRevokedOrUnknownPhoneGetsNoGrant() {
        val svc = LabExemptionService(fresh.db, signer, clock)
        assertFailsWith<IllegalArgumentException> { svc.grant(device(status = "revoked").first, 13, 1, null) }
        assertFailsWith<IllegalArgumentException> { svc.grant(999_999L, 13, 1, null) }
    }

    @Test fun withoutASigningKeyNothingIsGranted() {
        val off = LabExemptionService(fresh.db, LabGrantSigner(null), clock); val (id, _) = device()
        assertFalse(off.canGrant)
        assertFailsWith<IllegalArgumentException> { off.grant(id, 13, 1, null) }
        assertEquals(0, count("SELECT count(*) FROM app.device_lab_exemption WHERE device_id = $id"))
        assertFalse(LabGrantSigner("not a key").configured)
        assertFalse(LabGrantSigner("").configured)
    }

    @Test fun theDatabaseRefusesALongExemptionAndAMissingToken() {
        val (id, _) = device(); val (id2, _) = device()
        val tok = "x".repeat(60)
        assertFailsWith<Exception> { fresh.db.jdbi.useHandle<Exception> { h -> h.execute("INSERT INTO app.device_lab_exemption (device_id, granted_by, expires_at, grant_token) VALUES ($id, 13, now() + interval '30 days', '$tok')") } }
        assertFailsWith<Exception> { fresh.db.jdbi.useHandle<Exception> { h -> h.execute("INSERT INTO app.device_lab_exemption (device_id, granted_by, expires_at, grant_token) VALUES ($id2, 13, now() + interval '2 days', '')") } }
    }

    // ---- the admin route ----

    private fun json(b: String) = Json.parseToJsonElement(b).jsonObject
    private suspend fun ApplicationTestBuilder.put(id: Long, uid: Long, role: Role, body: String) =
        client.put("/v1/admin/devices/$id/lab-exemption") { bearerAuth(TestTokens.web(uid, role)); contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun ApplicationTestBuilder.del(id: Long, uid: Long, role: Role) = client.delete("/v1/admin/devices/$id/lab-exemption") { bearerAuth(TestTokens.web(uid, role)) }

    @Test fun anAdminGrantsAndRevokesAndBothAreAuditedWithoutTheToken() = app {
        val (id, uuid) = device()
        val r = put(id, 13, Role.ADMIN, """{"days":2,"note":"ADB walk of the SR app"}""")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val lab = json(r.bodyAsText())["lab_exemption"]!!.jsonObject
        assertEquals("2026-10-06T12:00:00.000Z", lab["expires_at"]!!.jsonPrimitive.content); assertEquals("13", lab["granted_by"]!!.jsonPrimitive.content)
        assertEquals("true", json(r.bodyAsText())["lab_exemption_available"]!!.jsonPrimitive.content)
        assertTrue(verifies(storedToken(id)!!, uuid))
        assertFalse(r.bodyAsText().contains(storedToken(id)!!), "the token is never sent to the portal")
        assertEquals("2026-10-06T12:00:00.000Z", json(client.get("/v1/admin/devices/$id") { bearerAuth(TestTokens.web(13, Role.ADMIN)) }.bodyAsText())["lab_exemption"]!!.jsonObject["expires_at"]!!.jsonPrimitive.content)
        assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'device_lab_exemption' AND entity_id = '$id' AND action = 'grant' AND actor_user_id = 13"))
        assertEquals(0, count("SELECT count(*) FROM app.audit_log WHERE entity = 'device_lab_exemption' AND (after::text LIKE '%${storedToken(id)!!}%' OR before::text LIKE '%${storedToken(id)!!}%' OR reason LIKE '%${storedToken(id)!!}%')"))
        // a second grant replaces the first
        now = now.plusSeconds(60); assertEquals(HttpStatusCode.OK, put(id, 13, Role.SUPERADMIN, """{"days":1}""").status)
        assertEquals(1, count("SELECT count(*) FROM app.device_lab_exemption WHERE device_id = $id"))
        assertEquals(HttpStatusCode.OK, del(id, 13, Role.ADMIN).status)
        assertNull(storedToken(id)); assertNull(json(client.get("/v1/admin/devices/$id") { bearerAuth(TestTokens.web(13, Role.ADMIN)) }.bodyAsText())["lab_exemption"]?.takeIf { it !is kotlinx.serialization.json.JsonNull })
        assertEquals(HttpStatusCode.OK, del(id, 13, Role.ADMIN).status, "revoking again changes nothing")
        assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'device_lab_exemption' AND entity_id = '$id' AND action = 'revoke'"))
        now = Instant.parse("2026-10-04T12:00:00Z")
    }

    @Test fun onlySuperAdminAndAdminMayAndAnInvalidRequestIsRefused() = app {
        val (id, _) = device()
        for (role in listOf(Role.TSO, Role.DMO, Role.WM, Role.AMO, Role.SUPPORT, Role.ANALYST)) {
            assertEquals(HttpStatusCode.Forbidden, put(id, 11, role, """{"days":1}""").status, "$role")
            assertEquals(HttpStatusCode.Forbidden, del(id, 11, role).status, "$role")
        }
        assertEquals(HttpStatusCode.BadRequest, put(id, 13, Role.ADMIN, """{"days":0}""").status)
        assertEquals(HttpStatusCode.BadRequest, put(id, 13, Role.ADMIN, """{"days":15}""").status)
        assertEquals(HttpStatusCode.BadRequest, put(id, 13, Role.ADMIN, """{"days":1,"note":"${"x".repeat(301)}"}""").status)
        assertEquals(HttpStatusCode.BadRequest, put(id, 13, Role.ADMIN, """{"days":1,"extra":1}""").status)
        assertEquals(0, count("SELECT count(*) FROM app.device_lab_exemption"))
        assertEquals(0, count("SELECT count(*) FROM app.audit_log WHERE entity = 'device_lab_exemption'"))
    }

    @Test fun aPhoneOutsideTheCallersReachOrRevokedIsNotFound() = app {
        val inZone2 = device(zone = z2).first; val revoked = device(status = "revoked").first
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("INSERT INTO app.app_user (id, username, full_name, role, must_change_password) OVERRIDING SYSTEM VALUE VALUES (15, 'user15', 'User 15', 'ADMIN', false)") }
        reaches[15] = com.aktcl.aron.backend.platform.Reach(15, Role.ADMIN, day, false, setOf(z1), emptySet(), false, listOf(com.aktcl.aron.backend.platform.ReachNode("zone", z1)))
        assertEquals(HttpStatusCode.NotFound, put(inZone2, 15, Role.ADMIN, """{"days":1}""").status)
        assertEquals(HttpStatusCode.NotFound, del(inZone2, 15, Role.ADMIN).status)
        assertEquals(HttpStatusCode.NotFound, put(revoked, 13, Role.ADMIN, """{"days":1}""").status)
        assertEquals(HttpStatusCode.NotFound, put(999_999, 13, Role.ADMIN, """{"days":1}""").status)
        assertEquals(0, count("SELECT count(*) FROM app.device_lab_exemption"))
    }

    @Test fun withoutTheServerSecretTheRouteAnswers409AndRecordsNothing() {
        withKey = false
        app {
            val (id, _) = device()
            val r = put(id, 13, Role.ADMIN, """{"days":1}""")
            assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText())
            assertEquals(0, count("SELECT count(*) FROM app.device_lab_exemption"))
            assertEquals(0, count("SELECT count(*) FROM app.audit_log WHERE entity = 'device_lab_exemption'"))
            assertEquals("false", json(client.get("/v1/admin/devices/$id") { bearerAuth(TestTokens.web(13, Role.ADMIN)) }.bodyAsText())["lab_exemption_available"]!!.jsonPrimitive.content)
        }
    }

    @Test fun aGrantForAnotherPhoneDoesNotVerifyForThisOne() {
        val svc = LabExemptionService(fresh.db, signer, clock); val (a, ua) = device(); val (b, ub) = device()
        svc.grant(a, 13, 1, null)
        assertTrue(verifies(storedToken(a)!!, ua)); assertFalse(verifies(storedToken(a)!!, ub)); assertNotEquals(ua, ub); assertEquals(0, count("SELECT count(*) FROM app.device_lab_exemption WHERE device_id = $b"))
    }
}
