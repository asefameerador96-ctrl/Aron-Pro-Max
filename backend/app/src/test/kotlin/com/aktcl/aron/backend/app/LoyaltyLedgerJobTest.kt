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
 * Push C: the worker's ledger writers ([LoyaltyLedger]: redemption posting, the FIFO expiry, adjustment plumbing) against the db seed. Money-grade: integer points, every
 * posting idempotent on (source_type, source_id), the balance (plain sum of every row) is the sum of the unexpired, unspent credits, and never below zero without an overdraw flag.
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

    /** The independent day-by-day model of one programme: credits enter on their day (before that day's debits), a debit takes the valid credit that expires first; voided debits do not exist. */
    private fun model(evs: List<Ev>, today: LocalDate, from: LocalDate): Triple<Long, Long, Long> {   // (balance, lapsed L, unfunded)
        class C(var left: Long, val exp: LocalDate?)
        val cs = ArrayList<C>(); var unfunded = 0L
        var d = from
        while (!d.isAfter(today)) {
            evs.filter { it.delivered && !it.debit && it.date == d }.forEach { cs += C(it.pts.toLong(), it.exp) }
            evs.filter { it.delivered && it.debit && !it.voided && it.date == d }.forEach { e ->
                var need = e.pts.toLong()
                for (c in cs.filter { it.left > 0 && (it.exp == null || !it.exp.isBefore(d)) }.sortedBy { it.exp ?: LocalDate.MAX }) { val u = minOf(c.left, need); c.left -= u; need -= u }
                unfunded += need
            }
            d = d.plusDays(1)
        }
        val lapsed = cs.filter { it.exp != null && it.exp.isBefore(today) }.sumOf { it.left }
        return Triple(cs.sumOf { it.left } - lapsed - unfunded, lapsed, unfunded)
    }

    /**
     * Days pass with gaps; several runs a day; credits, redemptions (late-synced, later-dated ones synced first), voids and two programmes arrive in random order. After EVERY step:
     * balance = the model's, the expiry rows sum to -L, a positive expiry row exists only when the model's L went DOWN, a repeated run posts nothing.
     */
    @Test
    fun propertyAgainstAnIndependentDayByDayModel() {
        repeat(30) { trial ->
            val rnd = Random(7000L + trial)
            val progs = listOf(newProgramme(), newProgramme()); val j = job()
            val base = today.minusDays(60); val evs = ArrayList<Ev>()
            var prevL = longArrayOf(0, 0); var positiveSeen = longArrayOf(0, 0)
            var day = 0
            while (day <= 30) {
                val date = base.plusDays(day.toLong())
                // events that OCCUR on this date (not yet delivered unless chosen below)
                repeat(rnd.nextInt(0, 3)) { val pr = rnd.nextInt(2); evs += Ev(pr, rnd.nextInt(1, 80), date, if (rnd.nextInt(8) == 0) null else date.plusDays(rnd.nextLong(1, 9)), false) }
                repeat(rnd.nextInt(0, 3)) { evs += Ev(rnd.nextInt(2), rnd.nextInt(1, 60), date, null, true) }
                repeat(rnd.nextInt(1, 4)) {   // several runs a day, deliveries in between: random subset of the undelivered, any date (late and later-dated-first)
                    evs.filter { !it.delivered && rnd.nextInt(3) == 0 }.shuffled(rnd).forEach { e ->
                        e.delivered = true
                        if (e.debit) e.cu = redemption(progs[e.programme], e.pts, e.date) else credit(progs[e.programme], e.pts, e.date, e.exp)
                    }
                    evs.filter { it.debit && it.delivered && !it.voided && rnd.nextInt(12) == 0 }.firstOrNull()?.let { it.voided = true; void(it.cu) }
                    nowAt = date
                    step(j, *progs.toLongArray())
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
        }
    }
}
