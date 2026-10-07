package com.aktcl.aron.backend.platform

import kotlinx.serialization.json.JsonElement
import org.jdbi.v3.core.Handle
import java.util.UUID

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
        h.createUpdate(
            "INSERT INTO app.audit_log (actor_user_id, actor_username, actor_role, via, entity, entity_id, action, before, after, reason, request_id, ip_class) " +
                "VALUES (:uid, :uname, :role, :via, :entity, :eid, :action, CAST(:before AS jsonb), CAST(:after AS jsonb), :reason, CAST(:rid AS uuid), :ip)",
        ).bind("uid", actor?.userId).bind("uname", actor?.username?.take(40)).bind("role", actor?.role?.wire).bind("via", via)
            .bind("entity", entity.take(40)).bind("eid", entityId.take(64)).bind("action", action.take(60))
            .bind("before", before?.let(::jsonb)).bind("after", after?.let(::jsonb)).bind("reason", reason?.replace("\u0000", "�")?.take(500))
            .bind("rid", requestId?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }).bind("ip", ipClass?.take(60)).execute()
    }

    /** Id of the first audit row that does not verify, or null when the whole chain is intact (`app.audit_verify()`). */
    fun verify(h: Handle): Long? = h.createQuery("SELECT app.audit_verify()").mapTo(Long::class.javaObjectType).one()

    private fun jsonb(e: JsonElement): String = e.toString().replace("\\u0000", "\\ufffd")
}
