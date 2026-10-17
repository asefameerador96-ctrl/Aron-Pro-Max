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
)

/** TSO leave is decided by the DMO (D24-55); the badge names that role. */
const val APPROVER_ROLE = "DMO"

/** The list order of the manual: newest period first, ties by uuid so the order is stable. */
fun orderLeave(items: List<LeaveItem>): List<LeaveItem> =
    items.sortedWith(compareByDescending<LeaveItem> { it.fromDate }.thenBy { it.leaveUuid })

/** `to_date = from_date + days - 1` (contract LeaveApplicationPayload.days is authoritative). */
fun leaveToDate(fromDate: String, days: Int): String = LocalDate.parse(fromDate).plusDays((days - 1).toLong()).toString()

/** The list a screen shows: server rows plus unsent local ones, a uuid present in both counts once (the server row wins). */
fun mergeLeave(server: List<LeaveItem>, local: List<LeaveItem>): List<LeaveItem> {
    val seen = server.map { it.leaveUuid }.toSet()
    return orderLeave((server + local.filter { it.leaveUuid !in seen }).distinctBy { it.leaveUuid })
}
