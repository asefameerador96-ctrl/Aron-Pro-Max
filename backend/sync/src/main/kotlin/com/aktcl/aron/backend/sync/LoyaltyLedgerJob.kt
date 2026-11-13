package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.exitIfFatal
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.toJavaLocalDate
import org.jdbi.v3.core.Handle
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The points ledger's writers (lane portal-loyalty-api, push C, round C-R1; D-LOY-API-18, D-LOY-API-22). `app.loyalty_ledger` is append-only and UNIQUE on (source_type,
 * source_id), so every posting below is a plain `INSERT ... ON CONFLICT DO NOTHING`: a retry, a second worker replica or a re-run is a no-op. Integer points only.
 *
 * LEDGER MODEL. A ledger row is a FACT: a credit (survey_response, adjustment, migration ...), a redemption debit, an adjustment, a reversal of a voided redemption, or an expiry
 * correction. No row carries a judgement made on a partial view (records arrive late and out of order, the ledger is append-only), so:
 *  - [postRedemptions] posts EVERY stored, non-voided `app.redemption` exactly once (`source_type` redemption, `source_id` = the client_uuid), always. There is no skip and no flag:
 *    `cfg.loyalty.negative_balance_policy = reject` is NOT enforced anywhere (not at ingest, not here) and its configuration refuses that value.
 *  - OVERDRAW IS DERIVED: an (outlet, programme) is overdrawn exactly when its balance (the plain sum of every row) is below zero (`LoyaltyBalance.overdrawn`).
 *  - [postReversals]: a redemption voided after posting gets one row (+points, `source_type` redemption_void, `source_id` = the redemption's client_uuid, UNIQUE). In the replay a
 *    voided redemption and its reversal cancel out.
 *  - EXPIRY IS A TARGET ([lapsing], [expire]): the whole history of the pair is replayed (see [replay]); the points that must have lapsed by today (Dhaka) are L; the points the
 *    expiry rows already took are E; the run posts ONE correction row of (E - L) when that is not zero (negative: more must lapse; positive: a late-synced debit dated on or
 *    before the expiry consumed points that had been lapsed). `source_id` = `programme:outlet:date:n` (n = the pair's expiry rows of that day + 1, under the pair's advisory
 *    lock), so a second correction on the same day is possible. Nothing new = nothing posted.
 *  - [reconcilePair]: all of the above for ONE pair under its advisory lock in the caller's transaction (the job gives each pair its own).
 *  - [postAdjustment]: the adjustment plumbing (`source_type` adjustment; the portal or a migration, no earning rule is known: owner Q1).
 */
object LoyaltyLedger {
    /** One balance, one writer at a time (a second worker replica waits here until the first commits). */
    private fun lockPair(h: Handle, outlet: Long, programme: Long) {
        h.createQuery("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))) x").bind("k", "loyalty:$outlet:$programme").mapTo(Int::class.java).one()
    }

    /** One ledger row as the replay sees it. [at] and [id] only break ties deterministically. */
    data class Row(val points: Int, val expiresOn: LocalDate?, val sourceType: String, val businessDate: LocalDate, val sourceId: String = "", val at: Instant = Instant.EPOCH, val id: Long = 0)

    /**
     * The outcome of [replay]: the points of credits that have lapsed by `today`, the DEBT still outstanding at the end (debit points no valid credit funded and no later credit
     * settled: the negative part of the balance), the net of the rows replayed, and the pool of credits with what is left of each.
     */
    data class Replayed(val lapsed: Long, val unfunded: Long, val net: Long, val pool: List<Credit> = emptyList())

    class Credit(var left: Long, val exp: LocalDate?)

    /**
     * Pure, deterministic. Replays every non-expiry row of ONE pair in `business_date` order (within a date: credits first, because a credit earned on a day is valid that day, and the credits
     * among themselves by expiry (earliest first, none last), points, source id - NOT by arrival, so which credit absorbs a debt never depends on which synced first; then debits by created time, id). A voided redemption and its reversal are left out together. A debit dated d consumes only credits that were still valid on d
     * (`expires_on` null or >= d), the one expiring first first; it NEVER consumes a credit that had already lapsed on d. What it cannot fund becomes DEBT (that is a negative
     * balance), and EVERY LATER CREDIT FIRST SETTLES THAT DEBT (C-R2): only the remainder of a credit enters the pool, so points that were never really there cannot be handed
     * back or lapse twice (+100 expiring, run -100, an unfunded -100, +100 expiring: the second credit is wholly consumed by the debt and lapses nothing). `lapsed` = what is
     * left of the credits with `expires_on < today`.
     */
    fun replay(rows: List<Row>, today: LocalDate): Replayed {
        val voided = rows.filter { it.sourceType == VOID }.map { it.sourceId }.toSet()
        val live = rows.filter { it.sourceType != EXPIRY && it.sourceType != VOID && !(it.sourceType == REDEMPTION && it.sourceId in voided) }
            .sortedWith(
                compareBy<Row>({ it.businessDate }, { if (it.points > 0) 0 else 1 }, { if (it.points > 0) it.expiresOn ?: LocalDate.MAX else LocalDate.MIN },
                    { if (it.points > 0) it.points else 0 }, { if (it.points > 0) it.sourceId else "" }, { it.at }, { it.id }),
            )
        val pool = ArrayList<Credit>(); var debt = 0L; var net = 0L
        for (r in live) {
            net += r.points
            if (r.points > 0) {
                val settled = minOf(debt, r.points.toLong()); debt -= settled
                if (r.points > settled) pool += Credit(r.points - settled, r.expiresOn)
                continue
            }
            var need = -r.points.toLong()
            for (c in pool.filter { it.left > 0 && (it.exp == null || it.exp >= r.businessDate) }.sortedBy { it.exp ?: LocalDate.MAX }) {   // stable: ties keep credit order
                if (need == 0L) break
                val used = minOf(c.left, need); c.left -= used; need -= used
            }
            debt += need
        }
        return Replayed(pool.filter { it.exp != null && it.exp < today }.sumOf { it.left }, debt, net, pool)
    }

    /** What the earliest upcoming expiry will take, AFTER debt settlement: (`expiry_date`, points) of the pool's credits with `expires_on >= today`; null when none has anything left. */
    fun upcoming(rows: List<Row>, today: LocalDate): Pair<LocalDate, Long>? {
        val live = replay(rows, today).pool.filter { it.left > 0 && it.exp != null && it.exp >= today }
        val first = live.minOfOrNull { it.exp!! } ?: return null
        return first to live.filter { it.exp == first }.sumOf { it.left }
    }

    /** The points the expiry rows should still take now (L) less what they already took (E): the correction row is `-(L - E)`; 0 = nothing to post. */
    fun lapsing(rows: List<Row>, today: LocalDate): Long = replay(rows, today).lapsed - rows.filter { it.sourceType == EXPIRY }.sumOf { -it.points.toLong() }

    private fun rowsOf(h: Handle, outlet: Long, programme: Long): List<Row> = h.createQuery(
        "SELECT points, expires_on, source_type, business_date, source_id, created_at, id FROM app.loyalty_ledger WHERE outlet_id = :o AND programme_id = :p",
    ).bind("o", outlet).bind("p", programme).map { rs, _ ->
        Row(rs.getInt(1), rs.getObject(2, LocalDate::class.java), rs.getString(3), rs.getObject(4, LocalDate::class.java), rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getLong(7))
    }.list()

    /** Posts the pair's stored, non-voided redemptions that have no ledger row yet, oldest first, every one, exactly once. Returns how many rows were posted. */
    fun postRedemptions(h: Handle, outlet: Long, programme: Long): Int {
        val todo = h.createQuery(
            """
            SELECT r.client_uuid::text, r.points_total, r.business_date FROM app.redemption r
             WHERE r.outlet_id = :o AND r.programme_id = :p AND r.voided_at IS NULL
               AND NOT EXISTS (SELECT 1 FROM app.loyalty_ledger l WHERE l.source_type = 'redemption' AND l.source_id = r.client_uuid::text)
             ORDER BY r.business_date, r.confirmed_at, r.id
            """.trimIndent(),
        ).bind("o", outlet).bind("p", programme).map { rs, _ -> Triple(rs.getString(1), rs.getInt(2), rs.getObject(3, LocalDate::class.java)) }.list()
        return todo.sumOf { (cu, points, date) ->
            h.createUpdate(
                "INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date, flags) VALUES (:o, :p, 'redemption', :cu, :pts, :bd, '{}') ON CONFLICT (source_type, source_id) DO NOTHING",
            ).bind("o", outlet).bind("p", programme).bind("cu", cu).bind("pts", -points).bind("bd", date).execute()
        }
    }

    /** One reversal row (+points) for every redemption that was posted and voided afterwards. Returns how many were posted. */
    fun postReversals(h: Handle, outlet: Long, programme: Long, today: LocalDate): Int = h.createQuery(
        """
        SELECT r.client_uuid::text, r.points_total FROM app.redemption r
         WHERE r.outlet_id = :o AND r.programme_id = :p AND r.voided_at IS NOT NULL
           AND EXISTS (SELECT 1 FROM app.loyalty_ledger l WHERE l.source_type = 'redemption' AND l.source_id = r.client_uuid::text)
           AND NOT EXISTS (SELECT 1 FROM app.loyalty_ledger v WHERE v.source_type = 'redemption_void' AND v.source_id = r.client_uuid::text)
         ORDER BY r.id
        """.trimIndent(),
    ).bind("o", outlet).bind("p", programme).map { rs, _ -> rs.getString(1) to rs.getInt(2) }.list().sumOf { (cu, points) ->
        h.createUpdate(
            "INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date, flags) VALUES (:o, :p, 'redemption_void', :cu, :pts, :bd, '{}') ON CONFLICT (source_type, source_id) DO NOTHING",
        ).bind("o", outlet).bind("p", programme).bind("cu", cu).bind("pts", points).bind("bd", today).execute()
    }

    /** The expiry correction of one pair as of [today] (Dhaka): at most ONE row (E - L), see the class text. Returns 1 when a row was posted, else 0. */
    fun expire(h: Handle, outlet: Long, programme: Long, today: LocalDate): Int {
        val rows = rowsOf(h, outlet, programme)
        val correction = -lapsing(rows, today)   // = E - L
        if (correction == 0L) return 0
        val n = rows.count { it.sourceType == EXPIRY && it.businessDate == today } + 1
        return h.createUpdate(
            "INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date, flags) VALUES (:o, :p, 'expiry', :sid, :pts, :bd, '{}') ON CONFLICT (source_type, source_id) DO NOTHING",
        ).bind("o", outlet).bind("p", programme).bind("sid", "$programme:$outlet:$today:$n").bind("pts", Math.toIntExact(correction)).bind("bd", today).execute()
    }

    /**
     * Everything for ONE pair under its advisory lock, in the caller's transaction: (redemption and reversal rows posted, expiry rows posted). [todayOf] is read AFTER the lock is
     * held (C-R2): two replicas straddling Dhaka midnight each judge the pair by the date at which they really hold it, never by a date taken before they waited for each other.
     */
    fun reconcilePair(h: Handle, outlet: Long, programme: Long, todayOf: () -> LocalDate): Pair<Int, Int> {
        lockPair(h, outlet, programme)
        val today = todayOf()
        val posted = postRedemptions(h, outlet, programme) + postReversals(h, outlet, programme, today)
        return posted to expire(h, outlet, programme, today)
    }

    /**
     * Where the last pass got to (in memory; a restart makes the next pass a full sweep). [ledgerId] is the newest ledger id seen, [date] the Dhaka date of the last pass,
     * [fullSweepOn] the date of the last daily sweep.
     */
    data class Cursor(val ledgerId: Long = 0, val date: LocalDate? = null, val fullSweepOn: LocalDate? = null)

    /** Ledger ids this far behind the cursor are looked at again: a lower id can commit after a higher one. */
    private const val OVERLAP = 1_000L

    /** An ordinary pass looks for unposted redemptions and unreversed voids this many days back (by the `business_date` index); the daily sweep looks at all of them. */
    const val LOOKBACK_DAYS = 90L

    /**
     * The pairs one pass must reconcile (C-R2). WHAT CAN MAKE E != L (the expiry rows taken vs the points that must have lapsed) is only: (i) a stored redemption with no ledger row
     * yet or a voided one with no reversal (found by "no ledger row" through the unique index on (source_type, source_id), NOT by an id window: a slow ingest transaction can
     * commit a low id a day late; an ordinary pass looks [LOOKBACK_DAYS] back on the business_date index, the first pass of each Dhaka day looks at all of them); (ii) a new ledger
     * row (ids past the cursor less [OVERLAP]); (iii) time passing: a credit whose `expires_on` fell between the cursor's date and today (the daily sweep: since the previous
     * sweep's date); (iv) a pair that failed before ([retry]). With no cursor (a start) every pair with an expiring credit is taken once. Nothing else needs a look, so the daily
     * sweep is bounded by the credits that expired since the last one, not by every pair that ever had a credit.
     */
    fun pairsToReconcile(h: Handle, today: LocalDate, cursor: Cursor, retry: Set<Pair<Long, Long>> = emptySet()): Set<Pair<Long, Long>> {
        val first = cursor.date == null
        val sweepDay = first || cursor.fullSweepOn != today
        val out = LinkedHashSet<Pair<Long, Long>>(retry)
        fun add(sql: String, bind: (org.jdbi.v3.core.statement.Query) -> Unit = {}) {
            h.createQuery(sql).also(bind).map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list().let(out::addAll)
        }
        add(
            """
            SELECT DISTINCT r.outlet_id, r.programme_id FROM app.redemption r
             WHERE (:sweep OR r.business_date >= :lookback) AND (
                   (r.voided_at IS NULL AND NOT EXISTS (SELECT 1 FROM app.loyalty_ledger l WHERE l.source_type = 'redemption' AND l.source_id = r.client_uuid::text))
                OR (r.voided_at IS NOT NULL AND EXISTS (SELECT 1 FROM app.loyalty_ledger l WHERE l.source_type = 'redemption' AND l.source_id = r.client_uuid::text)
                                           AND NOT EXISTS (SELECT 1 FROM app.loyalty_ledger v WHERE v.source_type = 'redemption_void' AND v.source_id = r.client_uuid::text)))
            """.trimIndent(),
        ) { it.bind("sweep", sweepDay).bind("lookback", today.minusDays(LOOKBACK_DAYS)) }
        if (first) {
            add("SELECT DISTINCT outlet_id, programme_id FROM app.loyalty_ledger WHERE points > 0 AND expires_on IS NOT NULL")
        } else {
            add("SELECT DISTINCT outlet_id, programme_id FROM app.loyalty_ledger WHERE id > :lid AND source_type <> 'expiry'") { it.bind("lid", maxOf(0L, cursor.ledgerId - OVERLAP)) }
            val since = if (sweepDay) cursor.fullSweepOn ?: cursor.date else cursor.date
            add("SELECT DISTINCT outlet_id, programme_id FROM app.loyalty_ledger WHERE points > 0 AND expires_on >= :since AND expires_on < :d") { it.bind("since", since).bind("d", today) }
        }
        return out
    }

    /** Adjustment plumbing: one signed row, idempotent on [sourceId]. Returns true when it was posted now, false on a replay. */
    fun postAdjustment(h: Handle, outletId: Long, programmeId: Long, points: Int, sourceId: String, businessDate: LocalDate, expiresOn: LocalDate? = null, flags: List<String> = emptyList()): Boolean {
        require(points != 0 && sourceId.length in 1..64)
        return h.createUpdate(
            """
            INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date, expires_on, flags)
            VALUES (:o, :p, 'adjustment', :sid, :pts, :bd, :exp, CAST(:flags AS text[])) ON CONFLICT (source_type, source_id) DO NOTHING
            """.trimIndent(),
        ).bind("o", outletId).bind("p", programmeId).bind("sid", sourceId).bind("pts", points).bind("bd", businessDate).bind("exp", expiresOn)
            .bind("flags", pgTextArray(flags)).execute() == 1
    }

    /** A PostgreSQL text[] literal with every element quoted and escaped. */
    internal fun pgTextArray(items: List<String>): String = items.joinToString(",", "{", "}") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }

    const val REDEMPTION = "redemption"
    const val VOID = "redemption_void"
    const val EXPIRY = "expiry"
}

/**
 * The worker job around [LoyaltyLedger] (D-LOY-API-22, D-LOY-API-24). One pass = the pairs from [LoyaltyLedger.pairsToReconcile], EACH IN ITS OWN TRANSACTION (one failing pair is
 * logged by class and does not roll back the others). The cursor ALWAYS advances; a pair that failed goes to a retry set and is taken again by the next pass, so one bad pair never
 * turns every pass into a full sweep. An Exception is logged and the schedule goes on; an Error ends the process through [exitIfFatal] (lead's ruling on push A3). The first pass is
 * 7 minutes after start (never 0), then every 10 minutes; the first pass after a start and the first of each Dhaka day is the (bounded) daily sweep.
 */
class LoyaltyLedgerJob(private val db: Database, private val clock: AronClock = AronClock.SYSTEM) {
    private val log = LoggerFactory.getLogger("aron.loyalty-ledger")
    private var cursor = LoyaltyLedger.Cursor()
    private var retry: Set<Pair<Long, Long>> = emptySet()

    private fun today(): LocalDate = BusinessDate.of(clock.now().toEpochMilli()).toJavaLocalDate()

    /** One pass; returns (redemption and reversal rows posted, expiry rows posted). Called by one thread at a time. */
    fun runOnce(): Pair<Int, Int> {
        val before = cursor; val day = today()
        val (lid, pairs) = db.jdbi.withHandle<Pair<Long, Set<Pair<Long, Long>>>, Exception> { h ->
            h.createQuery("SELECT COALESCE(max(id), 0) FROM app.loyalty_ledger").mapTo(Long::class.java).one() to LoyaltyLedger.pairsToReconcile(h, day, before, retry)
        }
        var posted = 0; var expired = 0; val failed = LinkedHashSet<Pair<Long, Long>>()
        for ((outlet, programme) in pairs) {
            try {
                val (a, b) = db.jdbi.inTransaction<Pair<Int, Int>, Exception> { h -> LoyaltyLedger.reconcilePair(h, outlet, programme, ::today) }
                posted += a; expired += b
            } catch (e: Exception) {
                failed += outlet to programme; log.error("loyalty ledger pair failed: {}", e.javaClass.simpleName)
            }
        }
        retry = failed
        cursor = LoyaltyLedger.Cursor(lid, day, if (before.date == null || before.fullSweepOn != day) day else before.fullSweepOn)
        return posted to expired
    }

    fun start() {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "loyalty-ledger").apply { isDaemon = true } }.scheduleWithFixedDelay({
            runCatching { runOnce() }.onSuccess { (r, e) -> if (r > 0 || e > 0) log.info("loyalty ledger: {} redemption row(s) posted, {} expiry row(s)", r, e) }
                .onFailure { it.exitIfFatal(log, "loyalty ledger"); log.error("loyalty ledger pass failed: {}", it.javaClass.simpleName) }
        }, 7, 10, TimeUnit.MINUTES)
    }
}
