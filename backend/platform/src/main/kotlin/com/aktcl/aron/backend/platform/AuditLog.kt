package com.aktcl.aron.backend.platform

import kotlinx.serialization.json.JsonElement
import org.jdbi.v3.core.Handle

/**
 * The platform audit writer (F-SYS-059, docs/24 s8.6). It appends one row to `app.audit_log` inside the caller's
 * transaction, so a write and its audit row commit together or not at all. The database does the rest:
 * - the insert trigger assigns `chain_seq`, `prev_hash` and `row_hash` under one lock (a linear SHA-256 chain);
 * - UPDATE, DELETE and TRUNCATE are refused;
 * - [verify] finds the first row whose hash, link or sequence does not verify.
 * Every module writes through here (backend-admin's `AuditWriter` delegates to it). Values are capped to the column
 * limits, and U+0000, which jsonb cannot hold, is replaced, so an audit row never makes its write fail.
 */
object AuditLog {
    fun write(
        h: Handle, actor: AronPrincipal?, entity: String, entityId: String, action: String, before: JsonElement?, after: JsonElement?,
        reason: String?, requestId: String?, via: String = "web", ipClass: String? = null,
    ) {
        require(via in setOf("web", "api", "job", "device")) { "audit via $via" }
        // An admin write (web or api) always names its actor; only jobs and devices may write without one.
        require(actor != null || via == "job" || via == "device") { "an audit row via $via needs an actor" }
        h.createUpdate(
            "INSERT INTO app.audit_log (actor_user_id, actor_username, actor_role, via, entity, entity_id, action, before, after, reason, request_id, ip_class) " +
                "VALUES (:uid, :uname, :role, :via, :entity, :eid, :action, CAST(:before AS jsonb), CAST(:after AS jsonb), :reason, CAST(:rid AS uuid), :ip)",
        ).bind("uid", actor?.userId).bind("uname", actor?.username?.take(40)).bind("role", actor?.role?.wire).bind("via", via)
            .bind("entity", entity.take(40)).bind("eid", entityId.take(64)).bind("action", action.take(60))
            .bind("before", before?.let(::jsonb)).bind("after", after?.let(::jsonb)).bind("reason", reason?.replace("\u0000", "�")?.take(500))
            .bind("rid", requestId?.lowercase()?.takeIf { STRICT_UUID.matches(it) }).bind("ip", ipClass?.take(60)).execute()
    }

    /** Id of the first audit row that does not verify, or null when the whole chain is intact (`app.audit_verify()`). */
    fun verify(h: Handle): Long? = h.createQuery("SELECT app.audit_verify()").mapTo(Long::class.javaObjectType).one()

    /** PostgreSQL's own uuid text form only (Java's UUID.fromString also accepts "1-1-1-1-1", which the cast refuses). */
    private val STRICT_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** Real U+0000 characters in keys and strings become U+FFFD (jsonb refuses them); a literal backslash-u0000 stays text. */
    private fun jsonb(e: JsonElement): String = clean(e).toString()

    private fun clean(e: JsonElement): JsonElement = when (e) {
        is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonObject(e.entries.associate { (k, v) -> k.replace('\u0000', '\uFFFD') to clean(v) })
        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map(::clean))
        is kotlinx.serialization.json.JsonPrimitive -> if (e.isString && '\u0000' in e.content) kotlinx.serialization.json.JsonPrimitive(e.content.replace('\u0000', '\uFFFD')) else e
    }
}
