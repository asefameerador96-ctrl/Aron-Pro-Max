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
    private val now = Instant.parse("2027-01-03T04:00:00Z")
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

    private fun envelope(type: String, cu: String, family: String, rank: Int, payload: JsonObject, route: Long, bd: String = day) = buildJsonObject {
        val at = Instant.parse("2027-01-02T18:30:00Z").plusSeconds(minute.incrementAndGet() * 60L).toString()
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

    private fun closeJson(visit: String, zero: Boolean, reason: String?, outcome: String = "zero_sale_stock_ok") = json(
        """{"visit_client_uuid":"$visit","outcome_code":"$outcome","call_declined":false,"ended_at":"${day}T03:50:00.000Z","is_zero_sale":$zero${if (reason == null) "" else ",\"zero_sale_reason_code\":\"$reason\""}}""",
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

    @Test
    fun aReasonOnASaleAnUnknownCodeOrARetiredCodeIsRefusedForGoodAndNothingChanges() {
        val outlet = outletId
        val v = uuid()
        send(sr, listOf(envelope("visit", v, v, 0, visitJson(outlet, seq = 20), routeId)))
        val cases = mapOf("sale with reason" to closeJson(v, false, "stock_available"), "unknown code" to closeJson(v, true, "battery_dead"), "bad shape" to closeJson(v, true, "Stock Available"))
        for ((name, payload) in cases) {
            val rec = envelope("visit_close", uuid(), v, 1, payload, routeId)
            val a = send(sr, listOf(rec)).ack(rec)
            assertTrue(a.startsWith("rejected schema_invalid"), "$name: $a")
        }
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.code_list_item SET valid_to = DATE '2026-12-01' WHERE list_key = 'zero_sale_reason' AND code = 'absence_of_owner'") }
        val retired = envelope("visit_close", uuid(), v, 1, closeJson(v, true, "absence_of_owner"), routeId)
        assertTrue(send(sr, listOf(retired)).ack(retired).startsWith("rejected schema_invalid"), "a retired code is refused")
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.code_list_item SET valid_to = NULL WHERE list_key = 'zero_sale_reason' AND code = 'absence_of_owner'") }
        assertEquals(null, str("SELECT zero_sale_reason_code FROM app.visit WHERE client_uuid = '$v'"), "nothing was stored")
        assertEquals(null, str("SELECT close_client_uuid::text FROM app.visit WHERE client_uuid = '$v'"), "the visit stays open")
        // a close without a reason (older app) is still valid, and the first close stands
        val ok = envelope("visit_close", uuid(), v, 1, closeJson(v, true, null), routeId)
        assertEquals("accepted", send(sr, listOf(ok)).ack(ok))
        val second = envelope("visit_close", uuid(), v, 1, closeJson(v, true, "stock_available"), routeId)
        val secondAck = send(sr, listOf(second)).ack(second)
        assertTrue(secondAck.startsWith("rejected") || secondAck.startsWith("quarantined"), "a second close with another uuid is refused, got: $secondAck")
        assertEquals(null, str("SELECT zero_sale_reason_code FROM app.visit WHERE client_uuid = '$v'"), "the first close stands")
    }

    // ---------------------------------------------------------------------------------------------- (b) call photo

    @Test
    fun aCallPhotoOrAReasonedSkipIsStoredOnceAndTheTwoNeverMix() {
        val photo = uuid()
        val o2 = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.outlet WHERE route_id = $routeId ORDER BY id OFFSET 10 LIMIT 1").mapTo(Long::class.java).one() }
        val v1 = uuid()
        val withPhoto = envelope("visit", v1, v1, 0, visitJson(o2, ""","call_photo_uuid":"$photo"""", 30), routeId)
        repeat(3) { send(sr, listOf(withPhoto)) }
        assertEquals(1L, count("SELECT count(*) FROM app.visit WHERE client_uuid = '$v1'"))
        assertEquals(photo, str("SELECT call_photo_uuid::text FROM app.visit WHERE client_uuid = '$v1'"))
        val o3 = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.outlet WHERE route_id = $routeId ORDER BY id OFFSET 11 LIMIT 1").mapTo(Long::class.java).one() }
        val v2 = uuid()
        val skip = envelope("visit", v2, v2, 0, visitJson(o3, ""","call_photo_skip_reason_code":"camera_unavailable"""", 31), routeId)
        assertEquals("accepted", send(sr, listOf(skip)).ack(skip))
        assertEquals("camera_unavailable", str("SELECT call_photo_skip_reason_code FROM app.visit WHERE client_uuid = '$v2'"))
        val o4 = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.outlet WHERE route_id = $routeId ORDER BY id OFFSET 12 LIMIT 1").mapTo(Long::class.java).one() }
        for ((name, extra) in mapOf(
            "both" to ""","call_photo_uuid":"${uuid()}","call_photo_skip_reason_code":"other"""",
            "bad skip code" to ""","call_photo_skip_reason_code":"lazy"""",
            "bad uuid" to ""","call_photo_uuid":"NOT-A-UUID"""",
        )) {
            val v = uuid()
            val rec = envelope("visit", v, v, 0, visitJson(o4, extra, 32), routeId)
            val a = send(sr, listOf(rec)).ack(rec)
            assertTrue(a.startsWith("rejected"), "$name: $a")
            assertEquals(0L, count("SELECT count(*) FROM app.visit WHERE client_uuid = '$v'"), "$name: nothing stored")
        }
        // a photo record of purpose outlet_call must belong to a visit
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
        val cat = str("SELECT category_code FROM app.sku WHERE id = ${priced[0]}") ?: ""
        fun List<com.aktcl.aron.backend.sync.CategoryQtyBaseDto>.of(c: String) = firstOrNull { it.category_code == c }?.qty_base ?: 0L
        assertEquals(before.sold.of(cat) + expectedBase, after.sold.of(cat), "sold quantity in base units under the SKU's category")
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

    @Test
    fun aSecondNewShopRequestForTheSameShopIsAnsweredDuplicateAndStoresNothing() {
        val cluster = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.cluster WHERE zone_id = (SELECT zone_id FROM app.route WHERE id = $routeId) ORDER BY id LIMIT 1").mapTo(Long::class.java).one() }
        val proposed = """{"name":"  Rahim  Store ","owner_name":"Rahim Uddin","contact_number":"01711223344","cluster_id":$cluster}"""
        val first = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("new", null, proposed), routeId)
        val again = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("new", null, proposed.replace("  Rahim  Store ", "rahim store")), routeId)
        val other = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("new", null, proposed.replace("01711223344", "01711223355")), routeId)
        assertEquals("accepted", send(sr, listOf(first)).ack(first))
        assertEquals("duplicate", send(sr, listOf(again)).ack(again).substringBefore(' '), "same shop, new uuid (the form was reopened)")
        assertEquals("accepted", send(sr, listOf(other)).ack(other), "another mobile number is another shop")
        repeat(2) { send(sr, listOf(first, again, other).shuffled(Random(it))) }
        assertEquals(2L, count("SELECT count(*) FROM app.outlet_change_request WHERE request_type = 'new' AND user_id = $sr"), "one row per shop")
    }

    @Test
    fun anInfoRequestMayCarryAClusterOfTheOutletsZoneAndNoOtherCluster() {
        val zone = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT zone_id FROM app.route WHERE id = $routeId").mapTo(Long::class.java).one() }
        val inZone = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.cluster WHERE zone_id = $zone ORDER BY id DESC LIMIT 1").mapTo(Long::class.java).one() }
        val elsewhere = fresh.db.jdbi.withHandle<Long?, Exception> { h -> h.createQuery("SELECT id FROM app.cluster WHERE zone_id <> $zone ORDER BY id LIMIT 1").mapTo(Long::class.java).findOne().orElse(null) }
        val ok = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("info", outletId, """{"name":"Karim Store","cluster_id":$inZone}"""), routeId)
        assertEquals("accepted", send(sr, listOf(ok)).ack(ok))
        val ghost = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("info", outletId, """{"cluster_id":987654321}"""), routeId)
        assertTrue(send(sr, listOf(ghost)).ack(ghost).startsWith("rejected schema_invalid"), "an unknown cluster")
        if (elsewhere != null) {
            val far = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("info", outletId, """{"cluster_id":$elsewhere}"""), routeId)
            assertTrue(send(sr, listOf(far)).ack(far).startsWith("rejected schema_invalid"), "a cluster of another zone")
        }
        // a route_add may carry the cluster (the end-to-end approval is in SrManualSv1ApiTest)
        val add = envelope("outlet_change_request", uuid(), uuid(), 0, requestPayload("route_add", outletId, """{"cluster_id":$inZone}"""), routeId)
        assertEquals("accepted", send(sr, listOf(add)).ack(add))
    }
}
