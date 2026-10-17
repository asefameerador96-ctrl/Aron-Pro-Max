package com.aktcl.aron.feature.sale.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * QC entry rules (F-SR-027, money): six types in two groups, settlement = sum of typed quantities x price, never above the cap,
 * and the codes and groups are exactly what the server's closed list and the contract enum accept.
 */
class QcEntryModelTest {
    private fun sku(price: Long, perQty: Int = 1) = SaleSku(100, "MAXR10", "cigarette", "MaxR 10S", null, "stick", 10, price, perQty, "2026-09-01", stockBase = 400)
    private fun draft() = SaleDraft("v1", 50001, 10231, "2026-10-05", memoUuid = "m1")

    @Test fun sixTypesThreeInEachGroupAndGroupsAreTheContractEnum() {
        assertEquals(6, QcEntryModel.APP_TYPES.size)
        assertEquals(3, QcEntryModel.typesOf(QcEntryModel.PRODUCTION).size)
        assertEquals(3, QcEntryModel.typesOf(QcEntryModel.TRANSPORT).size)
        assertEquals(setOf("MFC", "MKT"), QcEntryModel.APP_TYPES.map { it.group }.toSet())
        assertEquals(QcEntryModel.APP_TYPES.size, QcEntryModel.APP_TYPES.map { it.code }.toSet().size)
        assertTrue(QcEntryModel.APP_TYPES.all { Regex("^[a-z][a-z0-9_]{1,40}$").matches(it.code) }) // QcLinePayload.fault_type_code
    }

    /** The six codes are the `applies_to` app rows of the closed list the server validates against (db V0027). */
    @Test fun codesMatchTheServerClosedList() {
        var dir: File? = File(System.getProperty("user.dir"))
        var file: File? = null
        while (dir != null && file == null) { file = File(dir, "db/migrations/V0027__system_code_lists.sql").takeIf { it.exists() }; dir = dir.parentFile }
        org.junit.Assume.assumeNotNull(file)
        val rows = Regex("""\('qc_fault_type', '([a-z_]+)',[^\n]*"group": "(MFC|MKT)", "applies_to": \[([^\]]*)\]""").findAll(file!!.readText()).toList()
        val app = rows.filter { "\"app\"" in it.groupValues[3] }.map { it.groupValues[1] to it.groupValues[2] }
        assertEquals(app.toSet(), QcEntryModel.APP_TYPES.map { it.code to it.group }.toSet())
    }

    @Test fun theManualFixtureTenSticksAtEightTakaIsEightyTaka() {
        val s = sku(8_000)
        val entries = listOf(QcEntry(100, "damaged_crushed", "MFC", 5), QcEntry(100, "expired_stock", "MKT", 5))
        assertEquals(80_000, QcEntryModel.doneMtk(s, entries)) // manual p37: 5 + 5 sticks, settlement 80.00
    }

    @Test fun settlementIsTheSumOfTypedQuantitiesTimesPrice_property() {
        val rnd = Random(20261017)
        repeat(500) {
            val price = rnd.nextLong(0, 5_000_000)
            val perQty = listOf(1, 1, 1, 10, 12).random(rnd)
            val s = sku(price, perQty)
            val entries = QcEntryModel.APP_TYPES.map { QcEntry(100, it.code, it.group, rnd.nextLong(0, 2_000)) }
            val expected = entries.sumOf { QcEntryModel.settlementMtk(s, it.defectQty) }
            assertEquals(expected, QcEntryModel.doneMtk(s, entries))
            // and through the Review: the memo's QC deduction is that same sum
            var d = SaleDraftOps.setQuantity(draft(), 100, 1, com.aktcl.aron.rules.QtyUnit.STICK)
            entries.forEach { d = SaleDraftOps.setQc(d, it) }
            val review = SaleReviewCalculator.review(d, mapOf(100L to s))
            assertEquals(expected, review.totals.qcDeductionMtk)
            assertEquals(expected, review.qc.sumOf { it.settlementMtk })
        }
    }

    @Test fun maxQtyKeepsEveryRowWithinTheCapAndIsTheLargest_property() {
        val rnd = Random(77)
        repeat(300) {
            val s = sku(rnd.nextLong(1, 100_000), listOf(1, 1, 10).random(rnd))
            val cap = rnd.nextLong(0, 5_000_000)
            // fill the rows greedily the way the stepper would: each row up to its own max at that moment
            var entries = emptyList<QcEntry>()
            for (t in QcEntryModel.APP_TYPES.shuffled(rnd)) {
                val max = QcEntryModel.maxQty(s, entries, t.code, cap)
                val q = rnd.nextLong(0, max + 1)
                entries = entries.filterNot { it.faultTypeCode == t.code } + QcEntry(100, t.code, t.group, q)
                assertTrue("done ${QcEntryModel.doneMtk(s, entries)} above cap $cap", QcEntryModel.doneMtk(s, entries) <= cap)
            }
            // maximal: one more on any row would pass the cap (unless the contract quantity limit stops it first)
            for (t in QcEntryModel.APP_TYPES) {
                val max = QcEntryModel.maxQty(s, entries, t.code, cap)
                val others = entries.filter { it.faultTypeCode != t.code }
                if (max < QcEntryModel.MAX_QTY) {
                    val raised = others + QcEntry(100, t.code, t.group, max + 1)
                    assertTrue(QcEntryModel.doneMtk(s, raised) > cap)
                }
            }
            val done = QcEntryModel.doneMtk(s, entries)
            assertEquals((cap - done).coerceAtLeast(0), QcEntryModel.remainingMtk(cap, done))
        }
    }

    @Test fun noCapMeansNoLimitBeyondTheContractQuantityAndNoRemaining() {
        assertEquals(QcEntryModel.MAX_QTY, QcEntryModel.maxQty(sku(8_000), emptyList(), "taste", null))
        assertNull(QcEntryModel.remainingMtk(null, 5))
    }

    @Test fun aRowWithNoRoomIsZeroEvenWhenOverCap() {
        val s = sku(8_000)
        assertEquals(0, QcEntryModel.maxQty(s, listOf(QcEntry(100, "taste", "MKT", 100)), "other_mfc", 10_000))
    }

    @Test fun draftRefusesUnknownTypeWrongGroupAndOverContractQuantity() {
        assertThrows(IllegalArgumentException::class.java) { SaleDraftOps.setQc(draft(), QcEntry(100, "production_fault", "MFC", 1)) }
        assertThrows(IllegalArgumentException::class.java) { SaleDraftOps.setQc(draft(), QcEntry(100, "taste", "MFC", 1)) } // right code, wrong group
        assertThrows(IllegalArgumentException::class.java) { SaleDraftOps.setQc(draft(), QcEntry(100, "taste", "TFC", 1)) } // the old wrong group value
        assertThrows(IllegalArgumentException::class.java) { SaleDraftOps.setQc(draft(), QcEntry(100, "taste", "MKT", QcEntryModel.MAX_QTY + 1)) }
        assertFalse(SaleDraftOps.setQc(draft(), QcEntry(100, "taste", "MKT", QcEntryModel.MAX_QTY)).qc.isEmpty())
    }

    @Test fun zeroRemovesTheRowAndSixRowsCoexist() {
        var d = draft()
        QcEntryModel.APP_TYPES.forEachIndexed { i, t -> d = SaleDraftOps.setQc(d, QcEntry(100, t.code, t.group, i + 1L)) }
        assertEquals(6, d.qc.size)
        d = SaleDraftOps.setQc(d, QcEntry(100, "taste", "MKT", 0))
        assertEquals(5, d.qc.size)
    }
}
