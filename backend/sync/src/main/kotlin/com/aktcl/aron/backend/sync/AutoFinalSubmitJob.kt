package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.Inbox
import com.aktcl.aron.backend.platform.InboxItem
import com.aktcl.aron.backend.platform.Nudger
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.contract.ConfigScopeType
import com.aktcl.aron.contract.NotificationKind
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jdbi.v3.core.Handle
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The midnight auto Final Submit (tso-manual B3, D-TSO-MAN-30; screenshot 26; build contract s5 B3).
 *
 * At 00:00 Asia/Dhaka the zone-days of the day that ended close by themselves, and at start the job catches up every
 * day of the reopen window (`cfg.day.reopen_window_days`, default 3), so a worker that was down at midnight closes
 * them when it comes back. One pass ([runOnce]) covers the dates `today - window .. today - 1` oldest first; each pass
 * is idempotent, so a second worker replica or a second pass closes nothing twice.
 *
 * - **Which zone-days:** every service zone of the date (a planned route-day of a normal SR route that no approved day
 *   exception removes, the rule of the national final-submit figure) without a live submit (D-TSO-MAN-30). A zone-day
 *   that a TSO, a delegate or this job closed is skipped while that close is live; one an ADMIN reopened is closed
 *   again by the next pass inside the window, unless a person closed it first (D-B3-11, revised in the fix round).
 * - **Switch:** `cfg.day.auto_final_submit_enabled` (default true) resolved at the zone's wing, then global.
 * - **The close** is the manual submit's locked code path ([DayService.closeLocked]) under the same zone-day advisory
 *   lock, with `via = 'job'`, `submitted_by` NULL (V0135 CHECK `final_submit_job_actor`) and a client uuid derived from
 *   the zone-day and its cycle (`aron.final-submit.job:<zone>:<date>:<submit_seq>`, submit_seq = the reopened closes,
 *   D-TSO-MAN-28), so it is a v4-shaped uuid the replay rules know as kind `auto` and a re-close after a reopen never
 *   collides with the first close's unique client_uuid.
 *   Its trace is the `via = 'job'` row and its `zone_day.final_submitted` event; it writes no `app.audit_log` row (the
 *   worker has no INSERT there, R-F-07).
 * - **Notices**, in the close's transaction: one inbox row `final_submit_auto` for every active TSO whose reach covers
 *   the zone on that date ([Inbox.add], unique per recipient, R-F-04; the dedupe key also names the cycle and the
 *   recipient). After
 *   the commit: the push `notification` to each TSO with a new row and `day_final_submitted` to the zone's SR and AMO
 *   phones (as the manual close does). A lost push only delays the phone's next pull; ingest holds late rows anyway.
 */
class AutoFinalSubmitJob(
    private val db: Database,
    private val config: ServerConfig,
    private val reach: ReachResolver,
    private val nudger: Nudger = Nudger.NONE,
    private val clock: AronClock = AronClock.SYSTEM,
) {
    private val log = LoggerFactory.getLogger("aron.auto-final-submit")
    private val day = DayService(db, config, reach, ingest = null, clock = clock, nudger = Nudger.NONE)

    /** One zone-day the job closed: its uuid, the TSOs that got a new inbox row, the SR and AMO users of the zone. */
    data class Closed(val zoneId: Long, val businessDate: LocalDate, val clientUuid: String, val tsoUserIds: Set<Long>, val fieldUserIds: List<Long>)

    /** The Dhaka business date of now. */
    fun today(): LocalDate = LocalDate.ofInstant(clock.now(), DHAKA)

    /** The dates one pass covers: the reopen window before today, oldest first (empty when the window is 0). */
    fun window(today: LocalDate = today()): List<LocalDate> {
        val days = db.jdbi.withHandle<Int, Exception> { h ->
            ScopedConfig.load(h, clock.now(), clock.now(), setOf(WINDOW_KEY)).int(WINDOW_KEY, ScopedConfig.Chain.of(), 3)
        }.coerceIn(0, 31)
        return (days downTo 1).map { today.minusDays(it.toLong()) }
    }

    /** One pass over [window]: closes every open service zone-day; returns what it closed (empty on a repeat). */
    fun runOnce(): List<Closed> = window().flatMap { runCatching { closeDate(it) }.onFailure { e -> log.error("auto Final Submit of {} failed", it, e) }.getOrDefault(emptyList()) }

    /** Closes the open service zone-days of [date] (never today or later); each zone in its own transaction. */
    fun closeDate(date: LocalDate): List<Closed> {
        if (!date.isBefore(today())) return emptyList()
        val zones = db.jdbi.withHandle<List<Pair<Long, Long>>, Exception> { h -> openServiceZones(h, date) }
        if (zones.isEmpty()) return emptyList()
        val tsos = TsoReach(date)
        val switch = db.jdbi.withHandle<ScopedConfig, Exception> { h -> ScopedConfig.load(h, clock.now(), clock.now(), setOf(SWITCH_KEY)) }
        val out = ArrayList<Closed>()
        for ((zone, wing) in zones) {
            val on = switch.value(SWITCH_KEY, ScopedConfig.Chain.of(ConfigScopeType.WING to wing))?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: true
            if (!on) continue
            val closed = runCatching { closeZone(zone, date, tsos.covering(zone)) }
                .onFailure { e -> log.error("auto Final Submit of zone {} on {} failed", zone, date, e) }.getOrNull() ?: continue
            out += closed
            // After the commit only (D-TSO-MAN-26, 31): no business data, the phones pull.
            closed.tsoUserIds.forEach { u -> runCatching { nudger.nudge(u, Inbox.PUSH_REASON) } }
            closed.fieldUserIds.forEach { u -> runCatching { nudger.nudge(u, PUSH_DAY_FINAL_SUBMITTED) } }
        }
        if (out.isNotEmpty()) log.info("auto Final Submit closed {} zone-day(s) of {}", out.size, date)
        return out
    }

    /**
     * The close of one zone-day, or null when it was closed (or reopened) meanwhile: the zone-day lock, the check, the
     * locked close and the inbox rows in one transaction.
     */
    private fun closeZone(zone: Long, date: LocalDate, tsoUsers: List<Long>): Closed? = db.jdbi.inTransaction<Closed?, Exception> { h ->
        h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 3))", DayService.zoneDayLock(zone, date))
        if (day.liveClose(h, zone, date) != null) return@inTransaction null
        val seq = reopenedCloses(h, zone, date)
        val uuid = jobUuid(zone, date, seq)
        // The clock after the lock, cut to the microsecond the column stores (as the manual close, KD-2).
        val now = clock.now().truncatedTo(ChronoUnit.MICROS)
        val (_, field) = day.closeLocked(h, zone, date, uuid, by = null, via = "job", kind = "auto", now = now)
        val zoneName = h.createQuery("SELECT name FROM app.zone WHERE id = :z").bind("z", zone).mapTo(String::class.java).findOne().orElse("#$zone")
        val tsos = Inbox.addAll(h, tsoUsers.map { u -> notice(u, zone, zoneName, date, seq) })
        Closed(zone, date, uuid, tsos, field)
    }

    /** The service zones of [date] without a live submit, with their wing (D-TSO-MAN-30; D-B3-11), in zone id order. */
    private fun openServiceZones(h: Handle, date: LocalDate): List<Pair<Long, Long>> = h.createQuery(
        """
        SELECT z.id, dv.wing_id FROM app.zone z
          JOIN app.territory t ON t.id = z.territory_id JOIN app.division dv ON dv.id = t.division_id
         WHERE EXISTS (SELECT 1 FROM app.route_day rd JOIN app.route r ON r.id = rd.route_id
                        WHERE r.zone_id = z.id AND rd.business_date = :d AND rd.planned AND r.kind = 'sr' AND r.route_class = 'normal'
                          AND NOT EXISTS (SELECT 1 FROM app.day_exception e WHERE e.status = 'approved' AND e.voided_at IS NULL
                                           AND r.id = ANY (e.route_ids) AND :d BETWEEN e.from_date AND e.to_date))
           AND NOT EXISTS (SELECT 1 FROM app.final_submit f WHERE f.zone_id = z.id AND f.business_date = :d AND f.reopened_at IS NULL)
         ORDER BY z.id
        """.trimIndent(),
    ).bind("d", date).map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()

    /** The reopened closes of the zone-day: the cycle of the next close (D-TSO-MAN-28's submit_seq). */
    private fun reopenedCloses(h: Handle, zone: Long, date: LocalDate): Int =
        h.createQuery("SELECT count(*) FROM app.final_submit WHERE zone_id = :z AND business_date = :d AND reopened_at IS NOT NULL").bind("z", zone).bind("d", date).mapTo(Int::class.java).one()

    /** The active TSOs and their reach on one date, read once per pass of that date. */
    private inner class TsoReach(private val date: LocalDate) {
        private val reaches: List<Reach> by lazy {
            db.jdbi.withHandle<List<Pair<Long, Long>>, Exception> { h ->
                h.createQuery("SELECT id, scope_version FROM app.app_user WHERE role = 'TSO' AND status = 'active' ORDER BY id").map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
            }.mapNotNull { (u, sv) -> runCatching { reach.reach(u, Role.TSO, sv, date) }.onFailure { log.warn("reach of TSO {} on {} unreadable", u, date) }.getOrNull() }
        }

        fun covering(zone: Long): List<Long> = reaches.filter { it.coversZone(zone) }.map { it.userId }
    }

    /**
     * The inbox row of one TSO (D-TSO-MAN-26): fixed texts in both languages (constraint 8), the zone name and the date;
     * the dedupe key names the zone-day, the cycle (a re-close after a reopen is a new notice) and the recipient (R-F-04).
     */
    private fun notice(user: Long, zone: Long, zoneName: String, date: LocalDate, seq: Int): InboxItem {
        val name = zoneName.take(60)
        return InboxItem(
            userId = user, kind = NotificationKind.FINAL_SUBMIT_AUTO.wire, refType = "zone_day", refId = Inbox.zoneDayRefId(zone, date),
            titleEn = "Final Submit done automatically", titleBn = "ফাইনাল সাবমিট স্বয়ংক্রিয়ভাবে হয়েছে",
            bodyEn = "$name, $date: no Final Submit by midnight, so the system submitted the zone.",
            bodyBn = "$name, ${bnDigits(date.toString())}: মধ্যরাতের মধ্যে ফাইনাল সাবমিট হয়নি, তাই সিস্টেম জোনটি সাবমিট করেছে।",
            dedupeKey = "final_submit_auto:$zone:$date:$seq:$user",
        )
    }

    /** Runs [runOnce] now (the start-up catch-up) and then at every 00:00 Asia/Dhaka (a few seconds after). */
    fun start(): ScheduledExecutorService {
        val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "auto-final-submit").apply { isDaemon = true } }
        fun tick() {
            runCatching { runOnce() }.onFailure { log.error("auto Final Submit pass failed", it) }
            ex.schedule({ tick() }, delayToNextRun(clock.now()).toMillis(), TimeUnit.MILLISECONDS)
        }
        ex.execute { tick() }
        return ex
    }

    companion object {
        private val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")
        private const val WINDOW_KEY = "cfg.day.reopen_window_days"
        private const val SWITCH_KEY = "cfg.day.auto_final_submit_enabled"
        /** Past midnight by this much, so the Dhaka date has surely moved on every clock. */
        private val AFTER_MIDNIGHT: Duration = Duration.ofSeconds(5)

        /** The client uuid of the job's close of a zone-day in cycle [seq] (v4-shaped, as the batch contract requires). */
        fun jobUuid(zone: Long, date: LocalDate, seq: Int): String = DayService.derivedV4("aron.final-submit.job:$zone:$date:$seq")

        /** From [now] to the next 00:00:05 Asia/Dhaka. */
        fun delayToNextRun(now: Instant): Duration {
            val next = now.atZone(DHAKA).toLocalDate().plusDays(1).atStartOfDay(DHAKA).toInstant().plus(AFTER_MIDNIGHT)
            return Duration.between(now, next).coerceAtLeast(Duration.ofSeconds(1))
        }

        private fun bnDigits(s: String): String = s.map { c -> if (c in '0'..'9') '০' + (c - '0') else c }.joinToString("")
    }
}
