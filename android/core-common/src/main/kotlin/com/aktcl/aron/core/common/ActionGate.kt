package com.aktcl.aron.core.common

/** Thrown by [ActionGate.requireOpen] while the Developer options gate is closed. */
class GateClosedException : IllegalStateException("Developer options gate is closed")

/**
 * The race-proof half of the Developer options gate (D-OWNER-MDM-02): code that makes a record (a save, a sale, a visit)
 * asks here at the moment it acts, so a setting switched on in a split-screen pane a millisecond ago cannot slip a record
 * through before the screen has reacted. [check] is installed by core-system when the gate exists; until then everything
 * is open (unit tests, the process before the first activity). It is a cheap settings read, no I/O beyond that.
 * Reading data and uploading queued records never ask: only writes of NEW records do.
 */
object ActionGate {
    @Volatile var check: () -> Boolean = { true }

    fun isOpen(): Boolean = runCatching(check).getOrDefault(true)

    fun requireOpen() { if (!isOpen()) throw GateClosedException() }
}
