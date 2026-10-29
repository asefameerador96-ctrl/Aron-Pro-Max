package com.aktcl.aron.feature.tso.leave

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
 * "Held for review: <reason>" until the server list carries it.
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
)

/** How the server answered a queued application on sync, as the phone's outbox holds it (D-TSO-MAN-33, checker fix). */
enum class LeaveSyncAnswer { REFUSED, HELD }

/**
 * The unsent card after the server's answer: refused (outbox rejected) says "Not accepted", held for review (outbox
 * quarantined) says "Held for review", each with the reason worded from [code]; no answer leaves "Not sent yet".
 */
fun LeaveItem.answered(answer: LeaveSyncAnswer?, code: String?): LeaveItem = when (answer) {
    null -> this
    LeaveSyncAnswer.REFUSED -> copy(refusalCode = code ?: "server_error", held = false)
    LeaveSyncAnswer.HELD -> copy(refusalCode = code ?: "server_error", held = true)
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

/** Why a refused application was not accepted, as the card words it (unknown codes say "the server did not accept it"). */
enum class LeaveRefusal { DATE_WINDOW, DUPLICATE, NOT_ALLOWED, INVALID, OTHER;
    companion object {
        fun of(code: String?): LeaveRefusal = when (code) {
            "business_date_out_of_window" -> DATE_WINDOW
            "content_duplicate", "payload_conflict" -> DUPLICATE
            "scope_out_of_reach", "no_assignment_on_date", "user_disabled" -> NOT_ALLOWED
            "schema_invalid" -> INVALID
            else -> OTHER
        }
    }
}
