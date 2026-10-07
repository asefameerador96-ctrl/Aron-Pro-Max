package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.AuditWriter
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.RequestJson
import com.aktcl.aron.backend.platform.ResponseJson
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

// ---------------------------------------------------------------------------------------------------------------------
// F-API-080 (preview, publish, list) and F-ADM-081 (price-change rails: second approver above cfg.price.max_change_pct).
// NOT built, as the backlog says: the same-day correction lane and the break-glass restore of the source row. The contract
// has no `correct` path either, so nothing is exposed for it.
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Storage of publish batches (preview record, pending approval, decided). The db lane has no table for it yet, so this is the
 * proposed forward-only migration (docs/24 s12.1 lists `price_batch`); the lead copies it to db/migrations as the next version.
 * Tests apply it to the fresh database. Idempotent DDL so applying it twice is harmless.
 */
object PriceBatchSchema {
    const val DDL = """
CREATE TABLE IF NOT EXISTS app.price_batch (
  batch_uuid         uuid PRIMARY KEY,
  status             text NOT NULL CHECK (status IN ('previewed','pending_approval','published','rejected')),
  valid_from         date NOT NULL,
  fingerprint        text NOT NULL CHECK (length(fingerprint) = 64),
  price_rows         jsonb NOT NULL CHECK (jsonb_typeof(price_rows) = 'array'),
  row_count          int NOT NULL CHECK (row_count BETWEEN 1 AND 1000),
  change_reason      text CHECK (length(change_reason) <= 500),
  backdate           boolean NOT NULL DEFAULT false,
  max_change_pct     numeric(12,2),
  previewed_by       bigint REFERENCES app.app_user(id),
  submitted_by       bigint REFERENCES app.app_user(id),
  submitted_at       timestamptz,
  decided_by         bigint REFERENCES app.app_user(id),
  decided_at         timestamptz,
  decision_note      text CHECK (length(decision_note) <= 500),
  price_list_version bigint,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  CHECK (decided_by IS NULL OR decided_by <> submitted_by)
);
CREATE INDEX IF NOT EXISTS price_batch_pending ON app.price_batch (status) WHERE status = 'pending_approval';
"""
}

/** Exact integer arithmetic for the percent rail: prices are milli-taka integers, so no floating point enters the decision. */
object PriceMath {
    private fun big(v: Long) = BigInteger.valueOf(v)

    /** |new/newPer - old/oldPer| / (old/oldPer), as the exact numerator and denominator: num = |new*oldPer - old*newPer|, den = old*newPer. */
    private fun num(oldAmt: Long, oldPer: Int, newAmt: Long, newPer: Int): BigInteger = (big(newAmt) * big(oldPer.toLong()) - big(oldAmt) * big(newPer.toLong())).abs()
    private fun den(oldAmt: Long, newPer: Int): BigInteger = big(oldAmt) * big(newPer.toLong())

    /**
     * True when the move is strictly above [thresholdPct] (exactly at the threshold is allowed). A move from a zero price to a
     * non-zero one has no finite percent and always needs the second person.
     */
    fun exceeds(oldAmt: Long, oldPer: Int, newAmt: Long, newPer: Int, thresholdPct: BigDecimal): Boolean {
        if (oldAmt == 0L) return newAmt > 0L
        return BigDecimal(num(oldAmt, oldPer, newAmt, newPer) * BigInteger.valueOf(100)) > thresholdPct * BigDecimal(den(oldAmt, newPer))
    }

    /** The percent shown in the preview, two decimals half-up; display only, never used to decide. A zero-to-non-zero move shows 100.00. */
    fun displayPct(oldAmt: Long, oldPer: Int, newAmt: Long, newPer: Int): BigDecimal {
        if (oldAmt == 0L) return if (newAmt > 0L) BigDecimal("100.00") else BigDecimal("0.00")
        return BigDecimal(num(oldAmt, oldPer, newAmt, newPer) * BigInteger.valueOf(100)).divide(BigDecimal(den(oldAmt, newPer)), 2, RoundingMode.HALF_UP)
    }
}

@Serializable
data class SkuPriceOut(val id: Long, val sku_id: Long, val price_type: String, val amount_mtk: Long, val per_base_qty: Int, val valid_from: String, val valid_to: String?)

@Serializable
data class SkuPricePageOut(val items: List<SkuPriceOut>, val next_cursor: String?)

@Serializable
data class SkuPriceListOut(val items: List<SkuPriceOut>, val price_list_version: Long)

@Serializable
data class PriceRowIn(val sku_id: Long, val price_type: String, val amount_mtk: Long, val per_base_qty: Int = 1)

@Serializable
data class PricePublishIn(val batch_uuid: String, val valid_from: String, val prices: List<PriceRowIn>, val change_reason: String)

@Serializable
data class PricePreviewOut(
    val batch_uuid: String, val skus_affected: Int, val price_types: List<String>, val outlets_affected: Int, val devices_affected: Int,
    val max_change_pct: JsonElement, val approval_required: Boolean,
)

@Serializable
data class PriceBatchOut(val batch_uuid: String, val status: String, val valid_from: String, val rows: Int, val decided_by_user_id: Long?, val decided_at: String?)

@Serializable
data class DecisionIn(val decision: String, val note: String? = null)

/**
 * [config] reads `cfg.price.max_change_pct` (pass the platform DbServerConfig). [requirePreview]: when true (default, F-ADM-081 "mandatory
 * preview") a publish is refused with ERR_REQUEST_STATE unless the same batch_uuid and rows were previewed first.
 */
class AdminPricesDeps(
    val db: Database, val config: ServerConfig, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM, val requirePreview: Boolean = true,
)

private const val BACKDATE_PERMISSION = "finance.backdate"
private val PRICE_TYPES = listOf("outlet", "cc", "distributor", "reporting", "nto")
private val SELLING_TYPES = setOf("outlet", "cc", "distributor")
private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private const val LIST_VERSION_KEY = "cfg.price.list_version"
/** Header of the publish answer: the contract returns 201 SkuPriceList for both outcomes, so the state travels in a header. */
const val BATCH_STATUS_HEADER = "X-Aron-Price-Batch-Status"

/** The stored percent column is numeric(12,2); a larger (contract-valid) move still needs approval, only the shown figure is capped. */
private val PCT_CAP = BigDecimal("9999999999.99")

private data class Analysis(val rows: List<PriceRowIn>, val skus: Int, val types: List<String>, val outlets: Int, val devices: Int, val maxPct: BigDecimal, val approval: Boolean)

private class StoredBatch(val status: String, val fingerprint: String, val validFrom: LocalDate, val rows: List<PriceRowIn>, val reason: String?, val backdate: Boolean, val submittedBy: Long?,
                          val decidedBy: Long?, val decidedAt: OffsetDateTime?, val listVersion: Long?, val note: String?)

/** `listPrices`, `previewPrices`, `createPrices`, `decidePriceBatch`. */
fun Route.adminPricesRoutes(d: AdminPricesDeps) {
    authenticated(d.guard) {
        get("/admin/prices") { call.respond(listPrices(call, d)) }
        post("/admin/prices/preview") { call.respond(preview(call, d)) }
        post("/admin/prices") { publish(call, d) }
        post("/admin/prices/batches/{batch_uuid}/decision") { call.respond(decide(call, d)) }
    }
}

private fun today(d: AdminPricesDeps): LocalDate = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()

private fun priceOf(rs: java.sql.ResultSet) = SkuPriceOut(
    rs.getLong("id"), rs.getLong("sku_id"), rs.getString("price_type"), rs.getLong("amount_mtk"), rs.getInt("per_base_qty"), rs.getObject("valid_from", LocalDate::class.java).toString(),
    rs.getObject("valid_to", LocalDate::class.java)?.toString(),
)

private fun listPrices(call: ApplicationCall, d: AdminPricesDeps): SkuPricePageOut {
    val p = call.masterReader()
    val q = call.request.queryParameters
    val limit = call.pageLimit(); val cursor = call.pageCursor(); val sku = call.queryId("sku_id")
    val type = q["price_type"]?.also { if (it !in PRICE_TYPES) adminBad("query.price_type", "not_in_enum") }
    val on = q["valid_on"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: adminBad("query.valid_on") }
    val rows = d.db.jdbi.withHandle<List<SkuPriceOut>, Exception> { h ->
        val where = mutableListOf("TRUE")
        if (sku != null) where += "sku_id = :sku"
        if (type != null) where += "price_type = :type"
        if (on != null) where += "valid_from <= :on AND (valid_to IS NULL OR valid_to > :on)"
        if (cursor != null) where += "id > :cursor"
        // A TSO sees the three selling price types only; reporting and nto prices are for the office roles.
        if (p.role == Role.TSO) where += "price_type IN ('outlet','cc','distributor')"
        val query = h.createQuery("SELECT id, sku_id, price_type, amount_mtk, per_base_qty, valid_from, valid_to FROM app.sku_price WHERE ${where.joinToString(" AND ")} ORDER BY id LIMIT :lim").bind("lim", limit + 1)
        if (sku != null) query.bind("sku", sku)
        if (type != null) query.bind("type", type)
        if (on != null) query.bind("on", on)
        if (cursor != null) query.bind("cursor", cursor)
        query.map { rs, _ -> priceOf(rs) }.list()
    }
    val page = rows.take(limit)
    return SkuPricePageOut(page, if (rows.size > limit) page.last().id.toString() else null)
}

private fun fingerprint(validFrom: LocalDate, rows: List<PriceRowIn>): String {
    val canon = validFrom.toString() + "|" + rows.sortedWith(compareBy({ it.sku_id }, { it.price_type })).joinToString("|") { "${it.sku_id}:${it.price_type}:${it.amount_mtk}:${it.per_base_qty}" }
    return MessageDigest.getInstance("SHA-256").digest(canon.toByteArray()).joinToString("") { "%02x".format(it) }
}

private fun parseRequest(call: ApplicationCall, text: String): Pair<PricePublishIn, LocalDate> {
    val req = try { RequestJson.decodeFromString<PricePublishIn>(text) } catch (e: kotlinx.serialization.SerializationException) {
        throw ApiProblem(ProblemCode.ERR_VALIDATION, "malformed request body", errors = listOf(FieldError("body", "invalid_body")))
    }
    // The decoder accepts quoted numbers; money and ids must arrive as JSON numbers.
    (Json.parseToJsonElement(text).jsonObject["prices"]?.jsonArray ?: JsonArray(emptyList())).forEachIndexed { i, r ->
        listOf("sku_id", "amount_mtk", "per_base_qty").forEach { k -> (r.jsonObject[k] as? JsonPrimitive)?.let { v -> if (v.isString) adminBad("body.prices[$i].$k", "not_an_integer") } }
    }
    if (!UUID_V4.matches(req.batch_uuid)) adminBad("body.batch_uuid", "not_a_uuid")
    val from = runCatching { LocalDate.parse(req.valid_from) }.getOrNull() ?: adminBad("body.valid_from")
    if (req.change_reason.length > 500 || req.change_reason.trim().length < 10) adminBad("body.change_reason", "length")
    if (req.prices.isEmpty() || req.prices.size > 1000) adminBad("body.prices", "out_of_range")
    val seen = HashSet<Pair<Long, String>>()
    req.prices.forEachIndexed { i, r ->
        if (r.sku_id < 1) adminBad("body.prices[$i].sku_id", "out_of_range")
        if (r.price_type !in PRICE_TYPES) adminBad("body.prices[$i].price_type", "not_in_enum")
        if (r.amount_mtk < 0) adminBad("body.prices[$i].amount_mtk", "out_of_range")
        if (r.per_base_qty !in 1..1000) adminBad("body.prices[$i].per_base_qty", "out_of_range")
        if (!seen.add(r.sku_id to r.price_type)) adminBad("body.prices[$i]", "duplicate_sku_and_type")
    }
    return req to from
}

/** A price may start from the next Dhaka midnight; today or earlier is back-dating and needs `finance.backdate`. */
private fun checkDate(p: AronPrincipal, from: LocalDate, d: AdminPricesDeps): Boolean {
    val backdated = !from.isAfter(today(d))
    if (backdated && BACKDATE_PERMISSION !in p.permissions) throw ApiProblem(ProblemCode.ERR_MASTER_EFFECTIVE_DATE_PAST, "back-dating a price needs the $BACKDATE_PERMISSION permission", errors = listOf(FieldError("body.valid_from", "past")))
    return backdated
}

private fun threshold(d: AdminPricesDeps): BigDecimal = BigDecimal(d.config.value("cfg.price.max_change_pct").jsonPrimitive.content)

private fun analyse(h: Handle, rows: List<PriceRowIn>, from: LocalDate, d: AdminPricesDeps): Analysis {
    val ids = rows.map { it.sku_id }.distinct()
    val known = h.createQuery("SELECT id FROM app.sku WHERE id IN (<ids>)").bindList("ids", ids).mapTo(Long::class.java).list().toSet()
    rows.forEachIndexed { i, r -> if (r.sku_id !in known) adminBad("body.prices[$i].sku_id", "not_found") }
    val limit = threshold(d)
    var max = BigDecimal("0.00"); var approval = false
    for (r in rows) {
        val live = h.createQuery("SELECT amount_mtk, per_base_qty FROM app.sku_price WHERE sku_id = :s AND price_type = :t AND valid_from <= :d AND (valid_to IS NULL OR valid_to > :d)")
            .bind("s", r.sku_id).bind("t", r.price_type).bind("d", from).map { rs, _ -> rs.getLong(1) to rs.getInt(2) }.findOne().orElse(null) ?: continue
        val pct = PriceMath.displayPct(live.first, live.second, r.amount_mtk, r.per_base_qty)
        if (pct > max) max = pct
        if (PriceMath.exceeds(live.first, live.second, r.amount_mtk, r.per_base_qty, limit)) approval = true
    }
    val types = PRICE_TYPES.filter { t -> rows.any { it.price_type == t } }
    val selling = types.filter { it in SELLING_TYPES }
    var outlets = 0; var devices = 0
    if (selling.isNotEmpty()) {
        outlets = h.createQuery("SELECT count(*) FROM app.outlet WHERE status = 'active' AND price_type IN (<t>)").bindList("t", selling).mapTo(Int::class.java).one()
        devices = h.createQuery(
            "SELECT count(DISTINCT dv.id) FROM app.device dv JOIN app.device_binding b ON b.device_id = dv.id AND b.status = 'active' WHERE dv.status = 'active' AND EXISTS (" +
                "SELECT 1 FROM app.route_assignment a JOIN app.outlet o ON o.route_id = a.route_id AND o.status = 'active' AND o.price_type IN (<t>) " +
                "WHERE a.user_id = b.user_id AND a.ended_at IS NULL AND a.valid_from <= :today AND (a.valid_to IS NULL OR a.valid_to > :today))",
        ).bindList("t", selling).bind("today", today(d)).mapTo(Int::class.java).one()
    }
    return Analysis(rows, ids.size, types, outlets, devices, max, approval)
}

private fun previewOf(batch: String, a: Analysis) = PricePreviewOut(batch, a.skus, a.types, a.outlets, a.devices, JsonPrimitive(a.maxPct.min(PCT_CAP)), a.approval)

private fun rowsJson(rows: List<PriceRowIn>): String = ResponseJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(PriceRowIn.serializer()), rows)

private fun loadBatch(h: Handle, uuid: UUID, lock: Boolean): StoredBatch? = h.createQuery(
    "SELECT status, fingerprint, valid_from, CAST(price_rows AS text) AS r, change_reason, backdate, submitted_by, decided_by, decided_at, price_list_version, decision_note FROM app.price_batch WHERE batch_uuid = :b" + if (lock) " FOR UPDATE" else "",
).bind("b", uuid).map { rs, _ ->
    StoredBatch(
        rs.getString("status"), rs.getString("fingerprint"), rs.getObject("valid_from", LocalDate::class.java),
        Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PriceRowIn.serializer()), rs.getString("r")), rs.getString("change_reason"), rs.getBoolean("backdate"),
        rs.getObject("submitted_by") as Long?, rs.getObject("decided_by") as Long?, rs.getObject("decided_at", OffsetDateTime::class.java), rs.getObject("price_list_version") as Long?, rs.getString("decision_note"),
    )
}.findOne().orElse(null)

private suspend fun preview(call: ApplicationCall, d: AdminPricesDeps): PricePreviewOut {
    val p = call.masterWriter()
    val (req, from) = parseRequest(call, call.receiveText())
    checkDate(p, from, d)
    val uuid = UUID.fromString(req.batch_uuid)
    val fp = fingerprint(from, req.prices)
    return d.db.jdbi.inTransaction<PricePreviewOut, Exception> { h ->
        val existing = loadBatch(h, uuid, lock = true)
        if (existing != null && existing.status != "previewed" && existing.fingerprint != fp) throw ApiProblem(ProblemCode.ERR_CONFLICT, "batch_uuid already used with a different set of prices")
        val a = analyse(h, req.prices, from, d)
        if (existing == null || existing.status == "previewed") {
            // The preview record is what makes the preview mandatory: a publish needs a record with the same rows. It carries no price effect.
            h.createUpdate(
                "INSERT INTO app.price_batch (batch_uuid, status, valid_from, fingerprint, price_rows, row_count, max_change_pct, previewed_by) VALUES (:b, 'previewed', :f, :fp, CAST(:r AS jsonb), :n, :m, :by) " +
                    "ON CONFLICT (batch_uuid) DO UPDATE SET valid_from = :f, fingerprint = :fp, price_rows = CAST(:r AS jsonb), row_count = :n, max_change_pct = :m, previewed_by = :by, updated_at = now() WHERE app.price_batch.status = 'previewed'",
            ).bind("b", uuid).bind("f", from).bind("fp", fp).bind("r", rowsJson(req.prices)).bind("n", req.prices.size).bind("m", a.maxPct.min(PCT_CAP)).bind("by", p.userId).execute()
        }
        previewOf(req.batch_uuid, a)
    }
}

/** Closes what the new rows supersede and inserts them; returns the new rows. A price already scheduled from the date or later is an overlap. */
private fun applyRows(h: Handle, actor: AronPrincipal, call: ApplicationCall, uuid: UUID, from: LocalDate, rows: List<PriceRowIn>, reason: String): List<SkuPriceOut> {
    val out = ArrayList<SkuPriceOut>(rows.size)
    for (r in rows.sortedWith(compareBy({ it.sku_id }, { it.price_type }))) {
        val open = h.createQuery("SELECT id, sku_id, price_type, amount_mtk, per_base_qty, valid_from, valid_to FROM app.sku_price WHERE sku_id = :s AND price_type = :t AND (valid_to IS NULL OR valid_to > :d) FOR UPDATE")
            .bind("s", r.sku_id).bind("t", r.price_type).bind("d", from).map { rs, _ -> priceOf(rs) }.list()
        var closed: SkuPriceOut? = null
        for (o in open) {
            if (!LocalDate.parse(o.valid_from).isBefore(from)) throw ApiProblem(ProblemCode.ERR_MASTER_OVERLAP, "SKU ${r.sku_id} already has a ${r.price_type} price starting on or after $from", errors = listOf(FieldError("body.prices", "overlap")))
            h.createUpdate("UPDATE app.sku_price SET valid_to = :d WHERE id = :i").bind("d", from).bind("i", o.id).execute()
            closed = o
        }
        val created = h.createQuery(
            "INSERT INTO app.sku_price (sku_id, price_type, amount_mtk, per_base_qty, valid_from, publish_batch_uuid, created_by) VALUES (:s, :t, :a, :p, :d, :b, :by) RETURNING id, sku_id, price_type, amount_mtk, per_base_qty, valid_from, valid_to",
        ).bind("s", r.sku_id).bind("t", r.price_type).bind("a", r.amount_mtk).bind("p", r.per_base_qty).bind("d", from).bind("b", uuid).bind("by", actor.userId).map { rs, _ -> priceOf(rs) }.one()
        AuditWriter.write(
            h, actor, "sku_price", created.id.toString(), "publish",
            closed?.let { c -> auditObj("id" to c.id, "amount_mtk" to c.amount_mtk, "per_base_qty" to c.per_base_qty, "valid_from" to c.valid_from, "valid_to" to c.valid_to) },
            auditObj("amount_mtk" to created.amount_mtk, "per_base_qty" to created.per_base_qty, "valid_from" to created.valid_from, "sku_id" to created.sku_id, "price_type" to created.price_type, "batch_uuid" to uuid.toString()),
            reason, call.requestId,
        )
        out += created
    }
    return out
}

/** +1 on the global `cfg.price.list_version` marker in the same transaction (docs/24 s9.5): a new cfg_version commit, the open value closed, a new one opened. */
private fun bumpListVersion(h: Handle, actor: AronPrincipal, d: AdminPricesDeps, batch: UUID, reason: String): Long {
    h.execute("SELECT pg_advisory_xact_lock(7242001)")
    val now = OffsetDateTime.ofInstant(d.clock.now(), ZoneOffset.UTC)
    data class Open(val id: Long, val from: OffsetDateTime, val value: Long)
    val open = h.createQuery("SELECT id, effective_from, value::text FROM app.cfg_value WHERE key = :k AND scope_type = 'global' AND effective_to IS NULL FOR UPDATE").bind("k", LIST_VERSION_KEY)
        .map { rs, _ -> Open(rs.getLong(1), rs.getObject(2, OffsetDateTime::class.java), rs.getString(3).trim().toLong()) }.findOne().orElse(null)
    val current = open?.value ?: h.createQuery("SELECT default_value::text FROM app.cfg_key WHERE key = :k").bind("k", LIST_VERSION_KEY).mapTo(String::class.java).one().trim().toLong()
    val next = current + 1
    val from = if (open != null && !open.from.isBefore(now)) open.from.plusNanos(1_000_000) else now
    val version = h.createQuery("SELECT COALESCE(max(config_version), 0) + 1 FROM app.cfg_version").mapTo(Long::class.java).one()
    h.createUpdate("INSERT INTO app.cfg_version (config_version, kind, change_id, committed_at, committed_by, summary, max_risk_class) VALUES (:v, 'change', NULL, :at, :by, :s, 2)")
        .bind("v", version).bind("at", from).bind("by", actor.userId).bind("s", "$LIST_VERSION_KEY -> $next (price batch $batch)").execute()
    if (open != null) h.createUpdate("UPDATE app.cfg_value SET effective_to = :to, superseded_in_version = :v WHERE id = :id").bind("to", from).bind("v", version).bind("id", open.id).execute()
    h.createUpdate("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, created_by, reason) VALUES (:k, 'global', 0, CAST(:val AS jsonb), :from, :v, :by, :r)")
        .bind("k", LIST_VERSION_KEY).bind("val", next.toString()).bind("from", from).bind("v", version).bind("by", actor.userId).bind("r", reason.take(500)).execute()
    AuditWriter.write(h, actor, "cfg_version", version.toString(), "commit", auditObj("value" to current), auditObj("key" to LIST_VERSION_KEY, "value" to next, "batch_uuid" to batch.toString()), reason, null)
    h.createUpdate("SELECT pg_notify('cfg_changed', :v)").bind("v", version.toString()).execute()
    return next
}

private fun currentListVersion(h: Handle): Long = h.createQuery(
    "SELECT COALESCE((SELECT value FROM app.cfg_value WHERE key = :k AND scope_type = 'global' AND effective_to IS NULL), (SELECT default_value FROM app.cfg_key WHERE key = :k))::text",
).bind("k", LIST_VERSION_KEY).mapTo(String::class.java).one().trim().toLong()

private suspend fun publish(call: ApplicationCall, d: AdminPricesDeps) {
    val p = call.masterWriter()
    val (req, from) = parseRequest(call, call.receiveText())
    val backdated = checkDate(p, from, d)
    val uuid = UUID.fromString(req.batch_uuid)
    val fp = fingerprint(from, req.prices)
    val reason = req.change_reason.trim()
    val result = mapDb {
        d.db.jdbi.inTransaction<Pair<SkuPriceListOut, String>, Exception> { h ->
            val existing = loadBatch(h, uuid, lock = true)
            if (existing != null && existing.status != "previewed") {
                if (existing.fingerprint != fp) throw ApiProblem(ProblemCode.ERR_CONFLICT, "batch_uuid already used with a different set of prices")
                // Replay: the first answer again, nothing written.
                val items = if (existing.status == "published")
                    h.createQuery("SELECT id, sku_id, price_type, amount_mtk, per_base_qty, valid_from, valid_to FROM app.sku_price WHERE publish_batch_uuid = :b ORDER BY id").bind("b", uuid).map { rs, _ -> priceOf(rs) }.list()
                else emptyList()
                return@inTransaction SkuPriceListOut(items, existing.listVersion ?: currentListVersion(h)) to existing.status
            }
            if (d.requirePreview && (existing == null || existing.fingerprint != fp)) throw ApiProblem(
                ProblemCode.ERR_REQUEST_STATE, "preview these prices first (POST /v1/admin/prices/preview with the same batch_uuid and rows)", errors = listOf(FieldError("body.batch_uuid", "preview_required")),
            )
            val a = analyse(h, req.prices, from, d)
            val upsert = "INSERT INTO app.price_batch (batch_uuid, status, valid_from, fingerprint, price_rows, row_count, change_reason, backdate, max_change_pct, submitted_by, submitted_at) " +
                "VALUES (:b, :st, :f, :fp, CAST(:r AS jsonb), :n, :why, :bd, :m, :by, now()) ON CONFLICT (batch_uuid) DO UPDATE SET status = :st, valid_from = :f, fingerprint = :fp, price_rows = CAST(:r AS jsonb), row_count = :n, " +
                "change_reason = :why, backdate = :bd, max_change_pct = :m, submitted_by = :by, submitted_at = now(), updated_at = now()"
            fun store(status: String) = h.createUpdate(upsert).bind("b", uuid).bind("st", status).bind("f", from).bind("fp", fp).bind("r", rowsJson(req.prices)).bind("n", req.prices.size)
                .bind("why", reason).bind("bd", backdated).bind("m", a.maxPct.min(PCT_CAP)).bind("by", p.userId).execute()
            if (a.approval) {
                store("pending_approval")
                AuditWriter.write(h, p, "price_batch", uuid.toString(), "submit_pending", null, auditObj("status" to "pending_approval", "valid_from" to from, "rows" to req.prices.size, "max_change_pct" to a.maxPct.toPlainString()), reason, call.requestId)
                return@inTransaction SkuPriceListOut(emptyList(), currentListVersion(h)) to "pending_approval"
            }
            store("published")
            val items = applyRows(h, p, call, uuid, from, req.prices, reason)
            val version = bumpListVersion(h, p, d, uuid, reason)
            h.createUpdate("UPDATE app.price_batch SET price_list_version = :v, updated_at = now() WHERE batch_uuid = :b").bind("v", version).bind("b", uuid).execute()
            AuditWriter.write(h, p, "price_batch", uuid.toString(), "publish", null, auditObj("status" to "published", "valid_from" to from, "rows" to items.size, "price_list_version" to version), reason, call.requestId)
            SkuPriceListOut(items, version) to "published"
        }
    }
    call.response.header(BATCH_STATUS_HEADER, result.second)
    call.respond(HttpStatusCode.Created, result.first)
}

private fun batchOut(uuid: UUID, b: StoredBatch, status: String = b.status) =
    PriceBatchOut(uuid.toString(), status, b.validFrom.toString(), b.rows.size, b.decidedBy, b.decidedAt?.toInstant()?.wire())

private suspend fun decide(call: ApplicationCall, d: AdminPricesDeps): PriceBatchOut {
    val p = call.principal
    // The checker is a SUPERADMIN (docs/24 s8.5 "Price batches above cfg.price.max_change_pct": ADMIN is the maker, SUPERADMIN the checker).
    if (p.role != Role.SUPERADMIN) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "only a SUPERADMIN decides a price batch")
    val uuid = call.parameters["batch_uuid"]?.takeIf { UUID_V4.matches(it) }?.let(UUID::fromString) ?: adminBad("path.batch_uuid", "not_a_uuid")
    val req = try { RequestJson.decodeFromString<DecisionIn>(call.receiveText()) } catch (e: kotlinx.serialization.SerializationException) {
        throw ApiProblem(ProblemCode.ERR_VALIDATION, "malformed request body", errors = listOf(FieldError("body", "invalid_body")))
    }
    if (req.decision != "approve" && req.decision != "reject") adminBad("body.decision", "not_in_enum")
    val note = req.note?.trim()?.takeIf { it.isNotEmpty() }
    if (note != null && note.length > 500) adminBad("body.note", "length")
    val wanted = if (req.decision == "approve") "published" else "rejected"
    return mapDb {
        d.db.jdbi.inTransaction<PriceBatchOut, Exception> { h ->
            val b = loadBatch(h, uuid, lock = true)?.takeIf { it.status != "previewed" } ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no price batch with this id")
            if (b.status != "pending_approval") {
                // The same decision by the same person again is a replay and answers the stored result; anything else is a state conflict.
                if (b.status == wanted && b.decidedBy == p.userId) return@inTransaction batchOut(uuid, b)
                throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "the batch is already ${b.status}")
            }
            if (b.submittedBy == p.userId) throw ApiProblem(ProblemCode.ERR_SEPARATION_OF_DUTIES, "a different person must decide a batch you submitted")
            val why = note ?: b.reason ?: "price batch decision"
            if (req.decision == "reject") {
                h.createUpdate("UPDATE app.price_batch SET status = 'rejected', decided_by = :by, decided_at = now(), decision_note = :n, updated_at = now() WHERE batch_uuid = :b")
                    .bind("by", p.userId).bind("n", note).bind("b", uuid).execute()
                AuditWriter.write(h, p, "price_batch", uuid.toString(), "reject", auditObj("status" to "pending_approval"), auditObj("status" to "rejected", "submitted_by" to b.submittedBy), why, call.requestId)
            } else {
                // A back-dated batch stays valid only if its submitter had the permission; a forward-dated one that has since come due is refused as past.
                if (!b.validFrom.isAfter(today(d)) && !b.backdate) throw ApiProblem(ProblemCode.ERR_MASTER_EFFECTIVE_DATE_PAST, "the start date has passed; submit the prices again with a later date", errors = listOf(FieldError("valid_from", "past")))
                val items = applyRows(h, p, call, uuid, b.validFrom, b.rows, why)
                val version = bumpListVersion(h, p, d, uuid, why)
                h.createUpdate("UPDATE app.price_batch SET status = 'published', decided_by = :by, decided_at = now(), decision_note = :n, price_list_version = :v, updated_at = now() WHERE batch_uuid = :b")
                    .bind("by", p.userId).bind("n", note).bind("v", version).bind("b", uuid).execute()
                AuditWriter.write(h, p, "price_batch", uuid.toString(), "approve", auditObj("status" to "pending_approval"), auditObj("status" to "published", "rows" to items.size, "price_list_version" to version, "submitted_by" to b.submittedBy), why, call.requestId)
            }
            batchOut(uuid, loadBatch(h, uuid, lock = false)!!)
        }
    }
}
