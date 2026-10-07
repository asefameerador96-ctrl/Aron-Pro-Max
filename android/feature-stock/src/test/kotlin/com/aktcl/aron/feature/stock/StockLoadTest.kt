package com.aktcl.aron.feature.stock

import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.SkuEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-SR-014. */
class StockLoadTest {
    private fun sku(id: Long, cat: String, unit: String = "piece", entry: String = "base", perPack: Int = 1, sort: Int = id.toInt()) = SkuEntity(
        skuId = id, code = "S$id", variantId = id, categoryCode = cat, name = "n$id", shortName = "s$id", nameBn = null, baseUnit = unit,
        basePerPack = perPack, entryUnitDefault = entry, reportUnit = null, reportFactor = "1.000", sort = sort, status = "active", version = 1,
    )

    private val meta = CaptureMeta("2026-10-07", "2026-10-07T04:00:00.000Z", 1, 1, 0, true, 10231, null, "2026-10-07:1", false, 5)
    private val skus = listOf(sku(1, "lighter"), sku(2, "lighter"), sku(3, "match", "dozen"), sku(4, "match", "dozen"))
    private var n = 0
    private fun load(loaded: Map<Long, Long> = mapOf(1L to 250L, 2L to 150L, 3L to 100L, 4L to 300L)) =
        StockLoad(skus, loaded, 120_000, { "00000000-0000-4000-8000-%012d".format(++n) })

    @Test fun categoryTotalsShowFourHundredAndFourHundredForTheSeededLoad() {
        val t = load().totals.associate { it.categoryCode to it.issuedBase }
        assertEquals(400L, t["lighter"]); assertEquals(400L, t["match"])
    }

    @Test fun saveEmitsOnlyTheEnteredIncrement() {
        val l = load()
        l.setEntered(1, 20); l.step(3, 1); l.step(3, 1)
        val out = l.save(0, meta) as SaveOutcome.Saved
        assertEquals(setOf(1L, 3L), out.movements.map { it.skuId }.toSet())
        assertEquals(listOf(20L, 2L), out.movements.map { it.qtyBase })
        assertTrue(out.movements.all { it.kind == "issue" })
        l.committed(out, 0)
        assertEquals(420L, l.totals.first { it.categoryCode == "lighter" }.issuedBase)
        assertEquals(402L, l.totals.first { it.categoryCode == "match" }.issuedBase)
        assertTrue(l.rows.all { it.entered == 0L })
    }

    @Test fun nothingEnteredIsRefused() {
        assertEquals(SaveOutcome.Refused(SaveRefusal.NOTHING_ENTERED), load().save(0, meta))
    }

    @Test fun sameValuesResaveWithinGuardIsRefusedThenAllowedAfterIt() {
        val l = load()
        l.setEntered(1, 5)
        l.committed(l.save(1_000, meta) as SaveOutcome.Saved, 1_000)
        l.setEntered(1, 5)
        assertEquals(SaveOutcome.Refused(SaveRefusal.SAME_VALUES_WITHIN_GUARD), l.save(60_000, meta))
        assertTrue(l.save(200_000, meta) is SaveOutcome.Saved)
        l.setEntered(1, 6) // different values are fine inside the window
        assertTrue(l.save(61_000, meta) is SaveOutcome.Saved)
    }

    @Test fun packEntryMultipliesByPackFactor() {
        val l = StockLoad(listOf(sku(9, "cigarette", "stick", "pack", 200)), emptyMap(), newUuid = { "00000000-0000-4000-8000-000000000001" })
        l.setEntered(9, 3)
        val m = (l.save(0, meta) as SaveOutcome.Saved).movements.single()
        assertEquals(600L, m.qtyBase); assertEquals("pack", m.unitEntered); assertEquals(200, m.packFactor); assertEquals(3L, m.qtyEntered)
    }

    @Test fun invalidEntriesAreRejected() {
        val l = load()
        assertTrue(runCatching { l.setEntered(1, -1) }.isFailure)
        assertTrue(runCatching { l.setEntered(99, 1) }.isFailure)
        assertTrue(runCatching { l.setEntered(1, 1_000_000) }.isFailure)
        l.step(1, -5); assertEquals(0L, l.rows.first { it.sku.skuId == 1L }.entered)
    }
}
