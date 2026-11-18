package com.aktcl.aron.backend.sync

import org.jdbi.v3.core.Handle

/**
 * THE ONE FUNCTION that says what became of a gift photo's parent record (C-R3, D-LOY-API-25): the redemption header (`redemption`) or one of its lines (`redemption_line`).
 *
 * | state     | what it is                                                                                                                         | the photo                                         |
 * |-----------|------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------|
 * | STORED    | the row exists and is not voided                                                                                                   | judged on its own checks                          |
 * | VOIDED    | the row exists and is voided                                                                                                       | stored, flagged `parent_voided`                   |
 * | REJECTED  | the registry says the record of THIS type was refused (final)                                                                      | stored, flagged `parent_refused`                  |
 * | DISCARDED | the record is held and a reviewer DISCARDED its quarantine item (a "return to device" is not a discard: the phone may send it again) | stored, flagged `parent_refused`                  |
 * | OPEN      | the record is held and its quarantine item is still open in review                                                                 | held with it (same code, `parent <uuid> is quarantined`), released or flagged when the reviewer decides |
 * | MISSING   | nothing is known of it (not synced yet, a record of another type under that uuid, a returned item)                                 | `parent_missing`, retryable                       |
 *
 * FINAL = STORED, VOIDED, REJECTED, DISCARDED. Everything else waits, and OPEN waits in review, never in the phone's retry loop.
 */
object GiftParents {
    enum class State { STORED, VOIDED, REJECTED, DISCARDED, OPEN, MISSING;
        val isFinal: Boolean get() = this == STORED || this == VOIDED || this == REJECTED || this == DISCARDED
        /** A final state in which the parent can never take a photo as a child: the photo is stored and flagged `parent_refused`. */
        val isRefused: Boolean get() = this == REJECTED || this == DISCARDED
    }

    /** [code] and [uuid] are set for OPEN: the hold's outcome code and the held parent. */
    data class Verdict(val state: State, val uuid: String? = null, val code: String? = null)

    private val TABLES = mapOf("redemption" to "redemption", "redemption_line" to "redemption_line")

    /** The state of the parent [uuid] of record type [type] (`redemption` or `redemption_line`). */
    fun state(h: Handle, uuid: String, type: String): Verdict {
        val table = TABLES[type] ?: error("not a gift photo parent type: $type")
        val row = h.createQuery("SELECT voided_at IS NOT NULL FROM app.$table WHERE client_uuid = CAST(:u AS uuid)").bind("u", uuid).mapTo(Boolean::class.java).findOne().orElse(null)
        if (row != null) return Verdict(if (row) State.VOIDED else State.STORED)
        val reg = h.createQuery("SELECT record_type, status FROM app.ingest_registry WHERE client_uuid = CAST(:u AS uuid)").bind("u", uuid)
            .map { rs, _ -> rs.getString(1) to rs.getString(2) }.findOne().orElse(null) ?: return Verdict(State.MISSING)
        if (reg.first != type) return Verdict(State.MISSING)   // a refused record of another type under that uuid is not a refused parent
        return when (reg.second) {
            "accepted", "voided" -> Verdict(State.STORED)
            "rejected" -> Verdict(State.REJECTED)
            "quarantined" -> {
                val q = h.createQuery("SELECT status, code, COALESCE(resolution_note, '') FROM app.sync_quarantine WHERE client_uuid = CAST(:u AS uuid) ORDER BY id DESC LIMIT 1").bind("u", uuid)
                    .map { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.findOne().orElse(null)
                when {
                    q == null -> Verdict(State.MISSING)
                    q.first == "open" -> Verdict(State.OPEN, uuid, q.second)
                    q.first == "discarded" && !q.third.startsWith("return_to_device") -> Verdict(State.DISCARDED)
                    q.first.startsWith("accepted") -> Verdict(State.STORED)
                    else -> Verdict(State.MISSING)
                }
            }
            else -> Verdict(State.MISSING)
        }
    }

    /**
     * What the redemption's lines that are NOT stored add up to, for a header with fewer stored lines than its line_count: REJECTED/DISCARDED when any such line is final-refused (the
     * redemption can never be complete), else OPEN with the first held line (by uuid) when one is held in review, else MISSING (a line is still on its way). Found through
     * `ingest_registry_family_unresolved` (V0193), never through a scan.
     */
    fun missingLines(h: Handle, family: String): Verdict {
        val lines = h.createQuery("SELECT client_uuid::text FROM app.ingest_registry WHERE family_uuid = CAST(:f AS uuid) AND record_type = 'redemption_line' AND status IN ('rejected', 'quarantined') ORDER BY client_uuid")
            .bind("f", family).mapTo(String::class.java).list().map { state(h, it, "redemption_line") }
        return lines.firstOrNull { it.state.isRefused } ?: lines.firstOrNull { it.state == State.OPEN } ?: Verdict(State.MISSING)
    }
}
