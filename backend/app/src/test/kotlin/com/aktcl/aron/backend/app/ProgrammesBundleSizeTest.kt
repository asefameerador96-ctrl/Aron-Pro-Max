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
 * Size of the bundle's `programmes` section (lane portal-loyalty-api, train 3, push B; docs/04: "keep it compact, gzip it, delta refresh"). One SR route of 120 outlets,
 * three programmes (Astha, Diamond League, a campaign), 30 gifts and five ledger rows per outlet and programme. Two measurements through GET /v1/sync/bundle:
 *   realistic: 12 brands, so 120 outlets x 3 months x (12 brand rows + 1 memo row) = 4,680 target rows;
 *   worst case: 55 brands, so the section's 20,000-row cap is reached (120 x 3 x 56 = 20,160 rows asked for).
 * docs/04 sets no number for the bundle, so the assertions below are this lane's own bounds (stated in the status file), and the numbers go to
 * build/programmes-bundle-size.txt. gzip is measured here with the JDK's default level, as the server's Compression plugin would send it.
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
            val months = com.aktcl.aron.backend.platform.AsthaQuarter.of(today, 1).months
            fun targets(skip: Int, count: Int) {
                for (m in months) {
                    exec("INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target, value_target_mtk) SELECT $astha, DATE '$m', $route, o.id, b.id, 100, 0, 250000 FROM app.outlet o, (SELECT id FROM app.product_node WHERE code LIKE 'SZB-%' ORDER BY id OFFSET $skip LIMIT $count) b WHERE o.route_id = $route AND o.status = 'active'")
                }
            }
            for (m in months) exec("INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target) SELECT $astha, DATE '$m', $route, o.id, NULL, 0, 6 FROM app.outlet o WHERE o.route_id = $route AND o.status = 'active'")

            targets(0, 12); val realisticRows = measureNow("realistic (12 brands)")
            targets(12, 43); val worstRows = measureNow("worst case (55 brands, 20,000-row cap)")
            assertEquals(120 * 3 * 13, realisticRows.rows, "120 outlets x 3 months x (12 brands + the memo row)")
            assertEquals(20_000, worstRows.rows, "the section caps the Astha rows at 20,000")
            // This lane's own bounds (docs/04 has no number): a realistic route stays under 64 KB gzipped for the section, the cap under 256 KB (measured: 19 KB and 71 KB).
            assertTrue(realisticRows.sectionGz < 64 * 1024, "realistic section gzip ${realisticRows.sectionGz} B")
            assertTrue(worstRows.sectionGz < 256 * 1024, "worst-case section gzip ${worstRows.sectionGz} B")
            // Equal content, equal version: with no memo arriving in between, a rebuild is the same bundle version (a wall-clock `as_of` in the target rows would make every
            // build a new snapshot and every delta non-empty).
            val v1 = json(bundleText())["meta"]!!.jsonObject["bundle_version"].toString(); val v2 = json(bundleText())["meta"]!!.jsonObject["bundle_version"].toString()
            assertEquals(v1, v2, "an unchanged bundle keeps its version")
        }
    }

    private class Size(val rows: Int, val sectionRaw: Int, val sectionGz: Int, val restRaw: Int, val restGz: Int)

    private suspend fun io.ktor.client.HttpClient.measureNow(label: String): Size {
        val doc = json(bundleText())
        val section = doc["programmes"]!!.jsonObject
        val rest = kotlinx.serialization.json.JsonObject(doc.filterKeys { it != "programmes" }).toString()
        val rows = section["astha_targets"]!!.jsonArray.size
        val size = Size(rows, section.toString().length, gz(section.toString()), rest.length, gz(rest))
        report.appendLine("$label: astha_targets=$rows loyalty=${section["loyalty"]!!.jsonArray.size} gifts=${section["gifts"]!!.jsonArray.size} assignments=${section["gift_assignments"]!!.jsonArray.size}; " +
            "programmes section raw ${size.sectionRaw} B, gzip ${size.sectionGz} B; rest of the bundle raw ${size.restRaw} B, gzip ${size.restGz} B; total gzip ${size.sectionGz + size.restGz} B (sum of the parts)")
        return size
    }
}
