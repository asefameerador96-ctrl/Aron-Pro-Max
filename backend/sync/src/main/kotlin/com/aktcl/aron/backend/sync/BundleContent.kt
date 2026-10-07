package com.aktcl.aron.backend.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.time.LocalDate

/**
 * The bundle's `content` (AV/KV, contract ContentItem) and `surveys` (contract SurveyDef) sections (F-SR-020/021,
 * android-sr-a-av-kv-survey-data.md), read from the admin portal's `app.content_item` and `app.survey` /
 * `app.survey_version`. The phone records `content_view` and `survey_response` against them (both already ingested).
 * Survey points are loyalty and deferred (docs/27): the bundle carries no points and the server posts none.
 */
object BundleContent {
    /** Survey kind per role: the SR's POSM survey, the AMO survey, the TSO visit query. */
    private val KIND_BY_ROLE = mapOf("SR" to "posm", "AMO" to "amo_survey", "TSO" to "tso_visit_query")

    /**
     * Active AV/KV items valid on some day of [from]..[to] (the schedule horizon), play order first. An item that names
     * outlets keeps only those on the caller's routes and is left out when none is; an empty list means every outlet.
     */
    fun content(h: Handle, from: LocalDate, to: LocalDate, outletIds: Set<Long>): List<JsonObject> = h.createQuery(
        """
        SELECT id, version, kind, title_en, title_bn, asset_url, encode(sha256, 'hex') AS sha, bytes, duration_s, valid_from, valid_to, sequence, outlet_ids
        FROM app.content_item WHERE status = 'active' AND valid_from <= :to AND valid_to >= :from
        ORDER BY sequence, id LIMIT 200
        """.trimIndent(),
    ).bind("from", from).bind("to", to).map { rs, _ ->
        @Suppress("UNCHECKED_CAST")
        val named = (rs.getArray("outlet_ids").array as Array<Long>).toList()
        val mine = named.filter { it in outletIds }
        if (named.isNotEmpty() && mine.isEmpty()) null
        else buildJsonObject {
            put("content_id", rs.getLong("id")); put("version", rs.getInt("version")); put("kind", rs.getString("kind"))
            put("title_en", rs.getString("title_en")); put("title_bn", rs.getString("title_bn")); put("asset_url", rs.getString("asset_url"))
            put("sha256", rs.getString("sha")); put("bytes", rs.getInt("bytes")); put("duration_s", rs.getObject("duration_s")?.let { (it as Number).toInt() })
            put("valid_from", rs.getObject("valid_from", LocalDate::class.java).toString()); put("valid_to", rs.getObject("valid_to", LocalDate::class.java).toString())
            put("sequence", rs.getInt("sequence")); put("outlet_ids", JsonArray(mine.sorted().map { JsonPrimitive(it) }))
        }
    }.list().filterNotNull().take(50)

    /** The role's active surveys valid on [date], current version, questions as the phone needs them (contract max 20). */
    fun surveys(h: Handle, role: String, date: LocalDate): List<JsonObject> {
        val kind = KIND_BY_ROLE[role] ?: return emptyList()
        return h.createQuery(
            """
            SELECT s.id, s.version, v.title_en, v.title_bn, v.questions::text AS questions FROM app.survey s
            JOIN app.survey_version v ON v.survey_id = s.id AND v.version = s.version
            WHERE s.kind = :k AND s.status = 'active' AND s.valid_from <= :d AND (s.valid_to IS NULL OR s.valid_to >= :d)
            ORDER BY s.id LIMIT 20
            """.trimIndent(),
        ).bind("k", kind).bind("d", date).map { rs, _ ->
            buildJsonObject {
                put("survey_id", rs.getLong("id")); put("version", rs.getInt("version"))
                put("title_en", rs.getString("title_en")); put("title_bn", rs.getString("title_bn"))
                put("questions", JsonArray(Json.parseToJsonElement(rs.getString("questions")).jsonArray.take(50).map { question(it.jsonObject) }))
            }
        }.list()
    }

    /** A stored question (the admin's StoredQuestion) in the SurveyDef item shape; `photo` becomes `requires_photo`. */
    private fun question(q: JsonObject): JsonObject = buildJsonObject {
        fun copy(k: String) { q[k]?.takeIf { it !is JsonNull }?.let { put(k, it) } }
        val type = (q["answer_type"] as? JsonPrimitive)?.content
        put("question_id", q["question_id"] ?: JsonNull)
        put("answer_type", q["answer_type"] ?: JsonNull)
        put("label_en", q["label_en"] ?: JsonNull)
        put("label_bn", q["label_bn"] ?: JsonNull)
        q["option_codes"]?.takeIf { it is JsonArray }?.let { put("option_codes", it) }
        put("requires_photo", (q["photo"] as? JsonPrimitive)?.content == "true" || type == "photo_only")
        copy("key"); copy("required")
        // show_if_* are nullable in the contract: keep a stated null so "always shown" is explicit.
        put("show_if_key", q["show_if_key"] ?: JsonNull); put("show_if_bool", q["show_if_bool"] ?: JsonNull)
    }
}
