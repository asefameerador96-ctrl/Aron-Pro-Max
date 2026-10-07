package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.RecordOutcomeCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jdbi.v3.core.Handle
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes one validated record into its server table (docs/24 s4.2, s12.1). The tables carry the envelope columns and
 * columns named like the payload members (db lane, N-006), so most types are one generic statement:
 * `INSERT ... SELECT ... FROM jsonb_populate_record(NULL::app.<table>, :doc)` over the columns the document and the
 * table share, with PostgreSQL doing the type conversion and the table's CHECKs guarding values. A `fix` object is
 * flattened into the `fix_*` columns and stored whole in `app.geo_fix`; a visit's `geo` verdict into the verdict
 * columns. Special cases: `visit_close` (closes the visit row), `day_open`/`day_submit` (route_day_event kinds),
 * `outlet_request_verification` (request trail) and `device_status` (status report). Identity (user, device, batch)
 * comes from the [Uploader], never from the record.
 */
object RecordWriter {
    sealed class Result {
        data class Stored(val serverId: Long?) : Result()
        data class AlreadyThere(val serverId: Long?) : Result()
        data class Refused(val code: RecordOutcomeCode, val detail: String) : Result()
    }

    private val columnCache = ConcurrentHashMap<String, Set<String>>()

    private fun columns(h: Handle, table: String): Set<String> = columnCache.getOrPut(table) {
        h.createQuery(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = 'app' AND table_name = :t AND is_identity = 'NO' AND is_generated = 'NEVER'",
        ).bind("t", table).mapTo(String::class.java).set()
    }

    fun write(h: Handle, type: String, rule: TypeRule, env: JsonObject, payload: JsonObject, up: Uploader, batchUuid: String, now: Instant): Result {
        val clientUuid = env.str("client_uuid")!!
        return when (type) {
            "visit_close" -> closeVisit(h, clientUuid, env, payload, now)
            else -> {
                val doc = LinkedHashMap<String, JsonElement>()
                env.forEach { (k, v) -> if (k !in ENVELOPE_ONLY) doc[k] = v }
                payload.forEach { (k, v) ->
                    when (k) {
                        "fix" -> (v as? JsonObject)?.let { doc.putAll(fixColumns(it)) }
                        "geo" -> (v as? JsonObject)?.let { g -> g.forEach { (gk, gv) -> doc[if (gk == "action") "geo_action" else gk] = gv } }
                        "edit_fix" -> Unit
                        else -> doc[k] = v
                    }
                }
                when (type) {
                    "day_open", "day_submit" -> doc["kind"] = JsonPrimitive(type)
                    "outlet_request_verification" -> {
                        doc["event"] = payload["decision"] ?: JsonNull
                        doc["actor_user_id"] = JsonPrimitive(up.userId)
                        doc["via"] = JsonPrimitive("device")
                        doc["at"] = env["captured_at"] ?: JsonNull
                    }
                    "qc_line" -> {
                        // The QC header (one per visit) is the server's; the line points at it (s12.1).
                        val visit = payload.str("visit_client_uuid")!!
                        h.createUpdate(
                            """
                            INSERT INTO app.qc_entry (visit_client_uuid, business_date, user_id, route_id, outlet_id)
                            SELECT v.client_uuid, v.business_date, v.user_id, v.route_id, v.outlet_id FROM app.visit v WHERE v.client_uuid = CAST(:v AS uuid) LIMIT 1
                            ON CONFLICT (visit_client_uuid) DO NOTHING
                            """.trimIndent(),
                        ).bind("v", visit).execute()
                        val qc = h.createQuery("SELECT id FROM app.qc_entry WHERE visit_client_uuid = CAST(:v AS uuid)").bind("v", visit).mapTo(Long::class.java).findOne().orElse(null)
                            ?: return Result.Refused(RecordOutcomeCode.PARENT_MISSING, "visit $visit")
                        doc["qc_entry_id"] = JsonPrimitive(qc)
                    }
                    "config_ack" -> {
                        // The payload's config_version is the applied one; the envelope's stays the capture stamp.
                        doc["acked_config_version"] = payload["config_version"] ?: JsonNull
                        doc["config_version"] = env["config_version"] ?: JsonNull
                    }
                    "device_status" -> {
                        doc["source"] = JsonPrimitive("record")
                        doc["report"] = JsonObject(payload.filterKeys { it != "play_integrity" })
                    }
                }
                doc["user_id"] = JsonPrimitive(up.userId)
                doc["device_id"] = JsonPrimitive(up.deviceId)
                doc["first_batch_uuid"] = JsonPrimitive(batchUuid)
                doc["received_at"] = JsonPrimitive(now.wire())
                val result = insert(h, rule.table, clientUuid, doc)
                if (result is Result.Stored) {
                    (payload["fix"] as? JsonObject)?.let { geoFix(h, type, "fix", env, it, up, now) }
                    (payload["edit_fix"] as? JsonObject)?.let { geoFix(h, type, "edit_fix", env, it, up, now) }
                }
                result
            }
        }
    }

    private val ENVELOPE_ONLY = setOf("type", "rank", "payload")

    private fun fixColumns(fix: JsonObject): Map<String, JsonElement> = buildMap {
        fix["fix_status"]?.let { put("fix_status", it) }
        fix["lat"]?.let { put("fix_lat", it) }
        fix["lng"]?.let { put("fix_lng", it) }
        fix["accuracy_m"]?.let { put("fix_accuracy_m", it) }
        fix["is_mock"]?.let { put("fix_is_mock", it) }
    }

    /**
     * Generic insert over the shared columns. Only a conflict on this record's own client_uuid is
     * [Result.AlreadyThere]; any other unique violation (a reused memo number, a second check-in, a second gift photo)
     * is never swallowed: the record is refused with its outcome code and kept for review (docs/24 s4.5).
     */
    private fun insert(h: Handle, table: String, clientUuid: String, doc: Map<String, JsonElement>): Result {
        val cols = columns(h, table).let { tc -> doc.keys.filter { it in tc } }
        require(cols.isNotEmpty()) { "no columns for $table" }
        val list = cols.joinToString(", ") { "\"$it\"" }
        val sp = "ins"
        h.savepoint(sp)
        val id = try {
            h.createQuery("INSERT INTO app.$table ($list) SELECT $list FROM jsonb_populate_record(NULL::app.$table, CAST(:doc AS jsonb)) RETURNING id")
                .bind("doc", JsonObject(doc).toString()).mapTo(Long::class.java).one().also { h.release(sp) }
        } catch (e: Exception) {
            val sql = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
            if (sql?.sqlState != "23505") throw e
            h.rollbackToSavepoint(sp)
            existingId(h, table, clientUuid)?.let { return Result.AlreadyThere(it) }
            val constraint = Regex("unique constraint \"([^\"]+)\"").find(sql.message.orEmpty())?.groupValues?.get(1) ?: "unknown"
            return Result.Refused(uniqueCode(table, constraint), "unique violation on $constraint")
        }
        return Result.Stored(id)
    }

    /** Outcome code of a unique violation that is not this record's own client_uuid. */
    internal fun uniqueCode(table: String, constraint: String): RecordOutcomeCode = when {
        table == "memo" && "memo_no" in constraint -> RecordOutcomeCode.MEMO_NO_DUPLICATE
        table == "attendance_event" -> RecordOutcomeCode.ATTENDANCE_DUPLICATE
        table == "gift_photo" -> RecordOutcomeCode.GIFT_PHOTO_EXISTS
        else -> RecordOutcomeCode.CONTENT_DUPLICATE
    }

    private fun existingId(h: Handle, table: String, clientUuid: String): Long? =
        h.createQuery("SELECT id FROM app.$table WHERE client_uuid = CAST(:c AS uuid) LIMIT 1").bind("c", clientUuid).mapTo(Long::class.java).findOne().orElse(null)

    /** The full fix in app.geo_fix (one per record and slot), for the server geo re-check and the risk rules. */
    private fun geoFix(h: Handle, type: String, slot: String, env: JsonObject, fix: JsonObject, up: Uploader, now: Instant) {
        val doc = LinkedHashMap<String, JsonElement>()
        fix.forEach { (k, v) -> if (k != "device") doc[k] = v }
        (fix["device"] as? JsonObject)?.forEach { (k, v) -> doc[k] = v }
        (fix["gnss"] as? JsonObject)?.forEach { (k, v) -> if (k !in doc) doc[k] = v }
        doc["business_date"] = env["business_date"]!!
        doc["source_type"] = JsonPrimitive(type)
        doc["source_client_uuid"] = env["client_uuid"]!!
        doc["slot"] = JsonPrimitive(slot)
        doc["user_id"] = JsonPrimitive(up.userId)
        doc["device_id"] = JsonPrimitive(up.deviceId)
        env["route_id"]?.let { doc["route_id"] = it }
        doc["captured_at"] = env["captured_at"]!!
        doc["received_at"] = JsonPrimitive(now.wire())
        val cols = columns(h, "geo_fix").let { tc -> doc.keys.filter { it in tc } }
        val list = cols.joinToString(", ") { "\"$it\"" }
        h.createUpdate("INSERT INTO app.geo_fix ($list) SELECT $list FROM jsonb_populate_record(NULL::app.geo_fix, CAST(:doc AS jsonb)) ON CONFLICT DO NOTHING")
            .bind("doc", JsonObject(doc).toString()).execute()
    }

    /** visit_close fills the close columns of its visit once; the first close stands (s4.2). */
    private fun closeVisit(h: Handle, clientUuid: String, env: JsonObject, p: JsonObject, now: Instant): Result {
        val visit = p.str("visit_client_uuid")!!
        val id = h.createQuery(
            """
            UPDATE app.visit SET close_client_uuid = CAST(:cu AS uuid), close_received_at = :now, close_captured_at = CAST(:cap AS timestamptz),
                outcome_code = :oc, call_started_at = CAST(:cs AS timestamptz), call_declined = :cd, ended_at = CAST(:ea AS timestamptz), is_zero_sale = :z
            WHERE client_uuid = CAST(:v AS uuid) AND close_client_uuid IS NULL RETURNING id
            """.trimIndent(),
        ).bind("cu", clientUuid).bind("now", IngestService.ts(now)).bind("cap", env.str("captured_at")).bind("oc", p.str("outcome_code"))
            .bind("cs", p.str("call_started_at")).bind("cd", p.bool("call_declined")).bind("ea", p.str("ended_at")).bind("z", p.bool("is_zero_sale"))
            .bind("v", visit).mapTo(Long::class.java).findOne().orElse(null)
        if (id != null) return Result.Stored(id)
        val existing = h.createQuery("SELECT id, close_client_uuid::text FROM app.visit WHERE client_uuid = CAST(:v AS uuid) LIMIT 1").bind("v", visit)
            .map { rs, _ -> rs.getLong(1) to rs.getString(2) }.findOne().orElse(null)
            ?: return Result.Refused(RecordOutcomeCode.PARENT_MISSING, "visit $visit")
        return if (existing.second == clientUuid) Result.AlreadyThere(existing.first)
        else Result.Refused(RecordOutcomeCode.CONTENT_DUPLICATE, "visit $visit already closed by ${existing.second}")
    }
}
