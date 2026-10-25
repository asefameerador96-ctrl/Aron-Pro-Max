package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.analytics.AggregationWorker
import com.aktcl.aron.backend.analytics.Aggregator
import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.server.testing.TestApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wave 4 data-flow trace (docs/34 s4.4, D-SEED-02): one selling day of sr1001 on the Gulshan/Banani dev geography, from the
 * seed to the dashboard, through the production wiring on a database loaded like the Azure dev database (every
 * db/seed file except 04_*, then the smoke outlets; [ApiWorld] `devProfile`; not loaded: devseed-app-release.sql; the harness lifts rate limits and MFA).
 *
 *   seed facts -> login -> bundle -> sale entered in packets (device truth) -> sync upload (out of order, partial) ->
 *   duplicate and replay -> server count (server_totals, GET /sync/totals, tables) -> dw facts and aggregates in base units ->
 *   the national dashboard operations for a SUPERADMIN, NATIONAL, WM, DMO, TSO and BRAND caller.
 *
 * Every hop is a row of the trace written to `build/data-flow-trace.md`: the device figure and the figure of each later hop,
 * PASS when they are equal. Money, idempotency and unit conversions are asserted hard (the test fails); a dashboard figure
 * that differs is recorded as a FINDING row and the test fails at the end, naming them, so the trace never goes green over a
 * wrong number. The other division (mirror LKX, moved under its own wing) sells one smaller day on the same day, so a scope
 * filter has something to cut.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DataFlowTraceTest : DataFlowTraceSupport() {
    // ---- 1 the seed ------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun hop1TheDevSeed() {
        h("1 Seed (dev profile: db/seed without 04_*, plus the smoke outlets)")
        hop("config version `Dev database overrides` absent (the Gulshan seed applies)", "count" to sqlLong("SELECT count(*) FROM app.cfg_version WHERE summary = 'Dev database overrides (docs/24 s9.4, seed)'"), "expected" to 0L)
        hop("Gulshan/Banani routes, mask, outlets (20 seed + 1 smoke each)",
            "rows" to sql("SELECT string_agg(x, ',' ORDER BY x) FROM (SELECT r.code || ':' || r.visit_days_mask || ':' || count(o.id) AS x FROM app.route r JOIN app.outlet o ON o.route_id = r.id WHERE r.code IN ('GUL-SR-2','GUL-SR-1','BAN-SR-11') GROUP BY r.code, r.visit_days_mask) t"),
            "expected" to "BAN-SR-11:36:21,GUL-SR-1:18:24,GUL-SR-2:9:21")
        hop("Friday (bit 6) is no route's day", "sum of masks" to sqlLong("SELECT sum(visit_days_mask) FROM app.route WHERE code IN ('GUL-SR-2','GUL-SR-1','BAN-SR-11')"), "Sat..Thu" to 63L)
        val u = rows("SELECT category_code AS cat, count(*) AS n, min(base_per_pack) AS lo, max(base_per_pack) AS hi, string_agg(DISTINCT base_unit, ',') AS unit, string_agg(DISTINCT entry_unit_default, ',') AS entry FROM app.sku WHERE status = 'active' GROUP BY category_code ORDER BY 1")
        note("SKU units by category (count, base_per_pack lo-hi, base unit, entry unit)", u.joinToString(" / ") { "${it["cat"]}: ${it["n"]}, ${it["lo"]}-${it["hi"]}, ${it["unit"]}, ${it["entry"]}" })
        hop("sr1001 open primaries", "routes" to sql("SELECT string_agg(r.code, ',' ORDER BY r.code) FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id JOIN app.app_user u ON u.id = a.user_id WHERE u.username = 'sr1001' AND a.kind = 'primary' AND a.valid_to IS NULL"), "expected" to "BAN-SR-11,GUL-SR-1,GUL-SR-2")
    }

    // ---- 2 login and bundle ----------------------------------------------------------------------------------------

    @Test
    @Order(2)
    fun hop2LoginAndBundle() {
        h("2 Login and bundle (sr1001, seeded dev phone)")
        prepareSrDay()
        val token = sr.token
        val claims = json.readTree(Base64.getUrlDecoder().decode(token.split('.')[1]))
        note("access token claims (names only)", claims.fieldNames().asSequence().toList().sorted())
        note("scope claims in the token", claims.fields().asSequence().filter { it.key in setOf("role", "geo", "brands", "modules", "scope_version", "reach") }.joinToString(", ") { "${it.key}=${it.value.toString().take(80)}" })
        val meta = bundle.path("meta")
        note("bundle meta", "bundle_version=${meta.path("bundle_version").asText()} config_version=${meta.path("config_version").asText()}")
        hop("routes in the trace day's bundle", "planned today" to bundle.path("routes").filter { it.path("planned_today").asBoolean() }.joinToString(",") { it.path("route").path("code").asText("?") }, "expected" to "GUL-SR-2")
        hop("outlets on the planned route with a pin", "with pin" to route.path("outlets").count { it.path("lat").isNumber && it.path("lng").isNumber }, "outlets" to route.path("outlets").size(), "expected" to 21)
        hop("plan SKUs with an outlet price today", "count" to skus.size, "expected >= 39" to skus.size.coerceAtLeast(39))
        note("pack sizes in the bundle (category: entry unit x pack)", skus.values.groupBy { it.category }.entries.joinToString(" / ") { (c, l) -> "$c: " + l.map { "${it.entryUnit}x${it.perPack}" }.distinct().sorted().joinToString("|") })
        note("memo numbers: bind_ordinal x block + n", "ordinal ${bundle.path("user").path("bind_ordinal").asInt()}, block ${bundle.path("user").path("memo_seq_block_size").asInt()}, first number ${sr.seq}")
        hop("first bundle of the day logged the route-day in", "route_day.logged_in_at set" to (sql("SELECT (logged_in_at IS NOT NULL)::text FROM app.route_day WHERE route_id = $gulRouteId AND business_date = DATE '$day'") ?: "no row"), "expected" to "true")
    }

    // ---- 3 the sale on the phone -----------------------------------------------------------------------------------

    @Test
    @Order(3)
    fun hop3SaleInPacketsOnThePhone() {
        h("3 The day on the phone (device truth)")
        val cig20 = pick("cigarette", 20); val cig10 = pick("cigarette", 10); val bidi = pick("bidi"); val lighter = pick("lighter"); val match = pick("match")
        note("SKUs picked", listOf(cig20, cig10, bidi, lighter, match).joinToString("; ") { "${it.code} (${it.category}) id ${it.id}: ${it.entryUnit} x ${it.perPack} ${it.baseUnit}, price ${it.price}/${it.perBase} mtk" })
        sr.memos += Memo(outletByCode("GUL2-001"), "GUL2-001", listOf(Line(cig20, 3), Line(cig10, 2), Line(bidi, 1), Line(lighter, 2), Line(match, 5)), paidShare = 100, minute = 0)
        sr.memos += Memo(outletByCode("GUL2-002"), "GUL2-002", listOf(Line(cig20, 5), Line(bidi, 2)), paidShare = 40, minute = 40)
        sr.memos += Memo(outletByCode("GUL2-003"), "GUL2-003", listOf(Line(cig10, 4)), paidShare = 100, voided = true, minute = 80)
        sr.memos += Memo(outletByCode("GUL2-004"), "GUL2-004", emptyList(), paidShare = 0, minute = 120) // outlet closed: a visit, no sale
        for (m in sr.memos) for ((i, l) in m.lines.withIndex()) note("${m.outletCode} line ${i + 1}", "${l.sku.code}: ${l.qtyEntered} ${l.sku.entryUnit} x pack_factor ${l.packFactor} = qty_base ${l.qtyBase} ${l.sku.baseUnit}; gross ${l.gross} mtk")
        for (m in sr.memos) note("${m.outletCode} memo", "gross ${m.gross} paid ${m.paid} due ${m.due} mtk${if (m.voided) " (voided later)" else ""}")
        val active = sr.memos.filter { it.lines.isNotEmpty() && !it.voided }
        hop("device model: active memos (voided and zero-sale visits excluded)", "memos" to active.size, "expected" to 2)
        note("device totals", "gross ${active.sumOf { it.gross }} mtk, paid ${active.sumOf { it.paid }}, due ${active.sumOf { it.due }}")
    }

    // ---- 4 sync upload, out of order, duplicate, replay ------------------------------------------------------------

    private lateinit var allRecords: List<ObjectNode>
    private var dueCollection: ObjectNode? = null
    private lateinit var firstBatchUuid: String
    private lateinit var secondBatch: Answer

    @Test
    @Order(4)
    fun hop4SyncUploadDuplicateAndReplay() {
        h("4 Sync upload: out of order, partial, duplicate, replay")
        val recs = mutableListOf<ObjectNode>()
        sr.memos.forEachIndexed { i, m ->
            val (r, a) = radiusOf(m.outlet)
            recs += recordsOf(sr, m, gulPins.getValue(m.outlet), r, a, i + 1)
        }
        // A collection of 3,000 mtk... against the credit memo (GUL2-002): partial settlement.
        val credit = sr.memos[1]
        val collected = minOf(credit.due, 2_000L)
        val cu = uuid()
        val pin = gulPins.getValue(credit.outlet)
        recs += envelope(sr, "due_collection", cu, cu, 0, 100, json.readTree(
            """{"outlet_id":${credit.outlet},"against_memo_client_uuid":"${credit.memo}","against_memo_no":"${recs.first { it.path("client_uuid").asText() == credit.memo }.path("payload").path("memo_no").asText()}",
                "against_memo_business_date":"$day","amount_mtk":$collected,"is_full_settlement":${collected >= credit.due},"outstanding_before_mtk":${credit.due},"payment_mode":"cash"}"""))
        note("due collection", "$collected mtk against ${credit.outletCode} (due ${credit.due})")
        allRecords = recs
        recs.groupingBy { it.path("type").asText() }.eachCount().forEach { (t, n) -> sr.counts[t] = n }
        note("device counts per type", sr.counts)

        // (a) partial and out of order: lines of two memos, the memo of the credit sale and a visit_close, children before parents.
        val partial = recs.filter { it.path("type").asText() == "memo_line" }.take(3) + listOf(recs.first { it.path("client_uuid").asText() == credit.memo }) + listOf(recs.first { it.path("type").asText() == "visit_close" })
        firstBatchUuid = uuid()
        val a1 = send(sr, partial.shuffled(java.util.Random(3)), firstBatchUuid)
        note("batch 1 (5 rows, children first): HTTP ${a1.status}", acks(a1).groupingBy { it }.eachCount())
        note("batch 1 ack detail", detail(a1))
        // (b) the whole day shuffled: the server walks the array in order, so a child ahead of its parent is `rejected(parent_missing)`, retryable.
        val bu2 = uuid()
        secondBatch = send(sr, recs.shuffled(java.util.Random(7)), bu2)
        note("batch 2 (whole day, shuffled): HTTP ${secondBatch.status}", acks(secondBatch).groupingBy { it }.eachCount())
        note("batch 2 ack detail", detail(secondBatch))
        val retryable = secondBatch.body!!.path("acks").filter { it.path("status").asText() == "rejected" }
        hop("batch 2 rejected rows are all retryable parent_missing", "codes" to retryable.map { it.path("code").asText() + "/" + it.path("retryable").asText() }.distinct(), "expected" to listOf("parent_missing/true"))
        // (c) the phone resends everything not accepted, in order, under a new batch_uuid; rows already stored come back duplicate.
        val a3 = send(sr, recs, uuid())
        note("batch 3 (whole day in order): HTTP ${a3.status}", acks(a3).groupingBy { it }.eachCount())
        hop("batch 3 leaves no rejected or quarantined row", "bad rows" to acks(a3).count { it == "rejected" || it == "quarantined" }, "expected" to 0)
        // (d) and once more: a lost acknowledgement makes the phone send the whole day again under a new batch_uuid.
        val a4 = send(sr, recs, uuid())
        hop("batch 4 (whole day again, new batch_uuid: a lost-ack resend): every row duplicate", "statuses" to acks(a4).distinct(), "expected" to listOf("duplicate"))
        // (e) the same batch_uuid again: batch 1 (lost response) and batch 2 are replays that answer the same acks.
        val a1b = send(sr, partial.shuffled(java.util.Random(3)), firstBatchUuid)
        hop("batch 1 resent with its batch_uuid: replayed flag", "replayed" to a1b.body?.path("replayed")?.asText(), "expected" to "true")
        hop("batch 1 replay answers the original acks", "same" to (a1b.body?.path("acks") == a1.body?.path("acks")), "expected" to true)
        val a2b = send(sr, recs.shuffled(java.util.Random(7)), bu2)
        hop("batch 2 resent with its batch_uuid: replayed, same acks", "replayed+same" to ((a2b.body?.path("replayed")?.asText() ?: "?") + "/" + (a2b.body?.path("acks") == secondBatch.body?.path("acks"))), "expected" to "true/true")

        // Server truth: rows per table for the user and date, by identity.
        fun count(table: String, extra: String = "") = sqlLong("SELECT count(*) FROM $table t JOIN app.app_user u ON u.id = t.user_id WHERE u.username = 'sr1001' AND t.business_date = DATE '$day' $extra")
        val dev = sr.counts
        hop("visits: device vs app.visit", "device" to dev["visit"], "server" to count("app.visit"))
        hop("memos: device vs app.memo", "device" to dev["memo"], "server" to count("app.memo"))
        hop("memo lines: device vs app.memo_line", "device" to dev["memo_line"], "server" to count("app.memo_line"))
        hop("due collections: device vs app.due_collection", "device" to dev["due_collection"], "server" to count("app.due_collection"))
        hop("voids: device vs app.memo_void", "device" to dev["memo_void"], "server" to count("app.memo_void"))
        hop("distinct memo_no", "distinct" to sqlLong("SELECT count(DISTINCT memo_no) FROM app.memo t JOIN app.app_user u ON u.id = t.user_id WHERE u.username = 'sr1001' AND t.business_date = DATE '$day'"), "memos" to dev["memo"])
        hop("ingest registry holds each client_uuid once", "registry rows" to sqlLong("SELECT count(*) FROM app.ingest_registry r WHERE r.client_uuid = ANY(ARRAY[${recs.joinToString(",") { "'" + it.path("client_uuid").asText() + "'::uuid" }}])"),
            "uuids" to recs.size.toLong(), hard = false)
    }

    // ---- 5 the server's own count ----------------------------------------------------------------------------------

    @Test
    @Order(5)
    fun hop5ServerTotalsEqualTheDevice() {
        h("5 Server count: server_totals, GET /v1/sync/totals, tables (device count = server count)")
        val active = sr.memos.filter { it.lines.isNotEmpty() && !it.voided }
        val expGross = active.sumOf { it.gross }
        val expPaid = active.sumOf { it.paid }
        val expDue = active.sumOf { it.due }
        val soldBase = sortedMapOf<String, Long>()
        for (m in active) for (l in m.lines) soldBase.merge(l.sku.id.toString(), l.qtyBase, Long::plus)
        val resp = send(sr, allRecords, uuid())
        val totals = resp.body!!.path("server_totals").single { it.path("business_date").asText() == day }
        val money = totals.path("money")
        hop("active memos", "device" to active.size, "server_totals" to money.path("active_memo_count").asInt())
        hop("gross mtk", "device" to expGross, "server_totals" to money.path("gross_mtk").asLong())
        hop("net mtk", "device" to expGross, "server_totals" to money.path("net_mtk").asLong())
        hop("paid mtk (sale payments)", "device" to expPaid, "server_totals" to money.path("paid_mtk").asLong())
        hop("due mtk", "device" to expDue, "server_totals" to money.path("due_mtk").asLong())
        hop("due collected mtk", "device" to 2000L.coerceAtMost(sr.memos[1].due), "server_totals" to money.path("due_collected_mtk").asLong())
        hop("sold qty (base units) by sku, sticks/pieces/dozens", "device" to soldBase.toString(), "server_totals" to money.path("sold_qty_base_by_sku").fields().asSequence().associate { it.key to it.value.asLong() }.toSortedMap().toString())
        val byCat = money.path("net_by_category_mtk").fields().asSequence().associate { it.key to it.value.asLong() }.toSortedMap()
        val expCat = sortedMapOf<String, Long>(); for (m in active) for (l in m.lines) expCat.merge(l.sku.category, l.gross, Long::plus)
        hop("net mtk by category", "device" to expCat.toString(), "server_totals" to byCat.toString())
        val byType = totals.path("by_type")
        for ((t, n) in sr.counts) hop("accepted $t", "device" to n, "server_totals" to byType.path(t).path("accepted").asInt())
        hop("rejected + quarantined, all types", "server" to byType.fields().asSequence().sumOf { it.value.path("rejected").asInt() + it.value.path("quarantined").asInt() }, "expected" to 0)
        val g = get("/v1/sync/totals?business_date=$day", sr.token, sr.deviceUuid)
        hop("GET /v1/sync/totals answers 200", "status" to g.status.value, "expected" to 200)
        hop("GET /v1/sync/totals money equals the batch answer", "same" to (jsonOf(g).path("totals").path("money") == money), "expected" to true)
        sr.truthGross = expGross; sr.truthSold = soldBase
    }

    // ---- 6 data warehouse -----------------------------------------------------------------------------------------

    @Test
    @Order(6)
    fun hop6WarehouseViewsAndAggregatesInBaseUnits() {
        h("6 Warehouse: dw facts, views and aggregates (base units)")
        runWorker()
        val active = sr.memos.filter { it.lines.isNotEmpty() && !it.voided }
        hop("dw.fact_memo active rows", "dw" to sqlLong("SELECT count(*) FROM dw.fact_memo WHERE business_date = DATE '$day' AND route_id = $gulRouteId AND status = 'active' AND line_count > 0"), "device" to active.size.toLong())
        hop("dw.fact_memo voided rows", "dw" to sqlLong("SELECT count(*) FROM dw.fact_memo WHERE business_date = DATE '$day' AND route_id = $gulRouteId AND status <> 'active'"), "device" to 1L)
        hop("dw.fact_visit sr_call rows", "dw" to sqlLong("SELECT count(*) FROM dw.fact_visit WHERE business_date = DATE '$day' AND route_id = $gulRouteId AND visit_kind = 'sr_call' AND NOT voided"), "device" to sr.memos.size.toLong())
        val ar = rows("SELECT * FROM dw.agg_daily_route WHERE business_date = DATE '$day' AND route_id = $gulRouteId").singleOrNull() ?: error("no dw.agg_daily_route row for GUL-SR-2")
        hop("agg_daily_route.active_memo_count", "dw" to ar["active_memo_count"], "device" to active.size)
        hop("agg_daily_route.gross_mtk", "dw" to ar["gross_mtk"], "device" to sr.truthGross)
        hop("agg_daily_route.net_mtk", "dw" to ar["net_mtk"], "device" to sr.truthGross)
        hop("agg_daily_route.paid_mtk", "dw" to ar["paid_mtk"], "device" to active.sumOf { it.paid })
        hop("agg_daily_route.due_mtk", "dw" to ar["due_mtk"], "device" to active.sumOf { it.due })
        hop("agg_daily_route.dues_collected_mtk", "dw" to ar["dues_collected_mtk"], "device" to minOf(2000L, sr.memos[1].due))
        hop("agg_daily_route.successful_calls (outlets with a sale)", "dw" to ar["successful_calls"], "device" to active.size)
        hop("agg_daily_route.visits (sr calls)", "dw" to ar["visits"], "device" to sr.memos.size)
        hop("agg_daily_route.geo_valid_visits", "dw" to ar["geo_valid_visits"], "device" to sr.memos.size, hard = false)
        hop("agg_daily_route.target_outlets", "dw" to ar["target_outlets"], "bundle outlets" to route.path("outlets").size(), hard = false)
        note("agg_daily_route (selected)", listOf("day_state", "logged_in_at", "sales_submitted_at", "final_submitted_at", "target_outlets", "visited_outlets").joinToString(", ") { "$it=${ar[it]}" })
        val sku = rows("SELECT sku_id, sold_qty_base, gross_mtk, memo_count FROM dw.agg_daily_route_sku WHERE business_date = DATE '$day' AND route_id = $gulRouteId ORDER BY sku_id")
        hop("agg_daily_route_sku.sold_qty_base by sku (base units)", "dw" to sku.associate { it["sku_id"].toString() to (it["sold_qty_base"] as Number).toLong() }.toSortedMap().toString(), "device" to sr.truthSold.toString())
        val cat = rows("SELECT p.category_code AS cat, p.base_unit AS unit, sum(a.sold_qty_base) AS qty, sum(a.gross_mtk) AS gross FROM dw.v_daily_sku a JOIN dw.dim_product p ON p.sku_id = a.sku_id WHERE a.business_date = DATE '$day' AND a.route_id = $gulRouteId GROUP BY 1, 2 ORDER BY 1")
        val expCat = sortedMapOf<String, Long>(); for (m in active) for (l in m.lines) expCat.merge(l.sku.category, l.qtyBase, Long::plus)
        hop("dw.v_daily_sku summed by category (sticks, pieces, dozens)", "dw" to cat.associate { it["cat"].toString() to (it["qty"] as Number).toLong() }.toSortedMap().toString(), "device" to expCat.toString())
        note("category -> base unit", cat.joinToString(", ") { "${it["cat"]}=${it["unit"]}" })
        hop("dw.agg_daily_outlet_sku base units, checksum only (mixed units)", "dw" to sqlLong("SELECT coalesce(sum(sold_qty_base), 0) FROM dw.agg_daily_outlet_sku WHERE business_date = DATE '$day' AND route_id = $gulRouteId"), "device" to sr.truthSold.values.sum())
        hop("dw.agg_daily_route_variant base units, checksum only (mixed units)", "dw" to sqlLong("SELECT coalesce(sum(sold_qty_base), 0) FROM dw.agg_daily_route_variant WHERE business_date = DATE '$day' AND route_id = $gulRouteId"), "device" to sr.truthSold.values.sum())
        hop("dw.agg_daily_outlet net_mtk summed over the route (equals gross: no discounts)", "dw" to sqlLong("SELECT coalesce(sum(net_mtk), 0) FROM dw.agg_daily_outlet WHERE business_date = DATE '$day' AND route_id = $gulRouteId"), "device" to sr.truthGross)
        hop("dw.v_daily_route and v_daily_sr agree with the table", "v_daily_route.gross" to sqlLong("SELECT gross_mtk FROM dw.v_daily_route WHERE business_date = DATE '$day' AND route_id = $gulRouteId"),
            "v_daily_sr.gross" to sqlLong("SELECT coalesce(sum(s.gross_mtk), 0) FROM dw.v_daily_sr s JOIN app.app_user u ON u.id = s.user_id WHERE s.business_date = DATE '$day' AND u.username = 'sr1001'"), "device" to sr.truthGross)
    }

    // ---- 7 the other division sells a smaller day ---------------------------------------------------------------------

    private lateinit var lkxMemos: List<Memo>
    private val lkxSold = sortedMapOf<String, Long>()   // category -> base units
    private val lkxSoldChannel = sortedMapOf<String, Long>() // outlet channel -> cigarette base units
    private var lkxTargetOutlets = 0

    @Test
    @Order(7)
    fun hop7TheOtherDivisionSellsToo() {
        h("7 Other division (LKX, its own wing) sells on the same day, so a scope filter has something to cut")
        val ph = world.phoneFor(ApiWorld.OTHER, 50, day)
        db { hh ->
            // The same enrolment the dev seed job gives the smoke device, with the harness key so batch proofs verify.
            hh.createUpdate("UPDATE app.device SET public_key_jwk = CAST(:jwk AS jsonb) WHERE device_uuid = CAST(:d AS uuid)").bind("jwk", publicJwk(world.phoneKey)).bind("d", ph.deviceUuid).execute()
            hh.execute(
                "WITH tok AS (INSERT INTO app.enrolment_token (token_sha256, token_prefix, flavour, lockdown_level, max_uses, used_count, release_id, expires_at, created_by, note) " +
                    "SELECT sha256(convert_to(gen_random_uuid()::text, 'UTF8')), 'smoke0', 'sr', 'dev', 1, 1, (SELECT id FROM app.app_release WHERE flavour = 'sr' AND status = 'published' ORDER BY version_code DESC LIMIT 1), " +
                    "now() + interval '1 hour', (SELECT id FROM app.app_user WHERE username = 'admin1001'), 'qa trace: LKX phone enrolled' RETURNING id) " +
                    "UPDATE app.device d SET enrolment_token_id = tok.id FROM tok WHERE d.device_uuid = '${ph.deviceUuid}'",
            )
        }
        val token = login(ph.username, "app_sr", ph.deviceUuid)
        val b = fetchBundle(token, ph.deviceUuid)
        val r = b.path("routes").first { it.path("planned_today").asBoolean() }
        // Every route the bundle plans that day counts toward the target (the Daily route alone on a Saturday; the 3F route too on its days).
        lkxTargetOutlets = b.path("routes").filter { it.path("planned_today").asBoolean() }.sumOf { it.path("outlets").size() }
        val ls = skusOf(b, r)
        val devMeta = b.path("meta")
        lkx = Device(ph.username, ph.deviceUuid, ph.deviceId, r.path("route_id").asLong(), devMeta.path("bundle_version").asText(), devMeta.path("config_version").asLong(), token)
        lkx.seq = b.path("user").path("bind_ordinal").asInt() * b.path("user").path("memo_seq_block_size").asInt() + 1
        fun outlet(i: Int) = r.path("outlets")[i]
        val c20 = ls.values.filter { it.category == "cigarette" && it.perPack == 20 }.minByOrNull { it.id }!!
        val bd = ls.values.filter { it.category == "bidi" }.minByOrNull { it.id }!!
        val mt = ls.values.filter { it.category == "match" }.minByOrNull { it.id }!!
        lkxMemos = listOf(
            Memo(outlet(0).path("outlet_id").asLong(), outlet(0).path("code").asText(), listOf(Line(c20, 2), Line(bd, 1)), 100, minute = 0),
            Memo(outlet(1).path("outlet_id").asLong(), outlet(1).path("code").asText(), listOf(Line(mt, 3)), 100, minute = 40),
        )
        lkx.memos += lkxMemos
        val recs = mutableListOf<ObjectNode>()
        lkxMemos.forEachIndexed { i, m ->
            val o = r.path("outlets").single { it.path("outlet_id").asLong() == m.outlet }
            recs += recordsOf(lkx, m, o.path("lat").asDouble() to o.path("lng").asDouble(), o.path("radius_m").asInt(100), o.path("max_accuracy_m").asInt(100), i + 1)
        }
        recs.groupingBy { it.path("type").asText() }.eachCount().forEach { (t, n) -> lkx.counts[t] = n }
        val a = send(lkx, recs, uuid())
        hop("LKX day uploaded in order", "statuses" to acks(a).distinct(), "expected" to listOf("accepted"))
        for (m in lkxMemos) for (l in m.lines) lkxSold.merge(l.sku.category, l.qtyBase, Long::plus)
        for (m in lkxMemos) { val ch = sql("SELECT channel FROM app.outlet WHERE id = ${m.outlet}")!!; for (l in m.lines) if (l.sku.category == "cigarette") lkxSoldChannel.merge(ch, l.qtyBase, Long::plus) }
        note("LKX device truth (base units by category)", lkxSold)
        note("LKX gross mtk", lkxMemos.sumOf { it.gross })
        runWorker()
    }

    // ---- 8 national dashboard operations per caller -------------------------------------------------------------------

    private fun gulSold(): Map<String, Long> {
        val m = sortedMapOf<String, Long>()
        for (x in sr.memos.filter { it.lines.isNotEmpty() && !it.voided }) for (l in x.lines) m.merge(l.sku.category, l.qtyBase, Long::plus)
        return m
    }

    private fun cards(o: JsonNode): Map<String, Long> = o.path("cards").associate { it.path("category_code").asText() to it.path("total_sales").asLong() }.toSortedMap()

    private val callers = listOf("superadmin1001" to "SUPERADMIN", "admin1001" to "ADMIN", "qanational1001" to "NATIONAL", "qawm1001" to "WM", "dmo1001" to "DMO", "tso1001" to "TSO")

    @Test
    @Order(8)
    fun hop8NationalDashboardOperationsByCaller() {
        h("8 National dashboard (GET/POST /v1/dashboards/national/*): figures per caller, scope resolved on the server")
        val gul = gulSold(); val all = (gul.keys + lkxSold.keys).associateWith { (gul[it] ?: 0L) + (lkxSold[it] ?: 0L) }.toSortedMap()
        val body = """{"date":"$day"}"""
        val tokens = callers.associate { (u, _) -> u to login(u, "web", null) }
        for ((u, role) in callers) {
            val national = role in setOf("SUPERADMIN", "ADMIN", "NATIONAL")
            val t = tokens.getValue(u)
            note("$role web token claim names", json.readTree(Base64.getUrlDecoder().decode(t.split('.')[1])).fieldNames().asSequence().toList().sorted())
            if (role == "TSO") hop("TSO web token carries the scope claims of docs/34 s1 (geo)", "claims geo/brands/modules" to json.readTree(Base64.getUrlDecoder().decode(t.split('.')[1])).fieldNames().asSequence().any { it in setOf("geo", "brands", "modules", "mods") }, "expected" to true, hard = false)
            val expectSold = if (national) all else gul
            fun op(path: String, b: String = body): JsonNode { val r = postJson("/v1/dashboards/national/$path", t, b); assertEquals(HttpStatusCode.OK, r.status, "$u $path: ${runBlocking { r.bodyAsText() }.take(200)}"); return jsonOf(r) }
            val sales = op("sales")
            hop("$role sales cards: total_sales per category (base units)", "dashboard" to cards(sales), "device truth" to expectSold.toSortedMap(), hard = false)
            hop("$role sales cards: base_unit per category", "units" to sales.path("cards").associate { it.path("category_code").asText() to it.path("base_unit").asText() }.toSortedMap(), "expected" to sortedMapOf("bidi" to "stick", "cigarette" to "stick", "lighter" to "piece", "match" to "dozen"), hard = false)
            note("$role sales card detail", sales.path("cards").joinToString("; ") { "${it.path("category_code").asText()} target/day ${it.path("daily_target").asText("-")} remaining ${it.path("remaining").asText("-")} achv_bp ${it.path("achievement_pct_bp").asText("-")} routes ${it.path("target_routes").asInt()}" } + "; working_days ${sales.path("working_days").asInt()}; till_time ${sales.path("till_time").asBoolean()}")
            val cpr = op("cpr")
            val expTarget = if (national) route.path("outlets").size() + lkxTargetOutlets else route.path("outlets").size()
            val expCalls = if (national) sr.memos.count { it.lines.isNotEmpty() && !it.voided } + lkxMemos.size else sr.memos.count { it.lines.isNotEmpty() && !it.voided }
            hop("$role CPR target outlets / successful calls", "dashboard" to "${cpr.path("target_outlets").asInt()}/${cpr.path("successful_calls").asInt()}", "device truth" to "$expTarget/$expCalls", hard = false)
            note("$role CPR percent (basis points)", cpr.path("cpr_pct_bp").asText())
            val cc = op("channel-calls")
            hop("$role channel calls total", "dashboard" to cc.path("total").asInt(), "successful calls" to expCalls, hard = false)
            note("$role channel calls", cc.path("channels").joinToString(", ") { "${it.path("channel").asText()}=${it.path("value").asLong()}" })
            val cs = op("channel-std")
            hop("$role channel STD total = cigarette sticks", "dashboard" to cs.path("total").asLong(), "cigarette card" to (expectSold["cigarette"] ?: 0L), hard = false)
            note("$role channel STD by channel (${cs.path("unit").asText()})", cs.path("channels").joinToString(", ") { "${it.path("channel").asText()}=${it.path("value").asLong()}" })
            val ls = op("login-submit")
            note("$role login/submit", "target ${ls.path("target_routes").asInt()}, login ${ls.path("successful_login").asInt()} (${ls.path("login_pct_bp").asText()} bp), submitted ${ls.path("submitted").asInt()}")
            hop("$role successful login before any check-in (brief s3.4: a login is a check-in AND a download; the check-in comes in hop 11)", "dashboard" to ls.path("successful_login").asInt(), "device truth" to 0, hard = false)
            hop("$role submitted routes (nobody has submitted)", "dashboard" to ls.path("submitted").asInt(), "expected" to 0, hard = false)
            val fs = op("final-submit")
            note("$role final submit", "zones ${fs.path("total_zones").asInt()}, service zones ${fs.path("service_zones").asInt()}, submitted ${fs.path("final_submitted").asInt()}, remaining ${fs.path("remaining").asInt()}")
            val gf = op("geo-fencing")
            val expCallsAll = if (national) sr.memos.size + lkxMemos.size else sr.memos.size
            hop("$role geo-fencing total calls / validated", "dashboard" to "${gf.path("total_calls").asInt()}/${gf.path("geo_validated").asInt()}", "device truth" to "$expCallsAll/$expCallsAll", hard = false)
            val pins = op("ff-geo/pins", """{"filter":{"date":"$day"}}""")
            note("$role FF Geo pins", "${pins.path("pins").size()} pins, counts ${pins.path("counts")}, routes without position ${pins.path("routes_without_position").asInt()}")
            val freshness = jsonOf(get("/v1/dashboards/national/freshness", t))
            note("$role freshness", freshness.toString().take(300))
            val fo = jsonOf(get("/v1/dashboards/national/filter-options", t))
            note("$role filter-options counts", fo.fields().asSequence().joinToString(", ") { "${it.key}=${if (it.value.isArray) it.value.size() else it.value}" })
        }
    }

    // ---- 9 scope: the client sends no scope; widening is cut -----------------------------------------------------------

    @Test
    @Order(9)
    fun hop9ScopeIsTheServersEvenWhenTheClientAsksForMore() {
        h("9 Scope rules (server side): widening filters, wrong-level callers, BRAND, field roles")
        val gul = gulSold()
        val lkxDivision = sqlLong("SELECT id FROM app.division WHERE code = 'D-LKX'")
        val lkxWing = sqlLong("SELECT id FROM app.wing WHERE code = 'W-LKX'")
        val lkxZone = sqlLong("SELECT id FROM app.zone WHERE code = 'Z-LKX'")
        val lkxTerritory = sqlLong("SELECT id FROM app.territory WHERE code = 'T-LKX'")
        val widen = """{"date":"$day","wing_ids":[$lkxWing],"division_ids":[$lkxDivision],"territory_ids":[$lkxTerritory],"zone_ids":[$lkxZone]}"""
        for ((u, role) in callers.filter { it.second in setOf("WM", "DMO", "TSO") }) {
            val t = login(u, "web", null)
            val r = postJson("/v1/dashboards/national/sales", t, widen)
            val body = jsonOf(r)
            val answer = "${r.status.value} ${if (r.status.value == 200) cards(body).values.sum().toString() else body.path("code").asText()}"
            hop("$role asks for the other division's ids (cut to the reach, or refused)", "answer (status, units sold)" to answer, "ok" to answer.let { if (it in setOf("200 0", "403 ERR_OUT_OF_SCOPE", "403 ERR_FORBIDDEN")) it else "?" }, hard = false)
        }
        for ((u, role) in callers.filter { it.second in setOf("WM", "DMO", "TSO") }) {
            val t = login(u, "web", null)
            val own = cards(jsonOf(postJson("/v1/dashboards/national/sales", t, """{"date":"$day"}""")))
            hop("$role own figures equal the Gulshan day only", "dashboard" to own, "device truth" to gul.toSortedMap(), hard = false)
            val lkxRoute = sqlLong("SELECT id FROM app.route WHERE code LIKE 'LKX-SR-%' ORDER BY id LIMIT 1")
            val lkxOutlet = lkx.memos.first().outlet
            for (p in listOf("/v1/dashboards/route-map/$lkxRoute?date=$day", "/v1/dashboards/national/ff-geo/routes/$lkxRoute?date=$day", "/v1/dashboards/sale-position/$lkxOutlet?date=$day")) {
                hop("$role opens the other division's ${p.substringAfter("/v1/dashboards/").substringBefore('?')}", "status" to get(p, t).status.value, "expected" to 404, hard = false)
            }
        }
        // BRAND and the field roles.
        val brandToken = login("qabrand1001", "web", null)
        for (path in listOf("sales", "cpr", "channel-std", "breakdown")) {
            val r = postJson("/v1/dashboards/national/$path", brandToken, if (path == "breakdown") """{"filter":{"date":"$day"},"measure":"std","dimension":"brand"}""" else """{"date":"$day"}""")
            note("BRAND qabrand1001 POST national/$path", "${r.status.value} ${jsonOf(r).path("code").asText("")}")
        }
        note("BRAND grant rows", sql("SELECT string_agg(user_id || ':' || brand_id, ',') FROM app.user_brand_scope") + " user " + sql("SELECT id FROM app.app_user WHERE username = 'qabrand1001'"))
        postJson("/v1/dashboards/national/sales", brandToken, """{"date":"$day"}""").let { note("BRAND sales answer", runBlocking { it.bodyAsText() }.take(300)) }
        hop("BRAND user can read the product cards of its brands (docs/34 s1, level 4)", "sales status" to postJson("/v1/dashboards/national/sales", brandToken, """{"date":"$day"}""").status.value, "expected" to 200, hard = false)
        // The figures of a BRAND user: only the SKUs of its brand, in base units, from both divisions (docs/34 s1 level 4).
        val soldLines = (sr.memos + lkxMemos).filter { it.lines.isNotEmpty() && !it.voided }.flatMap { it.lines }
        val brandOfSku = soldLines.map { it.sku.id }.distinct().associateWith { sqlLong("SELECT brand_id FROM dw.dim_product WHERE sku_id = $it") }
        val ownBrand = brandOfSku.getValue(sr.memos[0].lines[0].sku.id)
        db { hd ->
            hd.execute("DELETE FROM app.user_brand_scope WHERE user_id = (SELECT id FROM app.app_user WHERE username = 'qabrand1001')")
            hd.execute("INSERT INTO app.user_brand_scope (user_id, brand_id) SELECT id, $ownBrand FROM app.app_user WHERE username = 'qabrand1001'")
        }
        val brandSold = sortedMapOf<String, Long>(); for (l in soldLines) if (brandOfSku.getValue(l.sku.id) == ownBrand) brandSold.merge(l.sku.category, l.qtyBase, Long::plus)
        val allSold = sortedMapOf<String, Long>(); for (l in soldLines) allSold.merge(l.sku.category, l.qtyBase, Long::plus)
        hop("the BRAND user's brand is a strict part of the day (so the filter has something to cut)", "brand slice differs from the national day" to (brandSold != allSold), "expected" to true)
        val brandTok = login("qabrand1001", "web", null)
        val bSales = jsonOf(postJson("/v1/dashboards/national/sales", brandTok, """{"date":"$day"}"""))
        hop("BRAND sales cards equal the brand's slice (base units, both divisions)", "dashboard" to cards(bSales).filterValues { it != 0L }.toSortedMap(), "device truth" to brandSold, hard = false)
        val bStd = jsonOf(postJson("/v1/dashboards/national/channel-std", brandTok, """{"date":"$day"}"""))
        hop("BRAND channel STD total = the brand's cigarette sticks", "dashboard" to bStd.path("total").asLong(), "device truth" to (brandSold["cigarette"] ?: 0L), hard = false)
        val bBrandCode = sql("SELECT min(brand_code) FROM dw.dim_product WHERE brand_id = $ownBrand")
        val bOpts = jsonOf(get("/v1/dashboards/national/filter-options", brandTok))
        hop("BRAND filter-options list its own brand only", "brands" to bOpts.path("brands").map { it.path("code").asText(it.asText()) }, "expected" to listOf(bBrandCode), hard = false)
        val srT = lkx.token
        val srStatus = postJson("/v1/dashboards/national/sales", srT, """{"date":"$day"}""").status.value
        hop("SR app token on the dashboard is refused (401 or 403)", "status" to (if (srStatus in setOf(401, 403)) "refused" else srStatus.toString()), "expected" to "refused", hard = false)
        val natToken = login("qanational1001", "web", null)
        hop("NATIONAL (read-only level) cannot Final Submit", "status" to postJson("/v1/day/final-submit", natToken, """{"client_uuid":"${uuid()}","zone_id":${sqlLong("SELECT id FROM app.zone WHERE code = 'Z-LKX'")},"business_date":"$day"}""").status.value, "expected" to 403, hard = false)
        val webSr = runBlocking { app.client.post("/v1/auth/login") { contentType(ContentType.Application.Json); header("X-App-Version", ApiWorld.APP_VERSION); setBody("""{"username":"sr1001","password":"${world.password}","client":"web"}""") } }
        hop("SR on the web login", "status" to webSr.status.value, "expected" to 403, hard = false)
        note("SR web login answer", runBlocking { webSr.bodyAsText() }.take(200))
        hop("anonymous", "status" to runBlocking { app.client.post("/v1/dashboards/national/sales") { contentType(ContentType.Application.Json); setBody("{}") } }.status.value, "expected" to 401, hard = false)
    }

    // ---- 10 maps -----------------------------------------------------------------------------------------------------

    @Test
    @Order(10)
    fun hop10RouteMapAndSalePosition() {
        h("10 Route map and Sale Position pages (GUL-SR-2, GUL2-001)")
        val t = login("superadmin1001", "web", null)
        val rm = jsonOf(get("/v1/dashboards/route-map/$gulRouteId?date=$day", t))
        note("route map outlets by state", rm.path("outlets").groupBy { it.path("state").asText() }.mapValues { it.value.size })
        note("route map CPR", rm.path("cpr").toString())
        note("route map target vs achievement", rm.path("target_vs_achievement").joinToString("; ") { "${it.path("category_code").asText()} sales ${it.path("sales").asLong()} target/day ${it.path("daily_target").asText("-")} bp ${it.path("achievement_pct_bp").asText("-")}" })
        note("route map time card", rm.path("time_card").toString())
        note("route map path points", rm.path("path").size())
        val withSales = rm.path("outlets").filter { it.path("sales").size() > 0 }
        hop("route map: outlets with sales", "map" to withSales.map { it.path("code").asText() }.sorted(), "device truth" to listOf("GUL2-001", "GUL2-002"), hard = false)
        val o1 = withSales.firstOrNull { it.path("code").asText() == "GUL2-001" }
        hop("route map GUL2-001 sales (category: base units)", "map" to o1?.path("sales")?.associate { it.path("category_code").asText() to it.path("qty").asLong() }?.toSortedMap(), "device truth" to sr.memos[0].lines.groupBy { it.sku.category }.mapValues { e -> e.value.sumOf { it.qtyBase } }.toSortedMap(), hard = false)
        val sp = get("/v1/dashboards/sale-position/${sr.memos[0].outlet}?date=$day", t)
        note("sale position GUL2-001", "${sp.status.value} " + jsonOf(sp).toString().take(500))
    }


    // ---- 11 the morning and evening records: attendance, stock, sales submit, Final Submit -------------------------------

    private var zGul = 0L

    @Test
    @Order(11)
    fun hop11AttendanceStockSalesSubmitAndFinalSubmit() {
        h("11 Day records: day_open, attendance, stock issue, Sales Submit (day_submit), TSO Final Submit")
        zGul = sqlLong("SELECT id FROM app.zone WHERE code = 'Z-GUL'")
        val first = gulPins.getValue(sr.memos[0].outlet)
        val recs = mutableListOf<ObjectNode>()
        recs += envelope(sr, "day_open", uuid(), uuid(), 0, -30, json.readTree("""{"route_ids":[$gulRouteId],"online":true,"bundle_valid_for":"$day","offline_start":false}"""))
        recs += envelope(sr, "attendance_event", uuid(), uuid(), 0, -20, json.readTree("""{"kind":"check_in","fix":${fix("attendance_in", first.first, first.second)}}"""))
        val issued = sortedMapOf<String, Long>()
        for ((sku, l) in sr.memos.flatMap { it.lines }.groupBy { it.sku }) {
            val sold = l.sumOf { it.qtyEntered }
            val qty = sold + 5
            val pf = l.first().packFactor
            issued[sku.id.toString()] = qty.toLong() * pf
            recs += envelope(sr, "stock_movement", uuid(), uuid(), 0, -10, json.readTree(
                """{"kind":"issue","sku_id":${sku.id},"qty_entered":$qty,"unit_entered":"${sku.entryUnit}","pack_factor":$pf,"qty_base":${qty.toLong() * pf},"slip_printed":true}"""))
        }
        val before = sr.counts.toSortedMap()
        recs.groupingBy { it.path("type").asText() }.eachCount().forEach { (t, n) -> sr.counts.merge(t, n, Int::plus) }
        val a = send(sr, recs, uuid())
        hop("morning records accepted (day_open, attendance, stock issue)", "statuses" to acks(a).distinct(), "expected" to listOf("accepted"))
        val active = sr.memos.filter { it.lines.isNotEmpty() && !it.voided }
        val cat = sortedMapOf<String, Long>(); for (m in active) for (l in m.lines) cat.merge(l.sku.category, l.gross, Long::plus)
        val money = """{"active_memo_count":${active.size},"gross_mtk":${sr.truthGross},"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,"net_mtk":${sr.truthGross},"paid_mtk":${active.sumOf { it.paid }},"due_mtk":${active.sumOf { it.due }},
            "due_collected_mtk":${minOf(2000L, sr.memos[1].due)},"net_by_category_mtk":${json.writeValueAsString(cat)},"issued_qty_base_by_sku":${json.writeValueAsString(issued)},"sold_qty_base_by_sku":${json.writeValueAsString(sr.truthSold)}}"""
        val outstanding = sr.memos[1].due - minOf(2000L, sr.memos[1].due)
        val submitUuid = uuid()
        val submit = envelope(sr, "day_submit", submitUuid, submitUuid, 0, 200, json.readTree(
            """{"scope":"route_day","submit_cycle":1,"device_counts":${json.writeValueAsString(sr.counts)},"device_money":$money,"rejected_count":0,"quarantined_count":0,"pending_count":0,
                "submitted_with_dues":true,"dues_outstanding_mtk":$outstanding,"retailers_with_dues":1,"stock_slip_printed":true}"""))
        val pvBefore = jsonOf(get("/v1/day/final-submit/preview?zone_id=$zGul&business_date=$day", login("tso1001", "web", null)))
        hop("Final Submit preview before the Sales Submit warns: the planned GUL-SR-2 is due and not submitted", "warnings" to pvBefore.path("warnings").map { it.asText() }, "expected" to listOf("routes_not_sales_submitted"), hard = false)
        val sa = send(sr, listOf(submit), uuid())
        note("Sales Submit (day_submit) in a batch", detail(sa))
        hop("Sales Submit accepted", "statuses" to acks(sa), "expected" to listOf("accepted"))
        val dup = send(sr, listOf(submit), uuid())
        hop("Sales Submit sent again with a new batch_uuid", "statuses" to acks(dup), "expected" to listOf("duplicate"))
        runWorker()
        val state = rows("SELECT day_state, logged_in_at IS NOT NULL AS li, sales_submitted_at IS NOT NULL AS ss, final_submitted_at IS NOT NULL AS fs FROM dw.agg_daily_route WHERE business_date = DATE '$day' AND route_id = $gulRouteId").single()
        hop("route-day state after Sales Submit", "day_state" to state["day_state"], "expected" to "sales_submitted")
        hop("route-day timestamps (logged in, sales submitted, final submitted)", "flags" to "${state["li"]}/${state["ss"]}/${state["fs"]}", "expected" to "true/true/false")
        val lsAfter = jsonOf(postJson("/v1/dashboards/national/login-submit", login("tso1001", "web", null), """{"date":"$day"}"""))
        hop("TSO successful login after the check-in = routes checked in and downloaded", "dashboard" to lsAfter.path("successful_login").asInt(), "device truth" to 1, hard = false)
        // Final Submit by the TSO of the zone (online only).
        val tso = login("tso1001", "web", null)
        val pv = jsonOf(get("/v1/day/final-submit/preview?zone_id=$zGul&business_date=$day", tso))
        note("Final Submit preview (routes)", pv.path("routes").joinToString("; ") { "${it.path("route_code").asText()}: ${it.path("state").asText()}, had_data ${it.path("had_data").asBoolean()}, memos ${it.path("memo_count").asInt()}, net ${it.path("net_mtk").asLong()}" })
        note("Final Submit preview warnings", pv.path("warnings").toString())
        hop("Final Submit preview lists every active route of the zone (F-TSO-010) and is listed", "routes" to pv.path("routes").map { it.path("route_code").asText() }.sorted(), "expected" to listOf("GUL-SR-1", "GUL-SR-2"), hard = false)
        hop("Final Submit preview warnings after the Sales Submit (D-BC-PREVIEW-01: the unplanned GUL-SR-1 without data is not due, so it raises none)", "warnings" to pv.path("warnings").map { it.asText() }, "expected" to emptyList<String>(), hard = false)
        val fu = uuid()
        val fr = postJson("/v1/day/final-submit", tso, """{"client_uuid":"$fu","zone_id":$zGul,"business_date":"$day"}""")
        hop("TSO Final Submit", "status" to fr.status.value, "expected" to 200)
        val fr2 = postJson("/v1/day/final-submit", tso, """{"client_uuid":"$fu","zone_id":$zGul,"business_date":"$day"}""")
        hop("Final Submit replayed with the same client_uuid", "status" to fr2.status.value, "expected" to 200)
        val fr3 = postJson("/v1/day/final-submit", tso, """{"client_uuid":"${uuid()}","zone_id":$zGul,"business_date":"$day"}""")
        hop("second Final Submit with another client_uuid", "status" to "${fr3.status.value} ${jsonOf(fr3).path("code").asText()}", "expected" to "409 ERR_DAY_ALREADY_FINAL_SUBMITTED")
        runWorker()
        val st2 = rows("SELECT day_state, final_submitted_at IS NOT NULL AS fs FROM dw.agg_daily_route WHERE business_date = DATE '$day' AND route_id = $gulRouteId").single()
        hop("route-day after Final Submit", "state/final" to "${st2["day_state"]}/${st2["fs"]}", "expected" to "final_submitted/true")
        // The dashboard cards that watch the day.
        val sup = login("superadmin1001", "web", null)
        val body = """{"date":"$day"}"""
        val ls = jsonOf(postJson("/v1/dashboards/national/login-submit", sup, body))
        hop("Login / Submit card after Sales Submit: submitted routes (national)", "dashboard" to ls.path("submitted").asInt(), "device truth" to 1, hard = false)
        val fs = jsonOf(postJson("/v1/dashboards/national/final-submit", sup, body))
        hop("Final Submit card: zones final submitted (national)", "dashboard" to fs.path("final_submitted").asInt(), "device truth" to 1, hard = false)
        note("Final Submit card", fs.toString().take(300))
        val tsoCard = jsonOf(postJson("/v1/dashboards/national/final-submit", tso, body))
        hop("Final Submit card for the TSO: final submitted / remaining", "dashboard" to "${tsoCard.path("final_submitted").asInt()}/${tsoCard.path("remaining").asInt()}", "device truth" to "1/0", hard = false)
        val log = rows("SELECT * FROM dw.v_final_submit_log WHERE business_date = DATE '$day'")
        note("dw.v_final_submit_log rows", log.joinToString(" | ") { it.entries.joinToString(",") { e -> "${e.key}=${e.value}" }.take(400) })
        // Sales unchanged by the submit steps.
        val after = cards(jsonOf(postJson("/v1/dashboards/national/sales", sup, body)))
        hop("sales cards unchanged by attendance, stock and submit records", "dashboard" to after, "device truth" to (gulSold().keys + lkxSold.keys).associateWith { (gulSold()[it] ?: 0L) + (lkxSold[it] ?: 0L) }.toSortedMap(), hard = false)
        hop("stock: issued base units by sku in the totals", "server" to jsonOf(get("/v1/sync/totals?business_date=$day", sr.token, sr.deviceUuid)).path("totals").path("money").path("issued_qty_base_by_sku").fields().asSequence().associate { it.key to it.value.asLong() }.toSortedMap(), "device" to issued)
    }

    // ---- 12 late data after Final Submit ---------------------------------------------------------------------------------

    @Test
    @Order(12)
    fun hop12ALateSaleAfterFinalSubmitIsAcceptedAndCountedOnce() {
        h("12 Late sale after Final Submit (accept and flag, never reopens, counted once)")
        val c10 = pick("cigarette", 10)
        val m = Memo(outletByCode("GUL2-005"), "GUL2-005", listOf(Line(c10, 3)), paidShare = 100, minute = 160)
        val recs = recordsOf(sr, m, gulPins.getValue(m.outlet), 100, 100, 5)
        recs.groupingBy { it.path("type").asText() }.eachCount().forEach { (t, n) -> sr.counts.merge(t, n, Int::plus) }
        sr.memos += m
        val a = send(sr, recs, uuid())
        hop("late sale statuses", "statuses" to acks(a).distinct(), "expected" to listOf("accepted"))
        val b = send(sr, recs, uuid())
        hop("late sale again", "statuses" to acks(b).distinct(), "expected" to listOf("duplicate"))
        runWorker()
        val ar = rows("SELECT day_state, active_memo_count, gross_mtk, late_rows_after_final FROM dw.agg_daily_route WHERE business_date = DATE '$day' AND route_id = $gulRouteId").single()
        val active = sr.memos.filter { it.lines.isNotEmpty() && !it.voided }
        hop("route-day still final_submitted", "state" to ar["day_state"], "expected" to "final_submitted")
        hop("active memos including the late one", "dw" to ar["active_memo_count"], "device" to active.size)
        hop("gross mtk including the late one", "dw" to ar["gross_mtk"], "device" to active.sumOf { it.gross })
        note("late rows after final (flag)", ar["late_rows_after_final"])
        hop("late rows flagged", "late_rows_after_final > 0" to ((ar["late_rows_after_final"] as Number).toInt() > 0), "expected" to true, hard = false)
        val sup = login("superadmin1001", "web", null)
        val s2 = cards(jsonOf(postJson("/v1/dashboards/national/sales", sup, """{"date":"$day"}""")))
        val expected = sortedMapOf<String, Long>()
        for (x in sr.memos.filter { it.lines.isNotEmpty() && !it.voided } + lkxMemos) for (l in x.lines) expected.merge(l.sku.category, l.qtyBase, Long::plus)
        hop("national sales cards include the late sale once", "dashboard" to s2, "device truth" to expected, hard = false)
    }


    // ---- 13 reports ---------------------------------------------------------------------------------------------------

    @Test
    @Order(13)
    fun hop13Reports() {
        h("13 Reports that exist (GET /v1/reports, POST /v1/reports/{key}/query), SUPERADMIN, the trace day")
        val t = login("superadmin1001", "web", null)
        val defs = jsonOf(get("/v1/reports", t)).let { if (it.isArray) it else it.path("items") }
        note("reports registered for SUPERADMIN", defs.size())
        val dump = json.createObjectNode()
        for (d in defs) {
            val key = d.path("report_key").asText()
            val known = d.path("columns_known").asBoolean(true)
            val r = postJson("/v1/reports/$key/query", t, """{"period":{"date":"$day"},"output":{"format":"json","page_size":100}}""")
            val body = runBlocking { r.bodyAsText() }
            val node = runCatching { json.readTree(body) }.getOrNull()
            if (key == "by-outlet" && node != null) {
            val cols = node.path("columns").map { it.path("key").asText() }
            hop("by-outlet separates units (sold_qty_base would add sticks, pieces and dozens)", "one sold_<category>_base column per category, no mixed sold_qty_base" to (cols.containsAll(listOf("sold_cigarette_base", "sold_bidi_base", "sold_lighter_base", "sold_match_base")) && "sold_qty_base" !in cols), "expected" to true, hard = false)
        }
        note("report $key (columns_known=$known)", "HTTP ${r.status.value}, rows ${node?.path("total_rows")?.asText("-")}, totals ${node?.path("totals")?.toString()?.take(160)}")
            if (node != null) dump.set<JsonNode>(key, node)
        }
        File("build/reports-dump.json").apply { parentFile.mkdirs() }.writeText(json.writerWithDefaultPrettyPrinter().writeValueAsString(dump))
    }


    // ---- 14 memo number rule (docs/24 s7.5) -------------------------------------------------------------------------------

    /** The `yyMMdd` of a memo number on the trace's day plus [plus] days (the day follows the seed clock, D-TEST-DATE-PIN-01). */
    private fun ymd(plus: Long = 0) = java.time.LocalDate.parse(day).plusDays(plus).format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"))

    @Test
    @Order(14)
    fun hop14MemoNumberRule() {
        h("14 Memo number rule: the server rejects a number that does not match user, date and block (memo_no_invalid, docs/24 s7.5)")
        val c10 = pick("cigarette", 10)
        val ord = bundle.path("user").path("bind_ordinal").asInt(); val block = bundle.path("user").path("memo_seq_block_size").asInt()
        val variants = listOf(
            "valid in the phone's block" to "sr1001-${ymd()}-${(ord * block + 50).toString().padStart(3, '0')}",
            "another user's name" to "lkxsr1001-${ymd()}-${(ord * block + 51).toString().padStart(3, '0')}",
            "another business date" to "sr1001-${ymd(-1)}-${(ord * block + 52).toString().padStart(3, '0')}",
            "sequence outside every block of the user" to "sr1001-${ymd()}-3000",
            "not the format" to "MEMO-1",
        )
        variants.forEachIndexed { i, (what, no) ->
            val m = Memo(outletByCode("GUL2-0%02d".format(6 + i)), "GUL2-0%02d".format(6 + i), listOf(Line(c10, 1)), paidShare = 100, minute = 200 + i * 20)
            val recs = recordsOf(sr, m, gulPins.getValue(m.outlet), 100, 100, 10 + i)
            recs.first { it.path("type").asText() == "memo" }.path("payload").let { (it as ObjectNode).put("memo_no", no) }
            val a = send(sr, recs, uuid())
            val memoAck = a.body?.path("acks")?.firstOrNull { it.path("type").asText() == "memo" }
            note("memo_no \"$no\" ($what)", "${memoAck?.path("status")?.asText() ?: "HTTP ${a.status}"} ${memoAck?.path("code")?.asText("") ?: ""}")
            if (i == 0) hop("a number in the phone's block is accepted", "status" to memoAck?.path("status")?.asText(), "expected" to "accepted", hard = false)
            else if (what == "not the format") hop("memo_no $what is rejected", "status" to memoAck?.path("status")?.asText(), "expected" to "rejected", hard = false)
            else hop("memo_no $what is refused as memo_no_invalid", "answer" to "${memoAck?.path("status")?.asText()} ${memoAck?.path("code")?.asText("")}".trim(), "expected" to "rejected memo_no_invalid", hard = false)
        }
    }


    // ---- 15 idempotency edges and the pack-size re-check ---------------------------------------------------------------

    @Test
    @Order(15)
    fun hop15IdempotencyEdgesAndPackSizeRecheck() {
        h("15 Edges: changed payload, same content under a new uuid, same memo_no under a new uuid, wrong pack size")
        val c10 = pick("cigarette", 10); val c20 = pick("cigarette", 20)
        val base = oneSale("GUL2-011", 300, Line(c10, 2), "sr1001-${ymd()}-060")
        hop("edge base sale accepted", "memo" to ackOf(send(sr, base, uuid())), "expected" to "accepted")
        val memoUuid = base.first { it.path("type").asText() == "memo" }.path("client_uuid").asText()
        // (a) same client_uuid, changed payload: must not overwrite the stored memo.
        val changed = base.map { it.deepCopy() }
        changed.first { it.path("type").asText() == "memo" }.path("payload").let { (it as ObjectNode).put("paid_mtk", 1).put("due_mtk", it.path("net_mtk").asLong() - 1) }
        note("same client_uuid, changed payload", ackOf(send(sr, changed, uuid())))
        hop("a changed payload under a stored client_uuid never overwrites the memo", "stored paid_mtk" to sqlLong("SELECT paid_mtk FROM app.memo WHERE client_uuid = CAST('$memoUuid' AS uuid)"), "original paid_mtk" to base.first { it.path("type").asText() == "memo" }.path("payload").path("paid_mtk").asLong())
        // (b) same content under new client_uuids and a new memo_no
        val again = oneSale("GUL2-011", 300, Line(c10, 2), "sr1001-${ymd()}-061")
        note("same outlet, lines and minute under new uuids", ackOf(send(sr, again, uuid())))
        hop("a second sale with the same content is held, not stored twice", "memos at GUL2-011" to sqlLong("SELECT count(*) FROM app.memo WHERE outlet_id = ${outletByCode("GUL2-011")} AND business_date = DATE '$day'"), "expected" to 1L, hard = false)
        // (c) same memo_no, different client_uuid, other outlet
        val da = send(sr, oneSale("GUL2-012", 330, Line(c10, 3), "sr1001-${ymd()}-060"), uuid())
        note("same memo_no under a new client_uuid", ackOf(da))
        hop("the same memo_no is never stored twice", "rows with memo_no 060" to sqlLong("SELECT count(*) FROM app.memo WHERE memo_no = 'sr1001-${ymd()}-060' AND business_date = DATE '$day'"), "expected" to 1L)
        // (d) a wrong pack size: a 20s SKU sold as packs of 10
        val wa = send(sr, oneSale("GUL2-013", 360, Line(c20, 2), "sr1001-${ymd()}-062", packFactorOverride = 10), uuid())
        note("2 packets of a 20s SKU sent with pack_factor 10", "memo ${ackOf(wa)}; line ${ackOf(wa, "memo_line")}")
        hop("a pack_factor that differs from the SKU's base_per_pack is refused (docs/34 s4.2: re-checked on the server)", "line" to ackOf(wa, "memo_line"), "expected" to "rejected qty_invalid", hard = false)
    }

    @Test
    @Order(99)
    fun everyFindingIsKnownAndEveryKnownFindingStillHappens() {
        val known = (javaClass.classLoader.getResourceAsStream("data-flow-trace-known-findings.txt")?.bufferedReader()?.readLines() ?: emptyList())
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val fresh = findings.filter { f -> known.none { f.contains(it) } }
        val gone = known.filter { k -> findings.none { it.contains(k) } }
        assertTrue(fresh.isEmpty(), "new findings (not in data-flow-trace-known-findings.txt):\n" + fresh.joinToString("\n"))
        assertTrue(gone.isEmpty(), "known findings that no longer happen; delete them from data-flow-trace-known-findings.txt (the list only shrinks):\n" + gone.joinToString("\n"))
    }
}
