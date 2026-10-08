package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Secret
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

    /** The worker's grants, not a superuser's: every step works for a login that is only `jobs_rw`. */
    @Test
    @Order(4)
    fun everyStepWorksAsJobsRw() {
        val login = "rt_jobs_" + fresh.name.takeLast(12).replace(Regex("[^a-z0-9_]"), "_")
        val pw = "pw-" + System.nanoTime()
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("CREATE ROLE $login LOGIN PASSWORD '$pw' IN ROLE jobs_rw")
            h.execute("GRANT CONNECT ON DATABASE ${fresh.name} TO $login")
        }
        try {
            fresh.db.jdbi.useHandle<Exception> { h -> h.createQuery("SELECT app.ensure_partitions(DATE '2025-02-01', DATE '2025-02-01')").mapTo(Int::class.java).one() }
            val url = fresh.url.replace(Regex("([?&])user=[^&]*"), "$1user=$login").replace(Regex("([?&])password=[^&]*"), "$1password=$pw")
            Database.pool(url, login, Secret(pw), 2, "rt-jobs").use { ds ->
                val r = RetentionJob(Database(ds), AronClock { Instant.parse("2026-10-07T06:00:00Z") }).tick()
                assertEquals(emptyMap(), r.failedParents)
                assertTrue(r.manifestsPlanned >= 1, "planned ${r.manifestsPlanned} as jobs_rw")
                assertEquals(1L, r.defaultRows["app.geo_breadcrumb"])
            }
        } finally {
            fresh.db.jdbi.useHandle<Exception> { h -> h.execute("DROP OWNED BY $login"); h.execute("DROP ROLE $login") }
        }
    }

    /** db V0069 note: web error reports past the telemetry keep window (12 months) are deleted; younger ones stay. */
    @Test
    @Order(99)
    fun clientErrorsPastTheTelemetryWindowAreDeleted() {
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                """
                CREATE TABLE IF NOT EXISTS app.client_error (
                  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, error_uuid uuid NOT NULL UNIQUE, user_id bigint NOT NULL REFERENCES app.app_user(id),
                  source text NOT NULL, occurred_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(), business_date date NOT NULL,
                  page text, message text NOT NULL, stack text, build text)
                """.trimIndent(),
            )
            h.execute(
                "INSERT INTO app.client_error (error_uuid, user_id, source, occurred_at, business_date, message) " +
                    "SELECT gen_random_uuid(), (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'web', now(), d, 'm' FROM unnest(ARRAY[DATE '2025-01-01', DATE '2026-12-01']) d",
            )
        }
        val r = job.tick()
        assertEquals(1, r.clientErrorsDeleted)
        assertEquals("2026-12-01", one("SELECT string_agg(business_date::text, ',') FROM app.client_error"))
    }
}
