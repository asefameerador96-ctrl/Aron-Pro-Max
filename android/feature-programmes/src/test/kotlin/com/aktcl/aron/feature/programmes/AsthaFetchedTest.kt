package com.aktcl.aron.feature.programmes

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AsthaFetchedTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun item(route: Long, month: String, brand: Long? = 11, target: String = "100", memoTarget: Int = 0) =
        """{"route_id":$route,"outlet_id":null,"brand_id":${brand ?: "null"},"month":"$month","std_target":"$target","std_achieved":"10","memo_target":$memoTarget,"memo_achieved":0,"value_target_mtk":"1000","value_achieved_mtk":"100","by_pack":[]}"""

    private fun page(asOf: String, items: List<String>, next: String? = null) =
        """{"quarter":"2026-Q4","as_of":"$asOf","items":[${items.joinToString(",")}],"next_cursor":${next?.let { "\"$it\"" } ?: "null"}}"""

    private fun arr(vararg items: String): JsonArray = Json.parseToJsonElement("[" + items.joinToString(",") + "]") as JsonArray
    private val q4 = listOf("2026-10", "2026-11", "2026-12")
    private fun cache(dir: String = "c") = AsthaMonthCache(File(tmp.root, dir))

    private val bundleJson = """{"astha_as_of":"2026-11-17T10:00:00Z","astha_truncated":false,"astha_targets":[${item(7, "2026-11", target = "2240")}]}"""
    private fun bundle(extra: String = "") = AsthaModel.parse(bundleJson.let { if (extra.isEmpty()) it else it.dropLast(1) + ",$extra}" }, """{"nodes":[],"skus":[]}""")!!

    // ---- the cache ----

    @Test fun aMonthWrittenIsReadBackByANewCacheOnTheSameFolderAsAfterAKillAndRelaunch() {
        cache().put(7, "2026-10", "2026-11-18T06:00:00Z", arr(item(7, "2026-10")))
        val again = cache().read(7, "2026-10")!!
        assertEquals("2026-11-18T06:00:00Z", again.asOf)
        assertEquals(1, again.items.size)
    }

    @Test fun aCorruptOrForeignFileIsIgnoredNeverACrash() {
        val dir = File(tmp.root, "c").also { it.mkdirs() }
        File(dir, "r7_2026-10.json").writeText("{not json")
        File(dir, "r7_2026-11.json").writeText("""{"v":1,"route_id":8,"month":"2026-11","as_of":null,"items":[]}""") // another route's content under this name
        assertNull(cache().read(7, "2026-10"))
        assertNull(cache().read(7, "2026-11"))
        assertTrue(cache().readAll(listOf(7)).isEmpty())
    }

    @Test fun aTemporaryLeftoverFromAKillIsNotReadAndTheOldFileSurvives() {
        val c = cache(); c.put(7, "2026-10", "2026-11-18T06:00:00Z", arr(item(7, "2026-10")))
        File(tmp.root, "c/r7_2026-10.json.tmp").writeText("{half")
        assertEquals(1, cache().readAll(listOf(7)).size)
        assertEquals(1, cache().read(7, "2026-10")!!.items.size)
    }

    @Test fun onlyTheNewestMonthsAreKeptPerRoute() {
        val c = cache()
        (1..30).forEach { c.put(7, "%04d-%02d".format(2024 + (it - 1) / 12, (it - 1) % 12 + 1), null, arr()) }
        val kept = c.readAll(listOf(7)).map { it.month }
        assertEquals(AsthaMonthCache.KEEP_MONTHS, kept.size)
        assertEquals("2026-06", kept.last())
    }

    @Test fun routesAreKeptApart() {
        val c = cache(); c.put(7, "2026-10", null, arr()); c.put(8, "2026-10", null, arr())
        assertEquals(1, c.readAll(listOf(7)).size); assertEquals(2, c.readAll(listOf(7, 8)).size)
    }

    // ---- the fetcher ----

    private fun fetcher(c: AsthaMonthCache, pages: Map<Pair<Long, String?>, PageOutcome>, calls: MutableList<Triple<String, Long, String?>> = mutableListOf()) =
        AsthaQuarterFetcher(c) { quarter, route, cursor -> calls += Triple(quarter, route, cursor); pages[route to cursor] ?: PageOutcome.Failed }

    @Test fun anOnlineReadKeepsEveryMonthOfTheQuarterIncludingOneThatHasNoRows() = runBlocking {
        val c = cache()
        val f = fetcher(c, mapOf((7L to null) to PageOutcome.Page(page("2026-11-18T06:00:00Z", listOf(item(7, "2026-10"), item(7, "2026-12", brand = null, memoTarget = 40))))))
        assertNull(f.fetch("2026-Q4", listOf(7), q4))
        val all = c.readAll(listOf(7)).associateBy { it.month }
        assertEquals(setOf("2026-10", "2026-11", "2026-12"), all.keys)
        assertEquals(1, all.getValue("2026-10").items.size)
        assertEquals(0, all.getValue("2026-11").items.size) // asked and empty: kept as empty, so it reads "no data", not "not downloaded"
        assertEquals(1, all.getValue("2026-12").items.size)
    }

    @Test fun pagesAreFollowedByTheCursorUntilThereIsNone() = runBlocking {
        val c = cache(); val calls = mutableListOf<Triple<String, Long, String?>>()
        val f = fetcher(c, mapOf(
            (7L to null) to PageOutcome.Page(page("2026-11-18T06:00:00Z", listOf(item(7, "2026-10", brand = 11)), next = "p2")),
            (7L to "p2") to PageOutcome.Page(page("2026-11-18T06:00:01Z", listOf(item(7, "2026-10", brand = 12)))),
        ), calls)
        assertNull(f.fetch("2026-Q4", listOf(7), q4))
        assertEquals(listOf(null, "p2"), calls.map { it.third })
        assertEquals(2, c.read(7, "2026-10")!!.items.size)
        assertEquals("2026-11-18T06:00:00Z", c.read(7, "2026-10")!!.asOf)
    }

    @Test fun aRepeatingCursorEndsTheReadAsFailedAndWritesNothing() = runBlocking {
        val c = cache()
        val f = fetcher(c, mapOf((7L to null) to PageOutcome.Page(page("2026-11-18T06:00:00Z", emptyList(), next = "x")), (7L to "x") to PageOutcome.Page(page("2026-11-18T06:00:00Z", emptyList(), next = "x"))))
        assertEquals(QuarterFetch.FAILED, f.fetch("2026-Q4", listOf(7), q4))
        assertTrue(c.readAll(listOf(7)).isEmpty())
    }

    @Test fun offlineIsOfflineAndTheEarlierFilesAreLeftAlone() = runBlocking {
        val c = cache(); c.put(7, "2026-10", "2026-11-01T00:00:00Z", arr(item(7, "2026-10")))
        assertEquals(QuarterFetch.OFFLINE, fetcher(c, mapOf((7L to null) to PageOutcome.Offline)).fetch("2026-Q4", listOf(7), q4))
        assertEquals("2026-11-01T00:00:00Z", c.read(7, "2026-10")!!.asOf)
    }

    @Test fun aFailureOnASecondPageKeepsNothingOfThatRouteButKeepsTheOtherRoute() = runBlocking {
        val c = cache()
        val f = fetcher(c, mapOf(
            (7L to null) to PageOutcome.Page(page("2026-11-18T06:00:00Z", listOf(item(7, "2026-10")), next = "p2")),
            (7L to "p2") to PageOutcome.Offline,
            (8L to null) to PageOutcome.Page(page("2026-11-18T06:00:00Z", listOf(item(8, "2026-10")))),
        ))
        assertEquals(QuarterFetch.OFFLINE, f.fetch("2026-Q4", listOf(7, 8), q4))
        assertTrue(c.readAll(listOf(7)).isEmpty())
        assertEquals(3, c.readAll(listOf(8)).size)
    }

    @Test fun aServerRefusalOrUnreadableBodyIsFailedNotOffline() = runBlocking {
        assertEquals(QuarterFetch.FAILED, fetcher(cache(), mapOf((7L to null) to PageOutcome.Failed)).fetch("2026-Q4", listOf(7), q4))
        assertEquals(QuarterFetch.FAILED, fetcher(cache("d"), mapOf((7L to null) to PageOutcome.Page("<html>"))).fetch("2026-Q4", listOf(7), q4))
    }

    @Test fun aRowOfAnotherRouteOrAMalformedRowIsNotKept() = runBlocking {
        val c = cache()
        val f = fetcher(c, mapOf((7L to null) to PageOutcome.Page(page("2026-11-18T06:00:00Z", listOf(item(9, "2026-10"), """{"route_id":7,"month":"bad"}""", item(7, "2026-10"))))))
        f.fetch("2026-Q4", listOf(7), q4)
        assertEquals(1, c.read(7, "2026-10")!!.items.size)
    }

    // ---- merging beside the bundle ----

    @Test fun aMonthTheBundleDoesNotHoldComesFromTheKeptRead() {
        val merged = AsthaFetched.merge(bundle(), listOf(CachedMonth(7, "2026-10", "2026-11-18T06:00:00Z", arr(item(7, "2026-10", target = "555")))))
        assertEquals(555_000L, merged.brandRows("2026-10").single().stdTargetMilli)
        assertEquals("2026-11-18T06:00:00Z", merged.asOfOf("2026-10"))
        assertEquals("2026-11-17T10:00:00Z", merged.asOfOf("2026-11")) // the bundle's own month keeps the bundle's time
    }

    @Test fun theBundleWinsForAMonthItHoldsInFull() {
        val merged = AsthaFetched.merge(bundle(), listOf(CachedMonth(7, "2026-11", "2026-11-18T06:00:00Z", arr(item(7, "2026-11", target = "1")))))
        assertEquals(2_240_000L, merged.brandRows("2026-11").single().stdTargetMilli)
        assertFalse("2026-11" in merged.fetchedMonths)
    }

    @Test fun aMonthNeverFetchedIsNotInTheDataSoItCanNeverReadAsZero() {
        val merged = AsthaFetched.merge(bundle(), listOf(CachedMonth(7, "2026-10", null, arr(item(7, "2026-10")))))
        assertEquals(listOf("2026-12"), merged.missingMonths(q4).filter { it == "2026-12" })
        assertTrue(merged.rows.none { it.month == "2026-12" })
        assertFalse("2026-12" in merged.fetchedMonths)
    }

    @Test fun aFetchedMonthWithNoRowsIsKnownAsFetchedAndEmpty() {
        val merged = AsthaFetched.merge(bundle(), listOf(CachedMonth(7, "2026-12", "2026-11-18T06:00:00Z", arr())))
        assertTrue("2026-12" in merged.fetchedMonths)
        assertTrue(merged.rows.none { it.month == "2026-12" })
    }

    @Test fun aMonthTheBundleCutShortIsReplacedByANewerReadAndKeptWhenTheReadIsOlder() {
        val cut = bundle().copy(truncatedMonths = setOf("2026-11"))
        assertTrue(cut.cutShort("2026-11"))
        val newer = AsthaFetched.merge(cut, listOf(CachedMonth(7, "2026-11", "2026-11-18T06:00:00Z", arr(item(7, "2026-11", brand = 11, target = "9"), item(7, "2026-11", brand = 12, target = "8")))))
        assertEquals(2, newer.brandRows("2026-11").size)
        assertFalse(newer.cutShort("2026-11"))
        val older = AsthaFetched.merge(cut, listOf(CachedMonth(7, "2026-11", "2026-11-10T06:00:00Z", arr(item(7, "2026-11", brand = 12, target = "8")))))
        assertEquals(1, older.brandRows("2026-11").size)
        assertEquals(2_240_000L, older.brandRows("2026-11").single().stdTargetMilli)
        assertTrue(older.cutShort("2026-11"))
    }

    @Test fun theTruncatedMonthsListOfTheBundleIsRead() {
        val d = AsthaModel.parse("""{"astha_targets":[],"astha_truncated_months":["2026-12","bad"]}""", null)!!
        assertEquals(setOf("2026-12"), d.truncatedMonths)
    }

    @Test fun twoRoutesOfOneMonthShowTheOlderTimeAndBothRoutesRows() {
        val merged = AsthaFetched.merge(bundle(), listOf(
            CachedMonth(7, "2026-10", "2026-11-18T06:00:00Z", arr(item(7, "2026-10"))),
            CachedMonth(8, "2026-10", "2026-11-17T06:00:00Z", arr(item(8, "2026-10"))),
        ))
        assertEquals(2, merged.rows.count { it.month == "2026-10" })
        assertEquals("2026-11-17T06:00:00Z", merged.asOfOf("2026-10"))
    }

    @Test fun theLocalMemosAreNotAddedToAFetchedMonth() {
        // the merge runs after AsthaLocal.apply: a fetched figure is the server's as of its read, whatever the phone holds
        val memo = LocalMemo("m1", 1, "2026-10-05", null, "pending", null, emptyList(), 7)
        val base = AsthaLocal.apply(bundle(), listOf(memo), emptySet(), "2026-11")
        val merged = AsthaFetched.merge(base, listOf(CachedMonth(7, "2026-10", "2026-11-18T06:00:00Z", arr(item(7, "2026-10", brand = null, memoTarget = 40)))))
        assertEquals(0L, merged.allBrandRow("2026-10")!!.memoAchieved)
    }

    @Test fun anOlderServerThatOnlySetsTheTruncatedFlagIsHandledLikeAListedMonth() {
        val legacy = bundle().copy(truncated = true)
        assertTrue(legacy.cutShort("2026-11")); assertFalse(legacy.cutShort("2026-12")) // no rows of December: nothing was cut there
        val merged = AsthaFetched.merge(legacy, listOf(CachedMonth(7, "2026-11", "2026-11-18T06:00:00Z", arr(item(7, "2026-11", brand = 12, target = "8")))))
        assertEquals(8_000L, merged.brandRows("2026-11").single().stdTargetMilli)
        assertFalse(merged.cutShort("2026-11"))
    }
}
