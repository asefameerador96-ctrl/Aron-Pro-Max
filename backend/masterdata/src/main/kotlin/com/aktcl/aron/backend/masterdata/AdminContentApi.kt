package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.AuditWriter
import com.aktcl.aron.backend.masterdata.AdminSupport.bad
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class AdminContentDeps(val db: Database, val blob: BlobSasIssuer, val config: ServerConfig, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

/** Who reads and who writes the admin-content pages (docs/24 s8.5: master data is W for ADMIN and SUPERADMIN, R for support). */
private val CONTENT_READERS = setOf(Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)
private val CONTENT_WRITERS = setOf(Role.ADMIN, Role.SUPERADMIN)

private val store = Json { encodeDefaults = true; ignoreUnknownKeys = true }

// ------------------------------------------------------------------------------------------------ DTOs (contract names)

@Serializable data class SurveyQuestionIn(
    val key: String, val answer_type: String, val label_en: String, val label_bn: String? = null, val required: Boolean? = null,
    val show_if_key: String? = null, val show_if_bool: Boolean? = null, val photo: Boolean? = null,
)
@Serializable data class SurveyWriteIn(
    val kind: String, val title_en: String, val title_bn: String? = null, val questions: List<SurveyQuestionIn>, val valid_from: String, val valid_to: String? = null,
    val points_per_photo: Int? = null, val status: String? = null, val change_reason: String,
)
@Serializable private data class StoredQuestion(
    val question_id: Long, val key: String, val answer_type: String, val label_en: String, val label_bn: String?, val required: Boolean, val show_if_key: String?, val show_if_bool: Boolean?, val photo: Boolean,
)
@Serializable data class SurveyQuestionOut(val question_id: Long, val answer_type: String, val label_en: String, val label_bn: String?, val requires_photo: Boolean)
@Serializable data class SurveyAdminDto(
    val survey_id: Long, val version: Int, val title_en: String, val title_bn: String?, val questions: List<SurveyQuestionOut>, val kind: String, val status: String,
    val valid_from: String, val valid_to: String?, val points_per_photo: Int?,
)
@Serializable data class SurveyPageDto(val items: List<SurveyAdminDto>, val next_cursor: String?)

@Serializable data class RubricCriterionIn(val key: String, val label_en: String, val label_bn: String? = null, val answer_type: String)
@Serializable data class RubricWriteIn(val kind: String, val criteria: List<RubricCriterionIn>, val status: String? = null, val change_reason: String)
@Serializable private data class StoredCriterion(val criterion_id: Long, val key: String, val label_en: String, val label_bn: String?, val answer_type: String)
@Serializable data class RubricCriterionOut(val criterion_id: Long, val label_en: String, val label_bn: String?, val answer_type: String, val enabled: Boolean)
@Serializable data class RubricAdminDto(val rubric_id: Long, val version: Int, val kind: String, val criteria: List<RubricCriterionOut>, val status: String)
@Serializable data class RubricPageDto(val items: List<RubricAdminDto>, val next_cursor: String?)

@Serializable data class ScopeNode(val node_type: String, val node_id: Long)
@Serializable data class ContentWriteIn(
    val kind: String, val title_en: String, val title_bn: String? = null, val asset_id: String, val valid_from: String, val valid_to: String, val sequence: Int,
    val assigned_scope: List<ScopeNode>? = null, val status: String? = null, val change_reason: String,
)
@Serializable data class ContentAdminDto(
    val content_id: Long, val version: Int, val kind: String, val title_en: String, val title_bn: String?, val asset_url: String, val sha256: String, val bytes: Long, val duration_s: Int?,
    val valid_from: String, val valid_to: String, val sequence: Int, val outlet_ids: List<Long>, val status: String, val assigned_scope: List<ScopeNode>, val updated_at: String,
)
@Serializable data class ContentPageDto(val items: List<ContentAdminDto>, val next_cursor: String?)

@Serializable data class TutorialWriteIn(
    val kind: String, val title_en: String, val title_bn: String? = null, val asset_id: String, val roles: List<String>, val sort: Int, val status: String? = null, val change_reason: String,
)
@Serializable data class TutorialAdminDto(
    val tutorial_id: Long, val kind: String, val title_en: String, val title_bn: String?, val url: String, val bytes: Long?, val duration_s: Int?, val sort: Int,
    val roles: List<String>, val status: String, val version: Int,
)
@Serializable data class TutorialAdminListDto(val items: List<TutorialAdminDto>)

@Serializable data class PrintTemplateWriteIn(val kind: String, val font_columns: Int, val template_json: String, val effective_from: String, val change_reason: String)
@Serializable data class PrintTemplateDto(val kind: String, val version: Int, val font_columns: Int, val template_json: String)
@Serializable data class PrintTemplateListDto(val items: List<PrintTemplateDto>)

@Serializable data class AdminAssetIn(val asset_id: String, val purpose: String, val mime: String, val bytes: Long, val sha256: String)
@Serializable data class AdminAssetOut(val asset_id: String, val upload_url: String, val blob_path: String, val expires_at: String)

// ------------------------------------------------------------------------------------------------ routes

/**
 * F-API-035a (trimmed per docs/27: no offers, no programme definitions, no gifts). admin operations under `/v1/admin` of the tag
 * `admin-content`: surveys, rubrics, AV and KV content, tutorials, print templates and the write-only asset SAS. Every write
 * needs `change_reason` (10 to 500 characters) and adds its audit row in the same transaction; PATCH is guarded by
 * `If-Match` (the quoted `version`, mismatch is 412); a published change reaches phones in the next bundle or delta.
 */
fun Route.adminContentRoutes(d: AdminContentDeps) {
    authenticated(d.guard) {
        get("/admin/surveys") { call.respond(listSurveys(call, d)) }
        post("/admin/surveys") { respondCreated(call, createSurvey(call, d)) }
        patch("/admin/surveys/{id}") { respondVersioned(call, updateSurvey(call, d)) }
        get("/admin/rubrics") { call.respond(listRubrics(call, d)) }
        post("/admin/rubrics") { respondCreated(call, createRubric(call, d)) }
        patch("/admin/rubrics/{id}") { respondVersioned(call, updateRubric(call, d)) }
        get("/admin/content") { call.respond(listContent(call, d)) }
        post("/admin/content") { respondCreated(call, createContent(call, d)) }
        patch("/admin/content/{id}") { respondVersioned(call, updateContent(call, d)) }
        get("/admin/tutorials") { call.respond(listTutorials(call, d)) }
        post("/admin/tutorials") { respondCreated(call, createTutorial(call, d)) }
        patch("/admin/tutorials/{id}") { respondVersioned(call, updateTutorial(call, d)) }
        get("/admin/print-templates") { call.respond(listTemplates(call, d)) }
        post("/admin/print-templates") { call.respond(HttpStatusCode.Created, createTemplate(call, d)) }
        post("/admin/assets") { call.respond(requestAsset(call, d)) }
    }
}

/** A written row plus its version, for the `ETag` header. */
private class Versioned(val version: Int, val body: Any)

private suspend fun respondCreated(call: ApplicationCall, v: Versioned) {
    call.response.headers.append(HttpHeaders.ETag, "\"${v.version}\"")
    call.respond(HttpStatusCode.Created, v.body)
}

private suspend fun respondVersioned(call: ApplicationCall, v: Versioned) {
    call.response.headers.append(HttpHeaders.ETag, "\"${v.version}\"")
    call.respond(v.body)
}

private fun reader(call: ApplicationCall) {
    if (call.principal.role !in CONTENT_READERS) throw AdminSupport.forbidden("admin content is not available to this role")
}

private fun writer(call: ApplicationCall) {
    if (call.principal.role !in CONTENT_WRITERS) throw AdminSupport.forbidden("not allowed to change admin content")
}

private fun status(value: String?, pointer: String = "body.status"): String = (value ?: "active").also { if (it !in setOf("active", "inactive")) bad(pointer) }

private fun statusQuery(call: ApplicationCall): String? = call.request.queryParameters["status"]?.also { if (it !in setOf("active", "inactive")) bad("query.status") }

private fun notFound(what: String) = ApiProblem(ProblemCode.ERR_NOT_FOUND, "$what not found")

private fun idPath(call: ApplicationCall): Long = call.parameters["id"]?.toLongOrNull()?.takeIf { it >= 1 } ?: bad("path.id")

private fun text(value: String?, pointer: String, max: Int, required: Boolean = true): String? {
    val t = value?.trim()
    if (t.isNullOrEmpty()) { if (required) bad(pointer, "required") else return null }
    if (t.length > max) bad(pointer, "length")
    return t
}

/** Optimistic concurrency on a head row: 404 when the row is gone, 412 when its version moved. */
private fun checkVersion(h: Handle, table: String, id: Long, expected: Int, what: String): Int {
    val v = h.createQuery("SELECT version FROM app.$table WHERE id = :i FOR UPDATE").bind("i", id).mapTo(Int::class.java).findOne().orElse(null) ?: throw notFound(what)
    if (v != expected) throw AdminSupport.preconditionFailed()
    return v
}

// ------------------------------------------------------------------------------------------------ surveys

private val SURVEY_KINDS = setOf("posm", "amo_survey", "tso_visit_query")
private val ANSWER_TYPES = setOf("bool", "num", "option", "text", "photo_only")

private fun surveyOf(h: Handle, id: Long): SurveyAdminDto? =
    h.createQuery(
        "SELECT s.id, s.version, s.kind, s.status, s.valid_from, s.valid_to, s.points_per_photo, v.title_en, v.title_bn, v.questions::text AS questions FROM app.survey s " +
            "JOIN app.survey_version v ON v.survey_id = s.id AND v.version = s.version WHERE s.id = :i",
    ).bind("i", id).map { rs, _ -> mapSurvey(rs) }.findOne().orElse(null)

private fun mapSurvey(rs: java.sql.ResultSet): SurveyAdminDto {
    val qs = store.decodeFromString(ListSerializer(StoredQuestion.serializer()), rs.getString("questions"))
    return SurveyAdminDto(
        rs.getLong("id"), rs.getInt("version"), rs.getString("title_en"), rs.getString("title_bn"),
        qs.map { SurveyQuestionOut(it.question_id, it.answer_type, it.label_en, it.label_bn, it.photo || it.answer_type == "photo_only") },
        rs.getString("kind"), rs.getString("status"), rs.getObject("valid_from", LocalDate::class.java).toString(), rs.getObject("valid_to", LocalDate::class.java)?.toString(), rs.getObject("points_per_photo") as Int?,
    )
}

private fun listSurveys(call: ApplicationCall, d: AdminContentDeps): SurveyPageDto {
    reader(call)
    val limit = AdminSupport.limit(call); val st = statusQuery(call)
    val cursor = AdminSupport.decodeCursor(call, 1)?.let { (a) -> a.toLongOrNull()?.takeIf { it >= 0 } ?: bad("query.cursor") }
    return d.db.jdbi.withHandle<SurveyPageDto, Exception> { h ->
        val rows = h.createQuery(
            "SELECT s.id, s.version, s.kind, s.status, s.valid_from, s.valid_to, s.points_per_photo, v.title_en, v.title_bn, v.questions::text AS questions FROM app.survey s " +
                "JOIN app.survey_version v ON v.survey_id = s.id AND v.version = s.version WHERE (CAST(:st AS text) IS NULL OR s.status = :st) AND (CAST(:c AS bigint) IS NULL OR s.id > :c) ORDER BY s.id LIMIT :lim",
        ).bind("st", st).bind("c", cursor).bind("lim", limit + 1).map { rs, _ -> mapSurvey(rs) }.list()
        SurveyPageDto(rows.take(limit), if (rows.size > limit) AdminSupport.encodeCursor(rows[limit - 1].survey_id) else null)
    }
}

private fun validSurvey(req: SurveyWriteIn): Triple<LocalDate, LocalDate?, String> {
    if (req.kind !in SURVEY_KINDS) bad("body.kind")
    val reason = AdminSupport.reason(req.change_reason)
    text(req.title_en, "body.title_en", 120)
    if (req.title_bn != null && req.title_bn.length > 120) bad("body.title_bn", "length")
    if (req.questions.isEmpty() || req.questions.size > 50) bad("body.questions", "out_of_range")
    val seen = HashSet<String>()
    req.questions.forEachIndexed { i, q ->
        val at = "body.questions[$i]"
        if (!AdminSupport.CODE.matches(q.key) || !seen.add(q.key)) bad("$at.key", if (q.key in seen) "duplicate" else "invalid_value")
        if (q.answer_type !in ANSWER_TYPES) bad("$at.answer_type")
        text(q.label_en, "$at.label_en", 300)
        if (q.label_bn != null && q.label_bn.length > 300) bad("$at.label_bn", "length")
        if (q.show_if_key != null) {
            // A condition points at an earlier question and says which bool answer shows this one.
            if (q.show_if_key !in seen || q.show_if_key == q.key || q.show_if_bool == null) bad("$at.show_if_key")
            if (req.questions.first { it.key == q.show_if_key }.answer_type != "bool") bad("$at.show_if_key", "not_bool")
        }
    }
    val from = AdminSupport.date(req.valid_from, "body.valid_from")
    val to = req.valid_to?.let { AdminSupport.date(it, "body.valid_to") }
    if (to != null && to.isBefore(from)) bad("body.valid_to", "before_valid_from")
    if (req.points_per_photo != null && req.points_per_photo !in 0..100000) bad("body.points_per_photo", "out_of_range")
    return Triple(from, to, reason)
}

/** Questions keep their `question_id` across versions (answers reference it); a new key gets the next id. */
private fun numberQuestions(h: Handle, surveyId: Long?, qs: List<SurveyQuestionIn>): List<StoredQuestion> {
    val known = HashMap<String, Long>(); var max = 0L
    if (surveyId != null) {
        h.createQuery("SELECT questions::text FROM app.survey_version WHERE survey_id = :i ORDER BY version").bind("i", surveyId).mapTo(String::class.java).list().forEach { json ->
            store.decodeFromString(ListSerializer(StoredQuestion.serializer()), json).forEach { known[it.key] = it.question_id; if (it.question_id > max) max = it.question_id }
        }
    }
    return qs.map { q -> StoredQuestion(known[q.key] ?: ++max, q.key, q.answer_type, q.label_en.trim(), q.label_bn?.trim()?.ifEmpty { null }, q.required ?: false, q.show_if_key, q.show_if_bool, q.photo ?: false) }
}

private fun surveyAudit(s: SurveyAdminDto) = buildJsonObject { put("version", s.version); put("status", s.status); put("valid_from", s.valid_from); put("valid_to", s.valid_to); put("questions", s.questions.size) }

private suspend fun createSurvey(call: ApplicationCall, d: AdminContentDeps): Versioned {
    writer(call)
    val p = call.principal
    val req = call.receiveStrict(SurveyWriteIn.serializer())
    val (from, to, reason) = validSurvey(req)
    val st = status(req.status)
    val out = d.db.jdbi.inTransaction<SurveyAdminDto, Exception> { h ->
        val id = h.createQuery("INSERT INTO app.survey (kind, valid_from, valid_to, points_per_photo, status, created_by, updated_by) VALUES (:k, :f, CAST(:t AS date), CAST(:pp AS int), :s, :by, :by) RETURNING id")
            .bind("k", req.kind).bind("f", from).bind("t", to).bind("pp", req.points_per_photo).bind("s", st).bind("by", p.userId).mapTo(Long::class.java).one()
        val qs = numberQuestions(h, null, req.questions)
        h.createUpdate("INSERT INTO app.survey_version (survey_id, version, title_en, title_bn, questions, created_by) VALUES (:i, 1, :te, :tb, CAST(:q AS jsonb), :by)")
            .bind("i", id).bind("te", req.title_en.trim()).bind("tb", req.title_bn?.trim()?.ifEmpty { null }).bind("q", store.encodeToString(ListSerializer(StoredQuestion.serializer()), qs)).bind("by", p.userId).execute()
        val s = surveyOf(h, id)!!
        AuditWriter.write(h, p, "survey", id.toString(), "create", null, surveyAudit(s), reason, call.requestId)
        s
    }
    return Versioned(out.version, out)
}

private suspend fun updateSurvey(call: ApplicationCall, d: AdminContentDeps): Versioned {
    writer(call)
    val p = call.principal
    val id = idPath(call); val expected = AdminSupport.ifMatch(call)
    val req = call.receiveStrict(SurveyWriteIn.serializer())
    val (from, to, reason) = validSurvey(req)
    val st = status(req.status)
    val out = d.db.jdbi.inTransaction<SurveyAdminDto, Exception> { h ->
        val v = checkVersion(h, "survey", id, expected, "survey")
        val before = surveyOf(h, id)!!
        if (before.kind != req.kind) bad("body.kind", "immutable")
        val qs = numberQuestions(h, id, req.questions)
        h.createUpdate("UPDATE app.survey SET version = :v, valid_from = :f, valid_to = CAST(:t AS date), points_per_photo = CAST(:pp AS int), status = :s, updated_at = now(), updated_by = :by WHERE id = :i")
            .bind("v", v + 1).bind("f", from).bind("t", to).bind("pp", req.points_per_photo).bind("s", st).bind("by", p.userId).bind("i", id).execute()
        h.createUpdate("INSERT INTO app.survey_version (survey_id, version, title_en, title_bn, questions, created_by) VALUES (:i, :v, :te, :tb, CAST(:q AS jsonb), :by)")
            .bind("i", id).bind("v", v + 1).bind("te", req.title_en.trim()).bind("tb", req.title_bn?.trim()?.ifEmpty { null }).bind("q", store.encodeToString(ListSerializer(StoredQuestion.serializer()), qs)).bind("by", p.userId).execute()
        val after = surveyOf(h, id)!!
        AuditWriter.write(h, p, "survey", id.toString(), "update", surveyAudit(before), surveyAudit(after), reason, call.requestId)
        after
    }
    return Versioned(out.version, out)
}

// ------------------------------------------------------------------------------------------------ rubrics

private val RUBRIC_KINDS = setOf("joint_call", "retailer_questionnaire")
private val CRITERION_TYPES = mapOf("stars_1_5" to "score_1_5", "bool" to "bool", "text" to "text")

private fun mapRubric(rs: java.sql.ResultSet): RubricAdminDto {
    val cs = store.decodeFromString(ListSerializer(StoredCriterion.serializer()), rs.getString("criteria"))
    return RubricAdminDto(rs.getLong("id"), rs.getInt("version"), rs.getString("kind"), cs.map { RubricCriterionOut(it.criterion_id, it.label_en, it.label_bn, it.answer_type, true) }, rs.getString("status"))
}

private const val RUBRIC_SQL = "SELECT r.id, r.version, r.kind, r.status, v.criteria::text AS criteria FROM app.rubric r JOIN app.rubric_version v ON v.rubric_id = r.id AND v.version = r.version"

private fun rubricOf(h: Handle, id: Long): RubricAdminDto? = h.createQuery("$RUBRIC_SQL WHERE r.id = :i").bind("i", id).map { rs, _ -> mapRubric(rs) }.findOne().orElse(null)

private fun listRubrics(call: ApplicationCall, d: AdminContentDeps): RubricPageDto {
    reader(call)
    val limit = AdminSupport.limit(call); val st = statusQuery(call)
    val cursor = AdminSupport.decodeCursor(call, 1)?.let { (a) -> a.toLongOrNull()?.takeIf { it >= 0 } ?: bad("query.cursor") }
    return d.db.jdbi.withHandle<RubricPageDto, Exception> { h ->
        val rows = h.createQuery("$RUBRIC_SQL WHERE (CAST(:st AS text) IS NULL OR r.status = :st) AND (CAST(:c AS bigint) IS NULL OR r.id > :c) ORDER BY r.id LIMIT :lim")
            .bind("st", st).bind("c", cursor).bind("lim", limit + 1).map { rs, _ -> mapRubric(rs) }.list()
        RubricPageDto(rows.take(limit), if (rows.size > limit) AdminSupport.encodeCursor(rows[limit - 1].rubric_id) else null)
    }
}

private fun validRubric(req: RubricWriteIn): String {
    if (req.kind !in RUBRIC_KINDS) bad("body.kind")
    val reason = AdminSupport.reason(req.change_reason)
    if (req.criteria.isEmpty() || req.criteria.size > 50) bad("body.criteria", "out_of_range")
    val seen = HashSet<String>()
    req.criteria.forEachIndexed { i, c ->
        val at = "body.criteria[$i]"
        if (!AdminSupport.CODE.matches(c.key)) bad("$at.key")
        if (!seen.add(c.key)) bad("$at.key", "duplicate")
        text(c.label_en, "$at.label_en", 300)
        if (c.label_bn != null && c.label_bn.length > 300) bad("$at.label_bn", "length")
        if (c.answer_type !in CRITERION_TYPES) bad("$at.answer_type")
    }
    return reason
}

private fun numberCriteria(h: Handle, rubricId: Long?, cs: List<RubricCriterionIn>): List<StoredCriterion> {
    val known = HashMap<String, Long>(); var max = 0L
    if (rubricId != null) {
        h.createQuery("SELECT criteria::text FROM app.rubric_version WHERE rubric_id = :i ORDER BY version").bind("i", rubricId).mapTo(String::class.java).list().forEach { json ->
            store.decodeFromString(ListSerializer(StoredCriterion.serializer()), json).forEach { known[it.key] = it.criterion_id; if (it.criterion_id > max) max = it.criterion_id }
        }
    }
    return cs.map { c -> StoredCriterion(known[c.key] ?: ++max, c.key, c.label_en.trim(), c.label_bn?.trim()?.ifEmpty { null }, CRITERION_TYPES.getValue(c.answer_type)) }
}

private fun rubricAudit(r: RubricAdminDto) = buildJsonObject { put("version", r.version); put("status", r.status); put("criteria", r.criteria.size) }

private suspend fun createRubric(call: ApplicationCall, d: AdminContentDeps): Versioned {
    writer(call)
    val p = call.principal
    val req = call.receiveStrict(RubricWriteIn.serializer())
    val reason = validRubric(req); val st = status(req.status)
    val out = d.db.jdbi.inTransaction<RubricAdminDto, Exception> { h ->
        val id = h.createQuery("INSERT INTO app.rubric (kind, status, created_by, updated_by) VALUES (:k, :s, :by, :by) RETURNING id").bind("k", req.kind).bind("s", st).bind("by", p.userId).mapTo(Long::class.java).one()
        h.createUpdate("INSERT INTO app.rubric_version (rubric_id, version, criteria, created_by) VALUES (:i, 1, CAST(:c AS jsonb), :by)")
            .bind("i", id).bind("c", store.encodeToString(ListSerializer(StoredCriterion.serializer()), numberCriteria(h, null, req.criteria))).bind("by", p.userId).execute()
        val r = rubricOf(h, id)!!
        AuditWriter.write(h, p, "rubric", id.toString(), "create", null, rubricAudit(r), reason, call.requestId)
        r
    }
    return Versioned(out.version, out)
}

private suspend fun updateRubric(call: ApplicationCall, d: AdminContentDeps): Versioned {
    writer(call)
    val p = call.principal
    val id = idPath(call); val expected = AdminSupport.ifMatch(call)
    val req = call.receiveStrict(RubricWriteIn.serializer())
    val reason = validRubric(req); val st = status(req.status)
    val out = d.db.jdbi.inTransaction<RubricAdminDto, Exception> { h ->
        val v = checkVersion(h, "rubric", id, expected, "rubric")
        val before = rubricOf(h, id)!!
        if (before.kind != req.kind) bad("body.kind", "immutable")
        h.createUpdate("UPDATE app.rubric SET version = :v, status = :s, updated_at = now(), updated_by = :by WHERE id = :i").bind("v", v + 1).bind("s", st).bind("by", p.userId).bind("i", id).execute()
        h.createUpdate("INSERT INTO app.rubric_version (rubric_id, version, criteria, created_by) VALUES (:i, :v, CAST(:c AS jsonb), :by)")
            .bind("i", id).bind("v", v + 1).bind("c", store.encodeToString(ListSerializer(StoredCriterion.serializer()), numberCriteria(h, id, req.criteria))).bind("by", p.userId).execute()
        val after = rubricOf(h, id)!!
        AuditWriter.write(h, p, "rubric", id.toString(), "update", rubricAudit(before), rubricAudit(after), reason, call.requestId)
        after
    }
    return Versioned(out.version, out)
}

// ------------------------------------------------------------------------------------------------ assets

private class Asset(val id: UUID, val purpose: String, val bytes: Long, val sha256: ByteArray, val blobPath: String)

private val PURPOSE_MIME = mapOf(
    "content_av" to setOf("video/mp4"), "content_kv" to setOf("image/jpeg", "image/png"), "tutorial_video" to setOf("video/mp4"),
    "tutorial_manual" to setOf("application/pdf"), "sku_image" to setOf("image/jpeg", "image/png"), "gift_image" to setOf("image/jpeg", "image/png"),
)
private const val IMAGE_CAP_BYTES = 300L * 1024
private const val SAS_TTL_S = 900L

private fun extOf(mime: String) = when (mime) { "video/mp4" -> "mp4"; "image/jpeg" -> "jpg"; "image/png" -> "png"; else -> "pdf" }

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

private fun unhex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private suspend fun requestAsset(call: ApplicationCall, d: AdminContentDeps): AdminAssetOut {
    writer(call)
    val p = call.principal
    val req = call.receiveStrict(AdminAssetIn.serializer())
    val id = AdminSupport.uuid(req.asset_id, "body.asset_id")
    val mimes = PURPOSE_MIME[req.purpose] ?: bad("body.purpose")
    if (req.mime !in mimes) bad("body.mime", "mime_not_allowed_for_purpose")
    if (req.bytes !in 1..104_857_600L) bad("body.bytes", "out_of_range")
    if (!Regex("^[0-9a-f]{64}$").matches(req.sha256)) bad("body.sha256")
    val maxItemMb = AdminSupport.intConfig(d.config, "cfg.content.max_item_mb", 20)
    val cap = when {
        req.mime.startsWith("image/") -> IMAGE_CAP_BYTES
        req.purpose.startsWith("content_") -> maxItemMb * 1_048_576L
        else -> 104_857_600L
    }
    if (req.bytes > cap) throw ApiProblem(ProblemCode.ERR_PAYLOAD_TOO_LARGE, "the file is above ${cap / 1024} KB", errors = listOf(FieldError("body.bytes", "above_cap")))
    val sha = unhex(req.sha256)
    val path = "assets/${req.purpose}/$id.${extOf(req.mime)}"
    d.db.jdbi.inTransaction<Unit, Exception> { h ->
        val inserted = h.createUpdate("INSERT INTO app.admin_asset (asset_id, purpose, mime, bytes, sha256, blob_path, uploaded_by) VALUES (:i, :p, :m, :b, :s, :path, :by) ON CONFLICT (asset_id) DO NOTHING")
            .bind("i", id).bind("p", req.purpose).bind("m", req.mime).bind("b", req.bytes).bind("s", sha).bind("path", path).bind("by", p.userId).execute()
        val row = h.createQuery("SELECT purpose, mime, bytes, sha256 FROM app.admin_asset WHERE asset_id = :i").bind("i", id).map { rs, _ -> arrayOf(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getBytes(4)) }.one()
        // A repeat of the same request returns the same blob path; the same id for a different file is a conflict.
        if (row[0] != req.purpose || row[1] != req.mime || row[2] != req.bytes || !(row[3] as ByteArray).contentEquals(sha)) throw ApiProblem(ProblemCode.ERR_CONFLICT, "asset_id already stores a different file")
        if (inserted > 0) AuditWriter.write(h, p, "admin_asset", id.toString(), "create", null, buildJsonObject { put("purpose", req.purpose); put("bytes", req.bytes) }, null, call.requestId)
    }
    val expires = d.clock.now().plusSeconds(SAS_TTL_S)
    return AdminAssetOut(id.toString(), d.blob.writeSas(path, req.bytes, expires), path, expires.wire())
}

private fun assetOf(h: Handle, id: UUID, pointer: String, purposes: Set<String>): Asset {
    val a = h.createQuery("SELECT purpose, bytes, sha256, blob_path FROM app.admin_asset WHERE asset_id = :i").bind("i", id)
        .map { rs, _ -> Asset(id, rs.getString(1), rs.getLong(2), rs.getBytes(3), rs.getString(4)) }.findOne().orElse(null) ?: bad(pointer, "unknown_asset")
    if (a.purpose !in purposes) bad(pointer, "wrong_asset_purpose")
    return a
}

// ------------------------------------------------------------------------------------------------ AV and KV content

private val CONTENT_KINDS = setOf("av", "kv")
private val NODE_TYPES = setOf("wing", "division", "territory", "zone", "route", "outlet")
private const val CONTENT_COLS = "c.id, c.version, c.kind, c.title_en, c.title_bn, c.asset_url, c.sha256, c.bytes, c.duration_s, c.valid_from, c.valid_to, c.sequence, c.outlet_ids, c.status, c.assigned_scope::text AS scope, c.updated_at"

private fun mapContent(rs: java.sql.ResultSet) = ContentAdminDto(
    rs.getLong("id"), rs.getInt("version"), rs.getString("kind"), rs.getString("title_en"), rs.getString("title_bn"), rs.getString("asset_url"), hex(rs.getBytes("sha256")), rs.getLong("bytes"),
    rs.getObject("duration_s") as Int?, rs.getObject("valid_from", LocalDate::class.java).toString(), rs.getObject("valid_to", LocalDate::class.java).toString(), rs.getInt("sequence"),
    (rs.getArray("outlet_ids").array as Array<*>).map { it as Long }, rs.getString("status"),
    store.decodeFromString(ListSerializer(ScopeNode.serializer()), rs.getString("scope")), rs.getObject("updated_at", OffsetDateTime::class.java).toInstant().wire(),
)

private fun contentOf(h: Handle, id: Long): ContentAdminDto? = h.createQuery("SELECT $CONTENT_COLS FROM app.content_item c WHERE c.id = :i").bind("i", id).map { rs, _ -> mapContent(rs) }.findOne().orElse(null)

private fun listContent(call: ApplicationCall, d: AdminContentDeps): ContentPageDto {
    reader(call)
    val limit = AdminSupport.limit(call); val st = statusQuery(call)
    val cursor = AdminSupport.decodeCursor(call, 1)?.let { (a) -> a.toLongOrNull()?.takeIf { it >= 0 } ?: bad("query.cursor") }
    return d.db.jdbi.withHandle<ContentPageDto, Exception> { h ->
        val rows = h.createQuery("SELECT $CONTENT_COLS FROM app.content_item c WHERE (CAST(:st AS text) IS NULL OR c.status = :st) AND (CAST(:c AS bigint) IS NULL OR c.id > :c) ORDER BY c.id LIMIT :lim")
            .bind("st", st).bind("c", cursor).bind("lim", limit + 1).map { rs, _ -> mapContent(rs) }.list()
        ContentPageDto(rows.take(limit), if (rows.size > limit) AdminSupport.encodeCursor(rows[limit - 1].content_id) else null)
    }
}

/** The outlets a node-level assignment covers today (a snapshot: the phones read `outlet_ids`; empty scope = every outlet). */
private fun expandScope(h: Handle, nodes: List<ScopeNode>): List<Long> {
    if (nodes.isEmpty()) return emptyList()
    val ids = LinkedHashSet<Long>()
    for ((type, list) in nodes.groupBy { it.node_type }) {
        val sql = when (type) {
            "outlet" -> "SELECT id FROM app.outlet WHERE id = ANY(:n)"
            "route" -> "SELECT id FROM app.outlet WHERE route_id = ANY(:n)"
            "zone" -> "SELECT id FROM app.outlet WHERE zone_id = ANY(:n)"
            "territory" -> "SELECT o.id FROM app.outlet o JOIN app.zone z ON z.id = o.zone_id WHERE z.territory_id = ANY(:n)"
            "division" -> "SELECT o.id FROM app.outlet o JOIN app.zone z ON z.id = o.zone_id JOIN app.territory t ON t.id = z.territory_id WHERE t.division_id = ANY(:n)"
            else -> "SELECT o.id FROM app.outlet o JOIN app.zone z ON z.id = o.zone_id JOIN app.territory t ON t.id = z.territory_id JOIN app.division dv ON dv.id = t.division_id WHERE dv.wing_id = ANY(:n)"
        }
        ids += h.createQuery(sql).bindArray("n", Long::class.javaObjectType, list.map { it.node_id }).mapTo(Long::class.java).list()
    }
    return ids.sorted()
}

private class ContentChecked(val from: LocalDate, val to: LocalDate, val assetId: UUID, val st: String, val reason: String)

private fun validContent(req: ContentWriteIn): ContentChecked {
    if (req.kind !in CONTENT_KINDS) bad("body.kind")
    val reason = AdminSupport.reason(req.change_reason)
    text(req.title_en, "body.title_en", 120)
    if (req.title_bn != null && req.title_bn.length > 120) bad("body.title_bn", "length")
    val assetId = AdminSupport.uuid(req.asset_id, "body.asset_id")
    val from = AdminSupport.date(req.valid_from, "body.valid_from"); val to = AdminSupport.date(req.valid_to, "body.valid_to")
    if (to.isBefore(from)) bad("body.valid_to", "before_valid_from")
    if (req.sequence !in 1..20) bad("body.sequence", "out_of_range")
    val scope = req.assigned_scope.orEmpty()
    if (scope.size > 200) bad("body.assigned_scope", "out_of_range")
    scope.forEachIndexed { i, n -> if (n.node_type !in NODE_TYPES) bad("body.assigned_scope[$i].node_type"); if (n.node_id < 1) bad("body.assigned_scope[$i].node_id") }
    return ContentChecked(from, to, assetId, status(req.status), reason)
}

private fun contentAudit(c: ContentAdminDto) = buildJsonObject { put("version", c.version); put("status", c.status); put("valid_from", c.valid_from); put("valid_to", c.valid_to); put("sequence", c.sequence); put("outlets", c.outlet_ids.size) }

private fun contentAsset(h: Handle, d: AdminContentDeps, req: ContentWriteIn, c: ContentChecked): Asset {
    val a = assetOf(h, c.assetId, "body.asset_id", setOf(if (req.kind == "av") "content_av" else "content_kv"))
    val maxMb = AdminSupport.intConfig(d.config, "cfg.content.max_item_mb", 20)
    if (a.bytes > maxMb * 1_048_576L) throw ApiProblem(ProblemCode.ERR_PAYLOAD_TOO_LARGE, "the asset is above $maxMb MB", errors = listOf(FieldError("body.asset_id", "above_cap")))
    return a
}

private suspend fun createContent(call: ApplicationCall, d: AdminContentDeps): Versioned {
    writer(call)
    val p = call.principal
    val req = call.receiveStrict(ContentWriteIn.serializer())
    val c = validContent(req)
    val out = d.db.jdbi.inTransaction<ContentAdminDto, Exception> { h ->
        val a = contentAsset(h, d, req, c)
        val outlets = expandScope(h, req.assigned_scope.orEmpty())
        if (outlets.size > 5000) bad("body.assigned_scope", "too_many_outlets")
        val id = h.createQuery(
            "INSERT INTO app.content_item (kind, title_en, title_bn, asset_url, sha256, bytes, valid_from, valid_to, sequence, outlet_ids, status, created_by, asset_id, assigned_scope) " +
                "VALUES (:k, :te, :tb, :url, :sha, :b, :f, :t, :seq, :o, :s, :by, :aid, CAST(:sc AS jsonb)) RETURNING id",
        ).bind("k", req.kind).bind("te", req.title_en.trim()).bind("tb", req.title_bn?.trim()?.ifEmpty { null }).bind("url", d.blob.readUrl(a.blobPath)).bind("sha", a.sha256).bind("b", a.bytes)
            .bind("f", c.from).bind("t", c.to).bind("seq", req.sequence).bindArray("o", Long::class.javaObjectType, outlets).bind("s", c.st).bind("by", p.userId).bind("aid", a.id)
            .bind("sc", store.encodeToString(ListSerializer(ScopeNode.serializer()), req.assigned_scope.orEmpty())).mapTo(Long::class.java).one()
        val row = contentOf(h, id)!!
        AuditWriter.write(h, p, "content_item", id.toString(), "create", null, contentAudit(row), c.reason, call.requestId)
        row
    }
    return Versioned(out.version, out)
}

private suspend fun updateContent(call: ApplicationCall, d: AdminContentDeps): Versioned {
    writer(call)
    val p = call.principal
    val id = idPath(call); val expected = AdminSupport.ifMatch(call)
    val req = call.receiveStrict(ContentWriteIn.serializer())
    val c = validContent(req)
    val out = d.db.jdbi.inTransaction<ContentAdminDto, Exception> { h ->
        val v = checkVersion(h, "content_item", id, expected, "content item")
        val before = contentOf(h, id)!!
        if (before.kind != req.kind) bad("body.kind", "immutable")
        val a = contentAsset(h, d, req, c)
        val outlets = expandScope(h, req.assigned_scope.orEmpty())
        if (outlets.size > 5000) bad("body.assigned_scope", "too_many_outlets")
        h.createUpdate(
            "UPDATE app.content_item SET version = :v, title_en = :te, title_bn = :tb, asset_url = :url, sha256 = :sha, bytes = :b, valid_from = :f, valid_to = :t, sequence = :seq, outlet_ids = :o, status = :s, " +
                "asset_id = :aid, assigned_scope = CAST(:sc AS jsonb), updated_at = now() WHERE id = :i",
        ).bind("v", v + 1).bind("te", req.title_en.trim()).bind("tb", req.title_bn?.trim()?.ifEmpty { null }).bind("url", d.blob.readUrl(a.blobPath)).bind("sha", a.sha256).bind("b", a.bytes)
            .bind("f", c.from).bind("t", c.to).bind("seq", req.sequence).bindArray("o", Long::class.javaObjectType, outlets).bind("s", c.st).bind("aid", a.id)
            .bind("sc", store.encodeToString(ListSerializer(ScopeNode.serializer()), req.assigned_scope.orEmpty())).bind("i", id).execute()
        val after = contentOf(h, id)!!
        AuditWriter.write(h, p, "content_item", id.toString(), "update", contentAudit(before), contentAudit(after), c.reason, call.requestId)
        after
    }
    return Versioned(out.version, out)
}

// ------------------------------------------------------------------------------------------------ tutorials

private val TUTORIAL_KINDS = setOf("video", "manual")

private const val TUT_SQL = "SELECT t.id, t.version, t.kind, t.title_en, t.title_bn, t.sort, t.status, t.roles, a.blob_path, a.bytes FROM app.tutorial t JOIN app.admin_asset a ON a.asset_id = t.asset_id"

private fun mapTutorial(rs: java.sql.ResultSet, d: AdminContentDeps) = TutorialAdminDto(
    rs.getLong("id"), rs.getString("kind"), rs.getString("title_en"), rs.getString("title_bn"), d.blob.readUrl(rs.getString("blob_path")), rs.getLong("bytes"), null, rs.getInt("sort"),
    (rs.getArray("roles").array as Array<*>).map { it as String }, rs.getString("status"), rs.getInt("version"),
)

private fun tutorialOf(h: Handle, d: AdminContentDeps, id: Long) = h.createQuery("$TUT_SQL WHERE t.id = :i").bind("i", id).map { rs, _ -> mapTutorial(rs, d) }.findOne().orElse(null)

private fun listTutorials(call: ApplicationCall, d: AdminContentDeps): TutorialAdminListDto {
    reader(call)
    val st = statusQuery(call)
    return d.db.jdbi.withHandle<TutorialAdminListDto, Exception> { h ->
        TutorialAdminListDto(h.createQuery("$TUT_SQL WHERE (CAST(:st AS text) IS NULL OR t.status = :st) ORDER BY t.sort, t.id LIMIT 200").bind("st", st).map { rs, _ -> mapTutorial(rs, d) }.list())
    }
}

private class TutorialChecked(val assetId: UUID, val roles: List<String>, val st: String, val reason: String)

private fun validTutorial(req: TutorialWriteIn): TutorialChecked {
    if (req.kind !in TUTORIAL_KINDS) bad("body.kind")
    val reason = AdminSupport.reason(req.change_reason)
    text(req.title_en, "body.title_en", 120)
    if (req.title_bn != null && req.title_bn.length > 120) bad("body.title_bn", "length")
    val assetId = AdminSupport.uuid(req.asset_id, "body.asset_id")
    if (req.roles.isEmpty()) bad("body.roles", "required")
    val valid = Role.entries.map { it.wire }.toSet()
    req.roles.forEachIndexed { i, r -> if (r !in valid) bad("body.roles[$i]") }
    if (req.sort < 0) bad("body.sort", "out_of_range")
    return TutorialChecked(assetId, req.roles.distinct(), status(req.status), reason)
}

private fun tutorialAudit(t: TutorialAdminDto) = buildJsonObject { put("version", t.version); put("status", t.status); put("kind", t.kind); put("sort", t.sort); put("roles", t.roles.joinToString(",")) }

private suspend fun createTutorial(call: ApplicationCall, d: AdminContentDeps): Versioned {
    writer(call)
    val p = call.principal
    val req = call.receiveStrict(TutorialWriteIn.serializer())
    val c = validTutorial(req)
    val out = d.db.jdbi.inTransaction<TutorialAdminDto, Exception> { h ->
        val a = assetOf(h, c.assetId, "body.asset_id", setOf(if (req.kind == "video") "tutorial_video" else "tutorial_manual"))
        val id = h.createQuery("INSERT INTO app.tutorial (kind, title_en, title_bn, asset_id, roles, sort, status, created_by, updated_by) VALUES (:k, :te, :tb, :aid, :r, :sort, :s, :by, :by) RETURNING id")
            .bind("k", req.kind).bind("te", req.title_en.trim()).bind("tb", req.title_bn?.trim()?.ifEmpty { null }).bind("aid", a.id).bindArray("r", String::class.java, c.roles).bind("sort", req.sort).bind("s", c.st).bind("by", p.userId)
            .mapTo(Long::class.java).one()
        val row = tutorialOf(h, d, id)!!
        AuditWriter.write(h, p, "tutorial", id.toString(), "create", null, tutorialAudit(row), c.reason, call.requestId)
        row
    }
    return Versioned(out.version, out)
}

private suspend fun updateTutorial(call: ApplicationCall, d: AdminContentDeps): Versioned {
    writer(call)
    val p = call.principal
    val id = idPath(call); val expected = AdminSupport.ifMatch(call)
    val req = call.receiveStrict(TutorialWriteIn.serializer())
    val c = validTutorial(req)
    val out = d.db.jdbi.inTransaction<TutorialAdminDto, Exception> { h ->
        val v = checkVersion(h, "tutorial", id, expected, "tutorial")
        val before = tutorialOf(h, d, id)!!
        if (before.kind != req.kind) bad("body.kind", "immutable")
        val a = assetOf(h, c.assetId, "body.asset_id", setOf(if (req.kind == "video") "tutorial_video" else "tutorial_manual"))
        h.createUpdate("UPDATE app.tutorial SET version = :v, title_en = :te, title_bn = :tb, asset_id = :aid, roles = :r, sort = :sort, status = :s, updated_at = now(), updated_by = :by WHERE id = :i")
            .bind("v", v + 1).bind("te", req.title_en.trim()).bind("tb", req.title_bn?.trim()?.ifEmpty { null }).bind("aid", a.id).bindArray("r", String::class.java, c.roles).bind("sort", req.sort).bind("s", c.st).bind("by", p.userId).bind("i", id).execute()
        val after = tutorialOf(h, d, id)!!
        AuditWriter.write(h, p, "tutorial", id.toString(), "update", tutorialAudit(before), tutorialAudit(after), c.reason, call.requestId)
        after
    }
    return Versioned(out.version, out)
}

// ------------------------------------------------------------------------------------------------ print templates

private val TEMPLATE_KINDS = setOf("cash_memo", "credit_memo", "offer_memo", "drp_memo", "zero_memo", "edited_memo", "stock_slip", "day_summary", "due_receipt", "void_slip")

private fun listTemplates(call: ApplicationCall, d: AdminContentDeps): PrintTemplateListDto {
    reader(call)
    return d.db.jdbi.withHandle<PrintTemplateListDto, Exception> { h ->
        PrintTemplateListDto(h.createQuery("SELECT kind, version, font_columns, template_json FROM app.print_template ORDER BY kind, version DESC LIMIT 500")
            .map { rs, _ -> PrintTemplateDto(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getString(4)) }.list())
    }
}

private suspend fun createTemplate(call: ApplicationCall, d: AdminContentDeps): PrintTemplateDto {
    writer(call)
    val p = call.principal
    val req = call.receiveStrict(PrintTemplateWriteIn.serializer())
    if (req.kind !in TEMPLATE_KINDS) bad("body.kind")
    val reason = AdminSupport.reason(req.change_reason)
    if (req.font_columns != 32 && req.font_columns != 42) bad("body.font_columns")
    if (req.template_json.isEmpty() || req.template_json.length > 20000) bad("body.template_json", "length")
    // The definition must be a JSON object; the phone validates its content against its embedded schema.
    if (runCatching { Json.parseToJsonElement(req.template_json) }.getOrNull() !is JsonObject) bad("body.template_json", "not_a_json_object")
    val from = AdminSupport.date(req.effective_from, "body.effective_from")
    if (from.isBefore(AdminSupport.today(d.clock))) bad("body.effective_from", "in_the_past")
    return d.db.jdbi.inTransaction<PrintTemplateDto, Exception> { h ->
        h.createQuery("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))").bind("k", "print_template:${req.kind}").mapToMap().list()
        val next = h.createQuery("SELECT COALESCE(max(version), 0) + 1 FROM app.print_template WHERE kind = :k").bind("k", req.kind).mapTo(Int::class.java).one()
        if (next > 999) throw ApiProblem(ProblemCode.ERR_CONFLICT, "version limit reached for ${req.kind}")
        h.createUpdate("INSERT INTO app.print_template (kind, version, font_columns, template_json, effective_from, created_by) VALUES (:k, :v, :fc, :j, :f, :by)")
            .bind("k", req.kind).bind("v", next).bind("fc", req.font_columns).bind("j", req.template_json).bind("f", from).bind("by", p.userId).execute()
        AuditWriter.write(h, p, "print_template", "${req.kind}:$next", "create", null, buildJsonObject { put("version", next); put("font_columns", req.font_columns); put("effective_from", from.toString()); put("bytes", req.template_json.length) }, reason, call.requestId)
        PrintTemplateDto(req.kind, next, req.font_columns, req.template_json)
    }
}
