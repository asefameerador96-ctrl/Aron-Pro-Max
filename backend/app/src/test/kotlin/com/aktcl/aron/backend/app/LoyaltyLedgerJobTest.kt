package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.sync.LoyaltyLedger
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

    private fun run(programme: Long, date: LocalDate): Pair<Int, Int> = world.fresh.db.jdbi.inTransaction<Pair<Int, Int>, Exception> { h -> LoyaltyLedger.postRedemptions(h) to LoyaltyLedger.expire(h, date) }

    private fun balance(programme: Long) = world.one("SELECT COALESCE(SUM(points), 0) FROM app.loyalty_ledger WHERE outlet_id = $outlet AND programme_id = $programme")
    private fun rows(programme: Long) = world.one("SELECT count(*) FROM app.loyalty_ledger WHERE outlet_id = $outlet AND programme_id = $programme")

    @Test
    fun aPartlyRedeemedExpiredCreditLapsesOnlyForWhatIsLeftAndOnlyOnce() {
        val p = newProgramme()
        credit(p, 100, today.minusDays(10), today.minusDays(1))
        redemption(p, 60, today.minusDays(5))
        assertEquals(1 to 0, run(p, today.minusDays(2)), "the redemption is posted; nothing has expired on that date")
        assertEquals(40, balance(p))
        assertEquals(0 to 1, run(p, today), "the nightly expiry lapses the 40 that are left of the credit")
        assertEquals(0, balance(p))
        assertEquals("$p:$outlet:$today", world.rows("SELECT source_id FROM app.loyalty_ledger WHERE programme_id = $p AND source_type = 'expiry'").single()["source_id"])
        val n = rows(p)
        assertEquals(0 to 0, run(p, today), "a re-run for the same date posts nothing"); assertEquals(0 to 0, run(p, today.plusDays(3)), "nor does a later run: nothing is left to lapse")
        assertEquals(n, rows(p))
    }

    @Test
    fun aRedemptionIsPostedOnceKeptWhenItOverdrawsAndFlagged() {
        val p = newProgramme()
        credit(p, 50, today.minusDays(3), null)
        val ok = redemption(p, 30, today.minusDays(2)); val over = redemption(p, 40, today.minusDays(1))
        assertEquals(2 to 0, run(p, today)); assertEquals(0 to 0, run(p, today), "replay: the unique (source_type, source_id) makes a retry a no-op")
        assertEquals(-20, balance(p), "the second phone's redemption is kept: -20, not dropped")
        val flags = world.rows("SELECT source_id, flags::text AS f FROM app.loyalty_ledger WHERE programme_id = $p AND source_type = 'redemption'").associate { it["source_id"] to it["f"] }
        assertEquals("{}", flags[ok]); assertEquals("{overdraw}", flags[over], "negative only with the overdraw flag")
    }

    @Test
    fun anAdjustmentIsIdempotentBySourceId() {
        val p = newProgramme()
        world.fresh.db.jdbi.useHandle<Exception> { h ->
            assertTrue(LoyaltyLedger.postAdjustment(h, outlet, p, 25, "adj-1", today)); assertTrue(!LoyaltyLedger.postAdjustment(h, outlet, p, 25, "adj-1", today))
        }
        assertEquals(25, balance(p))
    }

    /**
     * Any interleaving of credits, redemptions (some synced late, after an expiry run has already lapsed the credit they belong to) and expiry runs gives balance = the sum of the
     * unexpired, unspent credits of an independent day-by-day model, never below zero (no overdraw is generated here), and a repeated run changes nothing.
     */
    @Test
    fun propertyAnyInterleavingOfCreditsRedemptionsAndExpiryRunsKeepsTheBalanceExact() {
        class Credit(var left: Int, val exp: LocalDate?)
        repeat(25) { trial ->
            val rnd = Random(1000L + trial)
            val p = newProgramme()
            val model = ArrayList<Credit>()
            val pending = ArrayList<Triple<Int, LocalDate, Int>>()   // (points, occurrence day, post on this day index)
            val base = today.minusDays(40)
            for (d in 0..13) {
                val day = base.plusDays(d.toLong())
                repeat(rnd.nextInt(0, 3)) {
                    val pts = rnd.nextInt(1, 101); val exp = if (rnd.nextInt(10) == 0) null else day.plusDays(rnd.nextLong(1, 7))
                    credit(p, pts, day, exp); model += Credit(pts, exp)
                }
                // redemptions of the day: the phone spends the oldest points that had not expired on that day (the model is time-aware, the server only sees rows)
                repeat(rnd.nextInt(0, 3)) {
                    val avail = model.filter { it.exp == null || it.exp >= day }.sumOf { it.left }
                    if (avail > 0) {
                        val pts = rnd.nextInt(1, avail + 1); var need = pts
                        for (c in model) { if (need == 0) break; if (c.exp == null || c.exp >= day) { val u = minOf(c.left, need); c.left -= u; need -= u } }
                        if (rnd.nextBoolean()) redemption(p, pts, day) else pending += Triple(pts, day, d + rnd.nextInt(1, 5))
                    }
                }
                pending.filter { it.third <= d }.also { pending.removeAll(it.toSet()) }.forEach { redemption(p, it.first, it.second) }
                run(p, day)
                val after = rows(p); assertEquals(0 to 0, run(p, day), "trial $trial day $d: a second run for the same date posts nothing"); assertEquals(after, rows(p))
                assertTrue(balance(p) >= 0, "trial $trial day $d: never below zero without an overdraw flag")
            }
            // flush the late redemptions, then run at the end: the give-back path (a late debit of a credit that was already lapsed) must leave the balance exact
            val end = base.plusDays(14)
            pending.forEach { redemption(p, it.first, it.second) }; pending.clear()
            run(p, end)
            val expected = model.filter { it.exp == null || it.exp >= end }.sumOf { it.left }
            assertEquals(expected.toLong(), balance(p), "trial $trial: balance = sum of the unexpired, unspent credits")
            assertEquals(0L, world.one("SELECT count(*) FROM app.loyalty_ledger WHERE programme_id = $p AND 'overdraw' = ANY(flags)"), "trial $trial: no overdraw was generated, so none is flagged: " + world.rows("SELECT id, source_type, points, business_date::text AS bd, expires_on::text AS ex, flags::text AS f FROM app.loyalty_ledger WHERE programme_id = $p ORDER BY business_date, id"))
            run(p, end.plusDays(30)); assertEquals(model.filter { it.exp == null }.sumOf { it.left }.toLong(), balance(p), "trial $trial: a month later only the credits that never expire remain")
        }
    }
}
