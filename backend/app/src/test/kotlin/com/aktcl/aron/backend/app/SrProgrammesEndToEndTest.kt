package com.aktcl.aron.backend.app

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate
import java.time.YearMonth
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review of the lane at 6af9be045 (D-TKPI-10): the programme sections are proved from a MEMO through the projector into the bundle, not from rows inserted into
 * `dw.agg_*`. One selling day of sr1001 on the dev geography: three memos (cigarette 20s, cigarette 10s, match) are uploaded through the production sync path, the
 * aggregation worker runs, and the bundle of the NEXT business date carries the achievement (`achievement_mtd`, `kpi` with `by_pack`) built from those memos; the bundle
 * of the memos' own date carries none of them (today is never in a server figure).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SrProgrammesEndToEndTest : DataFlowTraceSupport() {
    override val traceName get() = "sr-programmes-trace"

    private fun bundleFor(date: String): com.fasterxml.jackson.databind.JsonNode = runBlocking {
        val r = app.client.get("/v1/sync/bundle?for=$date") {
            bearerAuth(sr.token); header("X-Device-Id", sr.deviceUuid); header("X-App-Version", ApiWorld.APP_VERSION); header("Accept-Encoding", "gzip")
        }
        assertEquals(HttpStatusCode.OK, r.status, "bundle for $date")
        val raw = r.bodyAsBytes()
        json.readTree(if (r.headers["Content-Encoding"] == "gzip") GZIPInputStream(raw.inputStream()).readBytes().toString(Charsets.UTF_8) else raw.toString(Charsets.UTF_8))
    }

    @Test
    @Order(1)
    fun aMemoGoesThroughTheProjectorIntoTheAchievementAndKpiSections() {
        h("1 memo -> projector -> bundle of the next business date")
        val next = LocalDate.parse(day).plusDays(1)
        Assumptions.assumeTrue(YearMonth.from(next) == YearMonth.from(LocalDate.parse(day)), "the trace day is the last of its month: the next bundle is a new month with no month-to-date")
        prepareSrDay()
        val cig20 = pick("cigarette", 20); val cig10 = pick("cigarette", 10); val match = pick("match")
        val month = day.take(7) + "-01"
        db { hd ->
            hd.execute("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('00000000-0000-4000-8000-00000000e2e1', decode(repeat('ab', 32), 'hex'), 3, (SELECT id FROM app.app_user WHERE username = 'admin1001')) ON CONFLICT DO NOTHING")
            for (s in listOf(cig20, cig10, match))
                hd.execute("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) VALUES ($gulRouteId, ${s.id}, DATE '$month', 10000, '00000000-0000-4000-8000-00000000e2e1') ON CONFLICT (route_id, sku_id, month) DO UPDATE SET qty_base = 10000")
        }
        val codes = route.path("outlets").map { it.path("code").asText() }.sorted().take(3)
        sr.memos += Memo(outletByCode(codes[0]), codes[0], listOf(Line(cig20, 2), Line(cig10, 3)), paidShare = 100, minute = 0)
        sr.memos += Memo(outletByCode(codes[1]), codes[1], listOf(Line(cig20, 1), Line(match, 1)), paidShare = 100, minute = 30)
        sr.memos += Memo(outletByCode(codes[2]), codes[2], listOf(Line(cig10, 1)), paidShare = 100, minute = 60)
        val recs = mutableListOf<com.fasterxml.jackson.databind.node.ObjectNode>()
        sr.memos.forEachIndexed { i, m -> val (r, a) = radiusOf(m.outlet); recs += recordsOf(sr, m, gulPins.getValue(m.outlet), r, a, i + 1) }
        recs.groupingBy { it.path("type").asText() }.eachCount().forEach { (t, n) -> sr.counts[t] = n }
        hop("the day is accepted", "statuses" to acks(send(sr, recs, uuid())).distinct(), "expected" to listOf("accepted"))
        runWorker()

        // expected from the device truth: per SKU base quantity and memo count
        val lines = sr.memos.flatMap { m -> m.lines.map { l -> m to l } }
        val qtyBySku = lines.groupBy({ it.second.sku.id }, { it.second.qtyBase }).mapValues { it.value.sum() }
        val memosBySku = lines.groupBy({ it.second.sku.id }, { it.first.memo }).mapValues { it.value.distinct().size }

        val today = bundleFor(day).path("routes").single { it.path("route_id").asLong() == gulRouteId }
        hop("the bundle of the memos' own date holds none of them (today is never in a server figure)", "achievement rows" to today.path("achievement_mtd").size(), "expected" to 0)

        val tomorrow = bundleFor(next.toString()).path("routes").single { it.path("route_id").asLong() == gulRouteId }
        val ach = tomorrow.path("achievement_mtd").associate { it.path("product_id").asLong() to (it.path("achieved_qty_base").asLong() to it.path("memo_count").asInt()) }
        for ((sku, qty) in qtyBySku) hop("achievement_mtd of SKU $sku: base quantity and memo count", "bundle" to ach[sku], "device" to (qty to memosBySku.getValue(sku)))
        assertTrue(tomorrow.path("achievement_mtd").all { it.path("through_date").asText() == day }, "through_date is the memos' date")
        hop("the calendar is present with through_date = the memos' date", "through_date" to tomorrow.path("calendar").path("through_date").asText(), "expected" to day)

        // kpi: brand rows by pack size from the same memos; the by_pack entries add up to the brand's volume (brand aggregate and SKU aggregate agree)
        val brandOf = qtyBySku.keys.associateWith { sqlLong("SELECT b.id FROM app.sku s JOIN app.product_node v ON v.id = s.variant_id JOIN app.product_node b ON b.id = v.parent_id WHERE s.id = $it") }
        val expectedByPack = lines.groupBy({ brandOf.getValue(it.second.sku.id) to it.second.sku.perPack }, { it.second.qtyBase }).mapValues { it.value.sum() }
        val kpi = tomorrow.path("kpi")
        for (b in kpi.path("brands")) {
            val id = b.path("brand_id").asLong()
            val packs = b.path("by_pack").associate { it.path("pack_factor").asInt() to it.path("achieved").asLong() }.filterValues { it > 0 }
            assertEquals(expectedByPack.filterKeys { it.first == id }.mapKeys { it.key.second }, packs, "brand $id by pack size")
            assertEquals(b.path("volume_achieved").asLong(), b.path("by_pack").sumOf { it.path("achieved").asLong() }, "brand $id: the pack sizes add up to the brand volume")
        }
        hop("kpi brands carry the sold brands", "brands" to kpi.path("brands").map { it.path("brand_id").asLong() }.toSet().containsAll(brandOf.values.toSet()), "expected" to true)

        // A rebuild of the same day that changes no figure (a late geo fix or a check-in dirties it the same way) keeps the rows' updated_at: no churn of as_of
        // and of the bundle version (review, D-TKPI-10).
        fun stamps() = rows("SELECT sku_id, updated_at::text AS u FROM dw.agg_daily_route_sku WHERE route_id = $gulRouteId AND business_date = DATE '$day' ORDER BY sku_id")
        val before = stamps(); val versionBefore = bundleFor(next.toString()).path("meta").path("bundle_version").asText()
        runWorker()
        note("a figure-neutral rebuild keeps updated_at and the bundle version", "stamps equal ${before == stamps()}")
        assertEquals(before, stamps()); assertEquals(versionBefore, bundleFor(next.toString()).path("meta").path("bundle_version").asText())
    }
}
