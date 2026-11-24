package com.aktcl.aron.backend.platform

/**
 * The reader text of a quarantine item that WAITS for another parent (D-LOY-API-28, -30). The marker itself is TYPED (columns `waiting_since`, `awaited_uuid`, `accepted_parent_uuid`,
 * `waiting_release_code`, `awaited_refused`, `waiting_checked_at` of `app.sync_quarantine`, V0204); this object only builds and reads the human-readable `detail`, which holds field names,
 * codes and uuids only, never personal data and never free text of a handler.
 *
 * A child held with a parent that a reviewer then accepted, whose OTHER parent is not stored yet, stays open with its code and the detail
 * `waits for another parent: <field> <awaited uuid>[ (<what>)] (parent <accepted uuid> was accepted)[ (the other parent was refused or discarded; discard this item to close it)]`.
 * The API process's sweep releases it when that parent is stored.
 */
object QuarantineWaiting {
    const val PREFIX = "waits for another parent:"
    const val REFUSED_HINT = " (the other parent was refused or discarded; discard this item to close it)"
    private const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val FIELD = Regex("^" + Regex.escape(PREFIX) + " ([A-Za-z0-9_]+) ")
    private val CODE = Regex("^[a-z0-9_]{1,60}$")

    fun detail(field: String, awaited: String, accepted: String, refused: Boolean, photo: Boolean): String =
        "$PREFIX $field $awaited" + (if (photo) " (the redemption or its lines are not stored yet)" else "") + " (parent $accepted was accepted)" + (if (refused) REFUSED_HINT else "")

    /** The field a waiting detail names, or null. */
    fun field(detail: String?): String? = detail?.let { FIELD.find(it) }?.groupValues?.get(1)
    fun hinted(detail: String?): Boolean = detail != null && detail.contains(REFUSED_HINT)
    /** [detail] with the refused hint present exactly when [refused]. */
    fun withHint(detail: String, refused: Boolean): String = detail.replace(REFUSED_HINT, "") + if (refused) REFUSED_HINT else ""

    /** An item that stays held after its parent's accept without a nameable awaited parent: ordinary, plain reason (codes only). */
    fun heldDetail(acceptedParent: String, outcomeCode: String?): String =
        "held after parent $acceptedParent was accepted: " + (outcomeCode?.takeIf { CODE.matches(it) } ?: "not stored")

    /** The reason of a child that became an ordinary held item: its own hold, from the server's own detail (field names and uuids), or its outcome code. */
    fun ownHoldDetail(serverDetail: String?, code: String): String =
        "a check of its own held it after the awaited parent was stored: " + (serverDetail?.takeIf { it.length <= 300 && it.all { c -> c.isLetterOrDigit() || c in " _-:.,()/'" } } ?: code.takeIf { CODE.matches(it) } ?: "held")
}
