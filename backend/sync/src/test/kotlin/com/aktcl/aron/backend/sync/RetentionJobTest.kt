package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-SYS-063 acceptance: monthly partitions exist three months ahead, partitions past their hot window get a `planned`
 * archive manifest once, nothing is ever dropped in this build, and rows in a DEFAULT partition are reported.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RetentionJobTest {
    private lateinit var fresh: FreshDb
    private lateinit var job: RetentionJob

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        job = RetentionJob(fresh.db, AronClock { Instant.parse("2026-10-07T06:00:00Z") })
    }

    @AfterAll
    fun tearDown() = fresh.close()

    private fun one(sql: String): String? = fresh.db.jdbi.withHandle<String?, Exception> { h -> h.createQuery(sql).mapTo(String::class.java).findOne().orElse(null) }

    @Test
    @Order(1)
    fun partitionsExistThreeMonthsAheadAndASecondRunCreatesNothing() {
        job.tick()
        val ahead = one("SELECT to_regclass('app.geo_breadcrumb_y' || to_char(date_trunc('month', app.dhaka_date(now()) + interval '3 months'), 'YYYY\"m\"MM'))::text")
        assertTrue(ahead != null, "the partition three months ahead exists")
        assertEquals(0, job.tick().partitionsCreated)
    }

    @Test
    @Order(2)
    fun aPartitionPastItsHotWindowGetsOnePlannedManifestAndIsNeverDropped() {
        // An old month (geo_breadcrumb is class `fix`, hot 6 months).
        fresh.db.jdbi.useHandle<Exception> { h -> h.createQuery("SELECT app.ensure_partitions(DATE '2025-01-01', DATE '2025-01-01')").mapTo(Int::class.java).one() }
        val first = job.tick()
        assertTrue(first.manifestsPlanned >= 1, "planned ${first.manifestsPlanned}")
        assertEquals("planned", one("SELECT status FROM app.archive_manifest WHERE partition_name = 'app.geo_breadcrumb_y2025m01'"))
        assertEquals(0, job.tick().manifestsPlanned, "one manifest per partition")
        assertTrue(one("SELECT to_regclass('app.geo_breadcrumb_y2025m01')::text") != null, "never dropped in this build")
        assertEquals("0", one("SELECT count(*) FROM app.archive_manifest WHERE status <> 'planned'"))
    }

    @Test
    @Order(3)
    fun rowsInADefaultPartitionAreReported() {
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                """
                INSERT INTO app.geo_breadcrumb (client_uuid, family_uuid, business_date, user_id, captured_at, config_version)
                VALUES (gen_random_uuid(), gen_random_uuid(), DATE '2035-06-01', (SELECT min(id) FROM app.app_user), TIMESTAMPTZ '2035-06-01 05:00:00+00', 0)
                """.trimIndent(),
            )
        }
        assertEquals(1L, job.tick().defaultRows["app.geo_breadcrumb"])
    }
}
