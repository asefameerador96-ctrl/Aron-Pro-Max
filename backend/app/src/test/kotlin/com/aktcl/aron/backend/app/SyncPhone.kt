package com.aktcl.aron.backend.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/**
 * N-024 / N-014: one SR phone of an [ApiWorld] mirror (its own division, route `<TAG>-SR-D`, 20 outlets) that captures
 * selling days as `POST /v1/sync/batch` records, and keeps the device-side truth (rows per type, memo balances,
 * money and sticks) the sync oracle compares with the server. Payload shapes follow SyncConvergenceFuzzTest.
 */
    /** One SR phone: identity, its route's outlets, the SKUs it sells, and its cumulative device-side truth. */
class SyncPhone(val day: String, val tag: String, val deviceUuid: String, val deviceId: Long, val userId: Long, val username: String, val routeId: Long, val outlets: List<Long>, val skus: List<Pair<Long, Long>>) {
    var token = ""
    private var saleSeq = 0
    private var otherSeq = 0
    val rows = HashMap<String, MutableSet<String>>() // record type -> client_uuids the device committed
    val memoDue = LinkedHashMap<String, Long>()
    var activeMemos = 0L; var gross = 0L; var due = 0L; var collected = 0L
    val sold = sortedMapOf<String, Long>()
    val accepted = sortedMapOf<String, Int>()

    private fun at(seconds: Int) = Instant.parse("2027-01-02T18:30:00Z").plusSeconds(seconds.toLong()).toString()

    private fun env(type: String, cu: String, family: String, rank: Int, payload: String, at: String) = buildJsonObject {
        put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
        put("business_date", day); put("captured_at", at); put("captured_elapsed_ms", 18_330_000); put("boot_count", 412)
        put("clock_offset_ms", -1840); put("captured_offline", true); put("route_id", routeId); put("bundle_version", "$day:3")
        put("bundle_stale", false); put("config_version", 1)
        put("payload", Json.parseToJsonElement(payload))
    }

    private val fix = """{"purpose":"P","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}"""

    /** A day of 1 to 4 sales; each sale is unique in (outlet, lines, minute), so the double-tap rule never fires. */
    fun captureDay(rnd: Random): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        repeat(rnd.nextInt(1, 5)) {
            val s = saleSeq++
            val time = at(s)
            val outlet = outlets[s % 60 % outlets.size]
            val qty1 = 20 + (s % 60) / outlets.size
            val dueMtk = 1_000L * rnd.nextInt(1, 40)
            val visit = uuid(); val memo = uuid(); val memoNo = "$username-270103-" + (1000 + s).toString()
            val (s1, p1) = skus[0]; val (s2, p2) = skus[1]
            val g = qty1 * p1 + 10 * p2
            out += env("visit", visit, visit, 0, """{"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"$time","sequence_no":1,"planned":true,"fix":${fix.replace("\"P\"", "\"visit_open\"")},
                "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}""", time)
            out += env("memo", memo, visit, 1, """{"visit_client_uuid":"$visit","outlet_id":$outlet,"memo_no":"$memoNo","memo_kind":"sale","committed_at":"$time",
                "price_list_date":"$day","price_type":"outlet","gross_mtk":$g,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,
                "round_adj_mtk":0,"net_mtk":$g,"paid_mtk":${g - dueMtk},"due_mtk":$dueMtk,"is_credit":true,"line_count":2,"discount_line_count":0,"qc_line_count":0,
                "offer_version_ids":[],"rounding_mode":"half_up_paisa"}""", time)
            fun line(no: Int, sku: Long, q: Int, price: Long) = env("memo_line", uuid(), visit, 2, """{"memo_client_uuid":"$memo","line_no":$no,"sku_id":$sku,"line_kind":"sale",
                "qty_entered":$q,"unit_entered":"stick","pack_factor":1,"qty_base":$q,"price_type":"outlet","price_valid_from":"2026-01-01","base_price_mtk":$price,"price_per_qty":1,"gross_mtk":${q * price}}""", time)
            out += line(1, s1, qty1, p1); out += line(2, s2, 10, p2)
            out += env("visit_close", uuid(), visit, 1, """{"visit_client_uuid":"$visit","outcome_code":"sold","call_declined":false,"ended_at":"$time","is_zero_sale":false}""", time)
            var open = dueMtk
            val later = at(20_000 + otherSeq++)
            if (rnd.nextInt(3) == 0) {
                val paid = minOf(dueMtk, 1_000L * rnd.nextInt(1, 40)); val cu = uuid()
                out += env("due_collection", cu, cu, 0, """{"outlet_id":$outlet,"against_memo_client_uuid":"$memo","against_memo_no":"$memoNo","against_memo_business_date":"$day",
                    "amount_mtk":$paid,"is_full_settlement":${paid >= dueMtk},"outstanding_before_mtk":$dueMtk,"payment_mode":"cash"}""", later)
                open -= paid; collected += paid
            }
            if (rnd.nextInt(5) == 0) {
                val cu = uuid()
                out += env("memo_void", cu, cu, 0, """{"memo_client_uuid":"$memo","memo_no":"$memoNo","reason_code":"retailer_cancelled","retailer_ack":true,"fix":${fix.replace("\"P\"", "\"memo_void\"")}}""", later)
                open = 0
            } else {
                activeMemos++; gross += g; due += dueMtk
                sold.merge(s1.toString(), qty1.toLong(), Long::plus); sold.merge(s2.toString(), 10L, Long::plus)
            }
            memoDue[memo] = open
        }
        out.forEach { r -> val t = r.type(); rows.getOrPut(t) { mutableSetOf() } += r.uuid(); accepted.merge(t, 1, Int::plus) }
        return out
    }
}

private fun uuid() = UUID.randomUUID().toString()
fun JsonObject.type() = this["type"]!!.jsonPrimitive.content
fun JsonObject.uuid() = this["client_uuid"]!!.jsonPrimitive.content
fun gz(s: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(s.toByteArray()) } }.toByteArray()

/** A batch body of [records] from [p] (device_counts carry the phone's own counts). */
fun SyncPhone.batch(records: List<JsonObject>, batchUuid: String = UUID.randomUUID().toString(), trigger: String = "manual"): String = buildJsonObject {
    put("batch_uuid", batchUuid); put("device_uuid", deviceUuid); put("schema_version", 1); put("app_version", ApiWorld.APP_VERSION)
    put("trigger", trigger); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
    put("time_anchors", JsonArray(emptyList()))
    put("device_counts", buildJsonObject { put(day, buildJsonObject { accepted.forEach { (t, n) -> put(t, n) } }) })
    put("records", JsonArray(records))
}.toString()

/**
 * Creates a bound, active phone for the SR of mirror [tag] (a copy of the seeded dev phone) and returns it with its
 * route, outlets and two priced SKUs.
 */
// The phones send no X-Device-Proof, so they carry the seed's placeholder key, not the SR phone's real key (ApiWorld
// gives the seeded SR phone a real key pair for the proof-signing contract fixtures; a copy of it would demand proofs).
fun ApiWorld.phoneFor(tag: String, index: Int, day: String): SyncPhone = fresh.db.jdbi.withHandle<SyncPhone, Exception> { h ->
    val device = "00000000-0000-4000-8000-0000000001%02d".format(index)
    val user = "${tag.lowercase()}sr1001"
    h.execute(
        "INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, trust_level, public_key_jwk, public_key_thumbprint, attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version) " +
            "SELECT '$device', d.flavour, d.app_package, d.status, d.device_owner, d.lockdown_level, d.trust_level, CAST('{\"kty\": \"EC\", \"crv\": \"P-256\", \"x\": \"seed-placeholder\", \"y\": \"seed-placeholder\"}' AS jsonb), 'qa-phone-$index', d.attestation_summary, d.app_signing_cert_sha256, z.id, d.device_info, d.app_version FROM app.device d, app.zone z WHERE d.device_uuid = '${ApiWorld.SR_PHONE}' AND z.code = 'Z-$tag'",
    )
    h.execute("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, bound_via) SELECT d.id, u.id, 0, 'support' FROM app.device d, app.app_user u WHERE d.device_uuid = '$device' AND u.username = '$user'")
    fun id(sql: String) = h.createQuery(sql).mapTo(Long::class.java).one()
    val route = id("SELECT id FROM app.route WHERE code = '$tag-SR-D'")
    val skus = h.createQuery("SELECT s.id, p.amount_mtk FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' WHERE p.amount_mtk > 0 ORDER BY s.id LIMIT 2")
        .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
    SyncPhone(
        day, tag, device, id("SELECT id FROM app.device WHERE device_uuid = '$device'"), id("SELECT id FROM app.app_user WHERE username = '$user'"), user, route,
        h.createQuery("SELECT id FROM app.outlet WHERE route_id = :r ORDER BY id").bind("r", route).mapTo(Long::class.java).list(), skus,
    )
}
