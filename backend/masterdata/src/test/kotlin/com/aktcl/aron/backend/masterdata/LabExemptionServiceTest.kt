package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AronClock
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The server side of the lab exemption: a row, at most 14 days, and a signed grant bound to the device uuid; no signing key means no grant. Keys are generated here, never stored. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LabExemptionServiceTest {
    private lateinit var env: SeededAdminDb
    @BeforeAll fun up() { env = SeededAdminDb() }
    @AfterAll fun down() = env.close()

    private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val signer = LabGrantSigner(Base64.getEncoder().encodeToString(kp.private.encoded))
    private var now = Instant.parse("2027-03-01T04:00:00Z")
    private val clock = AronClock { now }

    private fun device(): Pair<Long, String> {
        val uuid = UUID.randomUUID().toString()
        val id = env.fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery(
                "INSERT INTO app.device (device_uuid, flavour, app_package, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256) " +
                    "VALUES (CAST(:u AS uuid), 'sr', 'com.aktcl.aron.sr', false, 'prod', CAST('{}' AS jsonb), :t, decode(repeat('ab', 32), 'hex')) RETURNING id",
            ).bind("u", uuid).bind("t", "thumb-" + UUID.randomUUID()).mapTo(Long::class.java).one()
        }
        return id to uuid
    }

    private fun verifies(token: String, deviceUuid: String): Boolean {
        val (p, s) = token.split('.'); val d = Base64.getUrlDecoder()
        val ok = Signature.getInstance("SHA256withECDSA").apply { initVerify(kp.public); update(d.decode(p)) }.verify(d.decode(s))
        return ok && String(d.decode(p)).contains("\"d\":\"$deviceUuid\"")
    }

    @Test fun aGrantIsSignedForTheDeviceUuidAndEndsAtTheStoredExpiry() {
        val svc = LabExemptionService(env.fresh.db, signer, clock); val (id, uuid) = device(); val by = env.ids.getValue("sr1001")
        assertNull(svc.tokenFor(id), "no row, no grant")
        val exp = svc.grant(id, by, 3, "ADB walk")
        assertEquals(now.plusSeconds(3 * 86_400L), exp)
        val t = assertNotNull(svc.tokenFor(id)); assertTrue(verifies(t, uuid))
        assertTrue(String(Base64.getUrlDecoder().decode(t.substringBefore('.'))).contains("\"exp\":${exp.toEpochMilli()}"))
        now = now.plusSeconds(3 * 86_400L + 1); assertNull(svc.tokenFor(id), "expired: nothing is sent")
        now = Instant.parse("2027-03-01T04:00:00Z")
    }

    @Test fun theCapAndTheNoteAreEnforcedAndRevokeStopsTheGrant() {
        val svc = LabExemptionService(env.fresh.db, signer, clock); val (id, _) = device(); val by = env.ids.getValue("sr1001")
        assertFailsWith<IllegalArgumentException> { svc.grant(id, by, 15, null) }
        assertFailsWith<IllegalArgumentException> { svc.grant(id, by, 0, null) }
        assertFailsWith<IllegalArgumentException> { svc.grant(id, by, 1, "x".repeat(301)) }
        svc.grant(id, by, 14, null); assertNotNull(svc.tokenFor(id))
        assertTrue(svc.revoke(id)); assertNull(svc.tokenFor(id)); assertEquals(false, svc.revoke(id))
    }

    @Test fun withoutASigningKeyNothingIsGrantedOrSigned() {
        val off = LabExemptionService(env.fresh.db, LabGrantSigner(null), clock); val (id, _) = device()
        assertFailsWith<IllegalArgumentException> { off.grant(id, env.ids.getValue("sr1001"), 1, null) }
        assertNull(off.tokenFor(id))
        assertEquals(false, LabGrantSigner("not a key").configured)
    }

    @Test fun aRowWrittenByHandStillGivesNoGrantWithoutTheKeyAndTheDatabaseRefusesALongOne() {
        val (id, _) = device(); val by = env.ids.getValue("sr1001")
        env.fresh.db.jdbi.useHandle<Exception> { h -> h.execute("INSERT INTO app.device_lab_exemption (device_id, granted_by, expires_at) VALUES ($id, $by, now() + interval '2 days')") }
        assertNull(LabExemptionService(env.fresh.db, LabGrantSigner(null), clock).tokenFor(id))
        val (id2, _) = device()
        assertFailsWith<Exception> { env.fresh.db.jdbi.useHandle<Exception> { h -> h.execute("INSERT INTO app.device_lab_exemption (device_id, granted_by, expires_at) VALUES ($id2, $by, now() + interval '30 days')") } }
    }
}
