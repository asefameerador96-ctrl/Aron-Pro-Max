package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.masterdata.SqlRoutePlanner
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.sync.BundleService
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contract 1.25.0 (request `sr-kpi-by-pack-category`): every `by_pack` entry of the KPI names the SKU category it counts (`category_code`), so a brand whose SKUs mix categories, or whose variant is
 * inactive, is shown exactly. Real memos (`SrMemoFixture`), the real `BundleService`. Monday 2027-01-04 08:00 Dhaka; the memos are dated Saturday 2027-01-02 (closed).
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class SrKpiByPackCategoryTest {
    private lateinit var fresh: FreshDb
    private lateinit var service: BundleService
    private val clock = AronClock { Instant.parse("2027-01-04T02:00:00Z") }
    private var sr = 0L; private var route = 0L; private var outlet = 0L
    private lateinit var fx: SrMemoFixture

    private fun q(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun x(sql: String) = fresh.db.jdbi.useHandle<Exception> { h -> h.execute(sql) }
    private val principal get() = AronPrincipal(sr, "sr1001", Role.SR, 0, "api", null, null, "sr", emptyList(), false, emptyList(), "j", clock.now().plusSeconds(600))
    private fun kpi(): JsonObject = assertNotNull(service.bundle(principal, LocalDate.parse("2027-01-04"), "1.0.0+1").full.routes.single { it.route_id == route }.kpi).jsonObject

    @BeforeEach
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c -> seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } } }
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        val geo = GeoRepository(fresh.db, clock)
        service = BundleService(fresh.db, config, SqlRoutePlanner(fresh.db, geo, config), clock, reach = SqlReachResolver(fresh.db, geo, clock))
        sr = q("SELECT id FROM app.app_user WHERE username = 'sr1001'")
        val r = service.bundle(principal, LocalDate.parse("2027-01-04"), "1.0.0+1").full.routes.first()
        route = r.route_id; outlet = r.outlets.first().outlet_id
        fx = SrMemoFixture({ x(it) }, sr)
        x("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('00000000-0000-4000-8000-00000000c301', decode(repeat('cd', 32), 'hex'), 1, $sr)")
    }

    @AfterEach fun tearDown() = fresh.close()

    private val batch = "'00000000-0000-4000-8000-00000000c301'"

    /** A new SKU of [category] under the variant of [likeSku] (so under the same brand), copying the template columns of a real SKU of that category; returns its id. */
    private fun newSku(code: String, variantOf: Long, category: String, basePerPack: Int, status: String = "active"): Long = q(
        """
        INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, pack_type, entry_unit_default, status)
        SELECT '$code', (SELECT variant_id FROM app.sku WHERE id = $variantOf), t.category_code, 'T $code', left('$code', 20), t.base_unit, $basePerPack, t.pack_type, t.entry_unit_default, '$status'
          FROM (SELECT * FROM app.sku WHERE category_code = '$category' ORDER BY id LIMIT 1) t RETURNING id
        """.trimIndent(),
    )

    private fun brandOf(sku: Long) = q("SELECT v.parent_id FROM app.sku s JOIN app.product_node v ON v.id = s.variant_id WHERE s.id = $sku")
    private fun packs(k: JsonObject, brand: Long) = k["brands"]!!.jsonArray.map { it.jsonObject }.single { it["brand_id"]!!.jsonPrimitive.content == brand.toString() }["by_pack"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.l(k: String) = this[k]!!.jsonPrimitive.content.toLong()

    @Test
    fun aBrandThatMixesCategoriesHasOneEntryPerCategoryAndPackNeverSticksAndDozensInOne() {
        val cig = q("SELECT id FROM app.sku WHERE category_code = 'cigarette' ORDER BY id LIMIT 1"); val brand = brandOf(cig)
        val cigPack = q("SELECT base_per_pack FROM app.sku WHERE id = $cig").toInt()
        val match = newSku("MIXMATCH", cig, "match", 1)   // a match SKU under the SAME brand, base_per_pack 1: the same pack_factor as a stick SKU of 1
        val cig1 = newSku("MIXCIG1", cig, "cigarette", 1)
        for ((sku, qty) in listOf(cig to 2000, match to 30, cig1 to 7)) x("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) VALUES ($route, $sku, DATE '2027-01-01', $qty, $batch)")
        fx.memo("00000000-0000-4000-8000-0000000d0001", route, outlet, "2027-01-02", listOf(SrMemoFixture.L(cig, 400), SrMemoFixture.L(match, 12), SrMemoFixture.L(cig1, 3)))
        val p = packs(kpi(), brand)
        // sticks and dozens must not share an entry: the cigarette SKU of pack 1 and the match SKU of pack 1 are two entries, told apart by category_code
        val one = p.filter { it.l("pack_factor") == 1L }
        assertEquals(setOf("cigarette", "match"), one.map { it["category_code"]!!.jsonPrimitive.content }.toSet(), "pack 1 has a cigarette entry and a match entry, not one mixed entry")
        val m = one.single { it["category_code"]!!.jsonPrimitive.content == "match" }; val c1 = one.single { it["category_code"]!!.jsonPrimitive.content == "cigarette" }
        assertEquals(30L to 12L, m.l("target") to m.l("achieved"), "the match entry holds the match SKU only")
        assertEquals(7L to 3L, c1.l("target") to c1.l("achieved"), "the cigarette pack-1 entry holds the cigarette SKU only")
        if (cigPack != 1) { val big = p.single { it.l("pack_factor") == cigPack.toLong() }; assertEquals("cigarette", big["category_code"]!!.jsonPrimitive.content); assertEquals(2000L to 400L, big.l("target") to big.l("achieved")) }
        assertTrue(p.all { it["category_code"] != null }, "every entry names its category")
        // the BLM category rows carry the category too
        val cats = kpi()["categories"]!!.jsonArray.map { it.jsonObject }.single { it["category_code"]!!.jsonPrimitive.content == "match" }["by_pack"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("match"), cats.map { it["category_code"]!!.jsonPrimitive.content }, "the match category row's entries say match")
    }

    @Test
    fun anInactiveSkuAndAnInactiveVariantAreCountedAndLabelledLikeAnyOther() {
        val cig = q("SELECT id FROM app.sku WHERE category_code = 'cigarette' ORDER BY id LIMIT 1"); val brand = brandOf(cig)
        val old = newSku("OLDCIG10", cig, "cigarette", 10, status = "inactive")   // a discontinued SKU the phone's product tree no longer lists
        x("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) VALUES ($route, $old, DATE '2027-01-01', 500, $batch)")
        fx.memo("00000000-0000-4000-8000-0000000d0011", route, outlet, "2027-01-02", listOf(SrMemoFixture.L(old, 120)))
        // and the whole variant inactive too
        x("UPDATE app.product_node SET status = 'inactive' WHERE id = (SELECT variant_id FROM app.sku WHERE id = $old)")
        val p = packs(kpi(), brand)
        val e = p.single { it.l("pack_factor") == 10L && it["category_code"]!!.jsonPrimitive.content == "cigarette" }
        assertEquals(500L to 120L, e.l("target") to e.l("achieved"), "the discontinued SKU's target and sales are in the brand's by_pack, labelled cigarette")
    }
}
