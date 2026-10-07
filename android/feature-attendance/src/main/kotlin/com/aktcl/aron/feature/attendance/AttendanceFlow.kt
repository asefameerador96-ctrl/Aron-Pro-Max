package com.aktcl.aron.feature.attendance

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.feature.outlet.CaptureMetaProvider
import com.aktcl.aron.feature.outlet.FixReading
import com.aktcl.aron.feature.outlet.LocationFixSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Commits one attendance event with its fix and outbox record in one transaction (`CaptureRepository.recordAttendance`). */
fun interface AttendanceCommitter {
    suspend fun commit(event: AttendanceEventEntity, fix: GeoFixEntity)
}

/** What the Attendance screen shows (F-SR-011, F-SR-012). */
data class AttendanceState(
    val checkedInAt: String? = null,
    val checkedOutAt: String? = null,
    /** The check-in message is a prompt, never a block (Q-UI-04, `cfg.day.require_checkin_before_sale` off). */
    val showCheckInPrompt: Boolean = true,
    val checkInEnabled: Boolean = true,
    val checkOutEnabled: Boolean = false,
    /** Coordinates offline, a text address only when online and resolved (UI-SR-11). */
    val addressText: String? = null,
    val lastFixMock: Boolean = false,
    val busy: Boolean = false,
)

/** Result of a check-in or check-out press. */
sealed interface AttendanceResult {
    data class Done(val event: AttendanceEventEntity, val fix: FixReading) : AttendanceResult
    data object Ignored : AttendanceResult

    /** The save failed (database error): nothing was written and the buttons stay as they were. */
    data object Failed : AttendanceResult
}

/**
 * Attendance (F-SR-011 check-in, F-SR-012 check-out). Offline by construction: one on-demand fix, the event and its
 * outbox record in one transaction, the address resolved only when a resolver answers (display only).
 * Check-out opens at `cfg.day.checkout_earliest_time` (17:00 inclusive) on trusted Dhaka time and needs a check-in.
 */
class AttendanceFlow(
    private val fixes: LocationFixSource,
    private val metaProvider: CaptureMetaProvider,
    private val committer: AttendanceCommitter,
    private val routeIdOf: () -> Long?,
    private val nowIso: () -> String,
    /** Minutes since midnight, Asia/Dhaka, from trusted time. */
    private val dhakaMinutesNow: () -> Int,
    private val checkoutEarliestMinutes: () -> Int = { 17 * 60 },
    private val newUuid: () -> String = ClientIds::newUuid,
    /** Online reverse geocode, display only; null offline or on failure. Never awaited by the commit. */
    private val addressResolver: suspend (Double, Double) -> String? = { _, _ -> null },
) {
    private val ui = MutableStateFlow(AttendanceState())
    val state: StateFlow<AttendanceState> = ui.asStateFlow()

    /** Restores the screen from today's stored events (survives a kill and relaunch). */
    fun restore(today: List<AttendanceEventEntity>) {
        val inEv = today.filter { it.kind == "check_in" }.minByOrNull { it.meta.capturedAt }
        val outEv = today.filter { it.kind == "check_out" }.maxByOrNull { it.meta.capturedAt }
        ui.value = recompute(inEv?.meta?.capturedAt, outEv?.meta?.capturedAt, ui.value.addressText, ui.value.lastFixMock)
    }

    /** Called when the minute changes so the check-out button enables at 17:00 without a press. */
    fun tick() { ui.value = recompute(ui.value.checkedInAt, ui.value.checkedOutAt, ui.value.addressText, ui.value.lastFixMock) }

    suspend fun checkIn(): AttendanceResult {
        val s = ui.value
        if (s.checkedInAt != null || s.busy) return AttendanceResult.Ignored
        return capture("check_in", "attendance_in")
    }

    suspend fun checkOut(): AttendanceResult {
        val s = ui.value
        if (s.checkedInAt == null || s.checkedOutAt != null || s.busy) return AttendanceResult.Ignored
        if (dhakaMinutesNow() < checkoutEarliestMinutes()) return AttendanceResult.Ignored // recomputed, never a stale flag
        return capture("check_out", "attendance_out")
    }

    private suspend fun capture(kind: String, purpose: String): AttendanceResult {
        ui.value = ui.value.copy(busy = true)
        try {
            val fix = fixes.readFix(purpose)
            val eventUuid = newUuid()
            val fixEntity = fix.toEntity(newUuid(), eventUuid, purpose, 0)
            val meta: CaptureMeta = metaProvider.meta(routeIdOf() ?: 0L).let { if (routeIdOf() == null) it.copy(routeId = null) else it }
            val coords = if (fix.isOk) String.format(java.util.Locale.ROOT, "%.6f, %.6f", fix.lat, fix.lng) else null
            val event = AttendanceEventEntity(eventUuid, meta, kind, fixEntity.clientUuid, coords)
            try {
                committer.commit(event, fixEntity)
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                return AttendanceResult.Failed
            }
            val at = nowIso()
            val cur = ui.value
            ui.value = recompute(
                if (kind == "check_in") at else cur.checkedInAt,
                if (kind == "check_out") at else cur.checkedOutAt,
                coords, fix.isMock,
            ).copy(busy = false)
            // Display only: resolved after the commit and after the button state is final; a failure keeps the coordinates.
            if (fix.isOk) runCatching { addressResolver(fix.lat!!, fix.lng!!) }.getOrNull()?.let { ui.value = ui.value.copy(addressText = it) }
            return AttendanceResult.Done(event, fix)
        } finally {
            ui.value = ui.value.copy(busy = false)
        }
    }

    private fun recompute(inAt: String?, outAt: String?, address: String?, mock: Boolean) = AttendanceState(
        checkedInAt = inAt, checkedOutAt = outAt, showCheckInPrompt = inAt == null,
        checkInEnabled = inAt == null,
        checkOutEnabled = inAt != null && outAt == null && dhakaMinutesNow() >= checkoutEarliestMinutes(),
        addressText = address, lastFixMock = mock, busy = ui.value.busy,
    )
}
