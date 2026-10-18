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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PARITY-SPEC s2.6 (lead ruling 2026-10-18): a pack picture rides in the bundle as the stable `image_asset_id`, never as a
 * signed URL, so two independent builds of the same catalogue give the same bundle hash (no churn per build, no extra
 * download for 8,500 phones); binding a new picture changes it once.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BundleImageStabilityTest {
    private lateinit var fresh: FreshDb
    private val clock = AronClock { Instant.parse("2027-01-03T02:00:00Z") }
    private val day = LocalDate.parse("2027-01-03")
    private val asset = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
    }

    @AfterAll
    fun tearDown() = fresh.close()

    /** A fresh service each time: no fingerprint or page cache carries over, as on two API replicas. */
    private fun build(): BundleService.Result {
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        val geo = GeoRepository(fresh.db, clock)
        val service = BundleService(fresh.db, config, SqlRoutePlanner(fresh.db, geo, config), clock, reach = SqlReachResolver(fresh.db, geo, clock))
        val sr = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one() }
        val p = AronPrincipal(sr, "sr1001", Role.SR, 0, "api", null, null, "sr", emptyList(), false, emptyList(), "j", clock.now().plusSeconds(600))
        return service.bundle(p, day, "1.0.0+1")
    }

    @Test
    fun twoBuildsOfTheSameCatalogueHaveTheSameHashAndANewPictureChangesItOnce() {
        val before = BundleService.contentDigest(build().full)
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.admin_asset (asset_id, purpose, mime, bytes, sha256, blob_path) VALUES (CAST(? AS uuid), 'sku_image', 'image/png', 1000, decode(repeat('ab', 32), 'hex'), 'assets/sku_image/a.png')", asset)
            h.execute("UPDATE app.sku SET image_asset_id = CAST(? AS uuid) WHERE code = 'MaxR-10S'", asset)
        }
        val a = build(); val b = build()
        assertContentEquals(BundleService.contentDigest(a.full), BundleService.contentDigest(b.full), "two builds, one hash")
        assert(!BundleService.contentDigest(a.full).contentEquals(before)) { "binding a picture changes the bundle once" }
        val sku = a.full.products.skus.single { it.code == "MaxR-10S" }
        assertEquals(asset, sku.image_asset_id)
        assertNull(a.full.products.skus.firstOrNull { it.code == "MaxR-20S" }?.image_asset_id)
    }
}
