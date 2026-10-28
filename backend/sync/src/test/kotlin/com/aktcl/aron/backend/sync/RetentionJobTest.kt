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
import java.util.concurrent.TimeUnit
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
            fresh.db.jdbi.useHandle<Exception> { h ->
                h.execute(
                    "INSERT INTO app.ingest_registry (client_uuid, record_type, payload_sha256, status, user_id, first_batch_uuid, received_at, last_seen_at) " +
                        "SELECT gen_random_uuid(), 'visit', sha256('x'::bytea), 'accepted', (SELECT id FROM app.app_user WHERE username = 'aron.system'), gen_random_uuid(), " +
                        "TIMESTAMPTZ '2026-08-01 00:00:00+00', TIMESTAMPTZ '2026-08-01 00:00:00+00'",
                )
            }
            val url = fresh.url.replace(Regex("([?&])user=[^&]*"), "$1user=$login").replace(Regex("([?&])password=[^&]*"), "$1password=$pw")
            Database.pool(url, login, Secret(pw), 2, "rt-jobs").use { ds ->
                val r = RetentionJob(Database(ds), AronClock { Instant.parse("2026-10-07T06:00:00Z") }).tick()
                assertEquals(emptyMap(), r.failedParents)
                assertTrue(r.manifestsPlanned >= 1, "planned ${r.manifestsPlanned} as jobs_rw")
                assertEquals(1L, r.defaultRows["app.geo_breadcrumb"])
                assertEquals(1L, r.registryRowsPruned, "registry pruned as jobs_rw")
            }
        } finally {
            fresh.db.jdbi.useHandle<Exception> { h -> h.execute("DROP OWNED BY $login"); h.execute("DROP ROLE $login") }
        }
    }

    /**
     * db V0120: registry rows last seen more than cfg.retention.ingest_registry_days (45) before the job's clock are
     * pruned in chunks, voided tombstones and younger rows stay, a second run deletes nothing, and a global value below
     * the key's minimum (30) cannot shorten the window.
     */
    @Test
    @Order(5)
    fun ingestRegistryRowsPastTheWindowArePrunedAndTombstonesKept() {
        val now = "TIMESTAMPTZ '2026-10-07 06:00:00+00'"
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                "INSERT INTO app.ingest_registry (client_uuid, record_type, payload_sha256, status, outcome_code, user_id, first_batch_uuid, received_at, last_seen_at) " +
                    "SELECT gen_random_uuid(), t, sha256(g::text::bytea), s, CASE WHEN s <> 'accepted' AND s <> 'voided' THEN 'stale' END, (SELECT id FROM app.app_user WHERE username = 'aron.system'), gen_random_uuid(), " +
                    "$now - make_interval(days => d + 5), $now - make_interval(days => d) " +
                    "FROM (VALUES (1, 50, 'accepted', 'visit'), (2, 46, 'rejected', 'memo'), (3, 47, 'accepted', 'visit'), (4, 60, 'voided', 'visit'), (5, 44, 'accepted', 'visit'), " +
                    "(6, 1, 'accepted', 'visit'), (7, 70, 'quarantined', 'memo'), (8, 70, 'parked', 'due_collection'), (9, 70, 'accepted', 'memo')) v(g, d, s, t)",
            )
        }
        val chunked = RetentionJob(fresh.db, AronClock { Instant.parse("2026-10-07T06:00:00Z") }, registryChunk = 2)
        assertEquals(3L, chunked.tick().registryRowsPruned, "the three settled rows past 45 days, two chunks")
        // Kept: the matched memo uuid (no app.memo row of its own), the rows under review and the tombstone.
        assertEquals("accepted:70,parked:70,quarantined:70,voided:60,accepted:44,accepted:1", one("SELECT string_agg(status || ':' || extract(day FROM $now - last_seen_at)::int, ',' ORDER BY last_seen_at, status) FROM app.ingest_registry"))
        assertEquals(0L, chunked.tick().registryRowsPruned, "idempotent")
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                "INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) " +
                    "SELECT coalesce(max(config_version), 0) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'registry test' FROM app.cfg_version",
            )
            h.execute(
                "INSERT INTO app.cfg_value (key, scope_type, value, effective_from, config_version, reason) " +
                    "SELECT 'cfg.retention.ingest_registry_days', 'global', '5', $now - interval '1 day', max(config_version), 'test' FROM app.cfg_version",
            )
        }
        // A global value below the floor: max_backdate_days (7) + 31 wins over 5 and the key's minimum 30.
        assertEquals("38", one("SELECT app.ingest_registry_days($now)"))
        assertEquals(1L, chunked.tick().registryRowsPruned, "the 44-day row goes at the 38-day floor, the 1-day row stays")
        assertEquals("accepted:70,parked:70,quarantined:70,voided:60,accepted:1", one("SELECT string_agg(status || ':' || extract(day FROM $now - last_seen_at)::int, ',' ORDER BY last_seen_at, status) FROM app.ingest_registry"))
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("UPDATE app.cfg_value SET effective_to = effective_from + interval '1 second' WHERE key = 'cfg.retention.ingest_registry_days' AND reason = 'test'")
        }
        assertEquals("45", one("SELECT app.ingest_registry_days($now)"))
    }

    /** db V0120: a replay that touches a registry row while the prune waits for that row's lock keeps the row. */
    @Test
    @Order(6)
    fun aReplayRacingThePruneKeepsItsRegistryRow() {
        val now = "TIMESTAMPTZ '2026-10-07 06:00:00+00'"
        val uuid = java.util.UUID.randomUUID().toString()
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                "INSERT INTO app.ingest_registry (client_uuid, record_type, payload_sha256, status, user_id, first_batch_uuid, received_at, last_seen_at) " +
                    "SELECT '$uuid', 'visit', sha256('race'::bytea), 'accepted', (SELECT id FROM app.app_user WHERE username = 'aron.system'), gen_random_uuid(), " +
                    "$now - interval '55 days', $now - interval '50 days'",
            )
        }
        val replay = fresh.db.jdbi.open()
        try {
            replay.begin()
            replay.execute("UPDATE app.ingest_registry SET seen_count = seen_count + 1, last_seen_at = $now WHERE client_uuid = '$uuid'")
            val prune = java.util.concurrent.CompletableFuture.supplyAsync {
                fresh.db.jdbi.withHandle<Int, Exception> { h -> h.createQuery("SELECT app.prune_ingest_registry($now, 10)").mapTo(Int::class.java).one() }
            }
            // Wait until the prune is blocked on the row lock, then let the replay commit.
            val deadline = System.nanoTime() + 20_000_000_000
            while (one("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE 'SELECT app.prune_ingest_registry%'") != "1") {
                assertTrue(System.nanoTime() < deadline, "the prune never waited for the replay's row lock")
                Thread.sleep(50)
            }
            replay.commit()
            assertEquals(0, prune.get(20, TimeUnit.SECONDS), "the touched row is not deleted")
        } finally {
            replay.close()
        }
        assertEquals("1", one("SELECT count(*) FROM app.ingest_registry WHERE client_uuid = '$uuid'"))
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
