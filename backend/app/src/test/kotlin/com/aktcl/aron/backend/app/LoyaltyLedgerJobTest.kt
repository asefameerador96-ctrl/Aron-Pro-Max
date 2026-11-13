package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.sync.LoyaltyLedger
import com.aktcl.aron.backend.sync.LoyaltyLedgerJob
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Push C: the worker's ledger writers ([LoyaltyLedger]: redemption posting, the replay-based expiry with debt, adjustment plumbing) against the db seed. Money-grade: integer
 * points, every posting idempotent on (source_type, source_id), the balance (plain sum of every row) is the unexpired, unspent credits less the debt; a negative balance is the
 * overdraw (derived, no row flag).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoyaltyLedgerJobTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var today: LocalDate
    private var userId = 0L
    private var outlet = 0L
    private var seq = 0

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot)
        with(world) {
            today = today()
            userId = one("SELECT id FROM app.app_user WHERE username = 'sr1001'")
            outlet = one("SELECT id FROM app.outlet WHERE status = 'active' ORDER BY id LIMIT 1")
        }
    }

    @AfterAll fun tearDown() = world.close()

    private fun newProgramme(): Long = world.one("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('diamond_league', 'LL-${++seq}-${UUID.randomUUID().toString().take(6)}', 'Ledger test', DATE '${today.minusDays(400)}', DATE '${today.plusDays(400)}', 'active') RETURNING id")

    private fun redemption(programme: Long, points: Int, date: LocalDate): String {
        val cu = UUID.randomUUID().toString()
        world.exec(
            """
            INSERT INTO app.redemption (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, outlet_id, programme_id, balance_before_points, points_total, cash_points, cash_mtk,
                                        cash_rate_mtk_per_point, line_count, confirmed_at)
            VALUES (CAST('$cu' AS uuid), CAST('$cu' AS uuid), DATE '$date', $userId, TIMESTAMPTZ '$date 10:00:00+06', 0, $outlet, $programme, 0, $points, 0, 0, 2000, 1, TIMESTAMPTZ '$date 10:00:00+06')
            """.trimIndent(),
        )
        return cu
    }

    private fun credit(programme: Long, points: Int, date: LocalDate, expires: LocalDate?) =
        world.fresh.db.jdbi.withHandle<Boolean, Exception> { h -> LoyaltyLedger.postAdjustment(h, outlet, programme, points, "c-${UUID.randomUUID()}", date, expires) }

    private var nowAt: LocalDate = LocalDate.now()

    /** One job pass, counted for the given programmes only (the shared database holds other tests' pairs): (redemption and reversal rows, expiry rows) posted. */
    private fun step(j: LoyaltyLedgerJob, vararg ps: Long): Pair<Int, Int> {
        val inList = ps.joinToString(",")
        fun count(expiry: Boolean) = world.one("SELECT count(*) FROM app.loyalty_ledger WHERE programme_id IN ($inList) AND source_type ${if (expiry) "=" else "<>"} 'expiry' AND source_type IN ('redemption', 'redemption_void', 'expiry')").toInt()
        val a = count(false); val b = count(true); j.runOnce()
        return (count(false) - a) to (count(true) - b)
    }

    private fun job() = LoyaltyLedgerJob(world.fresh.db) { nowAt.atTime(12, 0).atZone(java.time.ZoneOffset.ofHours(6)).toInstant() }

    private fun balance(programme: Long) = world.one("SELECT COALESCE(SUM(points), 0) FROM app.loyalty_ledger WHERE outlet_id = $outlet AND programme_id = $programme")
    private fun expirySum(programme: Long) = world.one("SELECT COALESCE(SUM(points), 0) FROM app.loyalty_ledger WHERE outlet_id = $outlet AND programme_id = $programme AND source_type = 'expiry'")
    private fun positiveExpiry(programme: Long) = world.one("SELECT count(*) FROM app.loyalty_ledger WHERE outlet_id = $outlet AND programme_id = $programme AND source_type = 'expiry' AND points > 0")
    private fun rows(programme: Long) = world.one("SELECT count(*) FROM app.loyalty_ledger WHERE outlet_id = $outlet AND programme_id = $programme")
    private fun void(cu: String) = world.exec("UPDATE app.redemption SET voided_at = now() WHERE client_uuid = CAST('$cu' AS uuid)")

    @Test
    fun aPartlyRedeemedExpiredCreditLapsesOnlyForWhatIsLeftAndOnlyOnce() {
        val p = newProgramme(); val j = job()
        credit(p, 100, today.minusDays(10), today.minusDays(1))
        redemption(p, 60, today.minusDays(5))
        nowAt = today.minusDays(2)
        assertEquals(1 to 0, step(j, p)); assertEquals(40, balance(p))
        nowAt = today
        assertEquals(0 to 1, step(j, p), "the expiry lapses the 40 that are left"); assertEquals(0, balance(p))
        assertEquals("$p:$outlet:$today:1", world.rows("SELECT source_id FROM app.loyalty_ledger WHERE programme_id = $p AND source_type = 'expiry'").single()["source_id"])
        val n = rows(p)
        assertEquals(0 to 0, step(j, p)); nowAt = today.plusDays(3); assertEquals(0 to 0, step(j, p)); assertEquals(n, rows(p))
    }

    @Test
    fun everyStoredRedemptionIsPostedOnceNoFlagAndOverdrawIsTheNegativeBalance() {
        val p = newProgramme(); val j = job(); nowAt = today
        credit(p, 50, today.minusDays(3), null)
        redemption(p, 30, today.minusDays(2)); redemption(p, 40, today.minusDays(1))
        assertEquals(2 to 0, step(j, p)); assertEquals(0 to 0, step(j, p), "replay: a retry is a no-op")
        assertEquals(-20, balance(p), "kept: -20, not dropped")
        assertEquals(0, world.one("SELECT count(*) FROM app.loyalty_ledger WHERE programme_id = $p AND flags <> '{}'"), "no per-row flag: overdraw is derived from the balance")
    }

    @Test
    fun aVoidedRedemptionGetsOneReversalAndAnOldOneIsFoundByTheDailySweep() {
        val p = newProgramme(); val j = job(); nowAt = today
        credit(p, 100, today.minusDays(3), null); val cu = redemption(p, 60, today.minusDays(2))
        step(j, p); assertEquals(40, balance(p))
        void(cu); assertEquals(1 to 0, step(j, p)); assertEquals(100, balance(p)); assertEquals(0 to 0, step(j, p))
        val old = redemption(p, 10, today.minusDays(1)); step(j, p); assertEquals(90, balance(p))
        world.exec("SELECT setval(pg_get_serial_sequence('app.redemption', 'id'), nextval(pg_get_serial_sequence('app.redemption', 'id')) + 2000)")
        redemption(p, 1, today); step(j, p); assertEquals(89, balance(p))
        void(old); nowAt = today.plusDays(1)
        assertEquals(1 to 0, step(j, p), "the first pass of the next Dhaka day sweeps everything: the old void is reversed"); assertEquals(99, balance(p))
    }

    @Test
    fun aLateDebitDatedBeforeTheExpiryGivesBackAndASecondCorrectionOfTheSameDayIsPossible() {
        val p = newProgramme(); val j = job()
        credit(p, 100, today.minusDays(5), today.minusDays(1))
        nowAt = today; assertEquals(0 to 1, step(j, p)); assertEquals(0, balance(p))
        redemption(p, 30, today.minusDays(2))   // sold on a valid day, synced late
        assertEquals(1 to 1, step(j, p), "the debit is posted and the lapse is corrected the SAME day (+30)")
        assertEquals(0, balance(p)); assertEquals(1, positiveExpiry(p)); assertEquals(0 to 0, step(j, p))
        assertEquals(setOf("$p:$outlet:$today:1", "$p:$outlet:$today:2"), world.rows("SELECT source_id FROM app.loyalty_ledger WHERE programme_id = $p AND source_type = 'expiry'").map { it["source_id"] }.toSet())
    }

    @Test
    fun aDebitNeverConsumesAPointThatHadLapsedOnItsDate() {
        val p = newProgramme(); val j = job()
        credit(p, 10, today.minusDays(10), today.minusDays(6)); credit(p, 10, today.minusDays(8), null)
        redemption(p, 15, today.minusDays(3))   // 10 are lapsed on that date: only the 10 valid ones fund it
        nowAt = today; step(j, p)
        assertEquals(-5, balance(p), "overdrawn by 5 (balance < 0), the lapsed credit is not handed back"); assertEquals(-10, expirySum(p)); assertEquals(0 to 0, step(j, p))
    }

    @Test
    fun anAdjustmentIsIdempotentBySourceIdAndFlagsAreQuoted() {
        val p = newProgramme()
        world.fresh.db.jdbi.useHandle<Exception> { h ->
            assertTrue(LoyaltyLedger.postAdjustment(h, outlet, p, 25, "adj-1", today, null, listOf("a b", "c\"d,e"))); assertTrue(!LoyaltyLedger.postAdjustment(h, outlet, p, 25, "adj-1", today))
        }
        assertEquals(25, balance(p)); assertEquals("{\"a b\",\"c\\\"d,e\"}", world.rows("SELECT flags::text AS f FROM app.loyalty_ledger WHERE programme_id = $p").single()["f"])
    }

    @Test
    fun onePairFailingDoesNotRollBackTheOthers() {
        val p1 = newProgramme(); val p2 = newProgramme(); val j = job(); nowAt = today
        credit(p1, 10, today.minusDays(3), null); credit(p2, 10, today.minusDays(3), null)
        redemption(p1, 5, today.minusDays(1)); redemption(p2, 5, today.minusDays(1))
        world.exec("CREATE OR REPLACE FUNCTION app.t_boom() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN IF NEW.programme_id = $p1 THEN RAISE EXCEPTION 'boom'; END IF; RETURN NEW; END \$\$")
        world.exec("CREATE TRIGGER t_boom BEFORE INSERT ON app.loyalty_ledger FOR EACH ROW WHEN (NEW.source_type = 'redemption') EXECUTE FUNCTION app.t_boom()")
        try { assertEquals(1 to 0, step(j, p1, p2)) } finally { world.exec("DROP TRIGGER t_boom ON app.loyalty_ledger") }
        assertEquals(5, balance(p2)); assertEquals(10, balance(p1), "the failed pair rolled back alone")
        assertEquals(1 to 0, step(j, p1, p2), "the cursor did not advance: the failed pair is retried and now posts")
        assertEquals(5, balance(p1))
    }

    private class Ev(val programme: Int, val pts: Int, val date: LocalDate, val exp: LocalDate?, val debit: Boolean) { var voided = false; var delivered = false; var cu = "" }

    /** What the model saw, for the case-coverage counts. */
    private class Seen { var overdraw = 0; var debtThenCredit = 0; var debtSettledThenLapse = 0 }

    /**
     * The independent day-by-day model of one programme: credits enter on their day (before that day's debits) and FIRST pay off the debt (what no valid credit funded), a debit takes the
     * valid credit that expires first and what it cannot fund is debt; voided debits do not exist. Returns (balance, lapsed L, outstanding debt).
     */
    private fun model(evs: List<Ev>, today: LocalDate, from: LocalDate, seen: Seen? = null): Triple<Long, Long, Long> {
        class C(var left: Long, val exp: LocalDate?)
        val cs = ArrayList<C>(); var debt = 0L; var everDebt = false
        var d = from
        while (!d.isAfter(today)) {
            evs.filter { it.delivered && !it.debit && it.date == d }.forEach {
                var amount = it.pts.toLong()
                if (debt > 0) { val pay = minOf(debt, amount); debt -= pay; amount -= pay; seen?.let { s -> s.debtThenCredit++ } }
                if (amount > 0) cs += C(amount, it.exp)
            }
            evs.filter { it.delivered && it.debit && !it.voided && it.date == d }.forEach { e ->
                var need = e.pts.toLong()
                for (c in cs.filter { it.left > 0 && (it.exp == null || !it.exp.isBefore(d)) }.sortedBy { it.exp ?: LocalDate.MAX }) { val u = minOf(c.left, need); c.left -= u; need -= u }
                debt += need; if (need > 0) everDebt = true
            }
            d = d.plusDays(1)
        }
        val lapsed = cs.filter { it.exp != null && it.exp.isBefore(today) }.sumOf { it.left }
        if (seen != null) { if (debt > 0) seen.overdraw++; if (everDebt && lapsed > 0) seen.debtSettledThenLapse++ }
        return Triple(cs.sumOf { it.left } - lapsed - debt, lapsed, debt)
    }

    /**
     * Days pass with gaps; several runs a day; credits, redemptions (late-synced, later-dated ones synced first), voids and two programmes arrive in random order. After EVERY step:
     * balance = the model's, the expiry rows sum to -L, a positive expiry row exists only when the model's L went DOWN, a repeated run posts nothing.
     */
    @Test
    fun propertyAgainstAnIndependentDayByDayModel() {
        val hit = java.util.TreeMap<String, Int>()
        fun count(k: String) { hit.merge(k, 1, Int::plus) }
        val seen = Seen()
        repeat(40) { trial ->
            val rnd = Random(7000L + trial)
            val progs = listOf(newProgramme(), newProgramme()); val j = job()
            val base = today.minusDays(60); val evs = ArrayList<Ev>(); val posted = HashSet<Ev>()
            var prevL = longArrayOf(0, 0); var positiveSeen = longArrayOf(0, 0)
            var day = 0
            while (day <= 30) {
                val date = base.plusDays(day.toLong())
                // events that OCCUR on this date (not yet delivered unless chosen below); debits are sometimes large so that a credit that comes LATER meets an unfunded debit
                repeat(rnd.nextInt(0, 3)) { val pr = rnd.nextInt(2); evs += Ev(pr, rnd.nextInt(1, 80), date, if (rnd.nextInt(8) == 0) null else date.plusDays(rnd.nextLong(1, 9)), false) }
                repeat(rnd.nextInt(0, 3)) { evs += Ev(rnd.nextInt(2), rnd.nextInt(1, if (rnd.nextInt(3) == 0) 140 else 60), date, null, true) }
                val runs = rnd.nextInt(1, 4); if (runs > 1) count("several runs a day")
                repeat(runs) {   // deliveries in between: random subset of the undelivered, any date (late and later-dated-first)
                    evs.filter { !it.delivered && rnd.nextInt(3) == 0 }.shuffled(rnd).forEach { e ->
                        if (e.date.isBefore(date)) count("late sync (business date before the run)")
                        if (evs.any { it.delivered && it.programme == e.programme && it.date.isAfter(e.date) }) count("earlier-dated delivered after a later-dated one")
                        e.delivered = true
                        if (e.debit) e.cu = redemption(progs[e.programme], e.pts, e.date) else credit(progs[e.programme], e.pts, e.date, e.exp)
                    }
                    evs.filter { it.debit && it.delivered && !it.voided && rnd.nextInt(12) == 0 }.firstOrNull()?.let { it.voided = true; void(it.cu); count(if (it in posted) "void after posting" else "void before posting") }
                    nowAt = date
                    step(j, *progs.toLongArray())
                    evs.filter { it.debit && it.delivered && !it.voided }.let(posted::addAll)
                    for (i in 0..1) {
                        val m = model(evs.filter { it.programme == i }, date, base)
                        assertEquals(m.first, balance(progs[i]), "trial $trial day $day prog $i: balance = model")
                        assertEquals(-m.second, expirySum(progs[i]), "trial $trial day $day prog $i: expiry rows sum to -L")
                        if (positiveExpiry(progs[i]) > positiveSeen[i]) { assertTrue(m.second < prevL[i], "trial $trial day $day prog $i: a positive expiry row only when the model's L went down"); positiveSeen[i] = positiveExpiry(progs[i]) }
                        prevL[i] = m.second
                    }
                    val n = progs.sumOf { rows(it) }; assertEquals(0 to 0, step(j, *progs.toLongArray()), "trial $trial day $day: a re-run posts nothing"); assertEquals(n, progs.sumOf { rows(it) })
                }
                day += if (rnd.nextInt(4) == 0) rnd.nextInt(2, 6) else 1   // gaps
            }
            for (i in 0..1) model(evs.filter { it.programme == i }, base.plusDays(30), base, seen)
        }
        hit["overdraw (outstanding debt at the end)"] = seen.overdraw; hit["debt settled by a later credit"] = seen.debtThenCredit; hit["debt settled, then a credit lapsed"] = seen.debtSettledThenLapse
        println("LoyaltyLedgerJobTest property cases hit over 40 trials: $hit")
        listOf("late sync (business date before the run)", "earlier-dated delivered after a later-dated one", "overdraw (outstanding debt at the end)", "debt settled by a later credit",
            "debt settled, then a credit lapsed", "void before posting", "void after posting", "several runs a day").forEach { assertTrue((hit[it] ?: 0) > 0, "the generator never produced: $it ($hit)") }
    }

    /** The lead's example (C-R2 finding 1): +100 expiring Jan 31, the run takes it, an unfunded -100 dated after, then +100 expiring Mar 31: the debt eats the credit, Apr 1 posts nothing more. */
    @Test
    fun aLaterCreditSettlesTheDebtFirstSoItIsNotHandedBackOrLapsedTwice() {
        val p = newProgramme(); val j = job(); val d0 = today.minusDays(80)   // "Jan 31"
        credit(p, 100, d0.minusDays(5), d0)
        nowAt = d0.plusDays(1); assertEquals(0 to 1, step(j, p)); assertEquals(0, balance(p))   // Feb 1: -100 expiry
        redemption(p, 100, d0.plusDays(3)); nowAt = d0.plusDays(3); assertEquals(1 to 0, step(j, p)); assertEquals(-100, balance(p), "unfunded: the debt")
        credit(p, 100, d0.plusDays(10), d0.plusDays(60))   // Feb 10, expiring "Mar 31"
        nowAt = d0.plusDays(10); assertEquals(0 to 0, step(j, p)); assertEquals(0, balance(p), "the new credit paid the debt")
        nowAt = d0.plusDays(61)   // "Apr 1"
        assertEquals(0 to 0, step(j, p), "L stays 100 (the first credit): nothing more lapses"); assertEquals(0, balance(p), "was -100 before the fix")
        val b = world.fresh.db.jdbi.withHandle<LoyaltyLedger.Replayed, Exception> { h ->
            LoyaltyLedger.replay(h.createQuery("SELECT points, expires_on, source_type, business_date, source_id, created_at, id FROM app.loyalty_ledger WHERE programme_id = $p AND outlet_id = $outlet")
                .map { rs, _ -> LoyaltyLedger.Row(rs.getInt(1), rs.getObject(2, LocalDate::class.java), rs.getString(3), rs.getObject(4, LocalDate::class.java), rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getLong(7)) }.list(), today)
        }
        assertEquals(0, b.unfunded, "no debt is left"); assertEquals(100, b.lapsed)
    }

    @Test
    fun aBalanceHasNoDateCutABackdatedDebitWithATodayCorrectionIsNotAFalseOverdraw() {
        val p = newProgramme(); val j = job(); nowAt = today
        credit(p, 100, today.minusDays(20), today.minusDays(3)); redemption(p, 30, today.minusDays(4))
        nowAt = today.minusDays(3); step(j, p); nowAt = today.minusDays(2); step(j, p)   // the credit lapsed on day -3: 100 - 30 = 70 taken
        nowAt = today; redemption(p, 30, today.minusDays(3))   // synced late, dated on the last valid day: the correction is dated TODAY
        step(j, p)
        assertEquals(0, balance(p)); assertEquals(0L, bundleBalance(p).first, "the old read (rows dated up to yesterday) showed -30 here")
    }

    @Test
    fun anAcknowledgedRedemptionIsInTheFigureAtOnceAndPostingItChangesNothing() {
        val p = newProgramme(); val j = job(); nowAt = today
        credit(p, 100, today.minusDays(3), today.plusDays(9))
        val cu = redemption(p, 40, today.minusDays(1))
        assertEquals(100L, balance(p)); assertEquals(60L to 60L, bundleBalance(p), "stored, not yet posted: subtracted already (60), expiring follows the replay")
        step(j, p); assertEquals(60L, balance(p)); assertEquals(60L to 60L, bundleBalance(p), "after the job posted it the figure does not change")
        void(cu); assertEquals(100L to 100L, bundleBalance(p), "a voided one, posted but not yet reversed, is added back at once")
        step(j, p); assertEquals(100L, balance(p)); assertEquals(100L to 100L, bundleBalance(p))
    }

    /** (balance_points, expiring_points) of this outlet and programme as the bundle and the live read compute them. */
    private fun bundleBalance(programme: Long): Pair<Long, Long> {
        world.exec("INSERT INTO app.programme_enrolment (programme_id, outlet_id, tier_code, valid_from) SELECT $programme, $outlet, 'silver', DATE '${today.minusDays(30)}' WHERE NOT EXISTS (SELECT 1 FROM app.programme_enrolment WHERE programme_id = $programme AND outlet_id = $outlet)")
        val item = world.fresh.db.jdbi.withHandle<kotlinx.serialization.json.JsonObject, Exception> { h -> com.aktcl.aron.backend.sync.BundleProgrammes.loyalty(h, listOf(outlet), listOf(programme), today).items.single() }
        return item["balance_points"]!!.toString().toLong() to item["expiring_points"]!!.toString().toLong()
    }

    /** Pure replay cases: debt then credits, in and out of order. */
    @Test
    fun theReplayCarriesUnfundedDebitsAsDebtAndEveryLaterCreditSettlesItFirst() {
        fun row(pts: Int, exp: LocalDate?, bd: String, type: String = "adjustment", sid: String = "s$pts$bd") = LoyaltyLedger.Row(pts, exp, type, LocalDate.parse(bd), sid)
        val rows = listOf(row(100, LocalDate.parse("2026-01-31"), "2026-01-01"), row(-100, null, "2026-02-03", "redemption"), row(100, LocalDate.parse("2026-03-31"), "2026-02-10"))
        val r = LoyaltyLedger.replay(rows, LocalDate.parse("2026-04-01"))
        assertEquals(100L, r.lapsed); assertEquals(0L, r.unfunded)
        assertEquals(60L, LoyaltyLedger.replay(rows.take(2) + row(40, null, "2026-02-04"), LocalDate.parse("2026-04-01")).unfunded)   // 100 expired credit cannot fund; +40 pays 40 of the 100 debt: 60 left
    }
}
