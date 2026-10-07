package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.ApplyResult
import com.aktcl.aron.core.database.repo.ReferenceRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SYS-006: the whole reference bundle lands in Room in one transaction and only ever moves forward. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class BundleApplyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = ReferenceRepository(db)

    @After fun tearDown() = db.close()

    private fun bundle(version: String = "2026-10-05:3", date: String = "2026-10-05"): JsonObject {
        val base = Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.readBytes().decodeToString()).jsonObject
        val extra = Json.parseToJsonElement(
            """{
              "prices": [
                {"id": 1, "sku_id": 100, "price_type": "outlet", "amount_mtk": 9000, "per_base_qty": 1, "valid_from": "2026-09-01", "valid_to": "2026-10-05"},
                {"id": 2, "sku_id": 100, "price_type": "outlet", "amount_mtk": 9500, "per_base_qty": 1, "valid_from": "2026-10-06", "valid_to": null},
                {"id": 3, "sku_id": 100, "price_type": "cc", "amount_mtk": 8800, "per_base_qty": 1, "valid_from": "2026-09-01"}
              ],
              "config": {"config_version": 318,
                "values": [{"key": "geo.radius_m", "value": 100, "scope_type": "global", "effective_from": null, "requires_ack": false}],
                "scheduled": [{"key": "geo.radius_m", "value": 80, "scope_type": "zone", "scope_id": 7, "effective_from": "2026-10-05T06:00:00.000Z", "requires_ack": true}]},
              "user": {"user_id": 1001, "username": "sr334001", "full_name": "রহিম উদ্দিন", "role": "SR", "locale": "bn", "bind_ordinal": 1, "memo_seq_block_size": 500},
              "reason_texts": {"schema_invalid": {"bn": "তথ্য সঠিক নয়", "en": "Invalid data"}},
              "code_lists": [], "offers": [], "calendar": {"holidays": []}, "templates": [], "tasks": []
            }""",
        ).jsonObject
        val meta = JsonObject(base["meta"]!!.jsonObject + mapOf("bundle_version" to kotlinx.serialization.json.JsonPrimitive(version), "valid_for_business_date" to kotlinx.serialization.json.JsonPrimitive(date)))
        return JsonObject(base + extra + ("meta" to meta))
    }

    private suspend fun apply(raw: JsonObject, etag: String? = "\"e1\"") = repo.apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), raw), raw, etag)

    @Test fun routesOutletsSkusPricesConfigAndDateAreReadableFromRoom() = runTest {
        assertEquals(ApplyResult.APPLIED, apply(bundle()))
        assertEquals("2026-10-05", repo.businessDate())
        assertEquals("\"e1\"", repo.etag())
        assertEquals(2, repo.routesOfDay("2026-10-05").size)
        assertEquals(200, repo.routesOfDay("2026-10-05").sumOf { it.outlets.size })
        assertTrue(repo.skus().isNotEmpty())
        // A price change applies to the dates it covers only.
        assertEquals(9000L, repo.priceOn(100, "outlet", "2026-10-05")!!.amountMtk)
        assertEquals(9500L, repo.priceOn(100, "outlet", "2026-10-06")!!.amountMtk)
        assertEquals(8800L, repo.priceOn(100, "cc", "2026-10-06")!!.amountMtk)
        assertNull(repo.priceOn(100, "outlet", "2026-08-31"))
        // Scheduled config takes effect at its time.
        assertEquals("100", repo.config("geo.radius_m", "2026-10-05T05:59:59.999Z"))
        assertEquals("80", repo.config("geo.radius_m", "2026-10-05T06:00:00.000Z"))
        assertNull(repo.config("no.such.key", "2026-10-05T06:00:00.000Z"))
        assertEquals("318", db.referenceDao().meta(ReferenceRepository.KEY_CONFIG_VERSION))
        // Raw sections, Bangla intact.
        assertEquals("রহিম উদ্দিন", Json.parseToJsonElement(repo.section("user")!!).jsonObject["full_name"]!!.jsonPrimitive.content)
        assertTrue(repo.section("reason_texts")!!.contains("তথ্য সঠিক নয়"))
        val routeId = repo.routesOfDay("2026-10-05").first().route.routeId
        assertTrue(Json.parseToJsonElement(repo.section("route.$routeId")!!).jsonObject.containsKey("route_snapshot_version"))
        assertTrue(!repo.section("route.$routeId")!!.contains("\"outlets\""))
    }

    @Test fun reapplyingReplacesWithoutDuplicates() = runTest {
        apply(bundle()); apply(bundle()); apply(bundle("2026-10-05:4"))
        assertEquals(200, db.referenceDao().outletCount())
        assertEquals(3, db.referenceDao().priceCount())
        assertEquals(2, db.referenceDao().configRows("geo.radius_m").size)
    }

    @Test fun anOlderBundleNeverRollsBackANewerOne() = runTest {
        assertEquals(ApplyResult.APPLIED, apply(bundle("2026-10-06:1", "2026-10-06")))
        assertEquals(ApplyResult.OLDER_IGNORED, apply(bundle("2026-10-05:9")))
        assertEquals(ApplyResult.APPLIED, apply(bundle("2026-10-06:2", "2026-10-06")))
        assertEquals(ApplyResult.OLDER_IGNORED, apply(bundle("2026-10-06:1", "2026-10-06")))
        assertEquals("2026-10-06:2", repo.bundleVersion())
        assertEquals("2026-10-06", repo.businessDate())
    }

    @Test fun versionOrderIsNumericOnTheSnapshotSequence() {
        assertEquals(1, ReferenceRepository.compare("2026-10-05:10", "2026-10-05:9"))
        assertEquals(0, ReferenceRepository.compare("2026-10-05:3", "2026-10-05:3"))
        assertEquals(-1, ReferenceRepository.compare("2026-10-04:99", "2026-10-05:1"))
        assertEquals(1, ReferenceRepository.compare("2026-10-05:1", null))
        assertEquals(1, ReferenceRepository.compare("2026-10-05:1", "garbage"))
    }
}
