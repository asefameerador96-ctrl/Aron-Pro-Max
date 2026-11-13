package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.sync.IngestService
import com.aktcl.aron.backend.sync.RecordWriter
import com.aktcl.aron.backend.sync.SyncBatchRequest
import com.aktcl.aron.backend.sync.SyncBatchResponse
import com.aktcl.aron.backend.sync.Uploader
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.Money
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SR manual build, sr-server train 1 (docs/requests/contract-1.14-sr-manual.md; D-SV1-01 to 12) through the production ingest:
 * the zero-sale reason, the call-start photo, the Sales Submit business totals and the outlet requests (cluster on `info`,
 * duplicate new-shop request), each under duplicate, repeated and out-of-order uploads. Sunday 2027-01-03, sr1001, MIR-SR-D.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SrManualSv1IngestTest {
    private lateinit var fresh: FreshDb
    private lateinit var ingest: IngestService
    @Volatile private var now = Instant.parse("2027-01-03T04:00:00Z")
    private val clock = AronClock { now }
    private val day = "2027-01-03"
    private var routeId = 0L
    private var outletId = 0L
    private var sr = 0L
    private var sr2 = 0L
    private var route2 = 0L
    private val switches = AtomicInteger(0)
    private lateinit var device: Pair<Long, String>
    /** (sku, price per base_per_qty, per_base_qty, base_per_pack): a priced SKU, one priced at zero, one with no outlet price. */
    private lateinit var priced: List<Long>
    private lateinit var zero: List<Long>
    private lateinit var unpriced: List<Long>
    private lateinit var unpricedAsPrinted: List<Long>
    private val seq = AtomicInteger(100)
    private val minute = AtomicInteger(0)

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            fun id(q: String) = h.createQuery(q).mapTo(Long::class.java).one()
            routeId = id("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            outletId = id("SELECT min(id) FROM app.outlet WHERE route_id = $routeId")
            sr = id("SELECT id FROM app.app_user WHERE username = 'sr1001'")
            device = h.createQuery("SELECT id, device_uuid::text FROM app.device ORDER BY id LIMIT 1").map { rs, _ -> rs.getLong(1) to rs.getString(2) }.one()
            // A second SR with its own daily route in sr1001's zone (the user guard of the flag).
            sr2 = id("INSERT INTO app.app_user (username, full_name, role, password_hash) VALUES ('sr2002', 'Second SR', 'SR', 'x') RETURNING id")
            route2 = id("INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'MIR-SR-X', 'Second route', zone_id, 'sr', 'daily', 127 FROM app.route WHERE id = $routeId RETURNING id")
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason) VALUES ($route2, $sr2, 'primary', DATE '2026-01-01', 'test')")
            val skus = h.createQuery(
                "SELECT s.id, p.amount_mtk, p.per_base_qty, s.base_per_pack FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' " +
                    "WHERE p.amount_mtk > 0 AND p.valid_from <= DATE '$day' AND (p.valid_to IS NULL OR p.valid_to > DATE '$day') ORDER BY s.id LIMIT 3",
            ).map { rs, _ -> listOf(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }.list()
            priced = skus[0]
            // The catalogue's MaxDB-10S_10HL case: a price row of zero. The phone states the zero it was given.
            h.execute("UPDATE app.sku_price SET amount_mtk = 0 WHERE sku_id = ${skus[1][0]} AND price_type = 'outlet'")
            zero = listOf(skus[1][0], 0L, skus[1][2], skus[1][3])
            // No outlet price at all on the server; a phone stating one has it from nowhere the server knows.
            h.execute("DELETE FROM app.sku_price WHERE sku_id = ${skus[2][0]} AND price_type = 'outlet'")
            unpriced = listOf(skus[2][0], 0L, skus[2][2], skus[2][3])
            unpricedAsPrinted = skus[2]
        }
        RecordWriter.forgetColumns()
        ingest = IngestService(fresh.db, DbServerConfig(fresh.db, RegistryDefaults(), clock), SqlReachResolver(fresh.db, GeoRepository(fresh.db, clock), clock), clock,
            handlers = com.aktcl.aron.backend.platform.RecordHandlers(listOf(com.aktcl.aron.backend.masterdata.OutletChangeRequestHandler())))
    }

    @AfterAll
    fun tearDown() { RecordWriter.forgetColumns(); fresh.close() }

    private fun uuid() = UUID.randomUUID().toString()
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun count(q: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(q).mapTo(Long::class.java).one() }
    private fun uuidOf(r: JsonObject) = (r["client_uuid"] as JsonPrimitive).content
    private fun typeOf(r: JsonObject) = (r["type"] as JsonPrimitive).content
    private fun skuOf(r: JsonObject) = ((r["payload"] as JsonObject)["sku_id"] as JsonPrimitive).content.toLong()

    private fun envelope(type: String, cu: String, family: String, rank: Int, payload: JsonObject, route: Long, bd: String = day, base: String = "2027-01-02T18:30:00Z") = buildJsonObject {
        val at = Instant.parse(base).plusSeconds(minute.incrementAndGet() * 60L).toString()
        put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
        put("business_date", bd); put("captured_at", at); put("captured_elapsed_ms", 18330000); put("boot_count", 412)
        put("clock_offset_ms", 0); put("captured_offline", true); put("route_id", route); put("bundle_version", "$bd:3")
        put("bundle_stale", false); put("config_version", 1)
        put("payload", payload)
    }

    private val fix = """{"purpose":"visit_open","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}"""

    /** One line: (sku row, qty entered, unit, pack factor). qty_base and gross follow from them, as the phone computes. */
    private class L(val sku: List<Long>, val qty: Long, val unit: String, val factor: Long)

    private fun lineGross(l: L) = Money.lineGrossMtk(l.qty * (if (l.unit == "pack") l.factor else 1), l.sku[1], l.sku[2])

    /**
     * A sale family as a phone sends it: visit, memo, lines (consistent money). [web] keys it as a paper entry (web_entry
     * visit, no fix, no visit_close).
     */
    private fun sale(memoNo: String, lines: List<L>, route: Long = routeId, outlet: Long = outletId, bd: String = day, web: Boolean = false): List<JsonObject> {
        val visit = uuid(); val memo = uuid()
        val gross = lines.sumOf { lineGross(it) }
        val visitPayload = if (web) json(
            """{"visit_kind":"web_entry","outlet_id":$outlet,"opened_at":"${bd}T03:41:00.000Z","sequence_no":1,"planned":true,
               "geo":{"verdict":"no_fix","radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}""",
        ) else json(
            """{"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"${bd}T03:41:00.120Z","sequence_no":1,"planned":true,"fix":$fix,
               "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}""",
        )
        val memoPayload = json(
            """{"visit_client_uuid":"$visit","outlet_id":$outlet,"memo_no":"$memoNo","memo_kind":"sale","committed_at":"${Instant.parse("${bd}T00:00:00Z").plusSeconds(60L * minute.incrementAndGet())}",
               "price_list_date":"$bd","price_type":"outlet","gross_mtk":$gross,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,
               "round_adj_mtk":0,"net_mtk":$gross,"paid_mtk":$gross,"due_mtk":0,"is_credit":false,"line_count":${lines.size},"discount_line_count":0,"qc_line_count":0,
               "offer_version_ids":[],"rounding_mode":"half_up_paisa"}""",
        )
        return listOf(envelope("visit", visit, visit, 0, visitPayload, route, bd), envelope("memo", memo, visit, 1, memoPayload, route, bd)) +
            lines.mapIndexed { i, l -> envelope("memo_line", uuid(), visit, 2, lineOf(memo, i + 1, l), route, bd) }
    }

    private fun lineOf(memo: String, no: Int, l: L) = json(
        """{"memo_client_uuid":"$memo","line_no":$no,"sku_id":${l.sku[0]},"line_kind":"sale","qty_entered":${l.qty},"unit_entered":"${l.unit}","pack_factor":${l.factor},
           "qty_base":${l.qty * (if (l.unit == "pack") l.factor else 1)},"price_type":"outlet","price_valid_from":"2026-01-01","base_price_mtk":${l.sku[1]},
           "price_per_qty":${l.sku[2]},"gross_mtk":${lineGross(l)}}""",
    )

    private fun send(user: Long, records: List<JsonObject>, via: IngestService = ingest, key: ECPublicKey? = null): SyncBatchResponse = via.ingest(
        Uploader(user, Role.SR, 1, device.first, device.second, key),
        SyncBatchRequest(
            batch_uuid = uuid(), device_uuid = device.second, schema_version = 1, app_version = "1.0.9+9", trigger = "manual",
            sent_at_device = now.toString(), pending_rows = 0, time_anchors = emptyList(), device_counts = emptyMap(), records = records,
        ),
    )

    private fun SyncBatchResponse.ack(r: JsonObject) = acks.single { it.client_uuid == uuidOf(r) }.let { "${it.status} ${it.code ?: ""}".trim() }
    private fun SyncBatchResponse.retryable(r: JsonObject) = acks.single { it.client_uuid == uuidOf(r) }.retryable

    private fun memoNo() = "sr1001-270103-${seq.incrementAndGet()}"

    private fun visitJson(outlet: Long, extra: String = "", seq: Int = 1) = json(
        """{"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"${day}T03:41:00.120Z","sequence_no":$seq,"planned":true,"fix":$fix,
           "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}$extra}""",
    )

    private fun closeJson(visit: String, zero: Boolean, reason: String?, outcome: String = "zero_sale_stock_ok", extra: String = "") = json(
        """{"visit_client_uuid":"$visit","outcome_code":"$outcome","call_declined":false,"ended_at":"${day}T03:50:00.000Z","is_zero_sale":$zero${if (reason == null) "" else ",\"zero_sale_reason_code\":\"$reason\""}$extra}""",
    )

    private fun str(q: String): String? = fresh.db.jdbi.withHandle<String?, Exception> { h -> h.createQuery(q).mapTo(String::class.java).findOne().orElse(null) }

    // ---------------------------------------------------------------------------------------------- (a) zero-sale reason

    @Test
    fun aZeroSaleReasonIsStoredOnceUnderDuplicatesAndAnyOrder() {
        val codes = listOf("stock_available", "outlet_closed_during_visit", "absence_of_owner", "insufficient_cash")
        for (seed in 1..8) {
            val rnd = Random(seed)
            val v = uuid(); val c = uuid()
            val outlet = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.outlet WHERE route_id = $routeId ORDER BY id OFFSET $seed LIMIT 1").mapTo(Long::class.java).one() }
            val reason = codes[seed % 4]
            val visit = envelope("visit", v, v, 0, visitJson(outlet), routeId)
            val close = envelope("visit_close", c, v, 1, closeJson(v, true, reason), routeId)
            repeat(2 + rnd.nextInt(3)) { send(sr, listOf(visit, visit, close, close).shuffled(rnd).take(1 + rnd.nextInt(4))) }
            send(sr, listOf(close, visit))
            assertEquals(1L, count("SELECT count(*) FROM app.visit WHERE client_uuid = '$v'"), "seed $seed: one visit")
            assertEquals(reason, str("SELECT zero_sale_reason_code FROM app.visit WHERE client_uuid = '$v'"), "seed $seed: the reason is stored once and kept")
        }
    }

    private fun flagOf(v: String) = str("SELECT reason_code_unlisted::text FROM app.visit WHERE client_uuid = '$v'")

    @Test
    fun aMalformedOrMisplacedReasonIsRefusedButAnUnknownOrRetiredCodeIsStoredAsSentAndFlagged() {
        val outlet = outletId
        // shape and combination errors stay schema_invalid and leave the visit open
        val v = uuid()
        send(sr, listOf(envelope("visit", v, v, 0, visitJson(outlet, seq = 20), routeId)))
        for ((name, payload) in mapOf("sale with reason" to closeJson(v, false, "stock_available"), "bad shape" to closeJson(v, true, "Stock Available"))) {
            val rec = envelope("visit_close", uuid(), v, 1, payload, routeId)
            val a = send(sr, listOf(rec)).ack(rec)
            assertTrue(a.startsWith("rejected schema_invalid"), "$name: $a")
        }
        assertEquals(null, str("SELECT close_client_uuid::text FROM app.visit WHERE client_uuid = '$v'"), "the visit stays open")
        // an unknown code: the close is stored with the code as sent, flagged, idempotent, and a different body under the same uuid is a conflict
        val unknown = closeJson(v, true, "battery_dead")
        val rec = envelope("visit_close", uuid(), v, 1, unknown, routeId)
        assertEquals("accepted", send(sr, listOf(rec)).ack(rec), "an unknown optional label never costs the close")
        repeat(3) { assertEquals("duplicate", send(sr, listOf(rec)).ack(rec).substringBefore(' '), "replay") }
        assertEquals("battery_dead", str("SELECT zero_sale_reason_code FROM app.visit WHERE client_uuid = '$v'"))
        assertEquals("true", flagOf(v), "flagged for the reports and the portal")
        assertEquals(1L, count("SELECT count(*) FROM app.visit WHERE client_uuid = '$v' AND close_client_uuid IS NOT NULL"))
        val changed = envelope("visit_close", (rec["client_uuid"] as JsonPrimitive).content, v, 1, closeJson(v, true, "stock_available"), routeId)
        assertTrue(send(sr, listOf(changed)).ack(changed).contains("payload_conflict"), "same uuid, different content")
        assertEquals("battery_dead", str("SELECT zero_sale_reason_code FROM app.visit WHERE client_uuid = '$v'"), "the first copy stands")
        // a code retired on the business date: stored as sent, flagged
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.code_list_item SET valid_to = DATE '2026-12-01' WHERE list_key = 'zero_sale_reason' AND code = 'absence_of_owner'") }
        try {
            val w = uuid()
            val open = envelope("visit", w, w, 0, visitJson(outletAt(14), seq = 21), routeId)
            val close = envelope("visit_close", uuid(), w, 1, closeJson(w, true, "absence_of_owner"), routeId)
            assertEquals("accepted", send(sr, listOf(open, close)).ack(close), "a retired code is stored")
            assertEquals("absence_of_owner", str("SELECT zero_sale_reason_code FROM app.visit WHERE client_uuid = '$w'"))
            assertEquals("true", flagOf(w))
            send(sr, listOf(close)); assertEquals("true", flagOf(w), "a replay keeps the flag")
        } finally {
            fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.code_list_item SET valid_to = NULL WHERE list_key = 'zero_sale_reason' AND code = 'absence_of_owner'") }
        }
        // a listed code is not flagged
        val x = uuid()
        val openX = envelope("visit", x, x, 0, visitJson(outletAt(15), seq = 22), routeId)
        val closeX = envelope("visit_close", uuid(), x, 1, closeJson(x, true, "stock_available"), routeId)
        send(sr, listOf(openX, closeX)); assertEquals(null, flagOf(x), "no flag on a listed code")
        // an older app's close without a reason is valid and the first close stands
        val y = uuid()
        val openY = envelope("visit", y, y, 0, visitJson(outletAt(16), seq = 23), routeId)
        val okY = envelope("visit_close", uuid(), y, 1, closeJson(y, true, null), routeId)
        assertEquals("accepted", send(sr, listOf(openY, okY)).ack(okY))
        val second = envelope("visit_close", uuid(), y, 1, closeJson(y, true, "stock_available"), routeId)
        val secondAck = send(sr, listOf(second)).ack(second)
        assertTrue(secondAck.startsWith("rejected") || secondAck.startsWith("quarantined"), "a second close with another uuid is refused, got: $secondAck")
        assertEquals(null, str("SELECT zero_sale_reason_code FROM app.visit WHERE client_uuid = '$y'"), "the first close stands")
    }

    @Test
    fun anUnknownCallPhotoSkipCodeIsStoredAsSentAndFlagged() {
        val v = uuid()
        val open = envelope("visit", v, v, 0, visitJson(outletAt(17), "", 34), routeId)
        val close = envelope("visit_close", uuid(), v, 1, closeJson(v, false, null, "sold", ""","call_photo_skip_reason_code":"lazy""""), routeId)
        assertEquals("accepted", send(sr, listOf(open, close)).ack(close))
        repeat(2) { send(sr, listOf(close)) }
        assertEquals("lazy", str("SELECT call_photo_skip_reason_code FROM app.visit WHERE client_uuid = '$v'"))
        assertEquals("true", flagOf(v))
    }

    // ---------------------------------------------------------------------------------------------- (b) call photo

    private fun outletAt(offset: Int) = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.outlet WHERE route_id = $routeId ORDER BY id OFFSET $offset LIMIT 1").mapTo(Long::class.java).one() }

    @Test
    fun aCallPhotoOrAReasonedSkipIsStoredByTheCloseOnceAndTheTwoNeverMix() {
        val photo = uuid()
        val v1 = uuid(); val c1 = uuid()
        val open1 = envelope("visit", v1, v1, 0, visitJson(outletAt(10), "", 30), routeId)
        val close1 = envelope("visit_close", c1, v1, 1, closeJson(v1, false, null, "sold", ""","call_photo_uuid":"$photo""""), routeId)
        send(sr, listOf(close1, open1)); repeat(3) { send(sr, listOf(open1, close1).shuffled(Random(it))) }
        assertEquals(1L, count("SELECT count(*) FROM app.visit WHERE client_uuid = '$v1'"))
        assertEquals(photo, str("SELECT call_photo_uuid::text FROM app.visit WHERE client_uuid = '$v1'"), "stored once, kept under resends")
        val v2 = uuid()
        val open2 = envelope("visit", v2, v2, 0, visitJson(outletAt(11), "", 31), routeId)
        val close2 = envelope("visit_close", uuid(), v2, 1, closeJson(v2, false, null, "sold", ""","call_photo_skip_reason_code":"camera_unavailable""""), routeId)
        assertEquals("accepted", send(sr, listOf(open2, close2)).ack(close2))
        assertEquals("camera_unavailable", str("SELECT call_photo_skip_reason_code FROM app.visit WHERE client_uuid = '$v2'"))
        assertEquals(null, str("SELECT call_photo_uuid::text FROM app.visit WHERE client_uuid = '$v2'"))
        // a visit sent without a photo member (an older app, or the photo step off) closes as before
        val v3 = uuid()
        val open3 = envelope("visit", v3, v3, 0, visitJson(outletAt(12), "", 32), routeId)
        val close3 = envelope("visit_close", uuid(), v3, 1, closeJson(v3, false, null, "sold"), routeId)
        assertEquals("accepted", send(sr, listOf(open3, close3)).ack(close3))
        for ((name, extra) in mapOf(
            "both" to ""","call_photo_uuid":"${uuid()}","call_photo_skip_reason_code":"other"""",
            "skip code shape" to ""","call_photo_skip_reason_code":"Camera Broken"""",
            "bad uuid" to ""","call_photo_uuid":"NOT-A-UUID"""",
        )) {
            val v = uuid()
            val open = envelope("visit", v, v, 0, visitJson(outletAt(13), "", 33), routeId)
            send(sr, listOf(open))
            val close = envelope("visit_close", uuid(), v, 1, closeJson(v, false, null, "sold", extra), routeId)
            val a = send(sr, listOf(close)).ack(close)
            assertTrue(a.startsWith("rejected"), "$name: $a")
            assertEquals(null, str("SELECT close_client_uuid::text FROM app.visit WHERE client_uuid = '$v'"), "$name: the visit stays open")
        }
        // the photo itself is a media record of purpose outlet_call that must belong to a visit
        val m = uuid()
        val media = envelope("media_meta", m, m, 0, json(
            """{"purpose":"outlet_call","ref_type":"memo","ref_client_uuid":"${uuid()}","sha256":"${"ab".repeat(32)}","bytes":2000,"width":640,"height":480,"mime":"image/jpeg",
               "blob_path":"photos/$day/${uuid()}/$m.jpg","taken_at":"${day}T03:40:00Z"}""",
        ), routeId)
        assertTrue(send(sr, listOf(media)).ack(media).startsWith("rejected"), "outlet_call on a memo is refused")
    }

    // ---------------------------------------------------------------------------------------------- (c) business totals

    @Test
    fun theSubmitDigestCarriesTheBusinessTotalsAndAResendChangesNothing() {
        val svc = com.aktcl.aron.backend.sync.SalesSubmitDigestService(fresh.db, clock)
        val before = fresh.db.jdbi.withHandle<com.aktcl.aron.backend.sync.SubmitBusinessTotalsDto, Exception> { h -> svc.businessTotals(h, sr, java.time.LocalDate.parse("2027-01-03")) }
        val family = sale(memoNo(), listOf(L(priced, 3, "pack", priced[3]), L(priced, 2, "stick", 1)), outlet = outletId + 0)
        repeat(3) { send(sr, family.shuffled(Random(it))) }
        val after = fresh.db.jdbi.withHandle<com.aktcl.aron.backend.sync.SubmitBusinessTotalsDto, Exception> { h -> svc.businessTotals(h, sr, java.time.LocalDate.parse("2027-01-03")) }
        val expectedBase = 3 * priced[3] + 2
        fun List<com.aktcl.aron.backend.sync.SkuQtyBaseDto>.of(sku: Long) = firstOrNull { it.sku_id == sku }?.qty_base ?: 0L
        assertEquals(before.sold.of(priced[0]) + expectedBase, after.sold.of(priced[0]), "sold quantity in base units under the SKU, once for all resends")
        assertEquals(after.sold.map { it.sku_id }, after.sold.map { it.sku_id }.sorted(), "ordered by sku_id")
        assertEquals(0L, after.promotion_mtk - before.promotion_mtk, "no discount while the programmes are deferred")
        assertTrue(after.outlets_visited >= before.outlets_visited + (if (before.outlets_visited == 0) 1 else 0), "the visit counts once")
        // the digest served by the ingest service carries the same figures
        val d = svc.digest(sr, device.first, device.second, java.time.LocalDate.parse("2027-01-03"))
        assertEquals(after, d.business_totals)
        // a login with nothing stored has an empty, well-formed block
        val none = fresh.db.jdbi.withHandle<com.aktcl.aron.backend.sync.SubmitBusinessTotalsDto, Exception> { h -> svc.businessTotals(h, sr2, java.time.LocalDate.parse("2027-01-03")) }
        assertEquals(0, none.outlets_visited); assertTrue(none.sold.isEmpty()); assertTrue(none.stock_issued.isEmpty()); assertTrue(none.qc.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------- (d) outlet requests

    private fun requestPayload(type: String, outlet: Long?, proposed: String) = json(
        """{"request_type":"$type",${if (outlet != null) "\"outlet_id\":$outlet," else ""}"proposed":$proposed,"photo_uuids":[],
           "fix":{"purpose":"outlet_capture","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                  "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}}""",
    )

    private fun flagsOf(request: String) = fresh.db.jdbi.withHandle<List<String>, Exception> { h ->
        h.createQuery("SELECT event || ':' || coalesce(note, '') FROM app.outlet_request_event WHERE request_uuid = CAST(:u AS uuid) AND event LIKE 'flag_%' ORDER BY event").bind("u", request).mapTo(String::class.java).list()
    }

    @Test
    fun aLookAlikeNewShopIsStoredAsItsOwnRequestAckedAcceptedAndFlaggedForTheApprover() {
        val cluster = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.cluster WHERE zone_id = (SELECT zone_id FROM app.route WHERE id = $routeId) ORDER BY id LIMIT 1").mapTo(Long::class.java).one() }
        val proposed = """{"name":"  Rahim  Store ","owner_name":"Rahim Uddin","contact_number":"01711223344","cluster_id":$cluster}"""
        val first = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("new", null, proposed), routeId)
        val again = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("new", null, proposed.replace("  Rahim  Store ", "rahim store")), routeId)
        val other = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("new", null, proposed.replace("01711223344", "01711223355")), routeId)
        assertEquals("accepted", send(sr, listOf(first)).ack(first))
        assertEquals("accepted", send(sr, listOf(again)).ack(again), "a look-alike is stored, never swallowed (it may be a correction or a second shop)")
        assertEquals("accepted", send(sr, listOf(other)).ack(other), "another mobile number is another shop")
        // only the SAME client uuid is a replay
        repeat(2) { send(sr, listOf(first, again, other).shuffled(Random(it))) }
        assertEquals(3L, count("SELECT count(*) FROM app.outlet_change_request WHERE request_type = 'new' AND user_id = $sr AND client_uuid IN ('${uuidOf(first)}', '${uuidOf(again)}', '${uuidOf(other)}')"), "one row per client uuid")
        assertEquals("duplicate", send(sr, listOf(again)).ack(again).substringBefore(' '), "the same uuid is a replay")
        assertEquals(emptyList(), flagsOf(uuidOf(first)), "the first request carries no flag")
        assertEquals(listOf("flag_possible_duplicate:${uuidOf(first)}"), flagsOf(uuidOf(again)), "the look-alike points at the earlier one, once, under resends")
        assertEquals(emptyList(), flagsOf(uuidOf(other)))
    }

    @Test
    fun anInfoRequestKeepsItsChangesWhenTheClusterIsGoneOrInAnotherZoneAndFlagsIt() {
        val zone = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT zone_id FROM app.route WHERE id = $routeId").mapTo(Long::class.java).one() }
        val inZone = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.cluster WHERE zone_id = $zone ORDER BY id DESC LIMIT 1").mapTo(Long::class.java).one() }
        val elsewhere = fresh.db.jdbi.withHandle<Long?, Exception> { h -> h.createQuery("SELECT id FROM app.cluster WHERE zone_id <> $zone ORDER BY id LIMIT 1").mapTo(Long::class.java).findOne().orElse(null) }
        val ok = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("info", outletId, """{"name":"Karim Store","cluster_id":$inZone}"""), routeId)
        assertEquals("accepted", send(sr, listOf(ok)).ack(ok))
        assertEquals(emptyList(), flagsOf(uuidOf(ok)))
        assertEquals(inZone.toString(), str("SELECT proposed ->> 'cluster_id' FROM app.outlet_change_request WHERE client_uuid = '${uuidOf(ok)}'"))
        val ghost = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("info", outletId, """{"name":"Karim Store 2","contact_number":"01811223344","cluster_id":987654321}"""), routeId)
        assertEquals("accepted", send(sr, listOf(ghost)).ack(ghost), "a gone cluster never costs the request")
        repeat(2) { send(sr, listOf(ghost)) }
        assertEquals(null, str("SELECT proposed ->> 'cluster_id' FROM app.outlet_change_request WHERE client_uuid = '${uuidOf(ghost)}'"), "the cluster is dropped from the proposal")
        assertEquals("Karim Store 2", str("SELECT proposed ->> 'name' FROM app.outlet_change_request WHERE client_uuid = '${uuidOf(ghost)}'"), "the change itself is kept")
        assertEquals("01811223344", str("SELECT proposed ->> 'contact_number' FROM app.outlet_change_request WHERE client_uuid = '${uuidOf(ghost)}'"))
        assertEquals(listOf("flag_cluster_dropped:987654321"), flagsOf(uuidOf(ghost)))
        if (elsewhere != null) {
            val far = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("info", outletId, """{"name":"Karim Far","cluster_id":$elsewhere}"""), routeId)
            assertEquals("accepted", send(sr, listOf(far)).ack(far), "a cluster of another zone never costs the request")
            assertEquals(null, str("SELECT proposed ->> 'cluster_id' FROM app.outlet_change_request WHERE client_uuid = '${uuidOf(far)}'"))
            assertEquals(listOf("flag_cluster_dropped:$elsewhere"), flagsOf(uuidOf(far)))
        }
        // a route_add may carry the cluster (the end-to-end approval is in SrManualSv1ApiTest)
        val add = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("route_add", outletId, """{"cluster_id":$inZone}"""), routeId)
        assertEquals("accepted", send(sr, listOf(add)).ack(add))
    }

    @Test
    fun aRewrittenRequestIsAckedDuplicateOnReplayAndAClusterRequestWithAGoneClusterIsRefusedForGood() {
        val ghost = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("info", outletId, """{"name":"Replay Store","cluster_id":987654322}"""), routeId)
        assertEquals("accepted", send(sr, listOf(ghost)).ack(ghost))
        // the content fingerprint is that of the payload AS SENT: a resend of the same body is a replay, a different body under the uuid is a conflict
        repeat(3) { assertEquals("duplicate", send(sr, listOf(ghost)).ack(ghost).substringBefore(' '), "replay of a rewritten request") }
        assertEquals(listOf("flag_cluster_dropped:987654322"), flagsOf(uuidOf(ghost)), "the flag is written once under resends")
        val changed = envelope("outlet_change_request", (ghost["client_uuid"] as JsonPrimitive).content, (ghost["family_uuid"] as JsonPrimitive).content, 0,
            requestPayload("info", outletId, """{"name":"Replay Store","cluster_id":987654323}"""), routeId)
        assertTrue(send(sr, listOf(changed)).ack(changed).contains("payload_conflict"), "same uuid, another body")
        // a `cluster` request exists to move the outlet to that cluster: gone cluster = final schema_invalid, nothing stored, the answer is stable
        val gone = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("cluster", outletId, """{"cluster_id":987654324}"""), routeId)
        repeat(2) { assertTrue(send(sr, listOf(gone)).ack(gone).startsWith("rejected schema_invalid"), "a cluster request with a gone cluster is refused") }
        assertEquals(0L, count("SELECT count(*) FROM app.outlet_change_request WHERE client_uuid = '${uuidOf(gone)}'"))
    }

    @Test
    fun aNewOrClusterRequestNamingAnotherZonesClusterIsStoredWithTheClusterDroppedAndFlagged() {
        val zone = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT zone_id FROM app.route WHERE id = $routeId").mapTo(Long::class.java).one() }
        val elsewhere = fresh.db.jdbi.withHandle<Long?, Exception> { h -> h.createQuery("SELECT id FROM app.cluster WHERE zone_id <> $zone ORDER BY id LIMIT 1").mapTo(Long::class.java).findOne().orElse(null) } ?: return
        val inZone = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.cluster WHERE zone_id = $zone ORDER BY id LIMIT 1").mapTo(Long::class.java).one() }
        val newFar = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("new", null, """{"name":"Far Shop","contact_number":"01911000111","cluster_id":$elsewhere}"""), routeId)
        val clusterFar = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("cluster", outletId, """{"cluster_id":$elsewhere}"""), routeId)
        val newNear = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("new", null, """{"name":"Near Shop","contact_number":"01911000222","cluster_id":$inZone}"""), routeId)
        for (r in listOf(newFar, clusterFar, newNear)) {
            assertEquals("accepted", send(sr, listOf(r)).ack(r), typeOf(r))
            repeat(2) { assertEquals("duplicate", send(sr, listOf(r)).ack(r).substringBefore(' ')) }
        }
        for (r in listOf(newFar, clusterFar)) {
            assertEquals(null, str("SELECT proposed ->> 'cluster_id' FROM app.outlet_change_request WHERE client_uuid = '${uuidOf(r)}'"), "dropped")
            assertEquals(listOf("flag_cluster_dropped:$elsewhere"), flagsOf(uuidOf(r)))
        }
        assertEquals("Far Shop", str("SELECT proposed ->> 'name' FROM app.outlet_change_request WHERE client_uuid = '${uuidOf(newFar)}'"), "the rest of the proposal is kept")
        assertEquals(inZone.toString(), str("SELECT proposed ->> 'cluster_id' FROM app.outlet_change_request WHERE client_uuid = '${uuidOf(newNear)}'"))
        assertEquals(emptyList(), flagsOf(uuidOf(newNear)))
    }

    @Test
    fun theUnlistedLabelCheckUsesTheVisitsBusinessDateNotTheCloseEnvelopes() {
        val tomorrow = java.time.LocalDate.parse(day).plusDays(1).toString()
        // listed on the visit's day, retired from the next day: a close sent with the NEXT day's business date still belongs to the visit's day
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.code_list_item SET valid_to = DATE '$tomorrow' WHERE list_key = 'zero_sale_reason' AND code = 'insufficient_cash'") }
        val clockWas = now
        try {
            now = Instant.parse("2027-01-04T04:00:00Z")   // the close is sent the next morning
            val v = uuid()
            val open = envelope("visit", v, v, 0, visitJson(outletAt(18), seq = 40), routeId)
            val close = envelope("visit_close", uuid(), v, 1, closeJson(v, true, "insufficient_cash"), routeId, bd = tomorrow, base = "2027-01-03T18:30:00Z")
            val a = send(sr, listOf(open, close)).ack(close)
            assertEquals("accepted", a, "the close is stored")
            assertEquals("insufficient_cash", str("SELECT zero_sale_reason_code FROM app.visit WHERE client_uuid = '$v'"))
            assertEquals(null, flagOf(v), "the code was listed on the visit's own day")
        } finally {
            now = clockWas
            fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.code_list_item SET valid_to = NULL WHERE list_key = 'zero_sale_reason' AND code = 'insufficient_cash'") }
        }
    }

    @Test
    fun aMemoCapturedAfterTheSalesSubmitIsStoredAndCountedOnceAndOneCapturedBeforeIsNot() {
        // the fixture user holds no assignment: a cover of the route for the test (a route-day exists only for a route the uploader holds)
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason) VALUES ($routeId, $sr, 'cover', DATE '2026-01-01', 'late rows test')") }
        try { lateRowsCase() } finally { fresh.db.jdbi.useHandle<Exception> { h -> h.execute("DELETE FROM app.route_assignment WHERE user_id = $sr AND reason = 'late rows test'") } }
    }

    private fun lateRowsCase() {
        fun late() = count("SELECT coalesce((SELECT late_rows_after_sales_submit FROM app.route_day WHERE route_id = $routeId AND business_date = DATE '$day'), -1)")
        val first = sale(memoNo(), listOf(L(priced, 1, "stick", 1)), outlet = outletId)
        val firstAck = send(sr, first)
        first.forEach { assertEquals("accepted", firstAck.ack(it), "before any submit every record is stored: ${typeOf(it)}") }
        assertEquals(0L, late(), "no Sales Submit yet: nothing is late")
        // the route-day's Sales Submit (server state) sits BEFORE everything captured from now on
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.route_day SET sales_submitted_at = TIMESTAMPTZ '2027-01-02T18:00:00Z' WHERE route_id = $routeId AND business_date = DATE '$day'") }
        val after = sale(memoNo(), listOf(L(priced, 2, "stick", 1)), outlet = outletId)
        val ack = send(sr, after)
        after.forEach { assertEquals("accepted", ack.ack(it), "a row after the Sales Submit is stored, never refused: ${typeOf(it)}") }
        repeat(2) { send(sr, after.shuffled(Random(it))) }
        assertEquals(1L, late(), "the memo counts once under resends and any order")
        // a row captured before the submit instant (it only arrived late) is not 'after the submit'
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.route_day SET sales_submitted_at = TIMESTAMPTZ '2027-01-03T18:00:00Z' WHERE route_id = $routeId AND business_date = DATE '$day'") }
        val early = sale(memoNo(), listOf(L(priced, 1, "stick", 1)), outlet = outletId)
        send(sr, early)
        assertEquals(1L, late(), "captured before the submit: not counted")
    }

    // ---------------------------------------------------------------------------------------------- (c2) business totals, rule by rule

    @Test
    fun theBusinessTotalsCountExactlyWhatTheContractSays() {
        val svc = com.aktcl.aron.backend.sync.SalesSubmitDigestService(fresh.db, clock)
        val date = java.time.LocalDate.parse(day)
        fun totals() = fresh.db.jdbi.withHandle<com.aktcl.aron.backend.sync.SubmitBusinessTotalsDto, Exception> { h -> svc.businessTotals(h, sr, date) }
        fun List<com.aktcl.aron.backend.sync.SkuQtyBaseDto>.of(sku: Long) = firstOrNull { it.sku_id == sku }?.qty_base ?: 0L
        fun replica(block: (org.jdbi.v3.core.Handle) -> Unit) = fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("SET session_replication_role = replica")
            try { block(h) } finally { h.execute("SET session_replication_role = origin") }
        }
        // six outlets of the route that the login has no visit on yet, so every delta of outlets_visited is a whole outlet
        val fresh6 = fresh.db.jdbi.withHandle<List<Long>, Exception> { h ->
            h.createQuery("SELECT id FROM app.outlet WHERE route_id = $routeId AND id NOT IN (SELECT outlet_id FROM app.visit WHERE user_id = $sr) ORDER BY id DESC LIMIT 6").mapTo(Long::class.java).list()
        }
        assertEquals(6, fresh6.size, "the seed route has six outlets without a visit of this login")
        val sku = priced[0]
        val before = totals()

        // (a) a normal sale counts once in sold (base units) and its outlet once
        val a = sale(memoNo(), listOf(L(priced, 4, "stick", 1)), outlet = fresh6[0])
        val aAck = send(sr, a)
        repeat(2) { send(sr, a.shuffled(Random(it))) }
        val afterA = totals()
        a.forEach { assertEquals("accepted", aAck.ack(it), "(a) every record of the sale family is stored: ${typeOf(it)}") }
        assertEquals(before.sold.of(sku) + 4, afterA.sold.of(sku), "(a) sold once, in base units")
        assertEquals(before.outlets_visited + 1, afterA.outlets_visited, "(a) its outlet counts once")

        // (b) a memo marked superseded is not sold; (c) a voided memo is not sold. The visit of each is still a visit made.
        val b = sale(memoNo(), listOf(L(priced, 6, "stick", 1)), outlet = fresh6[1])
        val c = sale(memoNo(), listOf(L(priced, 9, "stick", 1)), outlet = fresh6[2])
        send(sr, b); send(sr, c)
        assertEquals(afterA.sold.of(sku) + 6 + 9, totals().sold.of(sku), "both memos are live until changed")
        val memoB = uuidOf(b.single { typeOf(it) == "memo" }); val memoC = uuidOf(c.single { typeOf(it) == "memo" })
        replica { h ->
            h.execute("UPDATE app.memo SET status = 'superseded', superseded_by_client_uuid = gen_random_uuid() WHERE client_uuid = CAST('$memoB' AS uuid)")
            h.execute("UPDATE app.memo SET voided_at = now() WHERE client_uuid = CAST('$memoC' AS uuid)")
        }
        val afterBC = totals()
        assertEquals(afterA.sold.of(sku), afterBC.sold.of(sku), "(b)(c) a superseded and a voided memo are not counted")
        assertEquals(afterA.outlets_visited + 2, afterBC.outlets_visited, "the calls made still count as outlets visited")
        assertEquals(0L, afterBC.promotion_mtk - before.promotion_mtk)

        // (d) an abandoned visit does not raise outlets_visited; (e) a declined call does not either
        val d = uuid()
        val dOpen = envelope("visit", d, d, 0, visitJson(fresh6[3], seq = 41), routeId)
        val dClose = envelope("visit_close", uuid(), d, 1, closeJson(d, false, null, "abandoned"), routeId)
        val dAck = send(sr, listOf(dOpen, dClose))
        assertEquals("accepted", dAck.ack(dOpen), "the visit is stored")
        assertEquals("accepted", dAck.ack(dClose), "the abandoned close is stored")
        assertEquals(afterBC.outlets_visited, totals().outlets_visited, "(d) an abandoned visit is not an outlet visited")
        val e = uuid()
        val eOpen = envelope("visit", e, e, 0, visitJson(fresh6[4], seq = 42), routeId)
        val eCloseBody = json(closeJson(e, false, null, "refused").toString().replace("\"call_declined\":false", "\"call_declined\":true"))
        val eClose = envelope("visit_close", uuid(), e, 1, eCloseBody, routeId)
        val eAck = send(sr, listOf(eOpen, eClose))
        assertEquals("accepted", eAck.ack(eOpen)); assertEquals("accepted", eAck.ack(eClose), "the declined close is stored")
        assertEquals("true", str("SELECT call_declined::text FROM app.visit WHERE client_uuid = '$e'"), "the decline is stored")
        assertEquals(afterBC.outlets_visited, totals().outlets_visited, "(e) a declined call is not an outlet visited")
        // control: a visit closed normally on the last outlet does count
        val f = uuid()
        val fOpen = envelope("visit", f, f, 0, visitJson(fresh6[5], seq = 43), routeId)
        val fClose = envelope("visit_close", uuid(), f, 1, closeJson(f, true, "stock_available"), routeId)
        send(sr, listOf(fOpen, fClose))
        assertEquals(afterBC.outlets_visited + 1, totals().outlets_visited, "a normally closed visit counts")

        // (f) stock: issue + adjustment only, not return, not voided
        val preStock = totals().stock_issued.of(sku)
        fun stock(kind: String, qty: Int, reason: String?, voided: Boolean) = replica { h ->
            val cu = uuid()
            h.execute(
                "INSERT INTO app.stock_movement (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind, sku_id, qty_entered, unit_entered, pack_factor, qty_base, reason_code, slip_printed, voided_at) " +
                    "VALUES (CAST('$cu' AS uuid), CAST('$cu' AS uuid), DATE '$day', $sr, $routeId, TIMESTAMPTZ '${day}T03:00:00Z', 1, '$kind', $sku, $qty, 'stick', 1, $qty, " +
                    "${if (reason == null) "NULL" else "'$reason'"}, false, ${if (voided) "now()" else "NULL"})",
            )
        }
        stock("issue", 10, null, false); stock("adjustment", -3, "count_fix", false); stock("issue", 50, null, true); stock("return", 4, null, false)
        assertEquals(preStock + 7, totals().stock_issued.of(sku), "(f) issue + adjustment (signed) only; a voided row and a return are left out")

        // (g) qc: a stored qc_line counts, a voided one does not
        val preQc = totals().qc.of(sku)
        val entry = uuid()
        replica { h ->
            val entryId = h.createQuery("INSERT INTO app.qc_entry (visit_client_uuid, business_date, user_id, route_id, outlet_id) VALUES (CAST('$entry' AS uuid), DATE '$day', $sr, $routeId, ${fresh6[0]}) RETURNING id").mapTo(Long::class.java).one()
            fun line(qty: Int, voided: Boolean) {
                val cu = uuid()
                h.execute(
                    "INSERT INTO app.qc_entry_line (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, qc_entry_id, visit_client_uuid, applied_to_memo, sku_id, " +
                        "fault_type_code, fault_group, qty_base, unit_price_mtk, settlement_mtk, voided_at) VALUES (CAST('$cu' AS uuid), CAST('$entry' AS uuid), DATE '$day', $sr, $routeId, " +
                        "TIMESTAMPTZ '${day}T03:00:00Z', 1, $entryId, CAST('$entry' AS uuid), false, $sku, 'damaged', 'MFC', $qty, 1000, 0, ${if (voided) "now()" else "NULL"})",
                )
            }
            line(5, false); line(11, true)
        }
        assertEquals(preQc + 5, totals().qc.of(sku), "(g) a stored qc line counts, a voided one does not")

        // the digest served by the service carries the same figures; the figures are ordered by sku
        val final = totals()
        assertEquals(final, svc.digest(sr, device.first, device.second, date).business_totals)
        for (list in listOf(final.sold, final.stock_issued, final.qc)) assertEquals(list.map { it.sku_id }, list.map { it.sku_id }.sorted())
    }
}
