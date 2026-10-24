package com.aktcl.aron.sr

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.PriceEntity
import com.aktcl.aron.core.database.entity.SkuEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.printing.doc.DocumentBuilder
import com.aktcl.aron.core.printing.template.DigitStyle
import com.aktcl.aron.feature.memo.domain.PrintMapping
import com.aktcl.aron.feature.memo.domain.PrintNames
import com.aktcl.aron.feature.memo.ui.RoomMemoStore
import com.aktcl.aron.feature.sale.domain.SaleCommitter
import com.aktcl.aron.feature.sale.domain.SaleDraft
import com.aktcl.aron.feature.sale.domain.SaleDraftOps
import com.aktcl.aron.feature.sale.domain.SaleReviewCalculator
import com.aktcl.aron.feature.stock.StockTracker
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * D-UNITS-02 acceptance (docs/34 s4.2), offline, through the production path: bundle rows in Room, the production catalog read,
 * the sale committer, the stored rows read back by the production memo store, the print mapping and the paper document builder.
 * For every SKU of the seed catalogue (10s and 20s of the same brand included) printed total = stored total = an independent
 * oracle (packet price x packets, BigDecimal, half-up to the paisa), the stored `qty_base` is `qty_entered x pack_factor`, and the
 * server-side aggregate (stock tracker, in base units) is the derived base quantity. Two bundles: the seed as it stands today
 * (lighter pack 1, entry default = base unit) and the bundle after the D-UNITS-02 seed update (lighter box of 25, entry `pack`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class UnitsAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val date = "2026-10-05"
    private val meta = CaptureMeta(date, "2026-10-05T04:31:07.120Z", 1, 1, 0, true, 10231, null, "b", false, 1)

    private data class Row(val category: String, val code: String, val shortName: String, val packSize: Int, val outletMtk: Long)

    private fun seed(): List<Row> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "db/seed/sku_catalog.csv").exists()) dir = dir.parentFile
        val lines = File(checkNotNull(dir) { "seed catalogue not found" }, "db/seed/sku_catalog.csv").readLines()
        val h = lines.first().split(',')
        val i = { n: String -> h.indexOf(n) }
        return lines.drop(1).filter { it.isNotBlank() }.map { l ->
            val f = l.split(',')
            Row(f[i("category")].lowercase(), f[i("sku_code")].trim().replace(' ', '_'), f[i("short_name")].trim(), f[i("pack_size")].toInt(), BigDecimal(f[i("price_outlet")]).movePointRight(3).toBigIntegerExact().toLong())
        }
    }

    private fun skuEntities(rows: List<Row>, afterUpdate: Boolean) = rows.mapIndexed { n, r ->
        val base = when (r.category) { "match" -> "dozen"; "lighter" -> "piece"; else -> "stick" }
        val perPack = when { r.category == "match" -> 1; r.category == "lighter" && afterUpdate -> 25; else -> r.packSize }
        val entry = if (afterUpdate) (if (r.category == "match") "dozen" else "pack") else base
        SkuEntity(1L + n, r.code, 1L + n, r.category, r.shortName, r.shortName, null, base, perPack, entry, null, "1.000", n, "active", 1)
    }

    private fun db() = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()

    private suspend fun runBundle(afterUpdate: Boolean) {
        val db = db()
        try {
            val rows = seed()
            val skus = skuEntities(rows, afterUpdate)
            db.referenceDao().insertSkus(skus)
            db.referenceDao().insertPrices(rows.mapIndexed { n, r -> PriceEntity(1L + n, 1L + n, "outlet", r.outletMtk, 1, "2026-01-01", null) })
            val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
            var seq = 0
            val committer = SaleCommitter(repo, { _, uuid -> "sr334001-261005-%03d".format(++seq) }, { _, route -> meta.copy(routeId = route) }, { "2026-10-05T04:35:00.000Z" }, findMemo = { db.captureDao().memo(it) })
            val catalog = RoomSaleReads(db, ReferenceRepository(db)).catalog(date, "outlet").associateBy { it.skuId }
            // D-SALE-ZERO-PRICE-01: the zero-price rows of the real catalogue are not offered on Sale, every other row is
            assertEquals(rows.count { it.outletMtk > 0 }, catalog.size)
            assertTrue(catalog.values.all { it.unitPriceMtk > 0 })

            val words = mapOf("packet" to "packet", "box" to "box", "dozen" to "dozen", "stick" to "stick", "piece" to "piece")
            val names = PrintNames(sku = { catalog.getValue(it).name }, outlet = { "Outlet" }, sr = "SR", route = "Route", categoryCode = { catalog.getValue(it).categoryCode })
            val store = RoomMemoStore(db)
            var soldBase = HashMap<Long, Long>()

            suspend fun sellAndCheck(picks: List<Pair<Long, Long>>) {
                val visit = ClientIds.newUuid(); val fixId = ClientIds.newUuid()
                val fix = GeoFixEntity(fixId, visit, "visit_open", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
                repo.recordVisitOpen(VisitEntity(visit, meta, "sr_call", 1, "2026-10-05T04:31:07.120Z", 1, true, null, fixId, "in_range", 18.4, 100, 50, "master", 23.7, 90.4, "sale_allowed"), fix)
                var d: SaleDraft = SaleDraft(visitUuid = visit, outletId = 1, routeId = 10231, businessDate = date, memoUuid = ClientIds.newUuid())
                picks.forEach { (id, q) -> d = SaleDraftOps.setQuantity(d, id, q, catalog.getValue(id).entry.unit) }
                val review = SaleReviewCalculator.review(d, catalog)
                assertTrue(review.problems.toString(), review.canCommit)
                val out = committer.commit(d, catalog)
                val m = store.memos(date).first { it.memoUuid == out.memoUuid }

                // independent oracle: price per entered unit x quantity, summed, rounded once half-up to the paisa
                var gross = BigDecimal.ZERO
                picks.forEach { (id, q) ->
                    val sku = catalog.getValue(id)
                    gross += BigDecimal(sku.unitPriceMtk).multiply(BigDecimal(sku.entry.factor)).multiply(BigDecimal(q))
                    soldBase.merge(id, q * sku.entry.factor, Long::plus)
                }
                val netOracle = gross.divide(BigDecimal(10), 0, RoundingMode.HALF_UP).multiply(BigDecimal(10)).toLong()
                assertEquals("stored gross", gross.toLong(), m.grossMtk); assertEquals("stored net", netOracle, m.netMtk)

                val lines = db.captureDao().linesOf(out.memoUuid).sortedBy { it.skuId }
                for (l in lines) {
                    val sku = catalog.getValue(l.skuId)
                    assertEquals(sku.entry.unit.wire, l.unitEntered)
                    assertEquals("${sku.code} qty_base", l.qtyEntered * (if (l.unitEntered == "pack") l.packFactor else 1), l.qtyBase)
                    assertEquals(sku.entry.factor.toLong() * l.qtyEntered, l.qtyBase)
                    sku.entryPriceMtk?.let { assertEquals("${sku.code} packet price", it * l.qtyEntered, l.grossMtk) }
                }
                val print = PrintMapping.memo(m, names)
                for (digits in DigitStyle.entries) {
                    val doc = DocumentBuilder(digits) { words[it].orEmpty() }.memo(print)
                    val printedNet = doc.fields.getValue("grand_total").map { if (it in '০'..'৯') '0' + (it - '০') else it }.joinToString("").replace(",", "")
                    assertEquals("printed net", BigDecimal(netOracle).movePointLeft(3).setScale(2).toPlainString(), printedNet)
                    // every printed quantity is the entered quantity with its unit word
                    val printedQty = doc.tables.getValue("lines").map { it.getValue("qty") }
                    printedQty.forEachIndexed { n, text ->
                        val sku = catalog.getValue(lines[n].skuId)
                        val expect = lines[n].qtyEntered.toString() + " " + words.getValue(sku.entryLabelKey)
                        assertEquals(expect, text.map { if (it in '০'..'৯') '0' + (it - '০') else it }.joinToString(""))
                    }
                }
            }

            // each SKU on its own memo: 1, 2 and 7 entry units
            catalog.keys.sorted().forEach { id -> sellAndCheck(listOf(id to (1L + (id % 3) * 3L))) }
            // and every SKU together on one memo (at most 60 lines)
            sellAndCheck(catalog.keys.sorted().take(60).map { it to 2L })
            // the server-side aggregate is the derived base quantity
            val sold = StockTracker.of(db, date)
            soldBase.forEach { (id, base) -> assertEquals("sku $id aggregate", base, sold.getValue(id).sold) }
        } finally { db.close() }
    }

    @Test fun theSeedBundleAsItStandsCountsPacketsAndPrintsTheTotalsItStores() = runTest { runBundle(afterUpdate = false) }

    @Test fun theBundleAfterTheUnitsSeedUpdateCountsPacketsBoxesAndDozens() = runTest { runBundle(afterUpdate = true) }
}
