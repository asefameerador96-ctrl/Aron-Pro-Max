package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.BundleDeltaResult
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SYS-007: a ?since= delta applies only the changed rows, in one transaction, and a price change reaches new memos only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class BundleDeltaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = ReferenceRepository(db)
    private val date = "2026-10-05"

    @After fun tearDown() = db.close()

    @Before fun setUp(): Unit = runBlocking {
        val base = Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.readBytes().decodeToString()).jsonObject
        val extra = Json.parseToJsonElement(
            """{"prices": [{"id": 1, "sku_id": 100, "price_type": "outlet", "amount_mtk": 9000, "per_base_qty": 1, "valid_from": "2026-09-01", "valid_to": null}],
              "code_lists": [{"list_key": "visit_outcome", "items": [{"code": "sold"}]}, {"list_key": "qc_fault", "items": []}],
              "offers": [], "tasks": [], "templates": [],
              "supervisor": {"zone_ids": [1], "route_ids": [10231], "team": [{"user_id": 7, "username": "sr7", "full_name": "A", "role": "SR", "route_ids": [10231]}], "pending_outlet_requests": []}}""",
        ).jsonObject
        val raw = JsonObject(base + extra)
        repo.apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), raw), raw, "\"e1\"")
        Unit
    }

    private fun outlet(id: Long, route: Long, name: String) = """{"outlet_id": $id, "route_id": $route, "code": "O-$id", "name": "$name", "name_bn": null,
        "name_sort_key": "x", "owner_name": "o", "contact_number": null, "lat": 23.79, "lng": 90.40, "location_confirmed": true,
        "provisional_lat": null, "provisional_lng": null, "cluster_id": 10, "cluster_name": "C", "channel": "retail", "sub_channel_id": null,
        "geo_class": "Urban", "status": "active", "price_type": "outlet", "outlet_kind": "retail", "radius_m": 60, "max_accuracy_m": 50,
        "visit_sequence": 3, "open_due_mtk": 120000, "open_due_as_of": null, "programme_flags": [], "pending_request": false}"""

    private fun delta(base: String = "c1", cursor: String = "c2", version: String = "2026-10-05:4", d: String = date, sections: String = "{}", extra: String = "{}") =
        JsonObject(Json.parseToJsonElement(
            """{"meta": {"bundle_version": "$version", "base_cursor": "$base", "cursor": "$cursor", "valid_for_business_date": "$d",
                 "config_version": 318, "server_time": "2026-10-05T06:00:00.000Z"},
               "sections": $sections, "routes_added": [], "routes_removed": [], "day_states": []}""",
        ).jsonObject + Json.parseToJsonElement(extra).jsonObject)

    @Test
    fun onlyTheNamedRowsChangeAndAPriceChangeReachesNewMemosOnly() = runBlocking {
        // A sale made before the delta keeps the price it was sold at.
        val capture = CaptureRepository(db) { "2026-10-05T05:00:00.000Z" }
        val (visit, fix) = TestRows.visit()
        capture.recordVisitOpen(visit, fix)
        val sale = TestRows.sale(visit.clientUuid)
        capture.recordSale(sale)
        val outletsBefore = db.referenceDao().outletCount()
        val untouched = db.referenceDao().outlet(50002)!!
        val d = delta(
            sections = """{
              "outlets": {"upsert": [${outlet(50000, 10231, "Renamed Store")}], "delete": [50001]},
              "prices": {"upsert": [{"id": 1, "sku_id": 100, "price_type": "outlet", "amount_mtk": 9500, "per_base_qty": 1, "valid_from": "2026-09-01", "valid_to": null}], "delete": []},
              "tasks": {"upsert": [{"task_uuid": "0b6a2f4e-1c3d-4e5f-8a9b-0c1d2e3f4a5b", "task_type_code": "collect_due", "title": "t", "assignee_user_id": 1001,
                 "assigned_by_user_id": 8, "status": "ongoing", "created_at": "2026-10-05T05:00:00.000Z", "source": "web"}], "delete": []},
              "open_memos": {"upsert": [{"memo_client_uuid": "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f", "memo_no": "sr-1", "business_date": "2026-10-04",
                 "outlet_id": 50000, "net_mtk": 120000, "due_mtk": 120000}], "delete": []},
              "team": {"upsert": [{"user_id": 9, "username": "sr9", "full_name": "B", "role": "SR", "route_ids": [10232]}], "delete": ["7"]}}""",
            extra = """{"code_lists": [{"list_key": "qc_fault", "items": [{"code": "torn_pack"}]}],
              "day_states": [{"route_id": 10231, "business_date": "2026-10-05", "state": "sales_submitted", "submit_cycle": 1, "submit_voided": false}]}""",
        )
        assertEquals(BundleDeltaResult.APPLIED, repo.applyBundleDelta(d))
        assertEquals(outletsBefore - 1, db.referenceDao().outletCount())
        assertNull(db.referenceDao().outlet(50001))
        assertEquals("Renamed Store", db.referenceDao().outlet(50000)!!.name)
        assertEquals(60, db.referenceDao().outlet(50000)!!.radiusM)
        assertEquals(untouched, db.referenceDao().outlet(50002)) // a row not in the delta is exactly as it was
        assertEquals(9500L, repo.priceOn(100, "outlet", date)!!.amountMtk) // the next memo prices at 9500
        val line = db.captureDao().linesOf(sale.memo.clientUuid).first { it.skuId == 100L }
        assertEquals(9_000L, line.basePriceMtk) // the memo already made keeps 9000
        assertEquals(1, repo.tasks().size)
        val route = Json.parseToJsonElement(repo.section("route.10231")!!).jsonObject
        assertEquals("sr-1", route["open_memos"]!!.jsonArray.single().jsonObject["memo_no"]!!.jsonPrimitive.content)
        assertEquals("sales_submitted", route["day_state"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        val lists = Json.parseToJsonElement(repo.section("code_lists")!!).jsonArray
        assertEquals(listOf("visit_outcome", "qc_fault"), lists.map { it.jsonObject["list_key"]!!.jsonPrimitive.content })
        assertEquals(1, lists[1].jsonObject["items"]!!.jsonArray.size)
        val team = Json.parseToJsonElement(repo.section("supervisor")!!).jsonObject["team"]!!.jsonArray
        assertEquals(listOf("9"), team.map { it.jsonObject["user_id"]!!.jsonPrimitive.content })
        assertEquals("2026-10-05:4", repo.bundleVersion())
        assertEquals("c2", db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_CURSOR))
        // The same delta again changes nothing.
        assertEquals(BundleDeltaResult.STALE, repo.applyBundleDelta(d))
        // A removed open memo goes.
        val gone = delta(base = "c2", cursor = "c3", version = "2026-10-05:5", sections = """{"open_memos": {"upsert": [], "delete": ["6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f"]}}""")
        assertEquals(BundleDeltaResult.APPLIED, repo.applyBundleDelta(gone))
        assertEquals(0, Json.parseToJsonElement(repo.section("route.10231")!!).jsonObject["open_memos"]!!.jsonArray.size)
    }

    @Test
    fun aDeltaFromAnotherCursorIsAGapAndChangesNothing() = runBlocking {
        val d = delta(base = "zz", sections = """{"outlets": {"upsert": [], "delete": [50001]}}""")
        assertEquals(BundleDeltaResult.GAP, repo.applyBundleDelta(d))
        assertNotNull(db.referenceDao().outlet(50001))
        assertEquals("true", db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_REFRESH))
        assertEquals("2026-10-05:3", repo.bundleVersion())
    }

    @Test
    fun aDeltaOfAnotherDateIsNotApplied() = runBlocking {
        assertEquals(BundleDeltaResult.NEW_DATE, repo.applyBundleDelta(delta(d = "2026-10-06", version = "2026-10-06:1")))
        assertEquals("2026-10-05:3", repo.bundleVersion())
    }

    @Test
    fun aMalformedRowFailsTheWholeDeltaBeforeAnyWrite() = runBlocking {
        val d = delta(sections = """{"outlets": {"upsert": [{"outlet_id": 50000}], "delete": [50001]}}""")
        assertTrue(runCatching { repo.applyBundleDelta(d) }.isFailure)
        assertNotNull(db.referenceDao().outlet(50001))
        assertEquals("c1", db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_CURSOR))
    }

    @Test
    fun routesAddedAndRemovedComeWhole() = runBlocking {
        val base = Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.readBytes().decodeToString()).jsonObject
        val snap = base["routes"]!!.jsonArray[1].jsonObject
        val moved = JsonObject(snap + ("route_id" to Json.parseToJsonElement("10299")) +
            ("route" to JsonObject(snap["route"]!!.jsonObject + ("id" to Json.parseToJsonElement("10299")))) +
            ("outlets" to JsonArray(listOf(Json.parseToJsonElement(outlet(70000, 10299, "New Route Store"))))))
        val d = delta(extra = """{"routes_added": [$moved], "routes_removed": [10232]}""")
        assertEquals(BundleDeltaResult.APPLIED, repo.applyBundleDelta(d))
        val routes = repo.routesOfDay(date).map { it.route.routeId }.toSet()
        assertEquals(setOf(10231L, 10299L), routes)
        assertNull(db.referenceDao().outlet(60000)) // the removed route's outlets went with it
        assertEquals(10299L, db.referenceDao().outlet(70000)!!.routeId)
        assertNull(repo.section("route.10232"))
        assertNotNull(repo.section("route.10299"))
    }
}
