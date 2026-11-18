package com.aktcl.aron.feature.attendance

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.feature.outlet.CaptureMetaProvider
import com.aktcl.aron.feature.outlet.FixReading
import com.aktcl.aron.feature.outlet.LocationFixSource
import com.aktcl.aron.feature.outlet.VisitFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock

/** Commits one attendance event with its fix and outbox record in one transaction (`CaptureRepository.recordAttendance`). */
fun interface AttendanceCommitter {
    suspend fun commit(event: AttendanceEventEntity, fix: GeoFixEntity)
}

/** What the Attendance screen shows (F-SR-011, F-SR-012). */
data class AttendanceState(
    val checkedInAt: String? = null,
    val checkedOutAt: String? = null,
    /** The check-in message above the buttons. Since D-OWNER-PREREQ-01 rule 1 it is also a GATE (hard, no config): stock, sales and visits stay closed until a check-in exists (D-AGATE-05); this flag is only the screen's text, the gate reads stored attendance. */
    val showCheckInPrompt: Boolean = true,
    val checkInEnabled: Boolean = true,
    val checkOutEnabled: Boolean = false,
    /** Coordinates offline, a text address only when online and resolved (UI-SR-11). */
    val addressText: String? = null,
    val lastFixMock: Boolean = false,
    val busy: Boolean = false,
    /** The last fix's coordinates as "lat, lng" (shown offline, always); [addressText] may then carry the resolved address. */
    val coordinatesText: String? = null,
    /** The last fix's accuracy in metres (the fix-quality chip: at most 30 good, to 100 fair, over 100 weak); null without a fix. */
    val fixAccuracyM: Int? = null,
    /** True when the last reading ended without a usable fix (timeout, location off): the screen shows the warning with a retry. */
    val fixFailed: Boolean = false,
    /** True while a display-only location refresh runs (the refresh icon is off, the card shows the reading line). */
    val readingFix: Boolean = false,
    /**
     * D-NIGHT-CHECKOUT-01: the day's Sales Submit is not committed on this phone yet, so check-out stays off and the screen
     * links to Sales Submit. Always false where the role has no such gate (TSO).
     */
    val needsSalesSubmit: Boolean = false,
    /** `cfg.day.checkout_earliest_time` has passed on trusted Dhaka time (whatever else still holds check-out). */
    val checkOutTimeReached: Boolean = false,
    /**
     * D-AGATE-06: why the last check-out press was refused (a call or a memo edit is still open), or null. The screen says it in words;
     * a press with nothing open, a tick or a restore clears it.
     */
    val checkOutBlockedBy: OpenWork? = null,
    /** D-AGATE-18: the phone is still working out the state of the day (the first resume has not answered): no press yet, the screen says "one moment". */
    val settling: Boolean = false,
)

/** What is still open at check-out: the rep must close it first (D-AGATE-06). Checked at the press, from stored state, never from a flag. */
enum class OpenWork { OPEN_CALL, OPEN_EDIT }

/**
 * D-AGATE-22 (item 6): thrown INSIDE the check-out's transaction when work opened in the gap between the press check and the commit (a call committed while the location read ran).
 * Nothing is written; [AttendanceFlow] turns it into [AttendanceResult.Refused] with the same words as the press check.
 */
class OpenWorkAtCheckOutException(val open: OpenWork) : IllegalStateException("check-out refused: $open is open")

/** Result of a check-in or check-out press. */
sealed interface AttendanceResult {
    data class Done(val event: AttendanceEventEntity, val fix: FixReading) : AttendanceResult
    data object Ignored : AttendanceResult

    /** The save failed (database error): nothing was written and the buttons stay as they were. */
    data object Failed : AttendanceResult

    /** D-AGATE-06: a call or a memo edit is still open: the check-out (which also submits the day) is refused and says so; nothing was written. */
    data class Refused(val open: OpenWork) : AttendanceResult
}

/**
 * Attendance (F-SR-011 check-in, F-SR-012 check-out). Offline by construction: one on-demand fix, the event and its
 * outbox record in one transaction, the address resolved only when a resolver answers (display only).
 * Check-out opens at `cfg.day.checkout_earliest_time` (17:00 inclusive) on trusted Dhaka time and needs a check-in.
 * With [salesSubmitted] (SR and AMO, D-NIGHT-CHECKOUT-01) it also needs the day's Sales Submit committed locally: offline
 * the local record is enough; until the first read answers, check-out stays off (no bypass).
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
    /** True once the business date's `day_submit` is committed on this phone (and not voided); null: no such gate (TSO). */
    private val salesSubmitted: (suspend () -> Boolean)? = null,
    /**
     * D-AGATE-06: what is still open (a call without its close, a memo edit in progress), read at the press. The check-out also writes the
     * day's Sales Submit, which closes the route-day, so it is refused while either is open. Null: nothing to check (tests, TSO).
     */
    private val openWork: (suspend () -> OpenWork?)? = null,
    /** D-AGATE-18: the day object's first resume result (a forgotten day closed or not) is not in yet, so check-in and check-out stay off with a "one moment" state until [settle]. Tests and TSO: false. */
    settleFirst: Boolean = false,
) {
    @Volatile private var settling = settleFirst
    /** The business date of the open check-in: the day a check-out closes, also when it is pressed after Dhaka midnight (D-AGATE-06). */
    @Volatile private var openDate: String? = null
    /** The last answer of [salesSubmitted]; null before the first read (check-out off meanwhile). */
    @Volatile private var submitted: Boolean? = null
    private val ui = MutableStateFlow(AttendanceState(needsSalesSubmit = salesSubmitted != null, settling = settleFirst, checkInEnabled = !settleFirst))
    val state: StateFlow<AttendanceState> = ui.asStateFlow()

    /**
     * Restores the screen from the stored events of the date that is open for the rep: today's, or (after Dhaka midnight, with the earlier day never
     * checked out) the OPEN check-in's own date, which the phone is about to close (D-LEAD-PREREQ-04 ruling 2). Any stored check-out on
     * that date means checked out (ruling 7), whatever the clock order of the rows.
     */
    fun restore(today: List<AttendanceEventEntity>) {
        val inEv = today.filter { it.kind == "check_in" }.minByOrNull { it.meta.capturedAt }
        val outEv = today.filter { it.kind == "check_out" }.maxByOrNull { it.meta.capturedAt }
        openDate = inEv?.meta?.businessDate
        ui.value = recompute(inEv?.meta?.capturedAt, outEv?.meta?.capturedAt, ui.value.addressText, ui.value.lastFixMock)
    }

    /**
     * True when the open check-in belongs to an EARLIER Dhaka date than today's. D-LEAD-PREREQ-04 ruling 2 (withdraws ruling 3 of D-LEAD-PREREQ-03): after Dhaka
     * midnight there is NO rep check-out for that day; the phone closes it (day object, `closeForgottenDays`), so the check-out button is off and a press is ignored.
     */
    private fun pastDay(): Boolean = openDate?.let { it < metaProvider.meta(0L).businessDate } == true

    /** The first resume of the day object has its result in (restore ran): the screen may be used. Idempotent. */
    fun settle() { settling = false; tick() }

    /** Called when the minute changes so the check-out button enables at 17:00 without a press. */
    fun tick() { ui.value = recompute(ui.value.checkedInAt, ui.value.checkedOutAt, ui.value.addressText, ui.value.lastFixMock) }

    /** Re-reads the local Sales Submit (screen open, resume, back from Sales Submit); a failed read keeps check-out off. */
    suspend fun refreshSubmit() {
        salesSubmitted?.let { submitted = readSubmitted(it) }
        tick()
    }

    private suspend fun readSubmitted(gate: suspend () -> Boolean): Boolean = try {
        gate()
    } catch (t: kotlinx.coroutines.CancellationException) {
        throw t
    } catch (_: Throwable) {
        false
    }

    suspend fun checkIn(): AttendanceResult {
        val s = ui.value
        if (s.checkedInAt != null || s.busy || settling) return AttendanceResult.Ignored
        return capture("check_in", "attendance_in")
    }

    /**
     * One press at a time: the gate read suspends between the checks and the busy flag, so a second press (a double
     * hold-complete) waits here and then finds the day checked out (checker, D-NIGHT-CHECKOUT-01).
     */
    suspend fun checkOut(): AttendanceResult = pressLock.withLock { checkOutLocked() }

    private val pressLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun checkOutLocked(): AttendanceResult {
        val s = ui.value
        if (s.checkedInAt == null || s.checkedOutAt != null || s.busy || settling) return AttendanceResult.Ignored
        if (pastDay() || dhakaMinutesNow() < checkoutEarliestMinutes()) return AttendanceResult.Ignored // recomputed, never a stale flag; a day left open past midnight is closed by the phone, never by a press
        // D-AGATE-06: an open call or memo edit refuses the check-out (it submits the day, which closes the route-day under it)
        openWork?.let { read ->
            val open = try { read() } catch (t: kotlinx.coroutines.CancellationException) { throw t } catch (_: Throwable) { null }
            ui.value = ui.value.copy(checkOutBlockedBy = open)
            if (open != null) return AttendanceResult.Refused(open)
        }
        // D-NIGHT-CHECKOUT-01 (only where a host still passes [salesSubmitted]; the SR and AMO no longer do, D-AGATE-06): read again at the press.
        salesSubmitted?.let { gate ->
            submitted = readSubmitted(gate)
            if (submitted != true) { tick(); return AttendanceResult.Ignored }
        }
        return capture("check_out", "attendance_out")
    }

    private suspend fun capture(kind: String, purpose: String): AttendanceResult {
        ui.value = ui.value.copy(busy = true)
        try {
            // D-AGATE-22 (item 1): a check-out is stamped when the rep PRESSED the button, before the location read (up to about 30 s), so a press at
            // 23:59:45 whose fix returns at 00:00:10 is still a rep's check-out of that day, never a time on the next date.
            val pressedMeta: CaptureMeta? = if (kind == "check_out") metaProvider.meta(routeIdOf() ?: 0L) else null
            val fix = fixes.readFix(purpose)
            val eventUuid = newUuid()
            val fixEntity = fix.toEntity(newUuid(), eventUuid, purpose, 0)
            val meta: CaptureMeta = (pressedMeta ?: metaProvider.meta(routeIdOf() ?: 0L)).let { if (routeIdOf() == null) it.copy(routeId = null) else it }
                // a check-out closes the day of the OPEN CHECK-IN, also after Dhaka midnight (its business date is explicit, not "today")
                .let { m -> if (kind == "check_out") openDate?.let { d -> m.copy(businessDate = d) } ?: m else m }
            val coords = if (fix.isOk) String.format(java.util.Locale.ROOT, "%.6f, %.6f", fix.lat, fix.lng) else null
            val event = AttendanceEventEntity(eventUuid, meta, kind, fixEntity.clientUuid, coords)
            try {
                committer.commit(event, fixEntity)
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: OpenWorkAtCheckOutException) {
                // D-AGATE-22 (item 6): a call opened while the fix was being read; the transaction found it and wrote nothing
                ui.value = ui.value.copy(checkOutBlockedBy = t.open)
                return AttendanceResult.Refused(t.open)
            } catch (t: Throwable) {
                return AttendanceResult.Failed
            }
            if (kind == "check_in") openDate = event.meta.businessDate
            // a check-out shows the STORED press time (what a restore shows and what is uploaded), not the time the fix came back
            val at = if (kind == "check_out") event.meta.capturedAt else nowIso()
            val cur = ui.value
            ui.value = recompute(
                if (kind == "check_in") at else cur.checkedInAt,
                if (kind == "check_out") at else cur.checkedOutAt,
                coords, fix.isMock,
            ).copy(busy = false).withFix(fix, coords)
            // Display only: resolved after the commit and after the button state is final; a failure keeps the coordinates.
            if (fix.isOk) runCatching { addressResolver(fix.lat!!, fix.lng!!) }.getOrNull()?.let { ui.value = ui.value.copy(addressText = it) }
            return AttendanceResult.Done(event, fix)
        } finally {
            ui.value = ui.value.copy(busy = false)
        }
    }

    /**
     * The location card's refresh (screens-sr s3): one display-only reading, nothing stored, nothing committed. Ignored
     * while a check-in or check-out is running. The buttons never wait for it.
     */
    suspend fun refreshFix() {
        if (ui.value.busy || ui.value.readingFix) return
        ui.value = ui.value.copy(readingFix = true)
        try {
            val fix = fixes.readFix(VisitFlow.PURPOSE_REFRESH) // contract `FixPurpose.REFRESH`: a display-only reading, never attached to an event
            val coords = if (fix.isOk) String.format(java.util.Locale.ROOT, "%.6f, %.6f", fix.lat, fix.lng) else null
            ui.value = ui.value.copy(readingFix = false, lastFixMock = fix.isMock).withFix(fix, coords)
            if (coords != null) ui.value = ui.value.copy(addressText = coords)
            if (fix.isOk) runCatching { addressResolver(fix.lat!!, fix.lng!!) }.getOrNull()?.let { ui.value = ui.value.copy(addressText = it) }
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (_: Throwable) {
            ui.value = ui.value.copy(fixFailed = true)
        } finally {
            ui.value = ui.value.copy(readingFix = false)
        }
    }

    private fun AttendanceState.withFix(fix: FixReading, coords: String?): AttendanceState = copy(
        coordinatesText = coords ?: coordinatesText,
        fixAccuracyM = if (fix.isOk) fix.accuracyM?.let { kotlin.math.round(it).toInt() } else fixAccuracyM,
        fixFailed = !fix.isOk,
    )

    private fun recompute(inAt: String?, outAt: String?, address: String?, mock: Boolean): AttendanceState {
        val needsSubmit = salesSubmitted != null && submitted != true
        return AttendanceState(
            checkedInAt = inAt, checkedOutAt = outAt, showCheckInPrompt = inAt == null,
            checkInEnabled = inAt == null && !settling,
            settling = settling,
            checkOutEnabled = !settling && inAt != null && outAt == null && !needsSubmit && !pastDay() && dhakaMinutesNow() >= checkoutEarliestMinutes(),
            addressText = address, lastFixMock = mock, busy = ui.value.busy,
            coordinatesText = ui.value.coordinatesText, fixAccuracyM = ui.value.fixAccuracyM, fixFailed = ui.value.fixFailed, readingFix = ui.value.readingFix,
            needsSalesSubmit = needsSubmit,
            checkOutTimeReached = !pastDay() && dhakaMinutesNow() >= checkoutEarliestMinutes(),
        )
    }
}
