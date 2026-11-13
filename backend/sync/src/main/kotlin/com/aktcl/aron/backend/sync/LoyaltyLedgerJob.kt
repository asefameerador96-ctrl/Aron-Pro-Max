package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.exitIfFatal
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.jsonPrimitive
import org.jdbi.v3.core.Handle
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The points ledger's writers (lane portal-loyalty-api, push C; D-LOY-API-18). `app.loyalty_ledger` is append-only and UNIQUE on (source_type, source_id), so every posting below is
 * a plain `INSERT ... ON CONFLICT DO NOTHING`: a retry, a second worker replica or a re-run is a no-op. Integer points only.
 *
 *  - [postRedemptions]: one NEGATIVE row per `app.redemption` (`source_type` redemption, `source_id` = the redemption's client_uuid). Two phones over one balance: the redemption
 *    is KEPT and, when it has to spend points already expired on its own date (a redemption synced late, after the expiry job lapsed a credit it validly spent, is not an overdraw: the expiry gives that credit back) and `cfg.loyalty.negative_balance_policy` is not `reject`, the row carries the flag `overdraw` for admin review (the worker cannot update the immutable redemption row); it is never dropped. Cash cashback is booked only as `redemption.cash_mtk` (never a memo discount).
 *  - [expire]: the nightly expiry, FIFO. For each (outlet, programme) with a credit past its `expires_on`, the credits are consumed OLDEST FIRST (by `business_date`, then id) by the
 *    sum of all non-expiry debits; what is left of each EXPIRED credit lapses. One row per (programme, outlet, run date), `source_type` expiry, `source_id`
 *    `programme:outlet:date`, points = -(unspent part of the expired credits - what earlier expiry rows already took). So a partly redeemed credit lapses only for what is left
 *    of it and a re-run, or a run for the same date, posts nothing. The balance (plain sum of every row) is then the sum of the unexpired, unspent credits.
 *  - [postAdjustment]: the adjustment plumbing (`source_type` adjustment; the portal or a migration, no earning rule is known: owner Q1).
 */
object LoyaltyLedger {
    /** Posts the redemptions that have no ledger row yet (oldest first, so the overdraw flag follows the order the balance really moved). Returns how many rows were posted. */
    fun postRedemptions(h: Handle, rejectOverdraw: Boolean = false, limit: Int = 500): Int {
        val todo = h.createQuery(
            """
            SELECT r.client_uuid::text AS cu, r.outlet_id, r.programme_id, r.points_total, r.business_date
              FROM app.redemption r
             WHERE r.voided_at IS NULL AND NOT EXISTS (SELECT 1 FROM app.loyalty_ledger l WHERE l.source_type = 'redemption' AND l.source_id = r.client_uuid::text)
             ORDER BY r.confirmed_at, r.id LIMIT :limit
            """.trimIndent(),
        ).bind("limit", limit).map { rs, _ -> arrayOf<Any>(rs.getString("cu"), rs.getLong("outlet_id"), rs.getLong("programme_id"), rs.getInt("points_total"), rs.getObject("business_date", LocalDate::class.java)) }.list()
        var posted = 0
        for (t in todo) {
            val (cu, outlet, programme) = Triple(t[0] as String, t[1] as Long, t[2] as Long)
            val points = t[3] as Int; val date = t[4] as LocalDate
            lockPair(h, outlet, programme)   // one balance, one writer at a time
            // Overdraw = the redemption had to spend points that were already expired on its own date, judged by the same FIFO replay as the expiry over every row now known
            // (a redemption synced late, after the expiry lapsed a credit it validly spent, is no overdraw: the next expiry run gives that credit back).
            val known = h.createQuery("SELECT points, expires_on, source_type, business_date, source_id FROM app.loyalty_ledger WHERE outlet_id = :o AND programme_id = :p AND business_date <= :bd ORDER BY business_date, id")
                .bind("o", outlet).bind("p", programme).bind("bd", date).map { rs, _ -> Row(rs.getInt(1), rs.getObject(2, LocalDate::class.java), rs.getString(3), rs.getObject(4, LocalDate::class.java), rs.getString(5)) }.list()
            val overdraw = cu in replay(known + Row(-points, null, "redemption", date, cu)).second
            if (overdraw && rejectOverdraw) continue   // policy reject: the phone is refused at the till; a redemption that still arrived stays unposted for review, never dropped
            val n = h.createUpdate(
                """
                INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date, flags)
                VALUES (:o, :p, 'redemption', :cu, :pts, :bd, CAST(:flags AS text[])) ON CONFLICT (source_type, source_id) DO NOTHING
                """.trimIndent(),
            ).bind("o", outlet).bind("p", programme).bind("cu", cu).bind("pts", -points).bind("bd", date).bind("flags", if (overdraw) "{overdraw}" else "{}").execute()
            posted += n
        }
        return posted
    }

    /** One balance, one writer at a time (a second worker replica waits here until the first commits). */
    private fun lockPair(h: Handle, outlet: Long, programme: Long) {
        h.createQuery("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))) x").bind("k", "loyalty:$outlet:$programme").mapTo(Int::class.java).one()
    }

    /** One ledger row as the expiry sees it. */
    data class Row(val points: Int, val expiresOn: LocalDate?, val sourceType: String, val businessDate: LocalDate, val sourceId: String = "")

    /** The FIFO expiry as of [today] (Dhaka business date). Returns how many (outlet, programme) rows were posted (a lapse is negative, a correction positive). */
    fun expire(h: Handle, today: LocalDate): Int {
        val pairs = h.createQuery(
            "SELECT DISTINCT outlet_id, programme_id FROM app.loyalty_ledger WHERE points > 0 AND expires_on IS NOT NULL AND expires_on < :d AND source_type <> 'expiry'",
        ).bind("d", today).map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
        var posted = 0
        for ((outlet, programme) in pairs) {
            lockPair(h, outlet, programme)
            val rows = h.createQuery(
                "SELECT points, expires_on, source_type, business_date, source_id FROM app.loyalty_ledger WHERE outlet_id = :o AND programme_id = :p ORDER BY business_date, id",
            ).bind("o", outlet).bind("p", programme).map { rs, _ -> Row(rs.getInt(1), rs.getObject(2, LocalDate::class.java), rs.getString(3), rs.getObject(4, LocalDate::class.java), rs.getString(5)) }.list()
            val lapse = lapsing(rows, today)
            if (lapse == 0L) continue
            posted += h.createUpdate(
                """
                INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date, flags)
                VALUES (:o, :p, 'expiry', :sid, :pts, :bd, '{}') ON CONFLICT (source_type, source_id) DO NOTHING
                """.trimIndent(),
            ).bind("o", outlet).bind("p", programme).bind("sid", "$programme:$outlet:$today").bind("pts", Math.toIntExact(-lapse)).bind("bd", today).execute()
        }
        return posted
    }

    /**
     * The points to lapse now (negative: give back) from the rows of one (outlet, programme). Pure, for the property test. Rows are replayed in `business_date, id` order with a
     * pool of credits: a debit dated d consumes the OLDEST credit that had not expired on d (it spills into expired credits only when nothing else is left: an overdraw). What
     * is left of the credits with `expires_on < today` is the amount that must have lapsed; the answer is that less what earlier expiry rows already took, so a debit that arrives
     * late (a sync delay) and belongs to a credit already lapsed is given back, and the balance stays the sum of the unexpired, unspent credits whatever the arrival order.
     */
    fun lapsing(rows: List<Row>, today: LocalDate): Long {
        val (pool, _) = replay(rows)
        val mustHaveLapsed = pool.filter { it.exp != null && it.exp < today }.sumOf { it.left }
        val alreadyLapsed = rows.filter { it.sourceType == "expiry" }.sumOf { -it.points.toLong() }
        return mustHaveLapsed - alreadyLapsed
    }

    class Credit(var left: Long, val exp: LocalDate?)

    /** The pool after replaying [rows] (expiry rows ignored) and the source ids of the debits that had to spill into credits already expired on their date: an overdraw. */
    fun replay(rows: List<Row>): Pair<List<Credit>, Set<String>> {
        val pool = ArrayList<Credit>(); val over = HashSet<String>()
        for (r in rows) {
            if (r.sourceType == "expiry") continue
            if (r.points > 0) { pool += Credit(r.points.toLong(), r.expiresOn); continue }
            var need = -r.points.toLong()
            for (pass in 0..1) {
                if (pass == 1 && need > 0) over += r.sourceId
                for (c in pool) {
                    if (need == 0L) break
                    val usable = if (pass == 0) c.exp == null || c.exp >= r.businessDate else c.exp != null && c.exp < r.businessDate
                    if (!usable) continue
                    val used = minOf(c.left, need); c.left -= used; need -= used
                }
            }
        }
        return pool to over
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
            .bind("flags", flags.joinToString(",", "{", "}")).execute() == 1
    }
}

/**
 * The worker job around [LoyaltyLedger]: redemptions are posted, then the expiry runs, in one transaction per pass. An Exception is logged and the schedule goes on; an Error
 * ends the process through [exitIfFatal] (lead's ruling on push A3). The first pass is 7 minutes after start (never 0), then every 10 minutes.
 */
class LoyaltyLedgerJob(private val db: Database, private val config: ServerConfig, private val clock: AronClock = AronClock.SYSTEM) {
    private val log = LoggerFactory.getLogger("aron.loyalty-ledger")

    /** One pass; returns (redemptions posted, expiry rows posted). */
    fun runOnce(): Pair<Int, Int> {
        val reject = runCatching { config.value("cfg.loyalty.negative_balance_policy").jsonPrimitive.content == "reject" }.getOrDefault(false)
        val today = BusinessDate.of(clock.now().toEpochMilli()).toJavaLocalDate()
        return db.jdbi.inTransaction<Pair<Int, Int>, Exception> { h -> LoyaltyLedger.postRedemptions(h, reject) to LoyaltyLedger.expire(h, today) }
    }

    fun start() {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "loyalty-ledger").apply { isDaemon = true } }.scheduleWithFixedDelay({
            runCatching { runOnce() }.onSuccess { (r, e) -> if (r > 0 || e > 0) log.info("loyalty ledger: {} redemption(s) posted, {} expiry row(s)", r, e) }
                .onFailure { it.exitIfFatal(log, "loyalty ledger"); log.error("loyalty ledger pass failed: {}", it.javaClass.simpleName) }
        }, 7, 10, TimeUnit.MINUTES)
    }
}
