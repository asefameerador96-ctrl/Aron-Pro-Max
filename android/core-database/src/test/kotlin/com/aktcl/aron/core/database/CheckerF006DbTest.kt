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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Checker (refuter) tests for F-SYS-006 at the Room level. Each test reproduces one defect. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CheckerF006DbTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = ReferenceRepository(db)

    @After fun tearDown() = db.close()

    private fun bundle(version: String, date: String, prefetch: Boolean = false, prices: String = "[]"): JsonObject {
        val base = Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.readBytes().decodeToString()).jsonObject
        val meta = JsonObject(
            base["meta"]!!.jsonObject + mapOf(
                "bundle_version" to JsonPrimitive(version), "valid_for_business_date" to JsonPrimitive(date), "is_prefetch" to JsonPrimitive(prefetch),
            ),
        )
        return JsonObject(base + ("meta" to meta) + ("prices" to Json.parseToJsonElement(prices)))
    }

    private suspend fun apply(raw: JsonObject) = repo.apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), raw), raw, "\"e\"")

    /**
     * SkuPrice.valid_to is EXCLUSIVE (contract SkuPrice.yaml "Exclusive; null = open"; db V0003 daterange '[)'; server
     * BundleService `valid_to > :d`). The DAO treats it as inclusive, so a price withdrawn from D is still sold on D.
     */
    @Test fun aPriceWhoseValidToIsTodayIsNotInForceToday() = runTest {
        apply(
            bundle(
                "2026-10-05:3", "2026-10-05",
                prices = """[{"id": 1, "sku_id": 100, "price_type": "outlet", "amount_mtk": 9000, "per_base_qty": 1, "valid_from": "2026-09-01", "valid_to": "2026-10-05"}]""",
            ),
        )
        assertEquals(9000L, repo.priceOn(100, "outlet", "2026-10-04")!!.amountMtk)
        assertNull(repo.priceOn(100, "outlet", "2026-10-05"))
    }

    /** A prefetch of tomorrow (meta.is_prefetch) must not replace today's routes while today's selling day is running. */
    @Test fun aPrefetchOfTomorrowKeepsTodaysRoutes() = runTest {
        assertEquals(ApplyResult.APPLIED, apply(bundle("2026-10-05:3", "2026-10-05")))
        apply(bundle("2026-10-06:1", "2026-10-06", prefetch = true))
        assertEquals(2, repo.routesOfDay("2026-10-05").size)
        assertEquals("2026-10-05", repo.businessDate())
    }

    /** After a prefetch is stored, a refreshed snapshot of today (a newer seq of D) must still be applied, not OLDER_IGNORED. */
    @Test fun aRefreshOfTodayAfterAPrefetchIsApplied() = runTest {
        apply(bundle("2026-10-05:3", "2026-10-05"))
        apply(bundle("2026-10-06:1", "2026-10-06", prefetch = true))
        assertEquals(ApplyResult.APPLIED, apply(bundle("2026-10-05:4", "2026-10-05")))
    }

    /**
     * Fix check: the prefetch is kept as ONE sync_meta row. A real bundle is up to 2 MiB gzip (10+ MB of JSON); Android's
     * CursorWindow (2 MB) cannot read a row that large back, so promotePrefetch fails on the morning it is needed.
     */
    @Test fun aLargePrefetchCanBePromoted() = runTest {
        apply(bundle("2026-10-05:3", "2026-10-05"))
        val big = JsonObject(bundle("2026-10-06:1", "2026-10-06", prefetch = true) + ("tutorials" to JsonPrimitive("ক".repeat(1_500_000))))
        assertEquals(ApplyResult.PREFETCH_STORED, apply(big))
        assertEquals(ApplyResult.APPLIED, repo.promotePrefetch("2026-10-06"))
        assertEquals(2, repo.routesOfDay("2026-10-06").size)
    }

    /** Fix check: a raw section over the CursorWindow limit (Bangla and a surrogate pair at a chunk edge) reads back whole. */
    @Test fun aLargeRawSectionReadsBackIntact() = runTest {
        val text = "ক".repeat(399_999) + "\uD83D\uDE00" + "খ".repeat(1_000_000)
        val raw = JsonObject(bundle("2026-10-05:3", "2026-10-05") + ("tutorials" to JsonPrimitive(text)))
        assertEquals(ApplyResult.APPLIED, apply(raw))
        assertEquals(JsonPrimitive(text).toString(), repo.section("tutorials"))
        assertEquals(ApplyResult.APPLIED, apply(bundle("2026-10-05:4", "2026-10-05")))
        assertEquals(null, db.referenceDao().section("tutorials#1"))
    }
}
