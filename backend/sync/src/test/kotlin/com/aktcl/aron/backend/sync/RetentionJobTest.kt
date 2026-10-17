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
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }

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
                val job = RetentionJob(Database(ds), AronClock { Instant.parse("2026-10-07T06:00:00Z") })
                val r = job.tick()
                assertEquals(emptyMap(), r.failedParents)
                assertTrue(r.manifestsPlanned >= 1, "planned ${r.manifestsPlanned} as jobs_rw")
                assertEquals(1L, r.defaultRows["app.geo_breadcrumb"])
                // DB-01 / HD-08 (V0086): the pruning's own grants (a missing one throws 42501 here).
                assertTrue(job.prune(LocalDate.parse("2026-10-07")).ran, "the pruning runs as jobs_rw")
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

    /**
     * DB-01 / HD-08 (V0086): the night pruning deletes registry rows past `cfg.retention.ingest_registry_days` (voided
     * rows stay, D-22) in chunks, drops expired batch responses and deletes batches past the window, deletes expired
     * nonces, drops the bodies of past-date bundle snapshots and deletes snapshots older than 7 days; it reports the
     * registry's oldest row and runs only in the night window of the 6-hourly tick.
     */
    @Test
    @Order(6)
    fun theNightPruningDropsOldSyncRowsInChunksAndKeepsVoidedOnes() {
        val now = Instant.parse("2026-10-07T20:30:00Z") // 02:30 Dhaka on 2026-10-08
        val today = LocalDate.parse("2026-10-08")
        val seedDir = java.io.File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            val user = h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one()
            val device = h.createQuery("SELECT min(id) FROM app.device").mapTo(Long::class.java).one()
            fun registry(status: String, daysAgo: Int) = h.createUpdate(
                "INSERT INTO app.ingest_registry (client_uuid, record_type, payload_sha256, status, outcome_code, user_id, device_id, first_batch_uuid, received_at, last_seen_at) " +
                    "VALUES (gen_random_uuid(), 'visit', decode(repeat('ab', 32), 'hex'), :s, :c, :u, :d, gen_random_uuid(), :at, :at)",
            ).bind("s", status).bind("c", if (status == "accepted" || status == "voided") null else "parent_missing").bind("u", user).bind("d", device)
                .bind("at", java.time.OffsetDateTime.ofInstant(now.minus(java.time.Duration.ofDays(daysAgo.toLong())), java.time.ZoneOffset.UTC)).execute()
            repeat(5) { registry("accepted", 60) }
            registry("parked", 50); registry("voided", 70); registry("accepted", 10)
            fun batch(hoursAgo: Long, withResponse: Boolean) = h.createUpdate(
                "INSERT INTO app.sync_batch (device_id, batch_uuid, user_id, fingerprint, record_count, response_gz, received_at, expires_at) " +
                    "VALUES (:d, gen_random_uuid(), :u, decode(repeat('cd', 32), 'hex'), 1, :r, :rcv, :exp)",
            ).bind("d", device).bind("u", user).bind("r", if (withResponse) byteArrayOf(1, 2, 3) else null)
                .bind("rcv", java.time.OffsetDateTime.ofInstant(now.minusSeconds(hoursAgo * 3600 + 48 * 3600), java.time.ZoneOffset.UTC))
                .bind("exp", java.time.OffsetDateTime.ofInstant(now.minusSeconds(hoursAgo * 3600), java.time.ZoneOffset.UTC)).execute()
            batch(2, true); batch(60 * 24, true); batch(-10, true)
            h.createUpdate("INSERT INTO app.device_nonce (nonce_sha256, device_id, purpose, expires_at) VALUES (decode(repeat('01', 32), 'hex'), :d, 'play_integrity', :e)")
                .bind("d", device).bind("e", java.time.OffsetDateTime.ofInstant(now.minusSeconds(60), java.time.ZoneOffset.UTC)).execute()
            h.createUpdate("INSERT INTO app.device_nonce (nonce_sha256, device_id, purpose, expires_at) VALUES (decode(repeat('02', 32), 'hex'), :d, 'play_integrity', :e)")
                .bind("d", device).bind("e", java.time.OffsetDateTime.ofInstant(now.plusSeconds(600), java.time.ZoneOffset.UTC)).execute()
            fun snapshot(date: LocalDate) = h.createUpdate("INSERT INTO app.bundle_snapshot (user_id, business_date, snapshot_seq, content_sha256, body_gz) VALUES (:u, :d, 1, decode(repeat('ef', 32), 'hex'), :b)")
                .bind("u", user).bind("d", date).bind("b", byteArrayOf(9)).execute()
            snapshot(today.minusDays(10)); snapshot(today.minusDays(1)); snapshot(today); snapshot(today.plusDays(1))
        }
        val night = RetentionJob(fresh.db, AronClock { now }, chunk = 2)
        assertTrue(night.inNightWindow(now)); assertFalse(night.inNightWindow(Instant.parse("2026-10-07T06:00:00Z")))
        val p = night.prune(today)
        assertEquals(6, p.registryRows, "five old accepted and the old parked row, in chunks of two")
        assertEquals(2, p.batchResponses, "the two expired responses dropped")
        assertEquals(1, p.batchRows, "the batch past the registry window deleted")
        assertEquals(1, p.nonces)
        assertEquals(2, p.snapshotBodies, "bodies of the two past dates dropped")
        assertEquals(1, p.snapshotRows, "the date older than 7 days deleted")
        assertEquals(emptyList(), p.capped)
        assertEquals(10L, p.oldestRegistryDays, "the young accepted row is the oldest left (the voided one is not counted)")
        assertEquals(2L, count("SELECT count(*) FROM app.ingest_registry WHERE status IN ('voided', 'accepted')"), "the voided row and the young row stay")
        assertEquals(2L, count("SELECT count(*) FROM app.sync_batch"))
        assertEquals(0L, count("SELECT count(*) FROM app.sync_batch WHERE expires_at < TIMESTAMPTZ '2026-10-07T20:30:00Z' AND response_gz IS NOT NULL"))
        assertEquals(1L, count("SELECT count(*) FROM app.device_nonce"))
        assertEquals(3L, count("SELECT count(*) FROM app.bundle_snapshot"))
        assertEquals(2L, count("SELECT count(*) FROM app.bundle_snapshot WHERE body_gz IS NOT NULL"), "today and tomorrow keep their bodies")
        // A second run finds nothing more; outside the night window the tick does not prune.
        assertEquals(0, night.prune(today).registryRows)
        assertFalse(RetentionJob(fresh.db, AronClock { Instant.parse("2026-10-07T06:00:00Z") }).tick().pruned.ran)
    }
}
