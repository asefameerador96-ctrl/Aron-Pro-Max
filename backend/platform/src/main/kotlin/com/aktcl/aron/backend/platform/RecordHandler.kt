package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.RecordOutcomeCode
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonObject
import org.jdbi.v3.core.Handle
import java.time.Instant
import java.time.LocalDate

/**
 * One device record as ingest sees it after its envelope, registry, scope and parent checks passed (docs/24 s4.2).
 * Identity (user, role, device) comes from the token and the device row, never from the record.
 */
data class IngestRecord(
    val type: String,
    val clientUuid: String,
    val businessDate: LocalDate,
    /** The whole envelope as sent (including `payload`). */
    val envelope: JsonObject,
    val payload: JsonObject,
    val userId: Long,
    val role: Role,
    val deviceId: Long,
    val batchUuid: String,
    val receivedAt: Instant,
)

/** A handler's refusal; the outcome code's status decides quarantine, park (retryable) or final rejection (s4.5). */
data class RecordRefusal(val code: RecordOutcomeCode, val detail: String)

/**
 * The ingest extension point (F-API-006 family): type-specific rules and side effects of a device record, so other
 * modules (config, masterdata, notify) add behaviour to `POST /v1/sync/batch` without editing the sync module.
 *
 * Both calls run inside the record's family transaction and savepoint, on the batch's [Handle]:
 * - [check] runs before the generic write; a [RecordRefusal] stops the record (nothing is stored, the registry keeps
 *   the outcome so a resend gets the same answer).
 * - [afterStored] runs once, only when the record was stored for the first time (never for a duplicate or replay).
 *   It must be idempotent at the row level anyway (`ON CONFLICT DO NOTHING` or a guarded `UPDATE`), never open its own
 *   transaction, never set session state (PgBouncer), never call the network. Throwing rolls the record back and acks
 *   it `rejected(server_error)` retryable, so the phone resends it.
 */
interface RecordHandler {
    /** Record types this handler is for (the envelope `type`, e.g. `config_ack`). */
    val types: Set<String>

    /**
     * Runs before the parent check, so a record (and its children) that must be refused for good is refused `final`
     * instead of parked while its parent is missing (the admin data-void barrier needs this).
     */
    fun checkEarly(h: Handle, rec: IngestRecord): RecordRefusal? = null

    fun check(h: Handle, rec: IngestRecord): RecordRefusal? = null

    /**
     * The server id of an earlier stored row that is the same fact under another client_uuid (a domain duplicate, e.g.
     * a consent re-accepted after a wipe); the record is then acked `duplicate` with that id and nothing is stored.
     * Runs after [check], in the record's savepoint; take any lock the answer needs here.
     */
    fun sameAs(h: Handle, rec: IngestRecord): Long? = null

    fun afterStored(h: Handle, rec: IngestRecord, serverId: Long?) {}

    /**
     * After the record's family transaction committed with the record stored (never for a rolled-back or refused
     * record, a duplicate or a replay): the place for side effects outside the database (a push nudge). It runs on the
     * request thread, must not block and must not throw (a throw is logged and ignored).
     */
    fun afterCommit(rec: IngestRecord) {}
}

/** Registered handlers, called in registration order. Built once at wiring time; immutable afterwards. */
class RecordHandlers(handlers: List<RecordHandler>) {
    private val byType: Map<String, List<RecordHandler>> =
        handlers.flatMap { hd -> hd.types.map { it to hd } }.groupBy({ it.first }, { it.second })

    fun forType(type: String): List<RecordHandler> = byType[type].orEmpty()

    companion object {
        val NONE = RecordHandlers(emptyList())
    }
}

/**
 * A data-only push nudge to a user's phones (N-037, D24-23): it tells the phone to sync now and carries no business
 * data (the task itself arrives with the next sync). Best effort and asynchronous; implemented by backend:notify.
 */
fun interface Nudger {
    fun nudge(userId: Long, reason: String)

    companion object {
        val NONE = Nudger { _, _ -> }
    }
}
