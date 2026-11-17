package com.aktcl.aron.core.common

import java.util.concurrent.CancellationException

/**
 * Thrown by [ActionGate.requireOpen] while the Developer options gate is closed. It IS a [CancellationException]: a
 * coroutine that hits it is cancelled quietly (no crash, no "save failed" noise), the screen the rep was on is replaced by
 * the gate at once (see [ActionGate.onClosed]) and any in-memory state (a half-built cart) is kept by the UI layer. A caller
 * that wants to tell the gate apart from other failures can catch this type.
 */
class GateClosedException : CancellationException("Developer options gate is closed")

/**
 * The race-proof half of the Developer options gate (D-OWNER-MDM-02): code that makes a NEW record (a save, a sale, a
 * visit) asks here at the moment it acts, so a setting switched on a millisecond ago cannot slip a record through before the
 * screen has reacted. [check] is installed in `Application.onCreate` of the three apps; until then everything is open (unit
 * tests). Reading data, uploading queued records, and records that describe something already done (a photo already taken,
 * a print already printed) never ask.
 */
object ActionGate {
    @Volatile var check: () -> Boolean = { true }

    /** Called (on the caller's thread) when [requireOpen] found the gate closed, so the UI can show it now. */
    @Volatile var onClosed: () -> Unit = {}

    fun isOpen(): Boolean = runCatching(check).getOrDefault(true)

    fun requireOpen() {
        if (!isOpen()) {
            runCatching(onClosed)
            throw GateClosedException()
        }
    }
}
