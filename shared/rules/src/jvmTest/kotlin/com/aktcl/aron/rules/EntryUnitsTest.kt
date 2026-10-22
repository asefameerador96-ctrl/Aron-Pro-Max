package com.aktcl.aron.rules

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** D-UNITS-02 (docs/34 s4.2): entry unit, packet price and the oracle agree for every seeded SKU. */
class EntryUnitsTest {
    private data class Row(val category: String, val code: String, val packSize: Int, val priceMtk: Long)

    private fun seed(): List<Row> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "db/seed/sku_catalog.csv").exists()) dir = dir.parentFile
        val lines = File(checkNotNull(dir) { "seed not found" }, "db/seed/sku_catalog.csv").readLines()
        val head = lines.first().split(',')
        val cat = head.indexOf("category"); val code = head.indexOf("sku_code"); val pack = head.indexOf("pack_size"); val price = head.indexOf("price_outlet")
        // the catalogue has no quoted commas in these columns; a fully quoted row would fail the size check below
        return lines.drop(1).filter { it.isNotBlank() }.map { l ->
            val f = l.split(',')
            Row(f[cat].lowercase(), f[code], f[pack].toInt(), java.math.BigDecimal(f[price]).movePointRight(3).toBigIntegerExact().toLong())
        }
    }

    @Test fun cigarettesCountInPacketsOfTheirOwnSize() {
        val s10 = EntryUnits.resolve("stick", 10, "pack"); val s20 = EntryUnits.resolve("stick", 20, "pack")
        assertEquals(EntrySpec(QtyUnit.PACK, 10), s10); assertEquals(EntrySpec(QtyUnit.PACK, 20), s20)
        assertEquals(30L, s10.toBase(3)); assertEquals(60L, s20.toBase(3))
    }

    @Test fun lighterBoxAndMatchDozen() {
        val box = EntryUnits.resolve("piece", 25, "pack"); val dozen = EntryUnits.resolve("dozen", 1, "dozen")
        assertEquals(50L, box.toBase(2)); assertEquals(7L, dozen.toBase(7))
        assertEquals("box", EntryUnits.labelKey("lighter", box.unit)); assertEquals("dozen", EntryUnits.labelKey("match", dozen.unit))
        assertEquals("packet", EntryUnits.labelKey("cigarette", QtyUnit.PACK)); assertEquals("packet", EntryUnits.labelKey("bidi", QtyUnit.PACK))
    }

    @Test fun legacyBaseUnitDefaultNeverLeavesLooseSticks() {
        // a bundle that still says entry_unit_default = stick (pre-migration seed) must not make the SR type sticks
        assertEquals(EntrySpec(QtyUnit.PACK, 20), EntryUnits.resolve("stick", 20, "stick"))
        assertEquals(EntrySpec(QtyUnit.STICK, 1), EntryUnits.resolve("stick", 20, "stick", allowLoose = true))
        assertEquals(EntrySpec(QtyUnit.PIECE, 1), EntryUnits.resolve("piece", 1, "piece"))
        assertEquals(EntrySpec(QtyUnit.DOZEN, 1), EntryUnits.resolve("dozen", 1, "dozen"))
    }

    @Test fun packetPriceIsExactOrAbsent() {
        assertEquals(158_700L, EntryUnits.entryPriceMtk(EntrySpec(QtyUnit.PACK, 20), 7_935))
        assertEquals(null, EntryUnits.entryPriceMtk(EntrySpec(QtyUnit.PACK, 10), 1_001, 4))
    }

    @Test fun everySeededSkuPacketPriceTimesQtyEqualsTheOracle() {
        val rows = seed(); assertTrue(rows.size >= 40)
        for (r in rows) {
            val unit = when (r.category) { "match" -> "dozen"; "cigarette", "bidi" -> "stick"; else -> "piece" }
            val spec = EntryUnits.resolve(unit, if (r.category == "match") 1 else r.packSize, if (r.category == "match") "dozen" else "pack")
            val packet = assertNotNull(EntryUnits.entryPriceMtk(spec, r.priceMtk), r.code)
            for (q in listOf(1L, 2L, 7L, 13L, 999L)) {
                val line = MemoLine.priced(1, spec.toBase(q), r.priceMtk)
                assertEquals(q * packet, line.grossMtk, "${r.code} x$q"); assertEquals(spec.toBase(q), q * spec.factor)
            }
        }
    }
}
