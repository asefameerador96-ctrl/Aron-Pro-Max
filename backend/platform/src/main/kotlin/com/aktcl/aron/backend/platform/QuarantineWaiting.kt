package com.aktcl.aron.backend.platform

/**
 * The one definition of a quarantine item that WAITS for another parent (D-LOY-API-28; the sync core writes it, the review queue and the worker sweep read it).
 *
 * A child held with a parent that a reviewer then accepted, whose OTHER parent is not stored yet, stays open with its code and a detail
 * `waits for another parent: <field> <awaited uuid>[ (<what>)] (parent <accepted uuid> was accepted)[ (the other parent was refused or discarded; discard this item to close it)]`.
 * The detail holds field names and uuids only, never personal data. No column: the marker is the detail, so no migration.
 */
object QuarantineWaiting {
    const val PREFIX = "waits for another parent:"
    const val REFUSED_HINT = " (the other parent was refused or discarded; discard this item to close it)"
    private const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val AWAITS = Regex("^" + Regex.escape(PREFIX) + " (\\w+) ($UUID)")
    private val ACCEPTED = Regex("\\(parent ($UUID) was accepted\\)")

    fun isWaiting(detail: String?): Boolean = detail != null && detail.startsWith(PREFIX)
    /** (field, uuid) of the parent a waiting item awaits, or null. */
    fun awaited(detail: String?): Pair<String, String>? = detail?.let { AWAITS.find(it) }?.let { it.groupValues[1] to it.groupValues[2] }
    /** The uuid of the parent whose accept released this child into waiting. */
    fun acceptedParent(detail: String?): String? = detail?.let { ACCEPTED.find(it) }?.groupValues?.get(1)
    fun refusedHint(detail: String?): Boolean = detail != null && detail.contains(REFUSED_HINT)
}
