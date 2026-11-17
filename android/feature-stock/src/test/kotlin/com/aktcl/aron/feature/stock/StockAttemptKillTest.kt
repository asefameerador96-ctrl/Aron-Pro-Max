package com.aktcl.aron.feature.stock

import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.SkuEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Review of slice 1, low 8: what a process kill does to a Stock Save. A Save that committed and was killed before the screen noticed leaves its
 * movements in the database and its UUIDs in the attempt file; a Save of the same values after the relaunch posts the SAME UUIDs, which the
 * repository stores once, so a re-entry can never double an issue that landed.
 */
class StockAttemptKillTest {
    @get:Rule val tmp = TemporaryFolder()

    private val date = "2026-10-07"
    private val meta = CaptureMeta(date, "2026-10-07T04:00:00.000Z", 1, 1, 0, true, 10231, null, "2026-10-07:1", false, 5)
    private val sku = SkuEntity(
        skuId = 1, code = "S1", variantId = 1, categoryCode = "lighter", name = "n1", shortName = "s1", nameBn = null, baseUnit = "piece",
        basePerPack = 1, entryUnitDefault = "base", reportUnit = null, reportFactor = "1.000", sort = 1, status = "active", version = 1,
    )
    private var n = 0
    private fun load(loaded: Map<Long, Long>, store: StockAttemptStore) =
        StockLoad(listOf(sku), loaded, newUuid = { "00000000-0000-4000-8000-%012d".format(++n) }, attemptStore = store, businessDate = date)

    /** The repository's "stored once" rule, as `CaptureRepository.recordStock` applies it. */
    private class Db { val rows = LinkedHashMap<String, StockMovementEntity>()
        fun record(m: List<StockMovementEntity>) { m.forEach { rows.putIfAbsent(it.clientUuid, it) } }
        fun loaded() = rows.values.sumOf { it.qtyBase }
    }

    @Test fun aSaveOfTheSameValuesAfterAKillPostsTheSameUuidsAndAddsNothingTwice() = runBlocking {
        val store = FileStockAttemptStore(File(tmp.root, "attempt"))
        val db = Db()
        // run 1: the Save commits, then the process dies before committed() (no clear, no screen update)
        val first = load(emptyMap(), store).also { it.setEntered(1, 5) }
        val out = first.save(0, meta) as SaveOutcome.Saved
        db.record(out.movements)
        assertEquals(5L, db.loaded())
        // run 2: a fresh StockLoad after the relaunch reads the figure from the database (5), the rep enters the same 5 again and saves
        val second = load(mapOf(1L to db.loaded()), FileStockAttemptStore(File(tmp.root, "attempt"))).also { it.setEntered(1, 5) }
        val res = StockSaving.save(second, { 1L }, meta, storedAlready = { ids -> ids.filter { it in db.rows }.toSet() }) { db.record(it) }
        assertEquals("the earlier Save is recognised, not added again", StockMessage.ALREADY_SAVED, res.message)
        assertEquals(1, db.rows.size)
        assertEquals(5L, db.loaded())
        assertEquals("the loaded figure of the screen did not double", 5L, second.rows.single().loadedBase)
    }

    @Test fun withoutTheAttemptFileTheSameReentryWouldBeANewIssue() = runBlocking {
        val db = Db()
        val first = load(emptyMap(), NoStockAttemptStore).also { it.setEntered(1, 5) }
        db.record((first.save(0, meta) as SaveOutcome.Saved).movements) // killed before committed()
        val second = load(mapOf(1L to db.loaded()), NoStockAttemptStore).also { it.setEntered(1, 5) }
        db.record((second.save(0, meta) as SaveOutcome.Saved).movements)
        assertEquals("this is the double the attempt file prevents", 2, db.rows.size)
    }

    @Test fun aConfirmedCommitClearsTheAttemptSoALaterSaveIsANewIssue() = runBlocking {
        val store = FileStockAttemptStore(File(tmp.root, "attempt"))
        val db = Db()
        val l = load(emptyMap(), store).also { it.setEntered(1, 5) }
        val a = StockSaving.save(l, { 1L }, meta, storedAlready = { ids -> ids.filter { it in db.rows }.toSet() }) { db.record(it) }
        assertEquals(StockMessage.SAVED, a.message)
        assertTrue("the file is gone once the commit is confirmed", !File(tmp.root, "attempt").exists())
        l.setEntered(1, 5)
        val b = StockSaving.save(l, { 2L }, meta, storedAlready = { ids -> ids.filter { it in db.rows }.toSet() }) { db.record(it) }
        assertEquals(StockMessage.SAVED, b.message)
        assertEquals(2, db.rows.size); assertEquals(10L, db.loaded())
    }

    @Test fun anAttemptOfAnotherDayOrADamagedFileIsIgnored() {
        val f = File(tmp.root, "attempt")
        FileStockAttemptStore(f).save("2026-10-06", mapOf("1=5" to "00000000-0000-4000-8000-0000000000aa"))
        assertEquals(emptyMap<String, String>(), FileStockAttemptStore(f).load(date))
        f.writeText("2026-10-07\n1=5\tnot-a-uuid\n\u0000garbage")
        assertEquals(emptyMap<String, String>(), FileStockAttemptStore(f).load(date))
        val first = load(emptyMap(), FileStockAttemptStore(f)).also { it.setEntered(1, 5) }
        val u = (first.save(0, meta) as SaveOutcome.Saved).movements.single().clientUuid
        assertNotEquals("00000000-0000-4000-8000-0000000000aa", u)
    }

    // ---- round-2 review lows: the attempt file across Dhaka midnight, after a purge, and with a damaged line

    /** A StockLoad built before midnight saves after it: the movements carry the NEW date, so the file is labelled with it and the relaunched load finds it. */
    @Test fun aSaveAcrossMidnightLeavesAFileTheRelaunchedDayFinds() = runBlocking {
        val next = "2026-10-08"
        val metaNext = meta.copy(businessDate = next)
        val f = File(tmp.root, "attempt")
        val before = load(emptyMap(), FileStockAttemptStore(f)).also { it.setEntered(1, 5) } // built on 10-07 (businessDate = date)
        val out = before.save(0, metaNext) as SaveOutcome.Saved                              // saved after midnight: the movement says 10-08
        val db = Db(); db.record(out.movements)                                              // committed, then killed
        val after = StockLoad(listOf(sku), mapOf(1L to db.loaded()), newUuid = { "00000000-0000-4000-8000-%012d".format(++n) }, attemptStore = FileStockAttemptStore(f), businessDate = next)
            .also { it.setEntered(1, 5) }
        val res = StockSaving.save(after, { 1L }, metaNext, storedAlready = { ids -> ids.filter { it in db.rows }.toSet() }) { db.record(it) }
        assertEquals("the earlier Save is recognised on the new day", StockMessage.ALREADY_SAVED, res.message)
        assertEquals(1, db.rows.size)
    }

    /** The repository dropped the UUID (the purge forgot an acked copy): nothing was stored, so the loaded figure must not grow and the message is not "saved". */
    @Test fun aMovementTheRepositoryDidNotKeepAddsNothingToTheLoadedFigure() = runBlocking {
        val db = Db()
        val l = load(emptyMap(), NoStockAttemptStore).also { it.setEntered(1, 5) }
        val res = StockSaving.save(l, { 1L }, meta, storedAlready = { ids -> ids.filter { it in db.rows }.toSet() }) { /* purged: stores nothing */ }
        assertEquals(StockMessage.ALREADY_SAVED, res.message)
        assertEquals(0L, l.rows.single().loadedBase)
    }

    /** A damaged line that looks like 36 hex-and-dash characters but is not a UUID v4 is ignored, so Save never posts it. */
    @Test fun aLineThatIsNotAUuidV4IsIgnored() {
        val f = File(tmp.root, "attempt")
        f.writeText("2026-10-07\n1=5\t" + "a".repeat(36))
        assertEquals(emptyMap<String, String>(), FileStockAttemptStore(f).load(date))
    }
}
