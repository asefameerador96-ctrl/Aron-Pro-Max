package com.aktcl.aron.feature.stock

import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

/** F-SR-050: current stock = issued + adjusted - qc returned - sold on live memos, after every sale, edit and relaunch. */
class StockTrackerTest {
    private val date = "2026-10-05"
    private fun meta() = CaptureMeta(date, "2026-10-05T04:31:07.120Z", 1, 1, 0, true, 10231, null, "b", false, 1)
    private fun mv(kind: String, sku: Long, qty: Long) = StockMovementEntity(UUID.randomUUID().toString(), meta(), kind, sku, qty, "stick", 1, qty, null, false)
    private fun memo(id: String, supersedes: String? = null) =
        MemoEntity(id, meta(), "v", 1, "no-$id", "sale", "2026-10-05T04:35:00.000Z", date, "outlet", 0, 0, 0, 0, 0, 0, 0, 0, true, null, 1, 0, 0, supersedesClientUuid = supersedes)
    private fun line(memo: String, sku: Long, qty: Long) =
        MemoLineEntity(UUID.randomUUID().toString(), meta(), memo, 1, sku, "sale", qty, "stick", 1, qty, "outlet", "2026-09-01", 100, 1, qty * 100)

    @Test fun balanceIsIssuedPlusAdjustmentMinusQcReturnMinusSold() {
        val b = StockTracker.balances(
            listOf(mv("issue", 1, 100), mv("adjustment", 1, -10), mv("qc_return", 1, 5)),
            listOf(memo("a")), listOf(line("a", 1, 30)),
        ).getValue(1)
        assertEquals(55L, b.current); assertEquals(55L, b.toReturn); assertEquals(100L, b.issued); assertEquals(30L, b.sold)
    }

    @Test fun anEditCountsOnceThroughItsReplacementNeverTwice() {
        val moves = listOf(mv("issue", 1, 100))
        assertEquals(70L, StockTracker.balances(moves, listOf(memo("a")), listOf(line("a", 1, 30))).getValue(1).current)
        val edited = StockTracker.balances(moves, listOf(memo("a"), memo("b", supersedes = "a")), listOf(line("a", 1, 30), line("b", 1, 20))).getValue(1)
        assertEquals(80L, edited.current)
        val twice = StockTracker.balances(moves, listOf(memo("a"), memo("b", "a"), memo("c", "b")), listOf(line("a", 1, 30), line("b", 1, 20), line("c", 1, 25))).getValue(1)
        assertEquals(75L, twice.current)
    }

    @Test fun overSellingGoesNegativeForTheWarningButReturnNeverDoes() {
        val b = StockTracker.balances(listOf(mv("issue", 1, 10)), listOf(memo("a")), listOf(line("a", 1, 12))).getValue(1)
        assertEquals(-2L, b.current); assertEquals(0L, b.toReturn)
    }

    @Test fun aSkuNeverLoadedHasNoStockRowButStillShowsWhatWasSold() {
        val m = StockTracker.balances(listOf(mv("issue", 1, 10)), listOf(memo("a")), listOf(line("a", 2, 4)))
        assertTrue(m.getValue(1).hasStockRow); assertFalse(m.getValue(2).hasStockRow); assertEquals(-4L, m.getValue(2).current)
    }

    @Test fun zeroSaleAndEmptyDayChangeNothing() {
        assertTrue(StockTracker.balances(emptyList(), emptyList(), emptyList()).isEmpty())
        assertEquals(10L, StockTracker.balances(listOf(mv("issue", 1, 10)), listOf(memo("z")), emptyList()).getValue(1).current)
    }

    /** After every step of a random day the tracker equals a plain running model; row order never matters (replay, relaunch). */
    @Test fun randomDayMatchesARunningModelAndIsOrderIndependent() {
        val rnd = Random(50)
        repeat(40) {
            val moves = mutableListOf<StockMovementEntity>(); val memos = mutableListOf<MemoEntity>(); val lines = mutableListOf<MemoLineEntity>()
            val live = mutableMapOf<String, Map<Long, Long>>()
            val stock = mutableMapOf(1L to 0L, 2L to 0L, 3L to 0L)
            var last: String? = null
            repeat(30) { step ->
                when (rnd.nextInt(5)) {
                    0 -> { val s = rnd.nextLong(1, 4); val q = rnd.nextLong(1, 200); moves += mv("issue", s, q); stock[s] = stock.getValue(s) + q }
                    1 -> { val s = rnd.nextLong(1, 4); val q = rnd.nextLong(-20, 20); moves += mv("adjustment", s, q); stock[s] = stock.getValue(s) + q }
                    2 -> { val s = rnd.nextLong(1, 4); val q = rnd.nextLong(1, 10); moves += mv("qc_return", s, q); stock[s] = stock.getValue(s) - q }
                    else -> {
                        val id = "m$step"; val edit = last != null && rnd.nextBoolean()
                        val sold = (1L..3L).associateWith { rnd.nextLong(0, 15) }.filterValues { it > 0 }
                        if (edit) live.remove(last)?.forEach { (s, q) -> stock[s] = stock.getValue(s) + q }
                        memos += memo(id, if (edit) last else null)
                        sold.forEach { (s, q) -> lines += line(id, s, q); stock[s] = stock.getValue(s) - q }
                        live[id] = sold; last = id
                    }
                }
                val got = StockTracker.balances(moves, memos, lines)
                stock.forEach { (s, v) -> assertEquals("sku $s step $step", v, got[s]?.current ?: 0L) }
                val shuffled = StockTracker.balances(moves.shuffled(rnd), memos.shuffled(rnd), lines.shuffled(rnd))
                assertEquals(got.mapValues { it.value.current }, shuffled.mapValues { it.value.current })
            }
        }
    }
}
