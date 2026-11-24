package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.masterdata.SqlRoutePlanner
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.sync.BundleService
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D-OWNER-MDM-02 iii: a phone's lab grant rides in ITS bundle as the device-scoped value `cfg.integrity.lab_exemption`, the stored token as it was signed, so two
 * builds give the same bundle bytes (a fresh signature per request would change the bundle every time); other phones, expired grants and withdrawn grants get nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BundleLabGrantTest {
    private lateinit var fresh: FreshDb
    private var now = Instant.parse("2027-01-03T02:00:00Z")
    private val clock = AronClock { now }
    private val day = LocalDate.parse("2027-01-03")
    private val key = BundleService.LAB_GRANT_KEY
    private val token = "eyJ2IjoxfQ." + "A".repeat(60)
    private var a = 0L
    private var b = 0L

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fun dev() = fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery(
                "INSERT INTO app.device (device_uuid, flavour, app_package, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256, status) " +
                    "VALUES (CAST(:u AS uuid), 'sr', 'com.aktcl.aron.sr', false, 'prod', CAST('{}' AS jsonb), :t, decode(repeat('ab', 32), 'hex'), 'active') RETURNING id",
            ).bind("u", UUID.randomUUID().toString()).bind("t", "thumb-" + UUID.randomUUID()).mapTo(Long::class.java).one()
        }
        a = dev(); b = dev()
    }

    @AfterAll
    fun tearDown() = fresh.close()

    private fun build(deviceId: Long?): BundleService.Result {
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        val geo = GeoRepository(fresh.db, clock)
        val service = BundleService(fresh.db, config, SqlRoutePlanner(fresh.db, geo, config), clock, reach = SqlReachResolver(fresh.db, geo, clock))
        val sr = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one() }
        val p = AronPrincipal(sr, "sr1001", Role.SR, 0, "api", deviceId, null, "sr", emptyList(), false, emptyList(), "j", clock.now().plusSeconds(600))
        return service.bundle(p, day, "1.0.0+1")
    }

    private fun grantValue(r: BundleService.Result) = r.full.config.values.singleOrNull { it.key == key }
    private fun sql(s: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(s) }

    @Test
    fun theStoredGrantRidesOnlyInThePhonesOwnBundleAndTwoBuildsAreTheSameBytes() {
        sql("DELETE FROM app.device_lab_exemption")
        assertNull(grantValue(build(a)), "no row, nothing sent: this is how the phone learns it was withdrawn")
        val sr = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one() }
        sql("INSERT INTO app.device_lab_exemption (device_id, granted_by, granted_at, expires_at, grant_token) VALUES ($a, $sr, '2027-01-03T01:00:00Z', '2027-01-05T01:00:00Z', '$token')")
        val x = build(a); val y = build(a)
        val v = grantValue(x)!!
        assertEquals(token, v.value.jsonPrimitive.content); assertEquals("device", v.scope_type); assertEquals(a, v.scope_id); assertFalse(v.requires_ack)
        assertContentEquals(BundleService.contentDigest(x.full), BundleService.contentDigest(y.full), "the token is stored, not re-signed: equal content, equal bundle")
        assertNull(grantValue(build(b)), "another phone gets nothing"); assertNull(grantValue(build(null)), "a bundle without a device gets nothing")
        assertTrue(BundleService.contentDigest(build(b).full).contentEquals(BundleService.contentDigest(build(b).full)))
        assertFalse(BundleService.contentDigest(x.full).contentEquals(BundleService.contentDigest(build(b).full)), "the grant is part of the bundle content")
    }

    @Test
    fun anExpiredOrWithdrawnGrantIsNotSent() {
        val sr = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one() }
        sql("DELETE FROM app.device_lab_exemption")
        sql("INSERT INTO app.device_lab_exemption (device_id, granted_by, granted_at, expires_at, grant_token) VALUES ($a, $sr, '2027-01-03T01:00:00Z', '2027-01-03T03:00:00Z', '$token')")
        assertTrue(grantValue(build(a)) != null)
        now = Instant.parse("2027-01-03T03:00:01Z"); assertNull(grantValue(build(a)), "expired: nothing is sent")
        now = Instant.parse("2027-01-03T02:00:00Z"); assertTrue(grantValue(build(a)) != null)
        sql("DELETE FROM app.device_lab_exemption WHERE device_id = $a"); assertNull(grantValue(build(a)), "withdrawn: the next full bundle drops it")
    }

    /** A grant or a withdrawal made after the phone's snapshot cannot ride in a delta: the delta says "fetch the full bundle" (410), so an AMO or TSO that refreshes by delta all day still gets it. */
    @Test
    fun aGrantOrAWithdrawalAfterThePhonesSnapshotMakesTheDeltaAskForTheFullBundle() {
        val sr = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one() }
        sql("DELETE FROM app.device_lab_exemption")
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        val geo = GeoRepository(fresh.db, clock)
        val service = BundleService(fresh.db, config, SqlRoutePlanner(fresh.db, geo, config), clock, reach = SqlReachResolver(fresh.db, geo, clock))
        val p = AronPrincipal(sr, "sr1001", Role.SR, 0, "api", a, null, "sr", emptyList(), false, emptyList(), "j", clock.now().plusSeconds(600))
        val first = service.bundle(p, day, "1.0.0+1")
        assertNull(service.delta(p, first.bundle.meta.cursor, day, "1.0.0+1"), "nothing changed: 304")
        sql("INSERT INTO app.device_lab_exemption (device_id, granted_by, granted_at, expires_at, grant_token) VALUES ($a, $sr, '2027-01-03T01:00:00Z', '2027-01-05T01:00:00Z', '$token')")
        val granted = kotlin.runCatching { service.delta(p, first.bundle.meta.cursor, day, "1.0.0+1") }.exceptionOrNull()
        assertTrue(granted is com.aktcl.aron.backend.platform.ApiProblem, "a grant after the snapshot: fetch the full bundle, got $granted")
        val second = service.bundle(p, day, "1.0.0+1")
        assertNull(service.delta(p, second.bundle.meta.cursor, day, "1.0.0+1"), "the phone holds the grant now: 304")
        sql("DELETE FROM app.device_lab_exemption WHERE device_id = $a")
        val withdrawn = kotlin.runCatching { service.delta(p, second.bundle.meta.cursor, day, "1.0.0+1") }.exceptionOrNull()
        assertTrue(withdrawn is com.aktcl.aron.backend.platform.ApiProblem, "a withdrawal after the snapshot: fetch the full bundle, got $withdrawn")
    }
}
