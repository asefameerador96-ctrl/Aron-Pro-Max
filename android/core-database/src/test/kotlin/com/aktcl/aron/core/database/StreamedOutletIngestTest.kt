package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.ApplyResult
import com.aktcl.aron.core.database.repo.BundleDeltaResult
import com.aktcl.aron.core.database.repo.OutletPages
import com.aktcl.aron.core.database.repo.ReferenceRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Streamed outlet ingest (`ReferenceRepository.apply(outletPages)`): the paged `outlets` section of an AMO/TSO bundle is parsed and
 * inserted a page at a time inside the one apply transaction. Properties: (1) any split into pages gives exactly the rows and meta
 * the in-memory apply gives; (2) a kill at any page leaves the previous snapshot untouched, and replaying (also twice) gives the
 * new snapshot with no duplicate; (3) rows of an unknown route are kept raw, a malformed page writes nothing; (4) a stored prefetch
 * keeps its pages one by one and promotes to the same rows; (5) a delta applies on top of a streamed snapshot.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class StreamedOutletIngestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbs = ArrayList<AronDatabase>()
    private fun newDb() = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build().also { dbs += it }

    @After fun tearDown() = dbs.forEach { it.close() }

    private val base: JsonObject = Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.readBytes().decodeToString()).jsonObject
    private val template = base["routes"]!!.jsonArray[0].jsonObject["outlets"]!!.jsonArray[0].jsonObject
    private val routeIds = base["routes"]!!.jsonArray.map { it.jsonObject["route_id"]!!.jsonPrimitive.content.toLong() }

    private fun outletRows(n: Int, name: String): List<JsonObject> = (0 until n).map { i ->
        JsonObject(template + mapOf(
            "outlet_id" to JsonPrimitive(70_000L + i), "route_id" to JsonPrimitive(routeIds[i % routeIds.size]),
            "code" to JsonPrimitive("S-$i"), "name" to JsonPrimitive("$name $i"), "visit_sequence" to JsonPrimitive(i / routeIds.size + 1),
        ))
    }

    private fun meta(version: String, date: String = "2026-10-05", cursor: String = "c1", prefetch: Boolean = false) =
        JsonObject(base["meta"]!!.jsonObject + mapOf(
            "bundle_version" to JsonPrimitive(version), "valid_for_business_date" to JsonPrimitive(date), "cursor" to JsonPrimitive(cursor),
            "is_prefetch" to JsonPrimitive(prefetch), "role" to JsonPrimitive("AMO"),
        ))

    /** The head of a paged bundle: routes without outlets. */
    private fun head(version: String, date: String = "2026-10-05", cursor: String = "c1", prefetch: Boolean = false) = JsonObject(base + mapOf(
        "meta" to meta(version, date, cursor, prefetch),
        "routes" to JsonArray(base["routes"]!!.jsonArray.map { JsonObject(it.jsonObject + ("outlets" to JsonArray(emptyList()))) }),
    ))

    /** The same bundle with every outlet inline (the in-memory path). */
    private fun whole(version: String, rows: List<JsonObject>, date: String = "2026-10-05", cursor: String = "c1") = JsonObject(head(version, date, cursor) + mapOf(
        "routes" to JsonArray(base["routes"]!!.jsonArray.map { r ->
            val id = r.jsonObject["route_id"]!!.jsonPrimitive.content
            JsonObject(r.jsonObject + ("outlets" to JsonArray(rows.filter { it["route_id"]!!.jsonPrimitive.content == id })))
        }),
    ))

    private class Pages(private val pages: List<List<JsonObject>>, private val killAt: Int? = null) : OutletPages {
        var read = 0
        override val count get() = pages.size
        override suspend fun rows(index: Int): List<JsonObject> {
            if (index == killAt) throw IllegalStateException("killed at page $index")
            read++
            return pages[index]
        }
    }

    private fun split(rows: List<JsonObject>, rnd: Random): List<List<JsonObject>> {
        val out = ArrayList<List<JsonObject>>(); var i = 0
        while (i < rows.size) { val n = rnd.nextInt(1, 12); out += rows.subList(i, minOf(rows.size, i + n)); i += n }
        return out
    }

    private suspend fun ReferenceRepository.applyStreamed(h: JsonObject, pages: OutletPages, asDay: Boolean = true) =
        apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), h), h, "\"e\"", asDay, pages)

    private suspend fun ReferenceRepository.applyWhole(w: JsonObject) =
        apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), w), w, "\"e\"")

    private suspend fun AronDatabase.outlets() = referenceDao().let { d -> routeIds.flatMap { d.outletsOf(it) } }.sortedBy { it.outletId }
    private suspend fun AronDatabase.metaOf(key: String) = referenceDao().meta(key)

    @Test fun anySplitIntoPagesGivesTheRowsOfTheInMemoryApply() = runBlocking {
        for (seed in 1..25) {
            val rnd = Random(seed)
            val rows = outletRows(rnd.nextInt(1, 60), "Store")
            val a = newDb(); val b = newDb()
            ReferenceRepository(a).applyWhole(whole("2026-10-05:9", rows))
            val pages = Pages(split(rows, rnd))
            assertEquals(ApplyResult.APPLIED, ReferenceRepository(b).applyStreamed(head("2026-10-05:9"), pages))
            assertEquals("seed $seed", a.outlets(), b.outlets())
            assertEquals(rows.size, b.referenceDao().outletCount())
            assertEquals(a.metaOf(ReferenceRepository.KEY_BUNDLE_VERSION), b.metaOf(ReferenceRepository.KEY_BUNDLE_VERSION))
            assertEquals(a.metaOf(ReferenceRepository.KEY_BUNDLE_CURSOR), b.metaOf(ReferenceRepository.KEY_BUNDLE_CURSOR))
            assertEquals(pages.count, pages.read)
        }
    }

    @Test fun aKillAtAnyPageLeavesThePreviousSnapshotAndAReplayCompletesIt() = runBlocking {
        val oldRows = outletRows(18, "Old")
        val newRows = outletRows(30, "New")
        for (killAt in 0..5) {
            val db = newDb(); val repo = ReferenceRepository(db)
            repo.applyWhole(whole("2026-10-05:3", oldRows))
            val before = db.outlets()
            val pages = split(newRows, Random(killAt + 100)).let { if (it.size > 6) it else it }
            val at = minOf(killAt, pages.size - 1)
            try {
                repo.applyStreamed(head("2026-10-05:4", cursor = "c2"), Pages(pages, killAt = at))
                fail("the kill at page $at must surface")
            } catch (e: IllegalStateException) {
                assertEquals("killed at page $at", e.message)
            }
            // rolled back: the old rows, the old version, the old cursor, no half of the new snapshot
            assertEquals(before, db.outlets())
            assertEquals("2026-10-05:3", repo.bundleVersion())
            assertEquals("c1", db.metaOf(ReferenceRepository.KEY_BUNDLE_CURSOR))
            // the replay (staged pages are still there) applies the new snapshot; a second replay changes nothing
            assertEquals(ApplyResult.APPLIED, repo.applyStreamed(head("2026-10-05:4", cursor = "c2"), Pages(pages)))
            val applied = db.outlets()
            assertEquals(newRows.size, applied.size)
            assertEquals(newRows.map { it["name"]!!.jsonPrimitive.content }.toSet(), applied.map { it.name }.toSet())
            assertEquals(ApplyResult.APPLIED, repo.applyStreamed(head("2026-10-05:4", cursor = "c2"), Pages(pages)))
            assertEquals(applied, db.outlets())
            assertEquals("2026-10-05:4", repo.bundleVersion())
        }
    }

    @Test fun anOlderStreamedSnapshotIsIgnoredAndWritesNothing() = runBlocking {
        val db = newDb(); val repo = ReferenceRepository(db)
        repo.applyWhole(whole("2026-10-05:8", outletRows(10, "Held")))
        val held = db.outlets()
        assertEquals(ApplyResult.OLDER_IGNORED, repo.applyStreamed(head("2026-10-05:7"), Pages(listOf(outletRows(4, "Older")))))
        assertEquals(held, db.outlets())
    }

    @Test fun rowsOfAnUnknownRouteAreKeptRawAndNeverBecomeOutlets() = runBlocking {
        val db = newDb(); val repo = ReferenceRepository(db)
        val known = outletRows(6, "Known")
        val stray = outletRows(3, "Stray").mapIndexed { i, r -> JsonObject(r + mapOf("outlet_id" to JsonPrimitive(90_000L + i), "route_id" to JsonPrimitive(999_999L))) }
        repo.applyStreamed(head("2026-10-05:5"), Pages(listOf(known.take(3) + stray, known.drop(3))))
        assertEquals(6, db.referenceDao().outletCount())
        val raw = Json.parseToJsonElement(repo.section("outlets_other_routes")!!).jsonArray
        assertEquals(listOf(90_000L, 90_001L, 90_002L), raw.map { it.jsonObject["outlet_id"]!!.jsonPrimitive.content.toLong() })
    }

    @Test fun aMalformedPageRollsEverythingBack() = runBlocking {
        val db = newDb(); val repo = ReferenceRepository(db)
        repo.applyWhole(whole("2026-10-05:3", outletRows(12, "Old")))
        val before = db.outlets()
        val bad = JsonObject(outletRows(1, "Bad").single() - "outlet_id")
        try {
            repo.applyStreamed(head("2026-10-05:4"), Pages(listOf(outletRows(5, "Good"), listOf(bad))))
            fail("a row without outlet_id must be refused")
        } catch (e: IllegalArgumentException) { // SerializationException is one too
        }
        assertEquals(before, db.outlets())
        assertEquals("2026-10-05:3", repo.bundleVersion())
    }

    @Test fun aStoredPrefetchKeepsItsPagesAndPromotesToTheSameRows() = runBlocking {
        val db = newDb(); val repo = ReferenceRepository(db)
        repo.applyWhole(whole("2026-10-05:3", outletRows(5, "Today")))
        val tomorrow = outletRows(21, "Tomorrow")
        val pages = split(tomorrow, Random(7))
        assertEquals(ApplyResult.PREFETCH_STORED, repo.applyStreamed(head("2026-10-06:1", date = "2026-10-06", prefetch = true), Pages(pages), asDay = false))
        assertEquals(5, db.referenceDao().outletCount()) // today's tables untouched
        assertEquals("2026-10-06", repo.prefetch()!!.first)
        assertEquals(ApplyResult.APPLIED, repo.promotePrefetch("2026-10-06"))
        assertEquals(tomorrow.map { it["name"]!!.jsonPrimitive.content }.toSet(), db.outlets().map { it.name }.toSet())
        assertEquals("2026-10-06:1", repo.bundleVersion())
        assertNull(repo.prefetch())
        assertTrue("page entries are gone", db.referenceDao().metaWithPrefix("prefetch.").isEmpty())
    }

    @Test fun aDeltaAppliesOnTopOfAStreamedSnapshot() = runBlocking {
        val db = newDb(); val repo = ReferenceRepository(db)
        val rows = outletRows(14, "Zone")
        repo.applyStreamed(head("2026-10-05:3", cursor = "c1"), Pages(split(rows, Random(3))))
        val renamed = JsonObject(rows[0] + ("name" to JsonPrimitive("Renamed")))
        val delta = Json.parseToJsonElement(
            """{"meta": {"bundle_version": "2026-10-05:4", "base_cursor": "c1", "cursor": "c2", "valid_for_business_date": "2026-10-05"},
               "sections": {"outlets": {"upsert": [$renamed], "delete": [70001]}}, "routes_added": [], "routes_removed": [], "day_states": []}""",
        ).jsonObject
        assertEquals(BundleDeltaResult.APPLIED, repo.applyBundleDelta(delta))
        assertEquals(13, db.referenceDao().outletCount())
        assertEquals("Renamed", db.referenceDao().outlet(70000)!!.name)
        assertNull(db.referenceDao().outlet(70001))
        assertNotNull(db.referenceDao().outlet(70002))
    }
}
