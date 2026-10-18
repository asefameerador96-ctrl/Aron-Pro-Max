package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.masterdata.AdminSupport.bad
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.Role
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime

@Serializable
data class AuditEntryDto(
    val id: Long, val at: String, val actor_user_id: Long?, val actor_username: String?, val actor_role: String?, val via: String,
    val entity: String, val entity_id: String, val action: String, val before: JsonObject?, val after: JsonObject?,
    val reason: String?, val request_id: String?, val row_hash: String,
)

@Serializable
data class AuditPageDto(val items: List<AuditEntryDto>, val next_cursor: String?)

class AuditReadDeps(val db: Database, val guard: AuthGuardDeps)

/** docs/24 s8.5 "Audit log": R for ANALYST, SUPPORT, ADMIN and SUPERADMIN, nationally (the log has no reach). */
private val AUDIT_READERS = setOf(Role.ANALYST, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)
private val ENTITY = Regex("^[a-z_]{2,40}$")
private val ACTION = Regex("^[a-z_.]{2,60}$")

/** `GET /v1/admin/audit` (contract listAudit): newest first, keyset on id, every value through [AuditMask]. */
fun Route.auditReadRoutes(d: AuditReadDeps) {
    authenticated(d.guard) {
        get("/admin/audit") { call.respond(listAudit(call, d)) }
    }
}

private fun listAudit(call: ApplicationCall, d: AuditReadDeps): AuditPageDto {
    val p = call.principal
    if (p.role !in AUDIT_READERS) throw AdminSupport.forbidden("the audit log is not available to this role")
    val q = call.request.queryParameters
    val entity = q["entity"]?.also { if (!ENTITY.matches(it)) bad("query.entity") }
    val entityId = q["entity_id"]?.also { if (it.isEmpty() || it.length > 64 || '\u0000' in it) bad("query.entity_id") }
    val actor = AdminSupport.queryId(call, "actor_user_id")
    val action = q["action"]?.also { if (!ACTION.matches(it)) bad("query.action") }
    val (from, to) = AdminSupport.window(call)
    val limit = AdminSupport.limit(call)
    val cursor = q["cursor"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 && it == v.toString() } ?: bad("query.cursor") }

    val where = mutableListOf("TRUE")
    if (entity != null) where += "entity = :entity"
    if (entityId != null) where += "entity_id = :eid"
    if (actor != null) where += "actor_user_id = :actor"
    if (action != null) where += "action = :action"
    if (from != null) where += "business_date >= :from"
    if (to != null) where += "business_date <= :to"
    if (cursor != null) where += "id < :cursor"
    val rows = d.db.jdbi.withHandle<List<AuditEntryDto>, Exception> { h ->
        val query = h.createQuery(
            "SELECT id, at, actor_user_id, actor_username, actor_role, via, entity, entity_id, action, before::text AS before, after::text AS after, reason, request_id::text AS request_id, " +
                "encode(row_hash, 'hex') AS row_hash FROM app.audit_log WHERE ${where.joinToString(" AND ")} ORDER BY id DESC LIMIT :lim",
        ).bind("lim", limit + 1)
        entity?.let { query.bind("entity", it) }; entityId?.let { query.bind("eid", it) }; actor?.let { query.bind("actor", it) }
        action?.let { query.bind("action", it) }; from?.let { query.bind("from", it) }; to?.let { query.bind("to", it) }; cursor?.let { query.bind("cursor", it) }
        val full = p.role == Role.ADMIN || p.role == Role.SUPERADMIN
        query.map { rs, _ ->
            val e = rs.getString("entity")
            val view = AuditMask.view(p, e, rs.getString("before")?.let(::obj), rs.getString("after")?.let(::obj))
            AuditEntryDto(
                rs.getLong("id"), rs.getObject("at", OffsetDateTime::class.java).toInstant().wire(), rs.getObject("actor_user_id") as Long?,
                rs.getString("actor_username"), rs.getString("actor_role"), rs.getString("via"), e,
                rs.getString("entity_id"), rs.getString("action"), view.first, view.second,
                if (full) rs.getString("reason") else rs.getString("reason")?.let { AuditMask.MASK }, rs.getString("request_id"), rs.getString("row_hash"),
            )
        }.list()
    }
    val page = rows.take(limit)
    return AuditPageDto(page, if (rows.size > limit) page.last().id.toString() else null)
}

/** A stored image that is not an object (never written today) is shown as one masked member, never as raw text. */
private fun obj(text: String): JsonObject = when (val e = Json.parseToJsonElement(text)) {
    is JsonObject -> e
    JsonNull -> JsonObject(emptyMap())
    else -> JsonObject(mapOf("value" to e))
}

/**
 * What a reader may see of an audit row's `before` and `after` (D-AUTHZ-AUDIT, docs/status/backend-authz.md "never-show list").
 * - Never shown to anyone: the members in [NEVER_SHOW] (token, secret, hash and OTP material) and any member whose name
 *   looks like one ([SECRETISH]), at any depth.
 * - Personal data ([PII]) is shown only to a token that carries the PII grant (docs/21 s4.5; no token carries it until the
 *   reveal flow is built), so ADMIN and SUPERADMIN see every other value.
 * - ANALYST and SUPPORT: default deny. Only members that changed are listed, and a value is shown only when its
 *   entity's allowlist ([PLAIN]) names it and it is a scalar; every other changed member (a new key nobody listed
 *   included) is listed by name with [MASK] as its value. The free-text `reason` is masked for them too.
 * The contract carries scalars only, so a nested value an ADMIN may see is shown as its (masked) JSON text, capped.
 */
internal object AuditMask {
    const val MASK = "[masked]"
    private const val MAX = 2000

    /**
     * Never shown to anyone (write-site audit 2026-10-18, docs/status/backend-authz.md). Today only `prefix` (the first six
     * characters of an enrolment token's secret) and `token_id` reach the log; the rest is a guard for future writers.
     */
    val NEVER_SHOW: Set<String> = setOf(
        "prefix", "token_id", "password_hash", "temporary_password", "otp", "otp_cipher", "otp_sha256", "secret", "secret_cipher",
        "token_sha256", "sig", "recovery_codes",
    )

    /** Member names that look like secret material are never shown either, so a new writer cannot leak one by accident. */
    private val SECRETISH = Regex("(secret|password|passwd|hash|token|otp|nonce|cipher|private|jti|refresh|totp|seed|signature|proof)", RegexOption.IGNORE_CASE)

    /** A config value (`cfg_version` images carry raw values) is never shown when its key name looks secret; none does today. */
    private val SECRET_CFG_KEY = Regex("(secret|password|passwd|private|signing|credential|seed|api_key|apikey)", RegexOption.IGNORE_CASE)

    /** Personal data in the images (docs/21 s4.5); `AdminCtx.audit` already writes some of it as "[redacted]". */
    val PII: Set<String> = setOf(
        "full_name", "phone", "email", "contact_number", "owner_name", "address", "pda_contact_no", "dep_name", "nid", "tin", "trade_license",
        "lat", "lng", "provisional_lat", "provisional_lng",
    )

    private val GEO = setOf("code", "name", "name_bn", "wing_id", "division_id", "territory_id", "house_id", "status", "version")

    /**
     * The non-sensitive scalar members ANALYST and SUPPORT may see, per entity. Free text (task titles, notes, notification
     * titles, report search filters), personal data, nested images and raw config values are never on it.
     */
    val PLAIN: Map<String, Set<String>> = mapOf(
        "app_release" to setOf("flavour", "version_code", "abi", "status", "rollout_pct", "sha256"),
        "cfg_change" to setOf("value"),
        "cfg_version" to setOf("key", "batch_uuid"),
        "mfa_secret" to setOf("replaced_unconfirmed", "codes_left"),
        "notification" to setOf("kind", "scope_type", "scope_id", "urgent", "devices_targeted"),
        "task" to setOf("task_uuid", "task_type_code", "assignee_user_id", "assigned_by_user_id", "route_id", "outlet_id", "due_date", "status", "created_at", "resolved_at", "source"),
        "survey" to setOf("version", "status", "valid_from", "valid_to", "questions"),
        "rubric" to setOf("version", "status", "criteria"),
        "admin_asset" to setOf("purpose", "bytes"),
        "content_item" to setOf("version", "status", "valid_from", "valid_to", "sequence", "outlets"),
        "tutorial" to setOf("version", "status", "kind", "sort", "roles"),
        "print_template" to setOf("version", "font_columns", "effective_from", "bytes"),
        "data_void" to setOf("route_id", "business_date", "scope", "tombstoned"),
        "sku_price" to setOf("id", "amount_mtk", "per_base_qty", "valid_from", "valid_to", "sku_id", "price_type", "batch_uuid"),
        "price_batch" to setOf("status", "valid_from", "rows", "max_change_pct", "price_list_version", "submitted_by", "backdated"),
        "memo" to setOf("phone_memo_uuid", "quarantine_id", "paper_memo_uuid", "voided_paper_visit_uuid", "phone_visit_uuid", "visit_reason"),
        "entry_unlock" to setOf("scope_type", "scope_id", "from", "to", "ttl_h", "expired_at"),
        "feedback" to setOf("status"),
        "product_node" to setOf("id", "level", "parent_id", "code", "name", "name_bn", "sort", "status", "created_at", "updated_at", "version"),
        "sku" to setOf(
            "id", "code", "variant_id", "category_code", "name", "short_name", "name_bn", "base_unit", "base_per_pack", "entry_unit_default", "report_unit",
            "report_factor", "sort", "status", "thumbnail_media_uuid", "image_url", "updated_at", "version", "image_asset_id",
        ),
        "paper_backfill" to setOf("memo_no", "user_id", "route_id", "outlet_id", "business_date", "net_mtk", "paid_mtk", "lines"),
        "device_otp" to setOf("rows", "live", "expires_at"),
        "leave_application" to setOf("status", "applicant_user_id"),
        "dues_adjustment" to setOf("outlet_id", "kind", "amount_mtk", "client_uuid", "status", "balance_after_mtk", "balance_below_zero"),
        "device" to setOf("pending_rows", "status", "device_uuid", "flavour", "lockdown_level"),
        "enrolment_token" to setOf("flavour", "lockdown_level", "max_uses"),
        "device_directive" to setOf("device_id", "type"),
        "web_entry_route_day" to setOf("route_id", "business_date", "lines", "successful_calls", "app_overlap", "supersedes"),
        "qc_summary_entry" to setOf("source", "zone_id", "business_date", "rows"),
        "tracking_action" to setOf("route_id", "business_date"),
        "report_export" to setOf("report_key", "format", "rows", "pii", "from", "to"),
        "quarantine" to setOf("status", "code", "client_uuid", "action"),
        "user" to setOf("id", "username", "role", "designation", "employee_code", "locale", "home_zone_id", "pilot", "status", "disabled_at", "scope_version", "version"),
        "route" to setOf("code", "name", "display_label", "zone_id", "kind", "visit_kind", "visit_days_mask", "sequence_no", "status", "version", "effective_from"),
        "route_assignment" to setOf("id", "route_id", "user_id", "kind", "valid_from", "valid_to", "created_at"),
        "geo_wing" to GEO, "geo_division" to GEO, "geo_territory" to GEO, "geo_house" to GEO, "geo_zone" to GEO,
        "cluster" to setOf("zone_id", "name", "cluster_type", "status", "version"),
        "outlet" to setOf(
            "code", "name", "name_bn", "zone_id", "route_id", "cluster_id", "channel", "sub_channel_id", "geo_class", "outlet_kind", "status", "visit_sequence",
            "external_ref", "id", "version", "location_basis", "location_confirmed", "closed_at", "batch_uuid",
        ),
        "outlet_kind_batch" to setOf("fingerprint", "outlet_kind", "outlets", "updated", "unchanged"),
        "calendar_holiday" to setOf("id", "date", "scope_type", "scope_id", "kind", "selling_day", "name_en", "name_bn"),
    )

    /** Names a writer might use for personal data besides the listed ones (case and suffixes vary: `Phone`, `owner_phone`, `owner_name_bn`). */
    private val PII_LIKE = Regex("(phone|e_?mail|contact|owner_name|full_name|address|dep_name|trade_license|(^|_)nid($|_)|(^|_)tin($|_)|(^|_)(lat|lng|latitude|longitude)$)", RegexOption.IGNORE_CASE)

    fun neverShow(key: String) = key.lowercase() in NEVER_SHOW || SECRETISH.containsMatchIn(key)

    /** An id or uuid named after a phone (`phone_memo_uuid`) is a reference, not personal data. */
    fun personal(key: String) = key.lowercase() in PII || (PII_LIKE.containsMatchIn(key) && !Regex("_(id|uuid)$", RegexOption.IGNORE_CASE).containsMatchIn(key))

    fun view(p: AronPrincipal, entity: String, before: JsonObject?, after: JsonObject?): Pair<JsonObject?, JsonObject?> =
        if (p.role == Role.ADMIN || p.role == Role.SUPERADMIN) full(p, before) to full(p, after) else restricted(entity, before, after)

    private fun full(p: AronPrincipal, o: JsonObject?): JsonObject? = o?.let { JsonObject(cfgValues(it).mapValues { (k, v) -> scalar(clean(p, k, v)) }) }

    /** An image of a config row whose key name looks secret keeps its key and loses its values. */
    private fun cfgValues(o: JsonObject): JsonObject {
        val key = (o["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return o
        return if (SECRET_CFG_KEY.containsMatchIn(key)) JsonObject(o.mapValues { (k, v) -> if (k == "value" || k == "old_value") JsonPrimitive(MASK) else v }) else o
    }

    /** Masks never-show members at any depth, and personal data unless the token carries the PII grant. */
    private fun clean(p: AronPrincipal, key: String, v: JsonElement): JsonElement = when {
        neverShow(key) -> JsonPrimitive(MASK)
        personal(key) && !p.pii -> JsonPrimitive(MASK)
        v is JsonObject -> JsonObject(cfgValues(v).mapValues { (k, x) -> clean(p, k, x) })
        v is JsonArray -> JsonArray(v.map { clean(p, key, it) })
        else -> v
    }

    private fun scalar(v: JsonElement): JsonElement = when (v) {
        is JsonPrimitive -> if (v.isString && v.content.length > MAX) JsonPrimitive(v.content.take(MAX)) else v
        else -> JsonPrimitive(v.toString().take(MAX))
    }

    private fun restricted(entity: String, before: JsonObject?, after: JsonObject?): Pair<JsonObject?, JsonObject?> {
        val allowed = PLAIN[entity].orEmpty()
        val changed = (before.orEmpty().keys + after.orEmpty().keys).filter { before?.get(it) != after?.get(it) }.toSet()
        fun side(o: JsonObject?): JsonObject? = o?.let {
            JsonObject(it.filterKeys { k -> k in changed }.mapValues { (k, v) ->
                if (k in allowed && !neverShow(k) && !personal(k) && v is JsonPrimitive) scalar(v) else JsonPrimitive(MASK)
            })
        }
        return side(before) to side(after)
    }
}
