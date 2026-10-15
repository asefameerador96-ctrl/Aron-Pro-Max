package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.AuditWriter
import com.aktcl.aron.backend.masterdata.AdminSupport.bad
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.Money
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Contract `PaperBackfillRequest` and `PaperBackfillResult`. */
@Serializable
data class PaperLineIn(val sku_id: Long, val qty_base: Long, val line_kind: String)

@Serializable
data class PaperBackfillIn(
    val client_uuid: String, val memo_no: String, val business_date: String, val user_id: Long, val route_id: Long, val outlet_id: Long,
    val lines: List<PaperLineIn>, val discount_mtk: Long? = null, val paid_mtk: Long, val reason: String,
)

@Serializable
data class PaperBackfillOut(val memo_client_uuid: String, val status: String, val net_mtk: Long)

/** What the ingest entry point (backend-core `IngestService.ingestManual`) answers, without this module depending on `sync`. */
data class PaperAck(val clientUuid: String, val type: String, val status: String, val code: String?, val serverId: Long?)
data class PaperIngestResult(val acks: List<PaperAck>, val duplicateMemoNo: Boolean, val stored: Boolean)

fun interface PaperIngest {
    fun ingest(actor: AronPrincipal, forUserId: Long, entryUuid: String, records: List<JsonObject>): PaperIngestResult
}

class PaperBackfillDeps(val db: Database, val guard: AuthGuardDeps, val ingest: PaperIngest?, val clock: AronClock = AronClock.SYSTEM)

/**
 * F-API-038 (T1, money): a dead-phone day is keyed from the printed memo. The request carries quantities only; the server prices every
 * line from `app.sku_price` at the business date for the outlet's price type (the browser never sends a price), builds the visit
 * (`web_entry`, no fix), the memo and its lines as sync records and hands them to `ingestManual` (one transaction, the same per-record checks as
 * a phone batch). Idempotent by `client_uuid`: the memo's uuid is the request's, the visit and line uuids derive from it, so a retry
 * resends identical records. A memo number already stored under another uuid answers `duplicate_memo_no` and writes nothing.
 * Roles SUPPORT, ADMIN, SUPERADMIN (docs/24 s8.5). Discounts and promotion lines are deferred programmes (docs/27): only `sale` lines, discount 0.
 */
fun Route.paperBackfillRoutes(d: PaperBackfillDeps) {
    authenticated(d.guard) {
        post("/admin/data-entry") { call.respond(createBackfill(call.principal, call.receiveStrict(PaperBackfillIn.serializer()), call.requestId, d)) }
    }
}

private val MEMO_NO = Regex("^[a-z][a-z0-9]{3,31}-\\d{6}-\\d{3,4}$")
private val STAMP = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
private val ROLES = setOf(Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)

/** A stable v4-shaped uuid for a record of an entry, so a retry of the same request builds the same records. */
private fun derived(entry: String, part: String): String {
    val h = MessageDigest.getInstance("SHA-256").digest("paper-backfill|$entry|$part".toByteArray())
    h[6] = ((h[6].toInt() and 0x0f) or 0x40).toByte(); h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = h.take(16).joinToString("") { "%02x".format(it) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
}

private class Priced(val skuId: Long, val baseUnit: String, val perPack: Int, val qty: Long, val kind: String, val price: Long, val per: Long, val from: LocalDate, val gross: Long)

private fun createBackfill(p: AronPrincipal, req: PaperBackfillIn, requestId: String?, d: PaperBackfillDeps): PaperBackfillOut {
    if (p.isPhone || p.role !in ROLES) throw AdminSupport.forbidden("paper backfill is keyed by SUPPORT, ADMIN or SUPERADMIN on the web")
    val ingest = d.ingest ?: throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "ingest is not available")
    val entry = AdminSupport.uuid(req.client_uuid, "body.client_uuid").toString()
    if (!MEMO_NO.matches(req.memo_no)) bad("body.memo_no")
    val bd = AdminSupport.date(req.business_date, "body.business_date")
    // The printed number is <username>-<yyMMdd>-<seq> (docs/24 s7.5): its date is the business date, so the same paper memo cannot be keyed
    // under another date (the table is unique on memo_no + business_date only) and the number belongs to the SR it is keyed for.
    if (req.memo_no.split('-')[1] != "%02d%02d%02d".format(bd.year % 100, bd.monthValue, bd.dayOfMonth)) bad("body.memo_no", "memo_no_date_mismatch")
    if (req.user_id < 1) bad("body.user_id"); if (req.route_id < 1) bad("body.route_id"); if (req.outlet_id < 1) bad("body.outlet_id")
    if (req.lines.isEmpty() || req.lines.size > 60) bad("body.lines", "length")
    req.lines.forEachIndexed { i, l ->
        if (l.qty_base !in 1..10_000_000L) bad("body.lines[$i].qty_base", "out_of_range")
        if (l.line_kind != "sale") bad("body.lines[$i].line_kind", "line_kind_not_supported")
        if (l.sku_id < 1) bad("body.lines[$i].sku_id")
    }
    if ((req.discount_mtk ?: 0L) != 0L) bad("body.discount_mtk", "discount_not_supported")
    if (req.paid_mtk < 0) bad("body.paid_mtk")
    AdminSupport.noNul("body.reason", req.reason)
    val reason = AdminSupport.reason(req.reason, "body.reason")

    val username = d.db.jdbi.withHandle<String?, Exception> { h -> h.createQuery("SELECT username FROM app.app_user WHERE id = :u").bind("u", req.user_id).mapTo(String::class.java).findOne().orElse(null) }
        ?: bad("body.user_id", "unknown_user")
    if (req.memo_no.substringBefore('-') != username) bad("body.memo_no", "memo_no_user_mismatch")

    // Prices from the database at the business date for the outlet's price type; unknown outlet answers like an unreachable one.
    val (priced, priceType) = d.db.jdbi.withHandle<Pair<List<Priced>, String>, Exception> { h ->
        val type = h.createQuery("SELECT price_type FROM app.outlet WHERE id = :o").bind("o", req.outlet_id).mapTo(String::class.java).findOne().orElse(null)
            ?: throw AdminSupport.forbidden("the outlet does not exist or is outside your reach")
        req.lines.mapIndexed { i, l ->
            val sku = h.createQuery("SELECT base_unit, base_per_pack FROM app.sku WHERE id = :s").bind("s", l.sku_id).map { rs, _ -> rs.getString(1) to rs.getInt(2) }.findOne().orElse(null) ?: bad("body.lines[$i].sku_id", "unknown_sku")
            val pr = h.createQuery("SELECT amount_mtk, per_base_qty, valid_from FROM app.sku_price WHERE sku_id = :s AND price_type = :t AND valid_from <= :d AND (valid_to IS NULL OR valid_to > :d)")
                .bind("s", l.sku_id).bind("t", type).bind("d", bd).map { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getObject(3, LocalDate::class.java)) }.findOne().orElse(null)
                ?: bad("body.lines[$i].sku_id", "no_price_on_date")
            val gross = try { Money.lineGrossMtk(l.qty_base, pr.first, pr.second) } catch (e: ArithmeticException) { bad("body.lines[$i].qty_base", "amount_overflow") }
            Priced(l.sku_id, sku.first, sku.second, l.qty_base, l.line_kind, pr.first, pr.second, pr.third, gross)
        } to type
    }
    val gross = try { priced.fold(0L) { a, l -> Math.addExact(a, l.gross) } } catch (e: ArithmeticException) { bad("body.lines", "amount_overflow") }
    val net = Money.roundToPaisaHalfUp(gross)
    if (req.paid_mtk > net) bad("body.paid_mtk", "paid_exceeds_net")
    val due = net - req.paid_mtk

    // Records: start of the Dhaka business day (never in the future for a date up to today), fixed per entry so a retry is identical.
    val dayStart = bd.atStartOfDay().minusHours(6).toInstant(ZoneOffset.UTC)
    fun at(min: Long): String = STAMP.format(dayStart.plusSeconds(min * 60))
    val visitId = derived(entry, "visit"); val memoId = entry
    fun env(type: String, id: String, rank: Int, minute: Long, payload: JsonObject) = buildJsonObject {
        put("type", type); put("client_uuid", id); put("family_uuid", visitId); put("rank", rank); put("schema_version", 1)
        put("business_date", bd.toString()); put("captured_at", at(minute)); put("captured_elapsed_ms", 0); put("boot_count", 0)
        put("clock_offset_ms", 0); put("captured_offline", false); put("route_id", req.route_id); put("bundle_version", "$bd:0")
        put("bundle_stale", false); put("config_version", 1); put("payload", payload)
    }
    val records = mutableListOf<JsonObject>()
    records += env("visit", visitId, 0, 0, buildJsonObject {
        put("visit_kind", "web_entry"); put("outlet_id", req.outlet_id); put("opened_at", at(0)); put("sequence_no", 1); put("planned", true)
        put("geo", buildJsonObject { put("verdict", "no_fix"); put("radius_m_used", 100); put("max_accuracy_m_used", 100); put("location_basis", "master"); put("action", "sale_allowed") })
    })
    records += env("memo", memoId, 1, 1, buildJsonObject {
        put("visit_client_uuid", visitId); put("outlet_id", req.outlet_id); put("memo_no", req.memo_no); put("memo_kind", "sale"); put("committed_at", at(1))
        put("price_list_date", bd.toString()); put("price_type", priceType); put("gross_mtk", gross); put("offer_discount_mtk", 0); put("drp_discount_mtk", 0)
        put("qc_deduction_mtk", 0); put("round_adj_mtk", net - gross); put("net_mtk", net); put("paid_mtk", req.paid_mtk); put("due_mtk", due)
        put("is_credit", due > 0); put("line_count", priced.size); put("discount_line_count", 0); put("qc_line_count", 0)
        put("offer_version_ids", JsonArray(emptyList())); put("rounding_mode", "half_up_paisa")
    })
    priced.forEachIndexed { i, l ->
        records += env("memo_line", derived(entry, "line:$i"), 2, 1, buildJsonObject {
            put("memo_client_uuid", memoId); put("line_no", i + 1); put("sku_id", l.skuId); put("line_kind", l.kind); put("qty_entered", l.qty); put("unit_entered", l.baseUnit)
            put("pack_factor", l.perPack); put("qty_base", l.qty); put("price_type", priceType); put("price_valid_from", l.from.toString()); put("base_price_mtk", l.price)
            put("price_per_qty", l.per); put("gross_mtk", l.gross)
        })
    }

    val res = ingest.ingest(p, req.user_id, entry, records)
    if (res.duplicateMemoNo) {
        val stored = d.db.jdbi.withHandle<Pair<String, Long>?, Exception> { h ->
            h.createQuery("SELECT client_uuid::text, net_mtk FROM app.memo WHERE memo_no = :n AND business_date = :d LIMIT 1").bind("n", req.memo_no).bind("d", bd)
                .map { rs, _ -> rs.getString(1) to rs.getLong(2) }.findOne().orElse(null)
        }
        return PaperBackfillOut(stored?.first ?: memoId, "duplicate_memo_no", stored?.second ?: net)
    }
    if (!res.stored) {
        val failed = res.acks.firstOrNull { it.status != "accepted" && it.status != "duplicate" } ?: res.acks.first()
        val code = failed.code ?: failed.status
        val problem = when (code) {
            "business_date_out_of_window" -> ProblemCode.ERR_ENTRY_WINDOW_CLOSED
            "payload_conflict" -> ProblemCode.ERR_CONFLICT
            else -> ProblemCode.ERR_VALIDATION
        }
        throw ApiProblem(problem, "the entry was not stored: $code", errors = if (problem == ProblemCode.ERR_VALIDATION) listOf(FieldError("body", code)) else emptyList())
    }
    val replayed = res.acks.all { it.status == "duplicate" }
    // The audit row of the keying: written once per entry (a retry after a crash between ingest and audit still gets one).
    d.db.jdbi.useTransaction<Exception> { h ->
        h.createQuery("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))").bind("k", "paper-audit:$entry").mapTo(String::class.java).findOne()
        val has = h.createQuery("SELECT EXISTS (SELECT 1 FROM app.audit_log WHERE entity = 'paper_backfill' AND entity_id = :e AND action = 'create')").bind("e", entry).mapTo(Boolean::class.java).one()
        if (!has) AuditWriter.write(h, p, "paper_backfill", entry, "create", null, buildJsonObject {
            put("memo_no", req.memo_no); put("user_id", req.user_id); put("route_id", req.route_id); put("outlet_id", req.outlet_id)
            put("business_date", bd.toString()); put("net_mtk", net); put("paid_mtk", req.paid_mtk); put("lines", priced.size)
        }, reason, requestId)
    }
    return PaperBackfillOut(memoId, if (replayed) "replayed" else "created", net)
}
