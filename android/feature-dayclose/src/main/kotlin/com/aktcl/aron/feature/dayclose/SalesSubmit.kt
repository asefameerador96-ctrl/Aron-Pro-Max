package com.aktcl.aron.feature.dayclose

/** Outbox state counts of the route-day (device side): rows by acknowledgement state. */
data class OutboxStates(val pending: Int, val inFlight: Int, val acked: Int, val rejected: Int, val quarantined: Int) {
    val total: Int get() = pending + inFlight + acked + rejected + quarantined
}

/**
 * The sync screen's reconciliation row (F-SR-034): device versus server count per record type. [server] is null before the
 * first successful sync (the Server column then shows blank with a timestamp, not zero).
 */
data class CountRow(val recordType: String, val device: Int, val server: Int?) {
    val matches: Boolean get() = server != null && server == device
}

/** Dues owed by retailers at submit: a warning, never a block (assumed rule, Q-UI-08). */
data class DuesAtSubmit(val retailersWithDues: Int, val outstandingMtk: Long)

sealed interface SubmitBlock {
    data class Unsynced(val pending: Int, val inFlight: Int) : SubmitBlock
    data class Rejected(val count: Int) : SubmitBlock
    data class Quarantined(val count: Int) : SubmitBlock
    data class CountMismatch(val recordType: String, val device: Int, val server: Int?) : SubmitBlock
    data object AlreadySubmitted : SubmitBlock
}

/** [notes] are answered-by-server rows (rejected, quarantined): shown and recorded in the submit, never blocking. */
data class SubmitGate(val enabled: Boolean, val blocks: List<SubmitBlock>, val duesWarning: DuesAtSubmit?, val notes: List<SubmitBlock> = emptyList())

object SalesSubmitRules {
    /**
     * Sales Submit is enabled when every record of the day is acknowledged and the per-type counts match the server's
     * (assumed rule, Q-UI-08). Outstanding dues add a warning and never disable the button. The submit itself is queued
     * offline as the last event of the day; the success text shows only once the server settles it.
     */
    fun gate(states: OutboxStates, counts: List<CountRow>, dues: DuesAtSubmit, alreadySubmitted: Boolean): SubmitGate {
        val blocks = ArrayList<SubmitBlock>()
        if (alreadySubmitted) blocks += SubmitBlock.AlreadySubmitted
        if (states.pending + states.inFlight > 0) blocks += SubmitBlock.Unsynced(states.pending, states.inFlight)
        // The server has already answered a rejected or quarantined row (docs/24 s4.12: reconciled = accepted + rejected + quarantined);
        // they can never be retried, so they must not lock the day. They are carried into the day_submit counts.
        val notes = ArrayList<SubmitBlock>()
        if (states.rejected > 0) notes += SubmitBlock.Rejected(states.rejected)
        if (states.quarantined > 0) notes += SubmitBlock.Quarantined(states.quarantined)
        counts.filterNot { it.matches }.forEach { blocks += SubmitBlock.CountMismatch(it.recordType, it.device, it.server) }
        return SubmitGate(blocks.isEmpty(), blocks, dues.takeIf { it.retailersWithDues > 0 || it.outstandingMtk > 0 }, notes)
    }

    /** The Sync data button retries until the counts agree; it is offered whenever the device and server disagree. */
    fun syncButtonEnabled(online: Boolean, syncing: Boolean): Boolean = online && !syncing
}
