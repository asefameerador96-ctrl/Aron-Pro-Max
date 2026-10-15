package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** What one pre-generation pass did: [built] users had a bundle built now, [failed] ones raised, [total] is the field users considered. */
data class PreGenResult(val total: Int, val built: Int, val failed: Int)

/** Share of the active field users that have a snapshot of [date] (`app.bundle_snapshot`). */
data class PreGenCoverage(val date: LocalDate, val active: Int, val covered: Int) {
    val pct: Double get() = if (active == 0) 100.0 else covered * 100.0 / active
}

/**
 * F-SYS-067 (docs/24 s4.10, docs/17 s6): the worker pre-builds the next business day's bundle for every active field
 * user (SR, AMO, TSO) at `cfg.bundle.d1_generation_time` (22:00 Dhaka) so the Wi-Fi pre-fetch and the morning download
 * find the snapshot (`app.bundle_snapshot`, the ETag) in place; refreshes the users whose `bundle_user` dirty key is set
 * at `cfg.bundle.refresh_time` (03:30); and at `cfg.bundle.coverage_check_time` (04:30) counts today's coverage and, below
 * [COVERAGE_TARGET_PCT], builds what is missing at once. The builds go through [BundleService.bundle] with `for = date`
 * (a pre-fetch for a later date never freezes a target or marks a login, s4.9), so the stored seq is exactly the one a
 * phone gets; builds are paced at `cfg.bundle.regen_max_per_s` (default 20). A request that finds no snapshot builds it
 * itself: the fallback is always on-request generation, so a failed pass costs time, never a day of selling.
 *
 * Idempotent and safe on several worker replicas: a user with a snapshot of the date is skipped by the nightly pass; each
 * phase takes a Postgres advisory lock for its date, and a replica that does not get it does nothing.
 */
class BundlePreGenerator(
    private val db: Database,
    private val config: ServerConfig,
    private val bundles: BundleService,
    private val clock: AronClock = AronClock.SYSTEM,
    /** Called after each build with the configured builds-per-second; the default sleeps so the rate holds. */
    private val pace: (perSecond: Int) -> Unit = { n -> Thread.sleep(1000L / n.coerceAtLeast(1)) },
) {
    private val log = LoggerFactory.getLogger("aron.bundle-pregen")
    private val done = HashSet<String>()

    private data class FieldUser(val id: Long, val username: String, val role: Role, val scopeVersion: Long)

    private fun today(): LocalDate = BusinessDate.of(clock.now().toEpochMilli()).toJavaLocalDate()

    private fun perSecond(): Int = runCatching { config.int("cfg.bundle.regen_max_per_s") }.getOrDefault(20).coerceIn(1, 200)

    private fun fieldUsers(only: Collection<Long>? = null, lacking: LocalDate? = null): List<FieldUser> = db.jdbi.withHandle<List<FieldUser>, Exception> { h ->
        h.createQuery(
            """
            SELECT u.id, u.username, u.role, u.scope_version FROM app.app_user u
            WHERE u.status = 'active' AND u.role IN ('SR', 'AMO', 'TSO')
              ${if (only == null) "" else "AND u.id = ANY(CAST(:only AS bigint[]))"}
              ${if (lacking == null) "" else "AND NOT EXISTS (SELECT 1 FROM app.bundle_snapshot s WHERE s.user_id = u.id AND s.business_date = :lack)"}
            ORDER BY u.id
            """.trimIndent(),
        ).also { q ->
            only?.let { q.bindArray("only", Long::class.javaObjectType, it.toList()) }
            lacking?.let { q.bind("lack", it) }
        }.map { rs, _ -> FieldUser(rs.getLong(1), rs.getString(2), Role.valueOf(rs.getString(3)), rs.getLong(4)) }.list()
    }

    private fun hasSnapshot(userId: Long, date: LocalDate): Boolean = db.jdbi.withHandle<Boolean, Exception> { h ->
        h.createQuery("SELECT EXISTS (SELECT 1 FROM app.bundle_snapshot WHERE user_id = :u AND business_date = :d)").bind("u", userId).bind("d", date).mapTo(Boolean::class.java).one()
    }

    /** Builds [dates] (or [date] plus the user's next working day when [withNextWorkingDay]) for [u]; true when none failed. Builds only lacking dates unless [force]. */
    private fun build(u: FieldUser, date: LocalDate, withNextWorkingDay: Boolean, force: Boolean): Pair<Boolean, Int> = try {
        val now = clock.now()
        var built = 0
        val (device, dates) = if (withNextWorkingDay) bundles.prebuildPlan(u.id, date.minusDays(1)).let { (d, ds) -> d to (listOf(date) + ds.filter { it.isAfter(date) }).distinct() } else null to listOf(date)
        val p = AronPrincipal(u.id, u.username, u.role, u.scopeVersion, "web", device, null, "web", emptyList(), false, emptyList(), "pregen-${u.id}", now.plusSeconds(600))
        for (d in dates) {
            if (!force && hasSnapshot(u.id, d)) continue
            bundles.bundle(p, d, null, prebuild = true); built++
        }
        true to built
    } catch (e: Exception) {
        log.warn("bundle pre-generation failed user={} date={} cause={}", u.id, date, e.javaClass.simpleName)
        false to 0
    }

    private val lastOk = HashSet<Long>()

    /** Builds for [users]; [lastOk] is left with the ids whose builds all succeeded. */
    private fun run(users: List<FieldUser>, date: LocalDate, withNextWorkingDay: Boolean = false, force: Boolean = false): PreGenResult {
        var built = 0; var failed = 0; var total = 0
        val rate = perSecond()
        lastOk.clear()
        for (u in users) {
            val (ok, n) = build(u, date, withNextWorkingDay, force)
            if (ok) lastOk += u.id else failed++
            if (n > 0) { built++; pace(rate) }
            total++
        }
        return PreGenResult(total, built, failed)
    }

    /** The one worker holding the lock does the phase; the others return null. The lock is per phase and date, released at the end. */
    private fun <T> exclusive(phase: String, date: LocalDate, body: () -> T): T? = db.jdbi.withHandle<T?, Exception> { h ->
        val key = "bundle-pregen:$phase:$date"
        val got = h.createQuery("SELECT pg_try_advisory_lock(hashtextextended(:k, 7))").bind("k", key).mapTo(Boolean::class.java).one()
        if (!got) return@withHandle null
        try { body() } finally { h.execute("SELECT pg_advisory_unlock(hashtextextended(?, 7))", key) }
    }

    /** 22:00: [date] (the next business day) for every active field user that has no snapshot of it yet. */
    fun generate(date: LocalDate): PreGenResult? = exclusive("d1", date) { run(fieldUsers(), date, withNextWorkingDay = true) }

    /** 03:30: users with a `bundle_user` dirty key for [date] are rebuilt and the key leaves the queue unless it was dirtied again meanwhile. */
    fun refresh(date: LocalDate): PreGenResult? = exclusive("refresh", date) {
        val keys = db.jdbi.withHandle<Map<Long, Int>, Exception> { h ->
            h.createQuery("SELECT subject_id, dirty_count FROM app.dirty_key WHERE kind = 'bundle_user' AND business_date = :d ORDER BY subject_id")
                .bind("d", date).map { rs, _ -> rs.getLong(1) to rs.getInt(2) }.list().toMap()
        }
        val result = run(fieldUsers(only = keys.keys), date, force = true)
        val okIds = lastOk.toSet()
        db.jdbi.useHandle<Exception> { h ->
            keys.filterKeys { it in okIds }.forEach { (id, count) ->
                h.execute("DELETE FROM app.dirty_key WHERE kind = 'bundle_user' AND subject_id = ? AND business_date = ? AND dirty_count = ?", id, date, count)
            }
        }
        result
    }

    fun coverage(date: LocalDate): PreGenCoverage = db.jdbi.withHandle<PreGenCoverage, Exception> { h ->
        h.createQuery(
            """
            SELECT count(*), count(*) FILTER (WHERE EXISTS (SELECT 1 FROM app.bundle_snapshot s WHERE s.user_id = u.id AND s.business_date = :d))
            FROM app.app_user u WHERE u.status = 'active' AND u.role IN ('SR', 'AMO', 'TSO')
            """.trimIndent(),
        ).bind("d", date).map { rs, _ -> PreGenCoverage(date, rs.getInt(1), rs.getInt(2)) }.one()
    }

    /** 04:30: counts today's coverage; below the target the missing bundles are built now (the day's fallback). */
    fun coverageCheck(date: LocalDate): PreGenCoverage? = exclusive("coverage", date) {
        val c = coverage(date)
        if (c.pct < COVERAGE_TARGET_PCT) {
            log.error("bundle coverage {}% of {} active field users for {} is below {}%: building the missing {} now", "%.1f".format(c.pct), c.active, date, COVERAGE_TARGET_PCT, c.active - c.covered)
            run(fieldUsers(lacking = date), date)
        } else {
            log.info("bundle coverage {}% of {} active field users for {}", "%.1f".format(c.pct), c.active, date)
        }
        coverage(date)
    }

    /** One scheduler tick at [now]: every phase whose Dhaka time has passed and that has not run for its date runs once per process. */
    fun tick(now: Instant = clock.now()) {
        val local = now.atZone(DHAKA)
        val d = local.toLocalDate()
        fun due(key: String, time: LocalTime) = !local.toLocalTime().isBefore(time) && "$key:$d" !in done
        fun mark(key: String) { done += "$key:$d"; done.removeIf { !it.endsWith(":$d") } }
        // Before 22:00 the cutoff for D+1 has not come; after 23:59 the date rolls and the 03:30 / 04:30 phases belong to the new day.
        if (due("refresh", timeOf("cfg.bundle.refresh_time", "03:30")) && local.toLocalTime().isBefore(NIGHT_START)) {
            if (refresh(d) != null) mark("refresh") // a null (another replica holds the phase) or a throw is retried next minute
        }
        if (due("coverage", timeOf("cfg.bundle.coverage_check_time", "04:30")) && local.toLocalTime().isBefore(NIGHT_START)) {
            if (coverageCheck(d) != null) mark("coverage")
        }
        if (due("d1", timeOf("cfg.bundle.d1_generation_time", "22:00"))) {
            if (generate(d.plusDays(1)) != null) mark("d1")
        }
    }

    private fun timeOf(key: String, default: String): LocalTime =
        runCatching { LocalTime.parse(config.value(key).jsonPrimitive.content) }.getOrDefault(LocalTime.parse(default))

    fun start(everyMin: Long = 1) {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bundle-pregen").apply { isDaemon = true } }.scheduleWithFixedDelay({
            runCatching { tick() }.onFailure { log.error("bundle pre-generation tick failed", it) }
        }, 1, everyMin, TimeUnit.MINUTES)
    }

    companion object {
        const val COVERAGE_TARGET_PCT = 99.0
        private val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")
        private val NIGHT_START: LocalTime = LocalTime.of(20, 0)
    }
}
