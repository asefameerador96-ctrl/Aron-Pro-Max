package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.FileOutletPages
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.rules.Geo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/**
 * ZONE-SCALE budget (lead package item 2). A 54-route zone is 3,500 to 11,000 outlets (docs/24 s4.10, F-AMO-044); the AMO's bundle pages the
 * outlets, 2,000 rows a page. This test builds an 11,000-outlet AMO bundle, stages the pages as files (as BundleDownloader does), merges them
 * into the snapshot, applies it into a FILE Room database, then runs the queries the AMO app runs (route list with every route's outlets,
 * the zone-wide outlet list, the outlets of one route) and a nearest-outlet search (the geofence core, haversine) over one route and over the
 * whole zone, and samples the peak heap while ingesting.
 *
 * BUDGETS (docs/04 and docs/31 give no number for this, so these are PROPOSED here, stated, asserted and logged; the A06 is assumed about 3x
 * slower than the CI JVM, so each figure is a third of what the phone may take; the ingest runs once a day in a worker, never in the sale path):
 *   ingest of 11,000 outlets (head parse + streamed page apply)         <= 8 s   (A06 <= 24 s)
 *   route list with all outlets (ReferenceRepository.routesOfDay)      <= 2 s
 *   outlets of one route (the AMO route picker)                        <= 100 ms
 *   nearest outlet in one route (about 200)                            <= 20 ms
 *   nearest outlet across the whole zone (11,000, worst case)          <= 200 ms
 *   live heap above the idle baseline while ingesting (sampled right after a forced GC, so uncollected garbage does not count): <= 150 MB; the raw
 *   peak (garbage included) is only a regression guard <= 400 MB (an app heap is 256 MB on a
 *   2 GB phone): the outlet pages are streamed into Room page by page, see ReferenceRepository.apply(outletPages)
 * The measured figures are printed (ZONE-SCALE ...) and copied into docs/status/android-amo.md.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ZoneScaleBudgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun fixture() = javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test fun anElevenThousandOutletZoneStaysInsideTheBudgets() = runBlocking {
        val rnd = Random(54)
        val base = Json.parseToJsonElement(fixture()).jsonObject
        val routeTemplate = base["routes"]!!.jsonArray[0].jsonObject
        val outletTemplate = routeTemplate["outlets"]!!.jsonArray[0].jsonObject
        val routes = 54; val outletsTotal = 11_000
        val perRoute = outletsTotal / routes
        val routeIds = (0 until routes).map { 20_000L + it }
        // routes[] without outlets (the paged section carries them)
        val routeRows = routeIds.mapIndexed { i, id ->
            val routeObj = routeTemplate["route"]!!.jsonObject.toMutableMap().also {
                it["id"] = JsonPrimitive(id); it["code"] = JsonPrimitive("R-$id"); it["name"] = JsonPrimitive("Route $id"); it["sequence_no"] = JsonPrimitive(i + 1)
            }
            JsonObject(routeTemplate.toMutableMap().also {
                it["route_id"] = JsonPrimitive(id); it["route"] = JsonObject(routeObj); it["outlets"] = JsonArray(emptyList()); it["open_memos"] = JsonArray(emptyList())
                it["target_outlets"] = JsonPrimitive(perRoute)
                it["day_state"] = JsonObject(routeTemplate["day_state"]!!.jsonObject.toMutableMap().also { d -> d["route_id"] = JsonPrimitive(id) })
            })
        }
        val outletRows = ArrayList<JsonElement>(outletsTotal)
        for (n in 0 until outletsTotal) {
            val route = routeIds[minOf(n / perRoute, routes - 1)]
            outletRows += JsonObject(outletTemplate.toMutableMap().also {
                it["outlet_id"] = JsonPrimitive(100_000L + n); it["route_id"] = JsonPrimitive(route); it["code"] = JsonPrimitive("O-$n"); it["name"] = JsonPrimitive("Store $n")
                it["name_bn"] = JsonPrimitive("দোকান $n"); it["name_sort_key"] = JsonPrimitive("store %06d".format(n)); it["owner_name"] = JsonPrimitive("Owner $n")
                it["lat"] = JsonPrimitive(23.70 + rnd.nextDouble() * 0.2); it["lng"] = JsonPrimitive(90.30 + rnd.nextDouble() * 0.2)
                it["location_confirmed"] = JsonPrimitive(true); it["visit_sequence"] = JsonPrimitive(n % perRoute + 1)
            })
        }

        context.deleteDatabase("zone-scale.db")
        val db = Room.databaseBuilder(context, AronDatabase::class.java, "zone-scale.db").build()
        db.openHelper.writableDatabase // opened before the timed part, as at login
        val repo = ReferenceRepository(db)
        val stage = File(context.cacheDir, "zone-scale-stage").also { it.deleteRecursively(); it.mkdirs() }

        // the pages are on disk before the clock starts (the download wrote them, one page text at a time)
        val pages = outletRows.chunked(2_000).mapIndexed { i, rows -> File(stage, "outlets-${i + 1}.json").also { it.writeText(JsonObject(mapOf("section" to JsonPrimitive("outlets"), "rows" to JsonArray(rows))).toString()) } }
        System.gc(); Thread.sleep(200)
        val rt = Runtime.getRuntime(); val baseline = rt.totalMemory() - rt.freeMemory()
        val peakRef = java.util.concurrent.atomic.AtomicLong(baseline); val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
        val livePeak = java.util.concurrent.atomic.AtomicLong(baseline)
        val sampler = Thread {
            var n = 0
            while (sampling.get()) {
                peakRef.accumulateAndGet(rt.totalMemory() - rt.freeMemory()) { a, b -> maxOf(a, b) }
                // every 25 ms a collection, then the heap in use: what the apply really holds (ART collects on demand, so garbage is not "used")
                if (++n % 12 == 0) { System.gc(); livePeak.accumulateAndGet(rt.totalMemory() - rt.freeMemory()) { a, b -> maxOf(a, b) } }
                Thread.sleep(2)
            }
        }.also { it.isDaemon = true; it.start() }

        val t0 = System.nanoTime()
        // pages of 2,000 rows staged as files, then streamed into Room one page at a time (BundleDownloader does the same): the
        // head (routes without outlets, products, config) is parsed, the outlet pages never are held together
        val meta = base["meta"]!!.jsonObject.toMutableMap().also { it["role"] = JsonPrimitive("AMO"); it["bundle_version"] = JsonPrimitive("2026-10-05:99") }
        val head = JsonObject(base.toMutableMap().also { it["routes"] = JsonArray(routeRows); it["meta"] = JsonObject(meta) })
        val mergePeakMb = (peakRef.get() - baseline) / 1_048_576
        val text = head.toString()
        val parsed = BundleReference.parse(text)
        val parsePeakMb = (peakRef.get() - baseline) / 1_048_576
        repo.apply(parsed, head, outletPages = FileOutletPages(pages))
        val ingestMs = (System.nanoTime() - t0) / 1_000_000
        sampling.set(false); sampler.join(500)
        val peakMb = (peakRef.get() - baseline) / 1_048_576
        val liveMb = (livePeak.get() - baseline) / 1_048_576

        assertEquals(outletsTotal, db.referenceDao().outletCount())
        fun <T> timed(block: () -> T): Pair<T, Long> { val s = System.nanoTime(); val r = block(); return r to (System.nanoTime() - s) / 1_000_000 }

        val (days, routeListMs) = timed { runBlocking { repo.routesOfDay("2026-10-05") } }
        assertEquals(routes, days.size); assertEquals(outletsTotal, days.sumOf { it.outlets.size })
        val (oneRoute, oneRouteMs) = timed { runBlocking { db.referenceDao().outletsOf(routeIds[7]) } }
        assertTrue(oneRoute.size in perRoute..perRoute + 1)

        val me = 23.79 to 90.40
        val zoneOutlets = days.flatMap { it.outlets }
        val routeOutlets = days[7].outlets
        fun nearest(list: List<com.aktcl.aron.core.database.entity.OutletEntity>) = list.filter { it.lat != null && it.lng != null }.minByOrNull { Geo.haversineM(me.first, me.second, it.lat!!, it.lng!!) }
        val routeNearestMs = (1..50).map { timed { nearest(routeOutlets) }.second }.average()
        val zoneNearestMs = (1..10).map { timed { nearest(zoneOutlets) }.second }.max()
        val routeNearestWorst = (1..50).maxOf { timed { nearest(routeOutlets) }.second }

        println("ZONE-SCALE mergePeakMb=$mergePeakMb parsePeakMb=$parsePeakMb headChars=${text.length} outlets=$outletsTotal routes=$routes pages=${pages.size}x2000 ingestMs=$ingestMs liveHeapAboveBaselineMb=$liveMb rawPeakHeapMb=$peakMb routeListMs=$routeListMs oneRouteQueryMs=$oneRouteMs nearestRouteAvgMs=$routeNearestMs nearestRouteWorstMs=$routeNearestWorst nearestZoneWorstMs=$zoneNearestMs")
        db.close(); context.deleteDatabase("zone-scale.db"); stage.deleteRecursively()

        assertTrue("ingest $ingestMs ms > 8000", ingestMs <= 8_000)
        assertTrue("route list $routeListMs ms > 2000", routeListMs <= 2_000)
        assertTrue("one route query $oneRouteMs ms > 100", oneRouteMs <= 100)
        assertTrue("nearest in a route $routeNearestWorst ms > 20", routeNearestWorst <= 20)
        assertTrue("nearest in the zone $zoneNearestMs ms > 200", zoneNearestMs <= 200)
        assertTrue("live heap $liveMb MB > 150", liveMb <= 150)
        assertTrue("raw peak heap $peakMb MB > 400 (regression guard, includes uncollected garbage)", peakMb <= 400)
    }

    private fun JsonElement.jsonPrimitiveContent() = (this as JsonPrimitive).content
}
