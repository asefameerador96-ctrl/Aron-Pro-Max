package com.aktcl.aron.feature.tso.leave

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/** Status of a leave application (contract `DecisionStatus`). */
enum class LeaveStatus(val wire: String) {
    PENDING("pending"), APPROVED("approved"), REJECTED("rejected");

    companion object { fun of(wire: String): LeaveStatus = entries.firstOrNull { it.wire == wire } ?: PENDING } // lenient lookup for local use; the JSON reader drops unknown statuses
}

/**
 * One leave application as the list shows it (`GET /v1/leave`, contract `LeaveApplication`). [sent] is false for an
 * application still in the outbox (F-TSO-009): the card says "not sent yet" until the server acknowledges it.
 * [refusalCode] is set when the server refused the record on sync (outbox state rejected, its `RecordOutcomeCode`): the
 * application never reached the DMO and the card says "Not accepted: <reason>" (D-TSO-MAN-33). With [held] the server
 * stored it but holds it for an admin's review (outbox state quarantined, e.g. `scope_out_of_reach`): the card says
 * "Held for review: <reason>" until the server list carries it. [refusalReason] is the bundle's reason text of that code,
 * the words for a code without a leave text (R-W-10).
 */
data class LeaveItem(
    val leaveUuid: String,
    val leaveTypeCode: String,
    val fromDate: String,
    val toDate: String,
    val days: Int,
    val reason: String,
    val status: LeaveStatus,
    val sent: Boolean = true,
    val refusalCode: String? = null,
    val held: Boolean = false,
    val refusalReason: LeaveReasonText? = null,
)

/** The bundle's reason text of one outcome code (`reason_texts`, `{code: {bn, en}}`, docs/24 s4.5). */
data class LeaveReasonText(val bn: String?, val en: String?) {
    /** The text in the app's language; null when the bundle has none in that language (the card then says the generic words). */
    fun of(bangla: Boolean): String? = (if (bangla) bn else en)?.trim()?.takeIf { it.isNotEmpty() }
}

/** Reads the bundle section `reason_texts`; an absent or unreadable section, or a bad entry, gives no text (never a crash). */
object LeaveReasonTexts {
    fun parse(json: String?): Map<String, LeaveReasonText> = runCatching {
        val o = Json.parseToJsonElement(json ?: return emptyMap()) as JsonObject
        o.mapNotNull { (code, v) ->
            val t = v as? JsonObject ?: return@mapNotNull null
            fun text(lang: String) = (t[lang] as? JsonPrimitive)?.takeIf { it.isString }?.content
            code to LeaveReasonText(text("bn"), text("en"))
        }.toMap()
    }.getOrDefault(emptyMap())
}

/** How the server answered a queued application on sync, as the phone's outbox holds it (D-TSO-MAN-33, checker fix). */
enum class LeaveSyncAnswer { REFUSED, HELD }

/**
 * The unsent card after the server's answer: refused (outbox rejected) says "Not accepted", held for review (outbox
 * quarantined) says "Held for review", each with the reason worded from [code] (a leave text, else the bundle's text of
 * the code from [reasons], R-W-10); no answer leaves "Not sent yet".
 */
fun LeaveItem.answered(answer: LeaveSyncAnswer?, code: String?, reasons: Map<String, LeaveReasonText> = emptyMap()): LeaveItem {
    val c = code ?: "server_error"
    return when (answer) {
        null -> this
        LeaveSyncAnswer.REFUSED -> copy(refusalCode = c, held = false, refusalReason = reasons[c])
        LeaveSyncAnswer.HELD -> copy(refusalCode = c, held = true, refusalReason = reasons[c])
    }
}

/** TSO leave is decided by the DMO (D24-55); the badge names that role. */
const val APPROVER_ROLE = "DMO"

/**
 * The list order of screenshot 19: oldest period first (ascending by `from_date`), ties by uuid so the order is stable.
 * The manual text says "newest first"; the screenshot wins (D-TSO-MAN-33, conflict rule D-TSO-MAN-04, s7.2).
 */
fun orderLeave(items: List<LeaveItem>): List<LeaveItem> =
    items.sortedWith(compareBy<LeaveItem> { it.fromDate }.thenBy { it.leaveUuid })

/**
 * `to_date = from_date + days - 1` in calendar days, Fridays and holidays counted (contract LeaveApplicationPayload.days is
 * authoritative; D-TSO-MAN-33 fixes the vendor's one-date display of a two-day leave).
 */
fun leaveToDate(fromDate: String, days: Int): String = LocalDate.parse(fromDate).plusDays((days - 1).toLong()).toString()

/** The list a screen shows: server rows plus unsent local ones, a uuid present in both counts once (the server row wins). */
fun mergeLeave(server: List<LeaveItem>, local: List<LeaveItem>): List<LeaveItem> {
    val seen = server.map { it.leaveUuid }.toSet()
    return orderLeave((server + local.filter { it.leaveUuid !in seen }).distinctBy { it.leaveUuid })
}

/**
 * Why the server did not accept an application, as the card words it (R-W-10, LV-16). The four leave refusal codes of
 * D-B3-15 have leave texts: `business_date_out_of_window` the first day is outside the allowed window, `content_duplicate`
 * it overlaps a leave already applied for, `scope_out_of_reach` only a TSO may apply, `schema_invalid` the leave type is
 * not recognised (download fresh data). Every other code is [OTHER]: the bundle's reason text of the code, else "the server
 * did not accept it".
 */
enum class LeaveRefusal { OUT_OF_WINDOW, OVERLAP, NOT_TSO, UNKNOWN_TYPE, OTHER;
    companion object {
        fun of(code: String?): LeaveRefusal = when (code) {
            "business_date_out_of_window" -> OUT_OF_WINDOW
            "content_duplicate" -> OVERLAP
            "scope_out_of_reach" -> NOT_TSO
            "schema_invalid" -> UNKNOWN_TYPE
            else -> OTHER
        }
    }
}
