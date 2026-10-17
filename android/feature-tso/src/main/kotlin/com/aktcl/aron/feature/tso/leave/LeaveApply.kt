package com.aktcl.aron.feature.tso.leave

import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Leave types of the manual (Casual, Sick, Earn); the codes are the contract's `leave_type_code`. */
val LEAVE_TYPES = listOf("casual", "sick", "earn")

/** Contract LeaveApplicationPayload bounds: days 1..365, reason 1..500. */
const val MAX_DAYS = 365
const val MAX_REASON = 500

/** How far back a start date may be (a sick day is applied for after the fact) and how far ahead. Decision D-TSO-LEAVE-WINDOW. */
const val MAX_BACKDATE_DAYS = 31L
const val MAX_AHEAD_DAYS = 365L

enum class LeaveError { TYPE, DATE_FORMAT, DATE_PAST, DATE_FAR, DAYS, REASON_EMPTY, REASON_LONG }

data class LeaveForm(val type: String = LEAVE_TYPES.first(), val fromDate: String, val days: Int = 1, val reason: String = "")

/** The record payload (contract `LeaveApplicationPayload`); `to_date` is not sent, `days` is authoritative. */
data class LeavePayload(val leaveTypeCode: String, val fromDate: String, val days: Int, val reason: String)

/** Pure validation. [today] is the Dhaka business date. Whitespace-only reasons are empty; the length counts characters of the trimmed text. */
fun validateLeave(form: LeaveForm, today: String): List<LeaveError> {
    val errors = mutableListOf<LeaveError>()
    if (form.type !in LEAVE_TYPES) errors += LeaveError.TYPE
    val from = runCatching { LocalDate.parse(form.fromDate) }.getOrNull()
    if (from == null) errors += LeaveError.DATE_FORMAT else {
        val t = LocalDate.parse(today)
        if (from.isBefore(t.minusDays(MAX_BACKDATE_DAYS))) errors += LeaveError.DATE_PAST
        if (from.isAfter(t.plusDays(MAX_AHEAD_DAYS))) errors += LeaveError.DATE_FAR
    }
    if (form.days !in 1..MAX_DAYS) errors += LeaveError.DAYS
    val r = form.reason.trim()
    if (r.isEmpty()) errors += LeaveError.REASON_EMPTY else if (r.length > MAX_REASON) errors += LeaveError.REASON_LONG
    return errors
}

fun leavePayload(form: LeaveForm) = LeavePayload(form.type, form.fromDate, form.days, form.reason.trim())

/** Queues the application as a sync record. Idempotent by [uuid]; false when the uuid was already queued. REQUEST-free: the host writes the outbox row. */
fun interface LeaveQueue {
    suspend fun submit(uuid: String, payload: LeavePayload): Boolean
}

sealed interface LeaveApplyResult {
    data object Editing : LeaveApplyResult
    data object Queued : LeaveApplyResult
    data class Invalid(val errors: List<LeaveError>) : LeaveApplyResult
    data object Failed : LeaveApplyResult
}

/**
 * The apply form (F-TSO-009). One client uuid per form instance, made when the form opens: a double tap or a retry after a
 * failure sends the same uuid, so it can never queue twice. Nothing waits for the network.
 */
class LeaveApplyState(private val queue: LeaveQueue, private val today: () -> String, private val newUuid: () -> String = { UUID.randomUUID().toString() }) {
    private var uuid = newUuid()
    private var busy = false
    private val _form = MutableStateFlow(LeaveForm(fromDate = today()))
    val form: StateFlow<LeaveForm> = _form.asStateFlow()
    private val _result = MutableStateFlow<LeaveApplyResult>(LeaveApplyResult.Editing)
    val result: StateFlow<LeaveApplyResult> = _result.asStateFlow()

    fun edit(f: (LeaveForm) -> LeaveForm) {
        if (_result.value is LeaveApplyResult.Queued) return
        _form.value = f(_form.value); _result.value = LeaveApplyResult.Editing
    }

    suspend fun submit() {
        if (busy || _result.value is LeaveApplyResult.Queued) return
        val errors = validateLeave(_form.value, today())
        if (errors.isNotEmpty()) { _result.value = LeaveApplyResult.Invalid(errors); return }
        busy = true
        try {
            queue.submit(uuid, leavePayload(_form.value))
            _result.value = LeaveApplyResult.Queued
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            _result.value = LeaveApplyResult.Failed
        } finally { busy = false }
    }

    /** Opens a fresh form with a new uuid after a queued application. */
    fun startNew() { uuid = newUuid(); _form.value = LeaveForm(fromDate = today()); _result.value = LeaveApplyResult.Editing }
}
