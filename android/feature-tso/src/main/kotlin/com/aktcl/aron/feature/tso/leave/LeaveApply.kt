package com.aktcl.aron.feature.tso.leave

import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** The leave type codes of the manual (Casual, Sick, Earn): the fallback when the bundle has no `leave_type` code list yet. */
val LEAVE_TYPES = listOf("casual", "sick", "earn")

/** One leave type of the bundle code list `leave_type` (screenshot 21). [labelBn] is null until AKTCL supplies it (s7.1 Q5). */
data class LeaveType(val code: String, val labelEn: String, val labelBn: String?, val sort: Int) {
    fun label(bangla: Boolean): String = if (bangla && !labelBn.isNullOrBlank()) labelBn else labelEn
}

object LeaveTypes {
    private val CODE = Regex("^[a-z][a-z0-9_]{1,40}$")

    /** The manual's three types, used only when the bundle sends no `leave_type` list. Labels come from the app's strings. */
    val DEFAULT: List<LeaveType> = listOf(LeaveType("casual", "Casual", null, 1), LeaveType("sick", "Sick", null, 2), LeaveType("earn", "Earn", null, 3))

    /** The `leave_type` list of the bundle section `code_lists` in sort order; bad items are dropped; empty when absent or unreadable. */
    fun parse(codeLists: String?): List<LeaveType> = runCatching {
        val a = JSONArray(codeLists ?: return emptyList())
        (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("list_key") == "leave_type" }?.getJSONArray("items")?.let { items ->
            (0 until items.length()).mapNotNull { i ->
                runCatching {
                    val o = items.getJSONObject(i)
                    require(CODE.matches(o.getString("code")) && o.getString("label_en").isNotBlank())
                    LeaveType(o.getString("code"), o.getString("label_en"), if (o.isNull("label_bn")) null else o.optString("label_bn").ifBlank { null }, o.optInt("sort", 0))
                }.getOrNull()
            }.sortedWith(compareBy<LeaveType> { it.sort }.thenBy { it.code })
        } ?: emptyList()
    }.getOrDefault(emptyList())
}

/** Contract LeaveApplicationPayload bounds: days 1..365, reason 1..500. */
const val MAX_DAYS = 365
const val MAX_REASON = 500

/** How far back a start date may be (a sick day is applied for after the fact) and how far ahead. Decision D-TSO-LEAVE-WINDOW. */
const val MAX_BACKDATE_DAYS = 31L
const val MAX_AHEAD_DAYS = 365L

/** `cfg.leave.allow_past_days` and `cfg.leave.allow_ahead_days` (D-TSO-MAN-33): the same window the server checks on sync and POST. */
data class LeaveWindow(val pastDays: Long = MAX_BACKDATE_DAYS, val aheadDays: Long = MAX_AHEAD_DAYS)

enum class LeaveError { TYPE, DATE_MISSING, DATE_FORMAT, DATE_PAST, DATE_FAR, DAYS, REASON_EMPTY, REASON_LONG }

/**
 * The apply form of screenshots 20 to 22: no leave type until one is chosen (""), no date until one is picked (""), the
 * typed number of days (0 shows as the screenshot's "0"), the reason.
 */
data class LeaveForm(val type: String = "", val fromDate: String = "", val days: Int = 0, val reason: String = "")

/** The record payload (contract `LeaveApplicationPayload`); `to_date` is not sent, `days` is authoritative. */
data class LeavePayload(val leaveTypeCode: String, val fromDate: String, val days: Int, val reason: String)

/**
 * Pure validation. [today] is the Dhaka business date; [types] the offered codes; [window] the config window. Whitespace-only
 * reasons are empty; the length counts characters of the trimmed text.
 */
fun validateLeave(form: LeaveForm, today: String, types: List<String> = LEAVE_TYPES, window: LeaveWindow = LeaveWindow()): List<LeaveError> {
    val errors = mutableListOf<LeaveError>()
    if (form.type !in types) errors += LeaveError.TYPE
    if (form.fromDate.isBlank()) errors += LeaveError.DATE_MISSING else {
        val from = runCatching { LocalDate.parse(form.fromDate) }.getOrNull()
        if (from == null) errors += LeaveError.DATE_FORMAT else {
            val t = LocalDate.parse(today)
            if (from.isBefore(t.minusDays(window.pastDays))) errors += LeaveError.DATE_PAST
            if (from.isAfter(t.plusDays(window.aheadDays))) errors += LeaveError.DATE_FAR
        }
    }
    if (form.days !in 1..MAX_DAYS) errors += LeaveError.DAYS
    val r = form.reason.trim()
    if (r.isEmpty()) errors += LeaveError.REASON_EMPTY else if (r.length > MAX_REASON) errors += LeaveError.REASON_LONG
    return errors
}

fun leavePayload(form: LeaveForm) = LeavePayload(form.type, form.fromDate, form.days, form.reason.trim())

/**
 * The typed "Number of days" text as the form's number: digits only, at most 3 of them; empty is 0. Leading zeros go
 * ("01" becomes 1), so typing over the screenshot's "0" gives the number typed.
 */
fun daysFromText(text: String): Int = text.filter { it in '0'..'9' }.take(3).toIntOrNull() ?: 0

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
 * failure sends the same uuid, so it can never queue twice. Nothing waits for the network. [types] and [window] read the
 * bundle (code list `leave_type`, `cfg.leave.*`); the form works with the manual's defaults until they answer.
 */
class LeaveApplyState(
    private val queue: LeaveQueue,
    private val today: () -> String,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
    private val types: suspend () -> List<LeaveType> = { emptyList() },
    private val window: suspend () -> LeaveWindow = { LeaveWindow() },
) {
    private var uuid = newUuid()
    private var busy = false
    fun todayIso() = today()
    private val _form = MutableStateFlow(LeaveForm())
    val form: StateFlow<LeaveForm> = _form.asStateFlow()
    private val _result = MutableStateFlow<LeaveApplyResult>(LeaveApplyResult.Editing)
    val result: StateFlow<LeaveApplyResult> = _result.asStateFlow()
    private val _options = MutableStateFlow(LeaveTypes.DEFAULT)
    /** The leave types offered in the dropdown (bundle code list, else the manual's three). */
    val options: StateFlow<List<LeaveType>> = _options.asStateFlow()
    private val _limits = MutableStateFlow(LeaveWindow())
    val limits: StateFlow<LeaveWindow> = _limits.asStateFlow()

    /** Reads the leave types and the window from the bundle once; never throws (the defaults stay). */
    suspend fun load() {
        runCatching { types() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { _options.value = it }
        runCatching { window() }.getOrNull()?.let { _limits.value = it }
    }

    fun edit(f: (LeaveForm) -> LeaveForm) {
        if (_result.value is LeaveApplyResult.Queued) return
        _form.value = f(_form.value); _result.value = LeaveApplyResult.Editing
    }

    suspend fun submit() {
        if (busy || _result.value is LeaveApplyResult.Queued) return
        val errors = validateLeave(_form.value, today(), _options.value.map { it.code }, _limits.value)
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
    fun startNew() { uuid = newUuid(); _form.value = LeaveForm(); _result.value = LeaveApplyResult.Editing }
}
