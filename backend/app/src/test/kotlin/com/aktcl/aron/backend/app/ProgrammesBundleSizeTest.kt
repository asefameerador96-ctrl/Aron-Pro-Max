package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.json
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Size of the bundle's `programmes` section (lane portal-loyalty-api, push B2; docs/04 budget set by the lead: the whole section at most 300 KB raw and 32 KB gzip for a
 * 120-outlet SR). One SR route of 120 outlets, three programmes (Astha, Diamond League, a campaign), 30 gifts, five ledger rows per outlet and programme. The bundle carries the
 * current month only, outlets enrolled in an active Astha programme only and no all-zero row; past 1,000 Astha rows it cuts loudly (`astha_truncated`). The numbers go to
 * build/programmes-bundle-size.txt. gzip is the JDK's default level, as the server's Compression plugin would send it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProgrammesBundleSizeTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var sr: TsoPhone
    private val report = StringBuilder()

    @BeforeAll
    fun setUp() { world = TsoWorld(repoRoot); sr = world.phone("sr1001", "sr") }

    @AfterAll
    fun tearDown() {
        File("build").mkdirs(); File("build/programmes-bundle-size.txt").writeText(report.toString()); print(report)
        world.close()
    }

    private fun gz(s: String): Int = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(s.toByteArray()) } }.size()

    private suspend fun io.ktor.client.HttpClient.bundleText(): String = with(world) { getAs(sr, "/v1/sync/bundle").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.bodyAsText() }

    @Test
    fun theProgrammesSectionStaysSmallForARealisticRouteAndBoundedAtTheCap() = world.withApi {
        with(world) {
            val today = today()
            val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            exec(
                """
                INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel, lat, lng, location_basis, location_confirmed)
                SELECT 'SZ-' || n, 'Size outlet ' || n, 'Owner', o.zone_id, o.route_id, o.cluster_id, 'GT', 23.80 + n * 0.0001, 90.36 + n * 0.0001, 'master', true
                  FROM app.outlet o, generate_series(1, 120) n WHERE o.id = (SELECT min(id) FROM app.outlet WHERE route_id = $route)
                """.trimIndent(),
            )
            exec("UPDATE app.outlet SET status = 'closed' WHERE route_id = $route AND code NOT LIKE 'SZ-%'")   // exactly 120 active outlets on the route
            val outlets = one("SELECT count(*) FROM app.outlet WHERE route_id = $route AND status = 'active'")
            assertEquals(120L, outlets)
            val segment = one("SELECT parent_id FROM app.product_node WHERE level = 'brand' ORDER BY id LIMIT 1")
            exec("INSERT INTO app.product_node (level, parent_id, code, name) SELECT 'brand', $segment, 'SZB-' || n, 'Size brand ' || n FROM generate_series(1, 55) n")
            exec("UPDATE app.product_node SET sales_enable = true WHERE code LIKE 'SZB-%'")
            val kinds = listOf("astha" to "ASTHA-SZ", "diamond_league" to "DL-SZ", "campaign" to "CMP-SZ")
            for ((kind, code) in kinds)
                exec("INSERT INTO app.programme (kind, code, name_en, name_bn, active_from, active_to, status) VALUES ('$kind', '$code', 'Size programme $code', 'আকার প্রোগ্রাম $code', DATE '${today.minusDays(40)}', DATE '${today.plusDays(40)}', 'active')")
            val astha = one("SELECT id FROM app.programme WHERE code = 'ASTHA-SZ'")
            for ((_, code) in kinds) {
                val p = one("SELECT id FROM app.programme WHERE code = '$code'")
                exec("INSERT INTO app.programme_enrolment (programme_id, outlet_id, tier_code, league_label, valid_from) SELECT $p, id, 'gold', 'Gold', DATE '${today.minusDays(30)}' FROM app.outlet WHERE route_id = $route AND status = 'active'")
                exec("INSERT INTO app.loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date, expires_on) SELECT o.id, $p, 'adjustment', 'sz-$p-' || o.id || '-' || n, 10 + n, DATE '${today.minusDays(10)}' + n, DATE '${today.plusDays(20)}' FROM app.outlet o, generate_series(1, 5) n WHERE o.route_id = $route AND o.status = 'active'")
                exec("INSERT INTO app.gift (programme_id, code, name_en, name_bn, points_cost, tier_codes) SELECT $p, 'sz_gift_' || n, 'Size gift ' || n, 'উপহার ' || n, 100 * n, '{gold,silver}' FROM generate_series(1, 10) n")
            }
            exec("INSERT INTO app.gift_assignment (programme_id, quarter, outlet_id, route_id, gift_id, tier_code, chosen_at) SELECT $astha, '${com.aktcl.aron.backend.platform.AsthaQuarter.of(today, 1).label}', o.id, $route, (SELECT min(id) FROM app.gift WHERE programme_id = $astha), 'gold', now() FROM app.outlet o WHERE o.route_id = $route AND o.status = 'active'")
            val month = today.withDayOfMonth(1)   // the bundle carries the current month only
            fun targets(skip: Int, count: Int) {
                exec("INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target, value_target_mtk) SELECT $astha, DATE '$month', $route, o.id, b.id, 100, 0, 250000 FROM app.outlet o, (SELECT id FROM app.product_node WHERE code LIKE 'SZB-%' ORDER BY id OFFSET $skip LIMIT $count) b WHERE o.route_id = $route AND o.status = 'active'")
            }
            exec("INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target) SELECT $astha, DATE '$month', $route, o.id, NULL, 0, 6 FROM app.outlet o WHERE o.route_id = $route AND o.status = 'active'")
            // A previous-month row per outlet and an all-zero row per outlet: neither may reach the bundle.
            exec("INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target) SELECT $astha, DATE '${month.minusMonths(1)}', $route, o.id, NULL, 0, 6 FROM app.outlet o WHERE o.route_id = $route AND o.status = 'active'")
            exec("INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target) SELECT $astha, DATE '$month', $route, o.id, (SELECT id FROM app.product_node WHERE code = 'SZB-55'), 0, 0 FROM app.outlet o WHERE o.route_id = $route AND o.status = 'active'")

            // Realistic: 6 brands with a target per outlet (+ the memo-target row) for the month.
            targets(0, 6); val realistic = measureNow("realistic (6 brands with targets, current month)")
            assertEquals(120 * 7, realistic.rows, "120 outlets x (6 brands + the memo row); the previous-month and all-zero rows are cut")
            // The lead's budget (docs/04): the whole programmes section at most 300 KB raw and 32 KB gzip for a 120-outlet SR.
            assertTrue(realistic.sectionRaw <= 300 * 1024, "section raw ${realistic.sectionRaw} B over 300 KB")
            assertTrue(realistic.sectionGz <= 32 * 1024, "section gzip ${realistic.sectionGz} B over 32 KB")
            // Over the bound: 54 more brands would be 6,500+ rows. The builder cuts at 1,000 rows, loudly: the flag is true, never a silent cut.
            targets(6, 48); val capped = measureNow("over the bound (54 brands with targets)")
            assertEquals(BundleProgrammesBound, capped.rows); assertTrue(capped.truncated, "astha_truncated must be true")
            assertTrue(!realistic.truncated)
            assertTrue(capped.sectionRaw <= 300 * 1024 && capped.sectionGz <= 32 * 1024, "even cut at the bound the section stays in budget: ${capped.sectionRaw} B raw, ${capped.sectionGz} B gzip")
            // Equal content, equal version: with no memo arriving in between, a rebuild is the same bundle version (a wall-clock `as_of` in the target rows would make every
            // build a new snapshot and every delta non-empty).
            val v1 = json(bundleText())["meta"]!!.jsonObject["bundle_version"].toString(); val v2 = json(bundleText())["meta"]!!.jsonObject["bundle_version"].toString()
            assertEquals(v1, v2, "an unchanged bundle keeps its version")
        }
    }

    private val BundleProgrammesBound = com.aktcl.aron.backend.sync.BundleProgrammes.MAX_ASTHA_ROWS

    private class Size(val rows: Int, val sectionRaw: Int, val sectionGz: Int, val restRaw: Int, val restGz: Int, val truncated: Boolean)

    private suspend fun io.ktor.client.HttpClient.measureNow(label: String): Size {
        val doc = json(bundleText())
        val section = doc["programmes"]!!.jsonObject
        val rest = kotlinx.serialization.json.JsonObject(doc.filterKeys { it != "programmes" }).toString()
        val rows = section["astha_targets"]!!.jsonArray.size
        val size = Size(rows, section.toString().length, gz(section.toString()), rest.length, gz(rest), section["astha_truncated"]?.toString() == "true")
        report.appendLine("$label: astha_targets=$rows loyalty=${section["loyalty"]!!.jsonArray.size} gifts=${section["gifts"]!!.jsonArray.size} assignments=${section["gift_assignments"]!!.jsonArray.size}; " +
            "programmes section raw ${size.sectionRaw} B, gzip ${size.sectionGz} B; rest of the bundle raw ${size.restRaw} B, gzip ${size.restGz} B; total gzip ${size.sectionGz + size.restGz} B (sum of the parts)")
        return size
    }
}
