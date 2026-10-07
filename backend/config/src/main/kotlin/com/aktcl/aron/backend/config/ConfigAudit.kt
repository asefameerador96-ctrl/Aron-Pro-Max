package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.AronPrincipal
import kotlinx.serialization.json.JsonElement
import org.jdbi.v3.core.Handle

/**
 * Appends one row to the hash-chained `app.audit_log` inside the caller's transaction (docs/24 s8.6): the database
 * trigger assigns the sequence, the previous hash and the row hash, and rejects UPDATE and DELETE. Every admin write
 * of backend-admin goes through here so a write and its audit row commit together or not at all.
 * Delegates to the platform writer (F-SYS-059, `com.aktcl.aron.backend.platform.AuditLog`); kept so existing callers stay as they are.
 */
object AuditWriter {
    fun write(
        h: Handle, actor: AronPrincipal?, entity: String, entityId: String, action: String, before: JsonElement?, after: JsonElement?,
        reason: String?, requestId: String?, via: String = "web",
    ) = com.aktcl.aron.backend.platform.AuditLog.write(h, actor, entity, entityId, action, before, after, reason, requestId, via)
}
