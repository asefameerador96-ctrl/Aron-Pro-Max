package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.AronPrincipal
import kotlinx.serialization.json.JsonElement
import org.jdbi.v3.core.Handle
import java.util.UUID

/**
 * Appends one row to the hash-chained `app.audit_log` inside the caller's transaction (docs/24 s8.6): the database
 * trigger assigns the sequence, the previous hash and the row hash, and rejects UPDATE and DELETE. Every admin write
 * of backend-admin goes through here so a write and its audit row commit together or not at all.
 * REQUEST: replace with the platform audit writer when backend-core ships F-SYS-059 (same columns).
 */
object AuditWriter {
    fun write(
        h: Handle, actor: AronPrincipal?, entity: String, entityId: String, action: String, before: JsonElement?, after: JsonElement?,
        reason: String?, requestId: String?, via: String = "web",
    ) {
        h.createUpdate(
            "INSERT INTO app.audit_log (actor_user_id, actor_username, actor_role, via, entity, entity_id, action, before, after, reason, request_id) " +
                "VALUES (:uid, :uname, :role, :via, :entity, :eid, :action, CAST(:before AS jsonb), CAST(:after AS jsonb), :reason, CAST(:rid AS uuid))",
        ).bind("uid", actor?.userId).bind("uname", actor?.username?.take(40)).bind("role", actor?.role?.wire).bind("via", via)
            .bind("entity", entity.take(40)).bind("eid", entityId.take(64)).bind("action", action.take(60))
            .bind("before", before?.toString()).bind("after", after?.toString()).bind("reason", reason?.take(500))
            .bind("rid", requestId?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }).execute()
    }
}
